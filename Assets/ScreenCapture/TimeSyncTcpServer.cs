using System;
using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using UnityEngine;

namespace PupilLabs.ScreenCapture
{
    public class TimeSyncTcpServer : MonoBehaviour
    {
        [Header("Time Sync Settings")]
        public int port = 8081;

        private TcpListener tcpListener;
        private Thread listenThread;
        private bool isServerRunning;

        private void Start()
        {
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
                    Name = "TimeSync_TCP_Listen_Thread"
                };
                listenThread.Start();

                Debug.Log($"[TimeSyncTcpServer] Started on port {port}");
            }
            catch (Exception e)
            {
                Debug.LogError($"[TimeSyncTcpServer] Failed to start server: {e.Message}");
            }
        }

        private void ListenForClients()
        {
            while (isServerRunning)
            {
                try
                {
                    TcpClient client = tcpListener.AcceptTcpClient();

                    // Disable Nagle's algorithm for instant transmission
                    client.NoDelay = true;

                    ThreadPool.QueueUserWorkItem(_ => HandleTimeClient(client));
                }
                catch (SocketException)
                {
                    // Expected when tcpListener.Stop() is called
                    break;
                }
            }
        }

        private void HandleTimeClient(TcpClient client)
        {
            // REQUIRED: Attach this ThreadPool thread to the Android JNI
            // so TimeUtils can safely call Java's currentTimeMillis
            if (Application.platform == RuntimePlatform.Android)
            {
                AndroidJNI.AttachCurrentThread();
            }

            try
            {
                using (client)
                using (NetworkStream stream = client.GetStream())
                {
                    byte[] readBuffer = new byte[1];
                    byte[] responseBuffer = new byte[8];

                    while (isServerRunning)
                    {
                        // Wait for the client to send a 1-byte "ping"
                        int bytesRead = stream.Read(readBuffer, 0, 1);
                        if (bytesRead == 0) break; // Client disconnected gracefully

                        // 1. Get the perfect Epoch millisecond from our utility class
                        long timeMs = TimeUtils.UnixTimeMs();

                        // 2. Write it in strict Big-Endian directly into the buffer
                        BinaryPrimitives.WriteInt64BigEndian(responseBuffer, timeMs);

                        // 3. Fire it back across the network instantly
                        stream.Write(responseBuffer, 0, 8);
                    }
                }
            }
            catch (Exception)
            {
                // Client forcibly disconnected or network dropped
            }
            finally
            {
                // REQUIRED: Detach the thread when we are done to prevent crashes/leaks
                if (Application.platform == RuntimePlatform.Android)
                {
                    AndroidJNI.DetachCurrentThread();
                }
            }
        }

        private void OnDestroy()
        {
            isServerRunning = false;

            if (tcpListener != null)
            {
                tcpListener.Stop();
            }
        }
    }
}