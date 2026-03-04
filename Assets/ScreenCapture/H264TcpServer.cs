using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Buffers;
using UnityEngine;

namespace PupilLabs.ScreenCapture
{
    public class H264TcpServer : MonoBehaviour
    {
        [Header("Server Settings")]
        public int port = 8080;
        public int sendTimeoutMs = 1000;

        private DisplayCaptureManager captureManager;
        private TcpListener tcpListener;
        private Thread listenThread;
        private volatile bool isServerRunning;

        private List<TcpClient> connectedClients = new List<TcpClient>();
        private readonly object clientsLock = new object();

        private ConcurrentQueue<(byte[] Data, int Size)> frameQueue;
        private AutoResetEvent frameAddedSignal;
        private Thread senderThread;
        private volatile bool isSenderRunning;

        private void Start()
        {
            captureManager = DisplayCaptureManager.Instance;
            captureManager.onNewH264Data += OnNewH264FrameReady;

            // Initialize our queue and synchronization signal
            frameQueue = new ConcurrentQueue<(byte[] Data, int Size)>();
            frameAddedSignal = new AutoResetEvent(false);
            isSenderRunning = true;

            // Start the dedicated sender thread
            senderThread = new Thread(SendLoop)
            {
                IsBackground = true,
                Name = "H264_TCP_Sender_Thread"
            };
            senderThread.Start();

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

                    client.NoDelay = true;
                    client.SendBufferSize = 1024 * 1024;
                    client.SendTimeout = sendTimeoutMs;

                    lock (clientsLock)
                    {
                        connectedClients.Add(client);
                    }

                    Debug.Log($"[H264TcpServer] Client connected: {client.Client.RemoteEndPoint}");
                }
                catch (SocketException)
                {
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

            byte[] rentedArray = ArrayPool<byte>.Shared.Rent(size);
            Buffer.BlockCopy(captureManager.LatestH264Data, 0, rentedArray, 0, size);

            while (frameQueue.Count >= 5)
            {
                if (frameQueue.TryDequeue(out var droppedFrame))
                {
                    ArrayPool<byte>.Shared.Return(droppedFrame.Data);
                }
            }

            // Enqueue the new frame and wake up the sender thread
            frameQueue.Enqueue((rentedArray, size));
            frameAddedSignal.Set();
        }

        private void SendLoop()
        {
            while (isSenderRunning)
            {
                // Put the thread to sleep until frameAddedSignal.Set() is called.
                // It will wake up instantly when a frame arrives.
                frameAddedSignal.WaitOne();

                // Dequeue and send all available frames
                while (frameQueue.TryDequeue(out var frame))
                {
                    SendToClients(frame.Data, frame.Size);
                }
            }
        }

        private void SendToClients(byte[] data, int size)
        {
            try
            {
                lock (clientsLock)
                {
                    for (int i = connectedClients.Count - 1; i >= 0; i--)
                    {
                        TcpClient client = connectedClients[i];
                        try
                        {
                            NetworkStream stream = client.GetStream();
                            stream.Write(data, 0, size);
                        }
                        catch (Exception e)
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
                // Always return the array to the pool!
                ArrayPool<byte>.Shared.Return(data);
            }
        }

        private void OnDestroy()
        {
            isServerRunning = false;
            isSenderRunning = false;

            if (captureManager != null)
            {
                captureManager.onNewH264Data -= OnNewH264FrameReady;
            }

            // Wake up the sender thread one last time so it can exit the while loop safely
            frameAddedSignal?.Set();

            if (tcpListener != null)
            {
                tcpListener.Stop();
            }

            // Clean up clients
            lock (clientsLock)
            {
                foreach (var client in connectedClients)
                {
                    client.Close();
                }
                connectedClients.Clear();
            }

            // Empty the queue and return any unsent frames to the pool to prevent leaks
            if (frameQueue != null)
            {
                while (frameQueue.TryDequeue(out var unreadFrame))
                {
                    ArrayPool<byte>.Shared.Return(unreadFrame.Data);
                }
            }

            frameAddedSignal?.Dispose();
        }
    }
}