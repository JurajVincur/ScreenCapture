using System;
using Unity.Collections.LowLevel.Unsafe;
using UnityEngine;
using UnityEngine.Events;

namespace PupilLabs.ScreenCapture
{
    [DefaultExecutionOrder(-1000)]
    public class DisplayCaptureManager : MonoBehaviour
    {
        public static DisplayCaptureManager Instance { get; private set; }

        public bool startScreenCaptureOnStart = true;
        public bool flipTextureOnGPU = false;
        public bool recordVideo = false;

        [SerializeField] private Vector2Int textureSize = new(1024, 1024);
        public Vector2Int Size => textureSize;

        private Texture2D screenTexture;
        public Texture2D ScreenCaptureTexture => screenTexture;

        private RenderTexture flipTexture;

        public Matrix4x4 ProjectionMatrix { get; private set; }

        public UnityEvent<Texture2D> onTextureInitialized = new();
        public UnityEvent onStarted = new();
        public UnityEvent onPermissionDenied = new();
        public UnityEvent onStopped = new();

        public event EventHandler onNewFrame;
        public event EventHandler onNewH264Data;

        // --- NEW: H264 Events and Data ---
        public byte[] LatestH264Data { get; private set; }
        public int LatestH264Size { get; private set; }
        public long LatestH264Timestamp { get; private set; }
        public bool LatestH264IsKeyframe { get; private set; }
        public byte[] LatestFrame { get; private set; }
        // ---------------------------------

        private int bufferSize;
        private volatile bool newFrameReceived = false;
        private readonly object frameLock = new object();

        private bool captureActive = false;
        private bool restartCapture = false;

        public bool RecordVideo { get { return recordVideo; } set { recordVideo = value; } }

        private class AndroidInterface : IDisposable
        {
            private AndroidJavaClass androidClass;
            private AndroidJavaObject androidInstance;

            public AndroidInterface(GameObject messageReceiver, int textureWidth, int textureHeight)
            {
                androidClass = new AndroidJavaClass("com.pupillabs.screencapture.DisplayCaptureManager");
                androidInstance = androidClass.CallStatic<AndroidJavaObject>("getInstance");
                androidInstance.Call("setup", messageReceiver.name, textureWidth, textureHeight);
            }

            public void RequestCapture(bool recordVideo) => androidInstance.Call("requestCapture", recordVideo);
            public void StopCapture() => androidInstance.Call("stopCapture");

            public unsafe sbyte* GetByteBuffer()
            {
                AndroidJavaObject byteBuffer = androidInstance.Call<AndroidJavaObject>("getByteBuffer");
                return (sbyte*)AndroidJNI.GetDirectBufferAddress(byteBuffer.GetRawObject());
            }

            // --- NEW: H264 Getters ---
            public unsafe sbyte* GetH264ByteBuffer()
            {
                using (AndroidJavaObject byteBuffer = androidInstance.Call<AndroidJavaObject>("getH264Buffer"))
                {
                    if (byteBuffer == null || byteBuffer.GetRawObject() == IntPtr.Zero) return null;
                    return (sbyte*)AndroidJNI.GetDirectBufferAddress(byteBuffer.GetRawObject());
                }
            }

            public int GetH264DataSize() => androidInstance.Call<int>("getH264DataSize");

            public long GetH264Timestamp() => androidInstance.Call<long>("getH264Timestamp");

            public bool GetH264IsKeyframe() => androidInstance.Call<bool>("getH264IsKeyframe");

            public void SetFrameListener(FrameListenerProxy listener)
            {
                androidInstance.Call("setFrameListener", listener);
            }
            // -------------------------

            public void Dispose()
            {
                if (androidInstance != null)
                {
                    SetFrameListener(null);
                    androidInstance.Call("dispose");
                    androidInstance.Dispose();
                    androidInstance = null;
                }
                if (androidClass != null)
                {
                    androidClass.Dispose();
                    androidClass = null;
                }
            }
        }

        private AndroidInterface androidInterface;
        private FrameListenerProxy frameListener;

        private void Awake()
        {
            Instance = this;

            androidInterface = new AndroidInterface(gameObject, Size.x, Size.y);
            frameListener = new FrameListenerProxy();
            frameListener.onNewFrame += OnNewFrame;
            frameListener.onNewH264Data += OnNewH264Data;
            androidInterface.SetFrameListener(frameListener);

            screenTexture = new Texture2D(Size.x, Size.y, TextureFormat.RGBA32, 1, false);
        }

        private void Start()
        {
            flipTexture = new RenderTexture(Size.x, Size.y, 1, RenderTextureFormat.ARGB32, 1);
            flipTexture.Create();

            onTextureInitialized.Invoke(screenTexture);

            if (startScreenCaptureOnStart)
            {
                StartScreenCapture();
            }
            bufferSize = Size.x * Size.y * 4; // RGBA_8888 format: 4 bytes per pixel
            LatestFrame = new byte[bufferSize];
        }

        public void StartScreenCapture()
        {
            if (captureActive == true)
            {
                StopScreenCapture();
            }
            captureActive = true;
            androidInterface.RequestCapture(recordVideo);
        }

        public void StopScreenCapture()
        {
            if (!captureActive) return;
            captureActive = false;
            androidInterface.StopCapture();
        }

        // Messages sent from Android

        private void OnScreenOff()
        {
            if (captureActive)
            {
                restartCapture = true;
                StopScreenCapture();
            }
        }

        private void OnScreenOn()
        {
            if (restartCapture)
            {
                restartCapture = false;
                StartScreenCapture();
            }
        }

#pragma warning disable IDE0051 // Remove unused private members
        private unsafe void OnCaptureStarted()
        {
            onStarted.Invoke();
        }

        private void OnPermissionDenied()
        {
            onPermissionDenied.Invoke();
        }

        private void Update()
        {
            if (newFrameReceived)
            {
                newFrameReceived = false;

                lock (frameLock)
                {
                    screenTexture.LoadRawTextureData(LatestFrame);
                }

                screenTexture.Apply();

                if (flipTextureOnGPU)
                {
                    Graphics.Blit(screenTexture, flipTexture, new Vector2(1, -1), Vector2.zero);
                    Graphics.CopyTexture(flipTexture, screenTexture);
                }
            }
        }

        internal unsafe void OnNewFrame(object sender, EventArgs e) //TODO
        {
            sbyte* imageData = androidInterface.GetByteBuffer();
            if (imageData == null) return;

            lock (frameLock)
            {
                fixed (byte* destPtr = LatestFrame)
                {
                    UnsafeUtility.MemCpy(destPtr, imageData, bufferSize);
                }
            }
            newFrameReceived = true;

            onNewFrame?.Invoke(this, EventArgs.Empty);
        }

        // --- NEW: Receiver for H264 Data ---
        internal unsafe void OnNewH264Data(object sender, EventArgs e)
        {
            int size = androidInterface.GetH264DataSize();
            if (size <= 0) return;

            sbyte* h264DataPtr = androidInterface.GetH264ByteBuffer();
            if (h264DataPtr == null) return;

            long timestamp = androidInterface.GetH264Timestamp();
            bool isKeyframe = androidInterface.GetH264IsKeyframe();
            Debug.Log($"Received new H264 data: Size={size} bytes, Timestamp={timestamp} ms, IsKeyframe={isKeyframe}");

            // Ensure our managed array is large enough to hold the data
            if (LatestH264Data == null || LatestH264Data.Length < size)
            {
                LatestH264Data = new byte[size];
            }

            LatestH264Size = size;
            LatestH264Timestamp = timestamp;
            LatestH264IsKeyframe = isKeyframe;

            fixed (byte* destinationPtr = LatestH264Data)
            {
                UnsafeUtility.MemCpy(destinationPtr, h264DataPtr, size);
            }
            onNewH264Data?.Invoke(this, EventArgs.Empty);
        }
        // -----------------------------------

        private void OnCaptureStopped()
        {
            onStopped.Invoke();
        }
#pragma warning restore IDE0051 // Remove unused private members

        private void OnDestroy()
        {
            StopScreenCapture();

            if (androidInterface != null)
            {
                androidInterface.Dispose();
                androidInterface = null;
            }
            frameListener.onNewH264Data -= OnNewH264Data;
            frameListener = null;

            if (screenTexture != null)
            {
                Destroy(screenTexture);
                screenTexture = null;
            }

            if (flipTexture != null)
            {
                flipTexture.Release();
                Destroy(flipTexture);
                flipTexture = null;
            }
        }
    }
}