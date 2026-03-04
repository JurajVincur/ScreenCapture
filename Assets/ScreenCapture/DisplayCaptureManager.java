package com.pupillabs.screencapture;

import static android.content.ContentValues.TAG;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
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
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.unity3d.player.UnityPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;

public class DisplayCaptureManager implements ImageReader.OnImageAvailableListener {

    public static DisplayCaptureManager instance = null;

    private ImageReader reader;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private Intent notifServiceIntent;
    private SurfaceSplitter surfaceSplitter;

    private boolean shouldRecordVideo = false;
    private ByteBuffer byteBuffer;

    // --- Unified Encoders ---
    private EncoderState hrEncoder;
    private EncoderState lrEncoder;

    // File I/O for High-Quality stream
    private FileOutputStream customVideoStream;
    private FileChannel videoFileChannel;
    private String videoFilePath;

    // Buffers and tracking exposed to Unity
    private ByteBuffer h264Buffer;
    private long h264Timestamp;
    private int h264DataSize;
    private boolean h264IsKeyframe;
    // -----------------------------

    private int width;
    private int height;

    private UnityInterface unityInterface;
    private IUnityFrameListener frameListener = null;
    private BroadcastReceiver screenStateReceiver;

    // Thread-safe state flags
    private volatile boolean isCaptureActive = false;
    private volatile boolean isDisposed = false;

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

        public void OnScreenOff() {
            Call("OnScreenOff");
        }

        public void OnScreenOn() {
            Call("OnScreenOn");
        }
    }

    public static synchronized DisplayCaptureManager getInstance() {
        if (instance == null) {
            instance = new DisplayCaptureManager();
        }

        return instance;
    }

    public void setFrameListener(IUnityFrameListener listener) {
        this.frameListener = listener;
    }

    public void onPermissionResponse(int resultCode, Intent intent) {

        if (resultCode != Activity.RESULT_OK) {
            unityInterface.OnPermissionDenied();
            Log.i(TAG, "Screen capture permission denied!");
            return;
        }

        notifServiceIntent = new Intent(
                UnityPlayer.currentContext,
                DisplayCaptureNotificationService.class);
        UnityPlayer.currentContext.startService(notifServiceIntent);

        registerScreenStateReceiver();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {

            Log.i(TAG, "Starting screen capture and recording...");

            // 1. Setup High-Quality MediaCodec FIRST if requested
            if (shouldRecordVideo) {
                setupHqStream();
            }

            // 2. Setup Low-Quality MediaCodec for Unity
            setupLqStream();

            // 3. Setup the OpenGL Surface Splitter
            surfaceSplitter = new SurfaceSplitter(
                    width,
                    height,
                    reader.getSurface(),
                    hrEncoder != null ? hrEncoder.surface : null,
                    lrEncoder != null ? lrEncoder.surface : null
            );

            surfaceSplitter.setup(inputSurface -> {
                // --- Prevent Zombie Capture ---
                if (isDisposed) {
                    Log.w(TAG, "GL Setup finished, but manager was already disposed. Aborting.");
                    return;
                }
                // ------------------------------

                var projectionManager = (MediaProjectionManager) UnityPlayer.currentContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                projection = projectionManager.getMediaProjection(resultCode, intent);

                projection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        Log.i(TAG, "Screen capture ended via Projection Callback!");
                        handleScreenCaptureEnd();
                    }
                }, new Handler(Looper.getMainLooper()));

                // 4. Create a SINGLE VirtualDisplay pointing to the OpenGL input surface
                virtualDisplay = projection.createVirtualDisplay("ScreenCapture",
                        width, height, 300,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        inputSurface, null, null);

                // Mark capture as active so cleanup can run when requested
                isCaptureActive = true;
                unityInterface.OnCaptureStarted();
            });

        }, 100);

        Log.i(TAG, "Screen capture started!");
    }

    private void registerScreenStateReceiver() {
        if (screenStateReceiver == null) {
            screenStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        if (unityInterface != null) {
                            unityInterface.OnScreenOff();
                        }
                    } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                        if (unityInterface != null) {
                            unityInterface.OnScreenOn();
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            UnityPlayer.currentContext.registerReceiver(screenStateReceiver, filter);
        }
    }

    private void unregisterScreenStateReceiver() {
        if (screenStateReceiver != null) {
            try {
                UnityPlayer.currentContext.unregisterReceiver(screenStateReceiver);
            } catch (IllegalArgumentException e) {
            }
            screenStateReceiver = null;
        }
    }

    @Override
    public void onImageAvailable(ImageReader imageReader) {
        Image image = imageReader.acquireLatestImage();
        if (image == null) {
            return;
        }

        byteBuffer = image.getPlanes()[0].getBuffer();

        if (frameListener != null) {
            frameListener.OnNewFrameAvailable();
        }

        byteBuffer = null;
        image.close();
    }

    // Helper function to write the 12-byte header (>ql)
    private void write12ByteHeader(ByteBuffer buffer, long timestamp, int payloadSize) {
        buffer.putLong(timestamp); // 8 bytes (q)
        buffer.putInt(payloadSize); // 4 bytes (l)
    }

    // =========================================================================================
    // UNIFIED VIDEO ENCODER LOGIC
    // =========================================================================================
    private interface FrameOutputListener {

        void onFrameReady(ByteBuffer formattedBuffer, long timestampMs, int totalSize, boolean isKeyframe);
    }

    private class EncoderState {

        MediaCodec codec;
        Surface surface;
        HandlerThread thread;
        Handler handler;
        byte[] spsPpsData = null;
        ByteBuffer formattedBuffer;
        long bootTimeOffsetNs;

        void release() {
            if (codec != null) {
                try {
                    codec.setCallback(null);
                    codec.stop();
                    codec.release();
                } catch (Exception e) {
                }
                codec = null;
            }
            if (surface != null) {
                surface.release();
                surface = null;
            }
            if (thread != null) {
                thread.quitSafely();
                thread = null;
            }
            handler = null;
            spsPpsData = null;
        }
    }

    private EncoderState createVideoEncoder(int bitRate, int frameRate, String threadName, FrameOutputListener outputListener) {
        EncoderState state = new EncoderState();
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            state.codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);

            state.thread = new HandlerThread(threadName);
            state.thread.start();
            state.handler = new Handler(state.thread.getLooper());

            state.codec.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec codec, int index) {
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                    if (outputBuffer != null && info.size > 0) {

                        // 1. Intercept SPS/PPS
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            state.spsPpsData = new byte[info.size];
                            outputBuffer.position(info.offset);
                            outputBuffer.limit(info.offset + info.size);
                            outputBuffer.get(state.spsPpsData);
                            codec.releaseOutputBuffer(index, false);
                            return;
                        }

                        boolean isKeyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        long frameTimestampMs = (state.bootTimeOffsetNs + info.presentationTimeUs * 1000L) / 1_000_000L;

                        int payloadSize = info.size;
                        if (isKeyframe && state.spsPpsData != null) {
                            payloadSize += state.spsPpsData.length;
                        }

                        int totalBufferSize = 12 + payloadSize;
                        if (state.formattedBuffer == null || state.formattedBuffer.capacity() < totalBufferSize) {
                            state.formattedBuffer = ByteBuffer.allocateDirect(totalBufferSize);
                        }

                        state.formattedBuffer.clear();
                        write12ByteHeader(state.formattedBuffer, frameTimestampMs, payloadSize);

                        if (isKeyframe && state.spsPpsData != null) {
                            state.formattedBuffer.put(state.spsPpsData);
                        }

                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        state.formattedBuffer.put(outputBuffer);
                        state.formattedBuffer.flip();

                        // 2. Trigger the callback logic defined for this specific stream
                        outputListener.onFrameReady(state.formattedBuffer, frameTimestampMs, totalBufferSize, isKeyframe);
                    }
                    codec.releaseOutputBuffer(index, false);
                }

                @Override
                public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                    Log.e(TAG, threadName + " Error", e);
                }

                @Override
                public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                }
            }, state.handler);

            state.codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            state.surface = state.codec.createInputSurface();
            state.codec.start();
            state.bootTimeOffsetNs = System.currentTimeMillis() * 1_000_000L - System.nanoTime();
        } catch (IOException e) {
            Log.e(TAG, "Failed to prepare " + threadName, e);
            state.release();
            return null;
        }
        return state;
    }

    // =========================================================================================
    private void setupHqStream() {
        try {
            File dir = UnityPlayer.currentContext.getExternalFilesDir(null);
            videoFilePath = dir.getAbsolutePath() + "/capture_" + System.currentTimeMillis() + ".bin";
            customVideoStream = new FileOutputStream(videoFilePath);
            videoFileChannel = customVideoStream.getChannel();
        } catch (IOException e) {
            Log.e(TAG, "Failed to create Binary file", e);
        }

        // Setup 5Mbps, 30FPS stream pointing to the FileChannel
        hrEncoder = createVideoEncoder(5000000, 30, "HqMediaCodecThread",
                (formattedBuffer, timestampMs, totalSize, isKeyframe) -> {
                    if (videoFileChannel != null) {
                        try {
                            while (formattedBuffer.hasRemaining()) {
                                videoFileChannel.write(formattedBuffer);
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to write high-quality frame to disk", e);
                        }
                    }
                }
        );
    }

    private void setupLqStream() {
        // Setup 1Mbps, 10FPS stream pointing to the Unity Variables
        lrEncoder = createVideoEncoder(1000000, 10, "MediaCodecThread",
                (formattedBuffer, timestampMs, totalSize, isKeyframe) -> {
                    h264Buffer = formattedBuffer;
                    h264Timestamp = timestampMs;
                    h264DataSize = totalSize;
                    h264IsKeyframe = isKeyframe;

                    if (frameListener != null) {
                        frameListener.OnNewH264DataAvailable();
                    }
                }
        );
    }

    // Synchronized lock to ensure it runs completely safely even if called
    // simultaneously from Unity thread and Projection thread
    private synchronized void handleScreenCaptureEnd() {

        // --- Execution Check ---
        if (!isCaptureActive) {
            return;
        }
        isCaptureActive = false;
        // -----------------------

        unregisterScreenStateReceiver();

        // --- Actually stop the OS-level projection service ---
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        // -----------------------------------------------------

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (surfaceSplitter != null) {
            surfaceSplitter.release();
            surfaceSplitter = null;
        }

        // Cleanup Encoders
        if (hrEncoder != null) {
            hrEncoder.release();
            hrEncoder = null;
            Log.i(TAG, "High-Quality Binary stream successfully saved to: " + videoFilePath);
        }

        if (lrEncoder != null) {
            lrEncoder.release();
            lrEncoder = null;
        }

        // Cleanup File Streams
        if (videoFileChannel != null) {
            try {
                videoFileChannel.close();
                customVideoStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close High-Quality file streams", e);
            }
            videoFileChannel = null;
            customVideoStream = null;
        }

        UnityPlayer.currentContext.stopService(notifServiceIntent);

        if (unityInterface != null) {
            unityInterface.OnCaptureStopped();
        }
    }

    // Called by Unity
    public void setup(String gameObjectName, int width, int height) {
        unityInterface = new UnityInterface(gameObjectName);
        this.width = width;
        this.height = height;

        int bufferSize = width * height * 4;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this, new Handler(Looper.getMainLooper()));
    }

    public void requestCapture(boolean recordVideo) {
        this.shouldRecordVideo = recordVideo;
        Intent intent = new Intent(UnityPlayer.currentActivity, DisplayCaptureRequestActivity.class);
        UnityPlayer.currentActivity.startActivity(intent);
    }

    public void stopCapture() {
        if (projection == null) {
            return;
        }
        projection.stop(); // This triggers the onStop() callback which calls handleScreenCaptureEnd()
    }

    public void dispose() {
        isDisposed = true; // Mark as disposed to prevent async setup from continuing
        handleScreenCaptureEnd();

        if (reader != null) {
            reader.close();
            reader = null;
        }
        byteBuffer = null;
        unityInterface = null;
        instance = null;

        Log.i(TAG, "DisplayCaptureManager disposed.");
    }

    public ByteBuffer getByteBuffer() {
        return byteBuffer;
    }

    public ByteBuffer getH264Buffer() {
        return h264Buffer;
    }

    public long getH264Timestamp() {
        return h264Timestamp;
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

        // --- Dynamic lists replace hardcoded surfaces ---
        private final ArrayList<Surface> targetSurfaces = new ArrayList<>();
        private final ArrayList<EGLSurface> eglSurfaces = new ArrayList<>();

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

        // Accepts a dynamic number of outputs (varargs)
        public SurfaceSplitter(int width, int height, Surface... surfaces) {
            this.width = width;
            this.height = height;

            for (Surface s : surfaces) {
                if (s != null) {
                    this.targetSurfaces.add(s);
                }
            }

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
            glHandler.post(() -> {
                // --- VRAM Leak Fix ---
                if (textureId != 0) {
                    GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
                    textureId = 0;
                }
                if (shaderProgram != 0) {
                    GLES20.glDeleteProgram(shaderProgram);
                    shaderProgram = 0;
                }
                // ---------------------

                if (inputSurface != null) {
                    inputSurface.release();
                }
                if (inputSurfaceTexture != null) {
                    inputSurfaceTexture.release();
                }

                // Dynamically destroy all surfaces
                for (EGLSurface eglSurface : eglSurfaces) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                }
                eglSurfaces.clear();

                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                    eglContext = EGL14.EGL_NO_CONTEXT;
                }

                EGL14.eglReleaseThread();
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglTerminate(eglDisplay);
                    eglDisplay = EGL14.EGL_NO_DISPLAY;
                }

                glThread.quitSafely();
            });
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            glHandler.post(() -> {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(transformMatrix);
                long timestampNs = surfaceTexture.getTimestamp();

                // Dynamically draw to all connected surfaces!
                for (EGLSurface eglSurface : eglSurfaces) {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
                    drawFrame();
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs);
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface);
                }
            });
        }

        private void initEGL() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

            int[] attribList = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);

            int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);

            int[] surfaceAttribs = {EGL14.EGL_NONE};

            // Dynamically create EGL layers
            for (Surface target : targetSurfaces) {
                EGLSurface eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], target, surfaceAttribs, 0);
                eglSurfaces.add(eglSurface);
            }

            if (!eglSurfaces.isEmpty()) {
                EGL14.eglMakeCurrent(eglDisplay, eglSurfaces.get(0), eglSurfaces.get(0), eglContext);
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
            inputSurfaceTexture.setDefaultBufferSize(width, height);
            inputSurfaceTexture.setOnFrameAvailableListener(this, glHandler);
            inputSurface = new Surface(inputSurfaceTexture);

            String vertexShader = "attribute vec4 aPosition;\n"
                    + "attribute vec4 aTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "uniform mat4 uMatrix;\n"
                    + "void main() {\n"
                    + "    gl_Position = aPosition;\n"
                    + "    vTexCoord = (uMatrix * aTexCoord).xy;\n"
                    + "}";

            String fragmentShader = "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "uniform samplerExternalOES sTexture;\n"
                    + "void main() {\n"
                    + "    gl_FragColor = texture2D(sTexture, vTexCoord);\n"
                    + "}";

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

            float[] coords = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
            float[] texs = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};

            vertexBuffer = ByteBuffer.allocateDirect(coords.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(coords);
            vertexBuffer.position(0);
            textureBuffer = ByteBuffer.allocateDirect(texs.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(texs);
            textureBuffer.position(0);
        }

        private void drawFrame() {
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(shaderProgram);

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
