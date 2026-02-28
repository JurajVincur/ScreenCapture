package com.pupillabs.screencapture;

import static android.content.ContentValues.TAG;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Build;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.unity3d.player.UnityPlayer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DisplayCaptureManager implements ImageReader.OnImageAvailableListener {

    public static DisplayCaptureManager instance = null;

    private ImageReader reader;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private Intent notifServiceIntent;
    private boolean isNotifServiceStarted = false;
    private boolean isStartingCapture = false;
    private boolean isPermissionRequestInFlight = false;

    private MediaRecorder mediaRecorder;
    private String videoFilePath;
    private boolean isRecording = false;
    private SurfaceSplitter surfaceSplitter;
    private boolean shouldRecordVideo = false;

    private ByteBuffer byteBuffer;
    
    // --- NEW: MediaCodec properties ---
    private MediaCodec mediaCodec;
    private Surface mediaCodecSurface;
    private HandlerThread codecThread;
    private Handler codecHandler;
    
    // Buffers and tracking for Unity
    private ByteBuffer h264Buffer;
    private long h264Timestamp;
    private int h264DataSize;
	private boolean h264IsKeyframe;
	private byte[] spsPpsData = null;
    // ----------------------------------

    private int width;
    private int height;

    private UnityInterface unityInterface;
    private IUnityFrameListener frameListener = null;

    private record UnityInterface(String gameObjectName) {

        private void Call(String functionName) {
            UnityPlayer.UnitySendMessage(gameObjectName, functionName, "");
        }

        public void OnCaptureStarted() {
            Call("OnCaptureStarted");
        }

        public void OnPermissionDenied() {
            Call("OnPermissionDenied");
        }

        public void OnCaptureStopped() {
            Call("OnCaptureStopped");
        }
    }

    public static synchronized DisplayCaptureManager getInstance() {
        if (instance == null)
            instance = new DisplayCaptureManager();

        return instance;
    }

    public void setFrameListener(IUnityFrameListener listener) {
        this.frameListener = listener;
    }

    public void onPermissionResponse(int resultCode, Intent intent) {
        isPermissionRequestInFlight = false;

        if (resultCode != Activity.RESULT_OK) {
            isStartingCapture = false;
            unityInterface.OnPermissionDenied();
            Log.i(TAG, "Screen capture permission denied!");
            return;
        }

        var projectionManager = (MediaProjectionManager)
                UnityPlayer.currentContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = projectionManager.getMediaProjection(resultCode, intent);

        startCaptureWithProjection();
    }

    private void startCaptureWithProjection() {

        if (projection == null) {
            Log.e(TAG, "Cannot start capture: MediaProjection is null.");
            isStartingCapture = false;
            return;
        }

        // Always teardown stale resources before a fresh start attempt.
        releaseCaptureResources(false, false);

        if (projectionCallback != null) {
            projection.unregisterCallback(projectionCallback);
        }
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "Screen capture ended!");
                projection = null;
                releaseCaptureResources(true, true);
            }
        };
        projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));

        ensureNotificationServiceStarted();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {

            Log.i(TAG, "Starting screen capture and recording...");

            // 1. Setup MediaRecorder FIRST
            if (shouldRecordVideo) {
                setupMediaRecorder();
            }
            
            // --- NEW: Setup MediaCodec ---
            setupMediaCodec();

                // 2. Setup the OpenGL Surface Splitter (Now taking 3 surfaces)
                surfaceSplitter = new SurfaceSplitter(
                    reader.getSurface(), 
                    mediaRecorder != null ? mediaRecorder.getSurface() : null,
                    mediaCodecSurface, // <--- ADDED 3rd output
                    width, 
                    height
            );

            surfaceSplitter.setup(inputSurface -> {
                // 3. Create a SINGLE VirtualDisplay pointing to the OpenGL input surface
                virtualDisplay = projection.createVirtualDisplay("ScreenCapture",
                        width, height, 300,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        inputSurface, null, null);

                // 4. Start recording
                if (mediaRecorder != null) {
                    try {
                        mediaRecorder.start();
                        isRecording = true;
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Failed to start MediaRecorder", e);
                    }
                }

                unityInterface.OnCaptureStarted();
                isStartingCapture = false;
            });

        }, 100);

        Log.i(TAG, "Screen capture started!");
    }

    @Override
    public void onImageAvailable(ImageReader imageReader) {
        Image image = imageReader.acquireLatestImage();

        if (image == null) return;

        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        buffer.rewind();

        // Clear the buffer for new data by resetting the position of the buffer to zero
        byteBuffer.clear();
        byteBuffer.put(buffer);

        long timestamp = image.getTimestamp();

        image.close();

        if (frameListener != null) {
            frameListener.OnNewFrameAvailable();
        }
    }

    private void setupMediaRecorder() {
        try {
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

            File dir = UnityPlayer.currentContext.getExternalFilesDir(null);
            videoFilePath = dir.getAbsolutePath() + "/capture_" + System.currentTimeMillis() + ".mp4";
            
            mediaRecorder.setOutputFile(videoFilePath);
            mediaRecorder.setVideoSize(width, height);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoEncodingBitRate(5000000); // 5 Mbps
            mediaRecorder.setVideoFrameRate(30);

            mediaRecorder.prepare();
        } catch (IOException e) {
            Log.e(TAG, "Failed to prepare MediaRecorder", e);
            mediaRecorder = null;
        }
    }
    
    // --- NEW: MediaCodec setup method ---
    private void setupMediaCodec() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 1000000); // Low Bitrate (1 Mbps)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 10);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            
            // Run codec callbacks on a separate thread to prevent blocking main/EGL
            codecThread = new HandlerThread("MediaCodecThread");
            codecThread.start();
            codecHandler = new Handler(codecThread.getLooper());

            mediaCodec.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec codec, int index) { }

                @Override
                public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                    if (outputBuffer != null && info.size > 0) {
                        
                        // 1. Intercept the SPS/PPS Config Buffer
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            spsPpsData = new byte[info.size];
                            outputBuffer.position(info.offset);
                            outputBuffer.limit(info.offset + info.size);
                            outputBuffer.get(spsPpsData);

							Log.i(TAG, "SPS PPS DATA RECEIVED");
                            
                            // Release and return. This isn't a video frame, so don't send to Unity yet.
                            codec.releaseOutputBuffer(index, false);
                            return; 
                        }

                        h264IsKeyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                        // 2. Calculate total size (Keyframe size + SPS/PPS size)
                        int totalSize = info.size;
                        if (h264IsKeyframe && spsPpsData != null) {
                            totalSize += spsPpsData.length;
                        }

                        // 3. Dynamically allocate or expand buffer if needed
                        if (h264Buffer == null || h264Buffer.capacity() < totalSize) {
                            h264Buffer = ByteBuffer.allocateDirect(totalSize);
                        }
                        
                        h264Buffer.clear();
                        
                        // 4. If it's a keyframe, prepend the SPS/PPS data first
                        if (h264IsKeyframe && spsPpsData != null) {
                            h264Buffer.put(spsPpsData);
                        }

                        // 5. Append the actual frame data
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        h264Buffer.put(outputBuffer);
                        h264Buffer.flip();
                        
                        // 6. Store metadata for Unity
                        h264DataSize = totalSize;
                        h264Timestamp = System.currentTimeMillis();//info.presentationTimeUs;

                        if (frameListener != null) {
                            frameListener.OnNewH264DataAvailable();
                        }
                    }
                    codec.releaseOutputBuffer(index, false);
                }

                @Override
                public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                    Log.e(TAG, "MediaCodec Error", e);
                }

                @Override
                public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) { }
            }, codecHandler);

            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mediaCodecSurface = mediaCodec.createInputSurface();
            mediaCodec.start();
        } catch (IOException e) {
            Log.e(TAG, "Failed to prepare MediaCodec", e);
            mediaCodec = null;
            mediaCodecSurface = null;
        }
    }

    private void releaseCaptureResources(boolean stopProjection, boolean notifyStop) {


        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (surfaceSplitter != null) {
            surfaceSplitter.release();
            surfaceSplitter = null;
        }

        if (mediaRecorder != null) {
            try {
                if (isRecording) {
                    mediaRecorder.stop();
                    Log.i(TAG, "Recording successfully saved to: " + videoFilePath);
                }
            } catch (RuntimeException stopException) {
                Log.e(TAG, "Failed to stop MediaRecorder cleanly", stopException);
            } finally {
                try {
                    mediaRecorder.reset();
                } catch (Exception ignored) { }
                try {
                    mediaRecorder.release();
                } catch (Exception ignored) { }
                mediaRecorder = null;
                isRecording = false;
            }
        }
        
        // --- NEW: Cleanup MediaCodec ---
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop MediaCodec", e);
            }
            mediaCodec = null;
            mediaCodecSurface = null;
            spsPpsData = null;
        }
        if (codecThread != null) {
            codecThread.quitSafely();
            codecThread = null;
            codecHandler = null;
        }

        if (stopProjection && projection != null) {
            if (projectionCallback != null) {
                projection.unregisterCallback(projectionCallback);
                projectionCallback = null;
            }
            projection.stop();
            projection = null;
        } else if (projection == null) {
            projectionCallback = null;
        }

        if (stopProjection) {
            stopNotificationService();
        }

        if (notifyStop) {
            isStartingCapture = false;
            unityInterface.OnCaptureStopped();
        }
    }

    // Called by Unity
    public void setup(String gameObjectName, int width, int height) {

        unityInterface = new UnityInterface(gameObjectName);

        this.width = width;
        this.height = height;

        // Calculate the exact buffer size required (4 bytes per pixel for RGBA_8888)
        int bufferSize = width * height * 4;

        // Allocate a direct ByteBuffer for better performance
        byteBuffer = ByteBuffer.allocateDirect(bufferSize);

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

        reader.setOnImageAvailableListener(this, new Handler(Looper.getMainLooper()));
    }

    public void requestCapture(boolean recordVideo) {
        this.shouldRecordVideo = recordVideo;

        if (virtualDisplay != null || isStartingCapture) {
            Log.i(TAG, "Screen capture is already running.");
            return;
        }

        if (isPermissionRequestInFlight) {
            Log.i(TAG, "Screen capture permission request already in flight.");
            return;
        }

        if (projection != null) {
            Log.i(TAG, "Resuming capture using active MediaProjection instance.");
            isStartingCapture = true;
            startCaptureWithProjection();
            return;
        }
        
        Log.i(TAG, "Asking for screen capture permission...");
        isStartingCapture = true;
        isPermissionRequestInFlight = true;
        Intent intent = new Intent(
                UnityPlayer.currentActivity,
                DisplayCaptureRequestActivity.class);
        UnityPlayer.currentActivity.startActivity(intent);
    }

    public void stopCapture() {
        Log.i(TAG, "Stopping screen capture...");
        isPermissionRequestInFlight = false;
        isStartingCapture = false;

        if (projection != null) {
            if (projectionCallback != null) {
                projection.unregisterCallback(projectionCallback);
                projectionCallback = null;
            }
            projection.stop();
            projection = null;
        }

        releaseCaptureResources(true, true);
    }

    private void ensureNotificationServiceStarted() {
        if (isNotifServiceStarted) {
            return;
        }

        notifServiceIntent = new Intent(
                UnityPlayer.currentContext,
                DisplayCaptureNotificationService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            UnityPlayer.currentContext.startForegroundService(notifServiceIntent);
        } else {
            UnityPlayer.currentContext.startService(notifServiceIntent);
        }

        isNotifServiceStarted = true;
    }

    private void stopNotificationService() {
        if (!isNotifServiceStarted || notifServiceIntent == null) {
            notifServiceIntent = null;
            isNotifServiceStarted = false;
            return;
        }

        UnityPlayer.currentContext.stopService(notifServiceIntent);
        notifServiceIntent = null;
        isNotifServiceStarted = false;
    }

    public ByteBuffer getByteBuffer() {
        return byteBuffer;
    }
    
    // --- NEW: Getters for Unity to retrieve the encoded H264 data ---
    public ByteBuffer getH264Buffer() {
        return h264Buffer;
    }

    public long getH264Timestamp() {
        return h264Timestamp; // in microseconds
    }
    
    public int getH264DataSize() {
        return h264DataSize;
    }

	public boolean getH264IsKeyframe() {
        return h264IsKeyframe;
    }

    // =========================================================================================
    // OpenGL Surface Splitter
    // =========================================================================================
    private static class SurfaceSplitter implements SurfaceTexture.OnFrameAvailableListener {
        
        private HandlerThread glThread;
        private Handler glHandler;

        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface imageReaderEglSurface = EGL14.EGL_NO_SURFACE;
        private EGLSurface mediaRecorderEglSurface = EGL14.EGL_NO_SURFACE;
        private EGLSurface mediaCodecEglSurface = EGL14.EGL_NO_SURFACE; // <--- NEW

        private Surface imageReaderSurface;
        private Surface mediaRecorderSurface;
        private Surface mediaCodecSurface; // <--- NEW
        
        private SurfaceTexture inputSurfaceTexture;
        private Surface inputSurface;
        private int textureId;

        private int width;
        private int height;

        private int shaderProgram;
        private FloatBuffer vertexBuffer;
        private FloatBuffer textureBuffer;

        private float[] transformMatrix = new float[16];

        public interface OnSetupCompleteListener {
            void onSetupComplete(Surface inputSurface);
        }

        // --- UPDATED: Pass 3rd Surface ---
        public SurfaceSplitter(Surface imageReaderSurface, Surface mediaRecorderSurface, Surface mediaCodecSurface, int width, int height) {
            this.imageReaderSurface = imageReaderSurface;
            this.mediaRecorderSurface = mediaRecorderSurface;
            this.mediaCodecSurface = mediaCodecSurface; // <--- NEW
            this.width = width;
            this.height = height;

            glThread = new HandlerThread("GLSplitterThread");
            glThread.start();
            glHandler = new Handler(glThread.getLooper());
        }

        public void setup(OnSetupCompleteListener listener) {
            glHandler.post(() -> {
                initEGL();
                initGL();
                listener.onSetupComplete(inputSurface);
            });
        }

        public void release() {
            CountDownLatch releaseLatch = new CountDownLatch(1);
            glHandler.post(() -> {
                if (inputSurface != null) {
                    inputSurface.release();
                    inputSurface = null;
                }
                if (inputSurfaceTexture != null) {
                    inputSurfaceTexture.setOnFrameAvailableListener(null);
                    inputSurfaceTexture.release();
                    inputSurfaceTexture = null;
                }
                
                if (imageReaderEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, imageReaderEglSurface);
                }
                if (mediaRecorderEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, mediaRecorderEglSurface);
                }
                // --- NEW: Cleanup 3rd EGL Surface ---
                if (mediaCodecEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, mediaCodecEglSurface);
                }
                
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                }
                
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(eglDisplay);

                glThread.quitSafely();
                releaseLatch.countDown();
            });

            try {
                releaseLatch.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            glHandler.post(() -> {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(transformMatrix);
                long timestampNs = surfaceTexture.getTimestamp();

                // Draw to ImageReader Surface
                if (imageReaderEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, imageReaderEglSurface, imageReaderEglSurface, eglContext);
                    drawFrame();
                    EGL14.eglSwapBuffers(eglDisplay, imageReaderEglSurface);
                }

                // Draw to MediaRecorder Surface
                if (mediaRecorderEglSurface != EGL14.EGL_NO_SURFACE && mediaRecorderSurface != null) {
                    EGL14.eglMakeCurrent(eglDisplay, mediaRecorderEglSurface, mediaRecorderEglSurface, eglContext);
                    drawFrame();
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, mediaRecorderEglSurface, timestampNs);
                    EGL14.eglSwapBuffers(eglDisplay, mediaRecorderEglSurface);
                }
                
                // --- NEW: Draw to MediaCodec Surface ---
                if (mediaCodecEglSurface != EGL14.EGL_NO_SURFACE && mediaCodecSurface != null) {
                    EGL14.eglMakeCurrent(eglDisplay, mediaCodecEglSurface, mediaCodecEglSurface, eglContext);
                    drawFrame();
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, mediaCodecEglSurface, timestampNs);
                    EGL14.eglSwapBuffers(eglDisplay, mediaCodecEglSurface);
                }
            });
        }

        private void initEGL() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    0x3142, 1, // EGL_RECORDABLE_ANDROID
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);

            int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);

            int[] surfaceAttribs = { EGL14.EGL_NONE };
            if (imageReaderSurface != null) {
                imageReaderEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], imageReaderSurface, surfaceAttribs, 0);
            }
            if (mediaRecorderSurface != null) {
                mediaRecorderEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], mediaRecorderSurface, surfaceAttribs, 0);
            }
            // --- NEW: Init 3rd EGL Surface ---
            if (mediaCodecSurface != null) {
                mediaCodecEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], mediaCodecSurface, surfaceAttribs, 0);
            }

            // Bind an initial context
            if (imageReaderEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, imageReaderEglSurface, imageReaderEglSurface, eglContext);
            } else if (mediaRecorderEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, mediaRecorderEglSurface, mediaRecorderEglSurface, eglContext);
            } else if (mediaCodecEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, mediaCodecEglSurface, mediaCodecEglSurface, eglContext);
            }
        }

        private void initGL() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            inputSurfaceTexture = new SurfaceTexture(textureId);
            // CRITICAL FIX: Tell the off-screen surface how big it needs to be!
            inputSurfaceTexture.setDefaultBufferSize(width, height);
            inputSurfaceTexture.setOnFrameAvailableListener(this, glHandler);
            inputSurface = new Surface(inputSurfaceTexture);

            String vertexShader = "attribute vec4 aPosition;\n" +
                    "attribute vec4 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform mat4 uMatrix;\n" +
                    "void main() {\n" +
                    "    gl_Position = aPosition;\n" +
                    "    vTexCoord = (uMatrix * aTexCoord).xy;\n" +
                    "}";

            String fragmentShader = "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
                    "}";

            shaderProgram = GLES20.glCreateProgram();
            int vShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
            GLES20.glShaderSource(vShader, vertexShader);
            GLES20.glCompileShader(vShader);
            GLES20.glAttachShader(shaderProgram, vShader);

            int fShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
            GLES20.glShaderSource(fShader, fragmentShader);
            GLES20.glCompileShader(fShader);
            GLES20.glAttachShader(shaderProgram, fShader);
            GLES20.glLinkProgram(shaderProgram);

            float[] coords = { -1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f };
            float[] texs = { 0f, 0f,  1f, 0f,  0f, 1f,  1f, 1f };

            vertexBuffer = ByteBuffer.allocateDirect(coords.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(coords);
            vertexBuffer.position(0);
            textureBuffer = ByteBuffer.allocateDirect(texs.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(texs);
            textureBuffer.position(0);
        }

        private void drawFrame() {
            // CRITICAL FIX: Explicitly set the rendering viewport to the screen dimensions
            GLES20.glViewport(0, 0, width, height);
            
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(shaderProgram);

            // Explicitly activate and bind the OES texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            
            int samplerLoc = GLES20.glGetUniformLocation(shaderProgram, "sTexture");
            GLES20.glUniform1i(samplerLoc, 0);

            int posLoc = GLES20.glGetAttribLocation(shaderProgram, "aPosition");
            GLES20.glEnableVertexAttribArray(posLoc);
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

            int texLoc = GLES20.glGetAttribLocation(shaderProgram, "aTexCoord");
            GLES20.glEnableVertexAttribArray(texLoc);
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, textureBuffer);

            int matLoc = GLES20.glGetUniformLocation(shaderProgram, "uMatrix");
            GLES20.glUniformMatrix4fv(matLoc, 1, false, transformMatrix, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(posLoc);
            GLES20.glDisableVertexAttribArray(texLoc);
        }
    }
}
