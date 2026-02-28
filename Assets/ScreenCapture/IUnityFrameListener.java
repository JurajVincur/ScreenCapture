package com.pupillabs.screencapture;

public interface IUnityFrameListener {
    void OnNewFrameAvailable();
    void OnNewH264DataAvailable();
}