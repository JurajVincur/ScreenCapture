using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Buffers; // <--- NEW: For ArrayPool
using UnityEngine;
using System.Buffers.Binary;

namespace PupilLabs.ScreenCapture
{
    public class H264TcpServer : MonoBehaviour
    {
        [Header("Server Settings")]
        public int port = 8080;
        public int sendTimeoutMs = 1000; // <--- NEW: 1 second timeout for slow clients

        private DisplayCaptureManager captureManager;
        private TcpListener tcpListener;
        private Thread listenThread;
        private bool isServerRunning;

        private static readonly ThreadLocal<byte[]> headerBuffer = new ThreadLocal<byte[]>(() => new byte[12]);
        private List<TcpClient> connectedClients = new List<TcpClient>();
        private readonly object clientsLock = new object();

        private void Start()
        {
            captureManager = DisplayCaptureManager.Instance;
            captureManager.onNewH264Data += OnNewH264FrameReady;

            StartServer();
        }

        private void StartServer()
        {
            try
            {
                tcpListener = new TcpListener(IPAddress.Any, port);
                tcpListener.Start();
                isServerRunning = true;

                listenThread = new Thread(ListenForClients)
                {
                    IsBackground = true,
                    Name = "H264_TCP_Listen_Thread"
                };
                listenThread.Start();

                Debug.Log($"[H264TcpServer] Started on port {port}");
            }
            catch (Exception e)
            {
                Debug.LogError($"[H264TcpServer] Failed to start server: {e.Message}");
            }
        }

        private void ListenForClients()
        {
            while (isServerRunning)
            {
                try
                {
                    TcpClient client = tcpListener.AcceptTcpClient();

                    // Optional: Tune socket parameters for video streaming
                    client.NoDelay = true; // Disable Nagle's algorithm for lower latency
                    client.SendBufferSize = 1024 * 1024; // 1MB buffer
                    client.SendTimeout = sendTimeoutMs;  // <--- CRITICAL: Drop slow clients

                    lock (clientsLock)
                    {
                        connectedClients.Add(client);
                    }

                    Debug.Log($"[H264TcpServer] Client connected: {client.Client.RemoteEndPoint}");
                }
                catch (SocketException)
                {
                    // Expected when tcpListener.Stop() is called
                    break;
                }
            }
        }

        private void OnNewH264FrameReady(object sender, EventArgs e)
        {
            lock (clientsLock)
            {
                if (connectedClients.Count == 0) return;
            }

            int size = captureManager.LatestH264Size;
            long timestamp = captureManager.LatestH264Timestamp;
            bool isKeyframe = captureManager.LatestH264IsKeyframe;

            // --- FIXED: Rent a reusable array instead of allocating a new one ---
            byte[] rentedArray = ArrayPool<byte>.Shared.Rent(size);
            Buffer.BlockCopy(captureManager.LatestH264Data, 0, rentedArray, 0, size);

            // Offload the network writing
            ThreadPool.QueueUserWorkItem(_ => SendToClients(rentedArray, size, timestamp, isKeyframe));
        }

        private void SendToClients(byte[] data, int size, long timestamp, bool isKeyframe)
        {
            try
            {
                byte[] header = headerBuffer.Value;
                BinaryPrimitives.WriteInt64BigEndian(header.AsSpan(0, 8), timestamp);
                BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(8, 4), size);

                lock (clientsLock)
                {
                    for (int i = connectedClients.Count - 1; i >= 0; i--)
                    {
                        TcpClient client = connectedClients[i];
                        try
                        {
                            NetworkStream stream = client.GetStream();
                            stream.Write(header, 0, header.Length);
                            stream.Write(data, 0, size);
                        }
                        catch (Exception e) // Will catch SendTimeout exceptions
                        {
                            Debug.LogWarning($"[H264TcpServer] Client disconnected or timed out: {e.Message}");
                            client.Close();
                            connectedClients.RemoveAt(i);
                        }
                    }
                }
            }
            finally
            {
                // --- FIXED: Always return the array to the pool when finished sending ---
                ArrayPool<byte>.Shared.Return(data);
            }
        }

        private void OnDestroy()
        {
            isServerRunning = false;

            if (captureManager != null)
            {
                captureManager.onNewH264Data -= OnNewH264FrameReady;
            }

            if (tcpListener != null)
            {
                tcpListener.Stop();
            }

            lock (clientsLock)
            {
                foreach (var client in connectedClients)
                {
                    client.Close();
                }
                connectedClients.Clear();
            }
        }
    }
}