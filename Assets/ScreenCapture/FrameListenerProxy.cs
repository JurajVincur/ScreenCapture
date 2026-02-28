using System;
using UnityEngine;

namespace PupilLabs.ScreenCapture
{

    public class FrameListenerProxy : AndroidJavaProxy
    {
        public event EventHandler onNewFrame;
        public event EventHandler onNewH264Data;

        public FrameListenerProxy() : base("com.pupillabs.screencapture.IUnityFrameListener") { }

        public void OnNewFrameAvailable()
        {
            onNewFrame?.Invoke(this, EventArgs.Empty);
        }

        public void OnNewH264DataAvailable()
        {
            onNewH264Data?.Invoke(this, EventArgs.Empty);
        }
    }
}