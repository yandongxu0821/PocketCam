package cn.sharinghub.ipcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;
import android.view.OrientationEventListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;

public class CameraStreamService extends Service implements Camera.PreviewCallback {
    private static final String TAG = "PocketCam";
    private static final int NOTIFICATION = 1;
    /** Ground truth for UI: lives in the same process as the service, so a dead process always reads false. */
    private static volatile CameraStreamService active;

    /** True only while a service instance has the camera and HTTP socket open. */
    public static boolean isRunning() {
        CameraStreamService s = active;
        if (s == null || !s.serving) return false;
        List<ServerSocket> servers = s.servers;
        return servers != null && !servers.isEmpty() && !servers.get(0).isClosed();
    }

    /** Latest encoded JPEG frame for in-app preview, or null when not running. */
    public static byte[] currentJpeg() {
        CameraStreamService s = active;
        return s == null ? null : s.latestJpeg;
    }

    /** Degrees to rotate raw camera frames so they appear upright for the natural device orientation. */
    public static int previewRotation() {
        return active == null ? 0 : active.frameRotation;
    }

    /** Camera state for the status icon: released, capturing, or failed to open. */
    public static int cameraState() {
        CameraStreamService s = active;
        if (s == null) return CAMERA_OFF;
        if (s.camera != null) return CAMERA_ON;
        return s.cameraError.length() > 0 ? CAMERA_ERROR : CAMERA_OFF;
    }

    /** The preview page holds the camera open while it is on screen. */
    public static void holdForPreview() {
        CameraStreamService s = active;
        if (s == null) return;
        synchronized (PREVIEW_LOCK) { previewHolders++; }
        s.requestCamera();
    }

    public static void releasePreview() {
        synchronized (PREVIEW_LOCK) { if (previewHolders > 0) previewHolders--; }
    }

    /** Opens the camera now, e.g. right after the user turns idle auto-close off. */
    public static void requestCameraOpen() {
        CameraStreamService s = active;
        if (s != null) s.requestCamera();
    }

    private volatile int frameRotation;

    /** True while the device is physically upside down; the encoder flips frames 180° so clients stay upright. */
    private volatile boolean flip180;
    /** Last quantized (0/90/180/270) physical orientation from the accelerometer, or -1 when unknown. */
    private int sensorOrientation = -1;
    private int cameraSensorOrientation = 90;
    private int cameraFacing = Camera.CameraInfo.CAMERA_FACING_BACK;
    private OrientationEventListener orientationListener;
    private byte[] flipBuffer;

    /** RTSP port actually in use, or 0 when RTSP is off. */
    public static int rtspPort() {
        CameraStreamService s = active;
        return s != null && s.rtsp != null ? s.rtspBoundPort : 0;
    }

    private volatile Camera camera;
    /** One listening socket per address family, so clients on any interface can reach the port. */
    private volatile List<ServerSocket> servers;
    private final ArrayBlockingQueue<byte[]> pendingFrame = new ArrayBlockingQueue<>(1);
    private final List<OutputStream> mjpegClients = new ArrayList<>();
    private final List<OutputStream> audioClients = new ArrayList<>();
    private final List<FlvClient> flvClients = new ArrayList<>();
    private volatile boolean serving;
    private volatile byte[] latestJpeg;
    private SurfaceTexture surfaceTexture;
    private final List<Thread> acceptThreads = new ArrayList<>();
    private Thread encoderThread;
    private String authHeader;
    /** Raw home page; the configured title is substituted per request. */
    private String indexTemplate;
    private volatile byte[] favicon;
    /** Cached burned-in overlay so text is rasterized once per second, not per frame. */
    private Bitmap overlayMask;
    private long overlayMaskBuiltAt;
    private String overlayMaskText = "";
    private int overlayMaskWidth, overlayMaskHeight;
    private static final int FAVICON_SIZE = 256;
    private int previewWidth = 320, previewHeight = 240;
    private int port = 8080;
    private int rtspBoundPort;
    private H264Encoder h264;
    private RtspServer rtsp;
    private volatile byte[] h264Sps, h264Pps;
    private AacEncoder aac;
    private volatile byte[] aacConfig;
    private Handler launchHandler;
    private Runnable launchRunnable, returnRunnable;
    private android.os.PowerManager.WakeLock wakeLock;

    /** One /live.flv viewer; timestamps start at its first tag, video at its first keyframe. */
    private static class FlvClient {
        OutputStream out;
        long basePtsUs = -1;
        boolean videoStarted;
    }

    public static final int CAMERA_OFF = 0, CAMERA_ON = 1, CAMERA_ERROR = 2;
    /** Grace window before an idle camera is released, so page reloads don't churn the HAL. */
    private static final long IDLE_GRACE_MS = 8000;
    private static final Object PREVIEW_LOCK = new Object();
    private static int previewHolders;
    private final Object cameraLock = new Object();
    private volatile String cameraError = "";
    private long cameraIdleSince;
    private Thread idleThread;

    @Override public void onCreate() {
        super.onCreate();
        // The display rotation is useless here: MainActivity is locked to portrait and this
        // service often runs with no activity at all, so watch the accelerometer instead.
        orientationListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override public void onOrientationChanged(int angle) {
                if (angle != ORIENTATION_UNKNOWN) onDeviceOrientation(angle);
            }
        };
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
    }

    /** Quantizes the raw angle to 90° steps and refreshes the 180° flip state. */
    private void onDeviceOrientation(int angle) {
        int quantized = ((angle + 45) / 90 % 4) * 90;
        if (quantized == sensorOrientation) return;
        sensorOrientation = quantized;
        updateFlip();
    }

    /**
     * Applies the standard camera formula with the physical device orientation. The sensor angle
     * runs opposite to display rotations (turning the device counter-clockwise reports 270), so
     * it is mirrored first. Only 0 and 180 are actionable (a 180° flip keeps the frame size);
     * 90/270 would swap width and height, so the previous state is kept while passing through.
     */
    private void updateFlip() {
        if (sensorOrientation < 0) return;
        int degrees = (360 - sensorOrientation) % 360;
        int rot = cameraFacing == Camera.CameraInfo.CAMERA_FACING_FRONT
                ? (cameraSensorOrientation + degrees) % 360
                : (cameraSensorOrientation - degrees + 360) % 360;
        if (rot == 0) flip180 = false;
        else if (rot == 180) flip180 = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        serving = true;
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            port = readPort(prefs, "http_port", 8080);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION, notification());
            }
            authHeader = "Basic " + Base64.encodeToString((prefs.getString("http_user", "") + ":" + prefs.getString("http_password", "")).getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP).trim();
            // Keep every URL inside the page relative so it also works behind a reverse proxy prefix.
            indexTemplate = readIndexPage();
            startHttpServer();
            startRtspIfEnabled(prefs);
            startEncoderThread();
            startIdleWatcher();
            active = this;
            cameraError = "";
            // Open at start so an unusable camera still fails loudly; the idle watcher
            // releases it again if no client shows up within the grace window.
            if (!ensureCamera()) throw new IOException(cameraError);
            prefs.edit().putBoolean("service_running", true)
                    .putBoolean("service_should_run", true)
                    .putString("service_error", "").apply();
            acquireWakeLock();
            KeepAlive.start(this);
            scheduleAppLaunch();
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            String message = e instanceof BindException ? "端口 " + port + " 已被占用。" : "启动失败：" + e.getMessage();
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean("service_running", false)
                    .putBoolean("service_should_run", false) // a failed start must not be retried by the watchdog
                    .putString("service_error", message).apply();
            KeepAlive.stop(this);
            release(); stopSelf();
        }
        return START_STICKY;
    }

    private static int readPort(SharedPreferences prefs, String key, int fallback) {
        int value = prefs.getInt(key, fallback);
        return value >= 1 && value <= 65535 ? value : fallback;
    }

    /** Loads the bundled home page once; it carries the buttons for every supported endpoint. */
    private String readIndexPage() {
        java.io.InputStream in = null;
        try {
            in = getResources().openRawResource(R.raw.index);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "index page unreadable", e);
            return "<!doctype html><html><body>主页资源缺失</body></html>";
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    /** Substitutes the configured title; read per request so a reload picks up edits. */
    private String indexPage() {
        String title = PreferenceManager.getDefaultSharedPreferences(this).getString("web_title", "").trim();
        if (title.isEmpty()) title = "PocketCam";
        return indexTemplate.replace("{{TITLE}}", escapeHtml(title));
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** The app icon rendered at 256 px; browsers accept PNG bytes even for a .ico request. */
    private void sendFavicon(OutputStream out) throws IOException {
        byte[] png = faviconPng();
        if (png == null) { sendNotFound(out); return; }
        sendBytes(out, 200, "OK", "image/png", png);
    }

    private byte[] faviconPng() {
        byte[] cached = favicon;
        if (cached != null) return cached;
        try {
            android.graphics.drawable.Drawable icon = getResources().getDrawable(
                    getPackageManager().getApplicationInfo(getPackageName(), 0).icon);
            Bitmap bitmap = Bitmap.createBitmap(FAVICON_SIZE, FAVICON_SIZE, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, FAVICON_SIZE, FAVICON_SIZE);
            icon.draw(new Canvas(bitmap));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
            cached = png.toByteArray();
            favicon = cached;
            return cached;
        } catch (Exception e) {
            Log.w(TAG, "favicon unavailable", e);
            return null;
        }
    }

    /** Launches the user-chosen app a configurable number of seconds after the service is up. */
    private void scheduleAppLaunch() {
        if (launchHandler == null) launchHandler = new Handler(); // onStartCommand runs on the main thread
        if (launchRunnable != null) launchHandler.removeCallbacks(launchRunnable);
        if (returnRunnable != null) { launchHandler.removeCallbacks(returnRunnable); returnRunnable = null; }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final String pkg = prefs.getString("launch_app_pkg", "");
        if (pkg.isEmpty()) return;
        final int delaySec = clamp(prefs.getInt("launch_app_delay", 10), 1, 600);
        // -1 keeps the other app in front; 0 returns at once, which effectively starts it in the
        // background with its process warmed up; N>0 leaves it visible for N seconds.
        final int staySec = clamp(prefs.getInt("launch_app_stay", 20), -1, 600);
        launchRunnable = () -> {
            launchRunnable = null;
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch == null) return; // the chosen app was uninstalled since
                // Services have no activity stack, so a task flag is mandatory.
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            } catch (Exception e) {
                Log.w(TAG, "launch app failed", e);
                return;
            }
            if (staySec < 0) return;
            returnRunnable = () -> {
                returnRunnable = null;
                bringSelfToFront();
            };
            launchHandler.postDelayed(returnRunnable, staySec * 1000L);
        };
        launchHandler.postDelayed(launchRunnable, delaySec * 1000L);
    }

    /** Brings the PocketCam main screen back so the launched app stays alive in the background. */
    private void bringSelfToFront() {
        final Intent back = new Intent(this, MainActivity.class);
        // CLEAR_TOP drops any settings page above the main screen, so the task never
        // ends up with two stack entries that each need a back press to leave; SINGLE_TOP
        // reuses the running main screen instead of stacking another copy of it.
        back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        final long attemptedAt = SystemClock.elapsedRealtime();
        try {
            startActivity(back);
        } catch (Exception e) {
            Log.w(TAG, "return to PocketCam failed", e);
            return;
        }
        if (launchHandler == null) launchHandler = new Handler();
        // A blocked background start fails silently, so watch whether the main screen really
        // resumed and keep the outcome for the launch page's permission hint.
        launchHandler.postDelayed(() -> {
            boolean reached = MainActivity.lastResumeAt >= attemptedAt;
            PreferenceManager.getDefaultSharedPreferences(this)
                    .edit().putBoolean("bg_popup_missing", !reached).apply();
        }, 1500);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    /** Keeps the CPU awake while the screen is off, otherwise streaming stalls once the device sleeps. */
    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PocketCam:camera");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "wake lock failed", e);
        }
    }

    private void startRtspIfEnabled(SharedPreferences prefs) {
        prefs.edit().putString("rtsp_error", "").apply();
        if (!prefs.getBoolean("rtsp_enabled", false)) return;
        rtspBoundPort = readPort(prefs, "rtsp_port", 8554);
        try {
            // The encoder is created when the camera opens, since it needs the real frame size.
            rtsp = new RtspServer(rtspBoundPort, prefs.getBoolean("rtsp_udp", false), this::requestCamera);
            rtsp.start();
        } catch (Exception e) {
            Log.e(TAG, "RTSP start failed", e);
            String message = e instanceof BindException
                    ? "RTSP 端口 " + rtspBoundPort + " 已被占用"
                    : "RTSP 服务启动失败：" + e.getMessage();
            prefs.edit().putString("rtsp_error", message).apply();
            if (rtsp != null) { rtsp.stop(); rtsp = null; }
        }
    }

    /** Creates the H.264 encoder for the size the camera actually opened at. */
    private void startH264EncoderIfNeeded() {
        startH264EncoderIfNeeded(false);
    }

    private void startH264EncoderIfNeeded(boolean force) {
        if (h264 != null) return;
        if (!force && rtsp == null && flvClientCount() == 0) return;
        try {
            h264 = new H264Encoder(previewWidth, previewHeight, new H264Encoder.Listener() {
                @Override public void onFormat(byte[] sps, byte[] pps) {
                    h264Sps = sps;
                    h264Pps = pps;
                    RtspServer r = rtsp;
                    if (r != null) r.setSpsPps(sps, pps);
                }
                @Override public void onAccessUnit(byte[] annexB, long ptsUs) {
                    RtspServer r = rtsp;
                    if (r != null) r.broadcast(annexB, ptsUs);
                    broadcastFlv(annexB, ptsUs);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "H.264 encoder failed", e);
            h264 = null;
            PreferenceManager.getDefaultSharedPreferences(this)
                    .edit().putString("rtsp_error", "H.264 编码器不可用：" + e.getMessage()).apply();
        }
        // Audio rides along whenever FLV is the reason the encoder runs.
        if (force || flvClientCount() > 0) startAacEncoderIfNeeded();
    }

    /** Microphone is optional: without it (or its permission) FLV just stays video-only. */
    private void startAacEncoderIfNeeded() {
        if (aac != null && aac.isAlive()) return;
        if (aac != null) { aac.close(); aac = null; aacConfig = null; }
        try {
            aac = new AacEncoder(new AacEncoder.Listener() {
                @Override public void onFormat(byte[] audioSpecificConfig) {
                    aacConfig = audioSpecificConfig;
                }
                @Override public void onAccessUnit(byte[] frame, long ptsUs) {
                    broadcastFlvAudio(frame, ptsUs);
                }
                @Override public void onPcm(byte[] pcm, int length) {
                    broadcastAudioPcm(pcm, length);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "audio capture unavailable", e);
            aac = null;
        }
    }

    private int flvClientCount() {
        synchronized (flvClients) { return flvClients.size(); }
    }

    /** Asynchronous open so HTTP/RTSP threads never wait on the camera HAL. */
    private void requestCamera() {
        if (camera != null) return;
        new Thread(this::ensureCamera, "camera-open").start();
    }

    /** Opens the camera if it is released; returns false and records the reason on failure. */
    private boolean ensureCamera() {
        synchronized (cameraLock) {
            if (camera != null) return true;
            try {
                openCamera();
                cameraError = "";
                cameraIdleSince = 0;
                Log.i(TAG, "camera opened " + previewWidth + "x" + previewHeight);
                return true;
            } catch (Throwable e) {
                camera = null;
                cameraError = "摄像头不可用：" + e.getMessage();
                Log.e(TAG, "camera open failed", e);
                return false;
            }
        }
    }

    private void openCamera() throws IOException {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int facing = prefs.getInt("camera_facing", Camera.CameraInfo.CAMERA_FACING_BACK);
        int cameraId = findCameraId(facing);
        if (cameraId < 0) cameraId = 0;
        Camera cam = openCameraWithRetry(cameraId);
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, info);
            cameraFacing = info.facing;
            cameraSensorOrientation = info.orientation;
            updateFlip(); // Re-evaluate with the real sensor orientation once the camera is known.
            frameRotation = computeFrameRotation(cameraId);
            Camera.Parameters params = cam.getParameters();
            cacheSupportedSizes(prefs, params);
            int width = prefs.getInt("camera_width", 320);
            int height = prefs.getInt("camera_height", 240);
            if (!isSupported(params, width, height)) {
                width = 320; height = 240;
                if (!isSupported(params, width, height)) {
                    Camera.Size fallback = params.getPreferredPreviewSizeForVideo();
                    if (fallback == null) fallback = params.getSupportedPreviewSizes().get(0);
                    width = fallback.width; height = fallback.height;
                }
            }
            params.setPreviewSize(width, height);
            params.setPreviewFormat(ImageFormat.NV21);
            applyFocusMode(params);
            cam.setParameters(params);
            int bufferSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            cam.addCallbackBuffer(new byte[bufferSize]);
            cam.addCallbackBuffer(new byte[bufferSize]);
            cam.addCallbackBuffer(new byte[bufferSize]);
            cam.setPreviewCallbackWithBuffer(this);
            // Another client (the system camera app, or a page that opens the camera itself)
            // evicts this handle on newer devices; drop it so the next viewer reopens cleanly.
            cam.setErrorCallback((error, failed) -> onCameraError(failed));
            SurfaceTexture texture = new SurfaceTexture(10);
            cam.setPreviewTexture(texture);
            previewWidth = width; previewHeight = height;
            // Publish the camera before frames arrive: the encoder recycles callback buffers
            // through this field, and dropping those would starve the preview permanently.
            camera = cam;
            cam.startPreview(); // Frames start arriving here, so the size must already be set.
            // Continuous modes refocus by themselves; single-shot AUTO needs one trigger after preview starts.
            if (Camera.Parameters.FOCUS_MODE_AUTO.equals(params.getFocusMode())) {
                try { cam.autoFocus(null); } catch (RuntimeException ignored) { }
            }
            surfaceTexture = texture;
            startH264EncoderIfNeeded();
            // Open the microphone while the app is still in the foreground. On Android 12+ the
            // system mics are blocked for background apps, so a lazy start on the first audio
            // client (which may arrive after the user left the app) would only capture silence.
            startAacEncoderIfNeeded();
        } catch (IOException e) {
            camera = null;
            try { cam.release(); } catch (Exception ignored) { }
            throw e;
        } catch (RuntimeException e) {
            camera = null;
            try { cam.release(); } catch (Exception ignored) { }
            throw e;
        }
    }

    /** Prefers continuous autofocus so distant/near subjects stay sharp while streaming. */
    private static void applyFocusMode(Camera.Parameters params) {
        List<String> modes = params.getSupportedFocusModes();
        if (modes == null || modes.isEmpty()) return;
        if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        } else if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        } else if (modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        }
    }

    /**
     * A handle that was evicted or errored out cannot be revived. Dropping it lets the next
     * viewer (or a still-open preview) open a fresh camera instead of streaming nothing.
     */
    private void onCameraError(Camera failed) {
        Log.w(TAG, "camera handle lost, reopening if still needed");
        synchronized (cameraLock) {
            if (camera != failed) return;
            camera = null;
            try { failed.setPreviewCallbackWithBuffer(null); } catch (Exception ignored) { }
            try { failed.release(); } catch (Exception ignored) { }
        }
        if (demand() > 0) requestCamera();
    }

    /** Releases the camera and encoder so the hardware can sleep while nobody watches. */
    private void closeCamera() {
        synchronized (cameraLock) {
            if (camera != null) {
                Log.i(TAG, "releasing idle camera");
                try {
                    camera.setPreviewCallbackWithBuffer(null);
                    camera.stopPreview();
                    camera.release();
                } catch (Exception ignored) { }
                camera = null;
            }
            if (h264 != null) { h264.close(); h264 = null; }
            h264Sps = null;
            h264Pps = null;
            if (aac != null) { aac.close(); aac = null; }
            aacConfig = null;
            RtspServer r = rtsp;
            if (r != null) r.setSpsPps(null, null);
            if (surfaceTexture != null) {
                try { surfaceTexture.release(); } catch (Exception ignored) { }
                surfaceTexture = null;
            }
            latestJpeg = null;
            cameraIdleSince = 0;
        }
    }

    /** Waits for an on-demand camera open so a stream request can report failure clearly. */
    private boolean awaitCamera(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (camera == null && cameraError.length() == 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) { break; }
        }
        return camera != null;
    }

    /** Number of consumers that currently need frames. */
    private int demand() {
        int n = 0;
        synchronized (mjpegClients) { n += mjpegClients.size(); }
        synchronized (audioClients) { n += audioClients.size(); }
        synchronized (flvClients) { n += flvClients.size(); }
        RtspServer r = rtsp;
        if (r != null) n += r.connectedCount();
        synchronized (PREVIEW_LOCK) { n += previewHolders; }
        return n;
    }

    /** Watches demand and releases the camera once nothing needs it for a grace period. */
    private void startIdleWatcher() {
        idleThread = new Thread(() -> {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            while (serving) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                try {
                    if (!prefs.getBoolean("camera_idle_close", true) || camera == null || demand() > 0) {
                        cameraIdleSince = 0;
                        continue;
                    }
                    long now = System.currentTimeMillis();
                    if (cameraIdleSince == 0) cameraIdleSince = now;
                    else if (now - cameraIdleSince >= IDLE_GRACE_MS) closeCamera();
                } catch (Throwable e) {
                    Log.w(TAG, "idle check failed", e);
                }
            }
        }, "idle-watch");
        idleThread.start();
    }

    /** Camera.open right after a previous process died (or right after boot, while the
     *  HAL still initializes) races the camera release; retry for up to ~30 seconds. */
    private static Camera openCameraWithRetry(int cameraId) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                return Camera.open(cameraId);
            } catch (RuntimeException e) {
                last = e;
                Log.w(TAG, "camera busy, retry " + (attempt + 1));
                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
            }
        }
        throw last;
    }

    private static int findCameraId(int facing) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int id = 0; id < Camera.getNumberOfCameras(); id++) {
            Camera.getCameraInfo(id, info);
            if (info.facing == facing) return id;
        }
        return -1;
    }

    /** Remembers the hardware size list so /size can answer without opening the camera. */
    private static void cacheSupportedSizes(SharedPreferences prefs, Camera.Parameters params) {
        StringBuilder sizes = new StringBuilder();
        for (Camera.Size size : params.getSupportedPreviewSizes()) {
            if (sizes.length() > 0) sizes.append(',');
            sizes.append(size.width).append('x').append(size.height);
        }
        prefs.edit().putString("camera_sizes", sizes.toString()).apply();
    }

    private boolean isSupported(Camera.Parameters params, int width, int height) {
        for (Camera.Size s : params.getSupportedPreviewSizes()) if (s.width == width && s.height == height) return true;
        return false;
    }

    /** Matches the framework rule for presenting camera frames upright on the natural display. */
    private int computeFrameRotation(int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int rotation = ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case android.view.Surface.ROTATION_90: degrees = 90; break;
            case android.view.Surface.ROTATION_180: degrees = 180; break;
            case android.view.Surface.ROTATION_270: degrees = 270; break;
            default: break;
        }
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return (info.orientation + degrees) % 360;
        return (info.orientation - degrees + 360) % 360;
    }

    private void startHttpServer() throws IOException {
        servers = bindAllAddresses(port);
        for (final ServerSocket socket : servers) {
            Thread accept = new Thread(() -> {
                while (serving) {
                    try { handle(socket.accept()); }
                    catch (IOException e) { if (serving) Log.w(TAG, "accept failed", e); }
                }
            }, "http-accept");
            acceptThreads.add(accept);
            accept.start();
        }
    }

    /**
     * Binds the port on the IPv4 and the IPv6 wildcard address. A plain ServerSocket(port) only
     * listens on IPv4, which leaves clients that reach the phone over an IPv6 VPN/tun interface
     * unable to connect. Either family alone is enough to keep serving, so a failed bind for one
     * of them is tolerated (the platforms often make one of the two collide with the other).
     */
    private static List<ServerSocket> bindAllAddresses(int port) throws IOException {
        List<ServerSocket> sockets = new ArrayList<>(2);
        IOException failure = null;
        for (String host : new String[]{"0.0.0.0", "::"}) {
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(InetAddress.getByName(host), port), 50);
                sockets.add(socket);
            } catch (IOException e) {
                if (failure == null) failure = e;
            }
        }
        if (sockets.isEmpty()) throw failure;
        return sockets;
    }

    private void handle(final Socket socket) {
        new Thread(() -> {
            try {
                socket.setSoTimeout(0);
                OutputStream out = socket.getOutputStream();
                java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                String request = in.readLine();
                if (request == null) { socket.close(); return; }
                String auth = null, line;
                while ((line = in.readLine()) != null && line.length() > 0)
                    if (line.regionMatches(true, 0, "Authorization:", 0, 14)) auth = line.substring(14).trim();
                String path = request.split(" ").length > 1 ? request.split(" ")[1] : "/";
                int query = path.indexOf('?');
                if (query >= 0) path = path.substring(0, query);
                if (authHeader == null || !authHeader.equals(auth)) { sendUnauthorized(out); return; }
                serve(path, out);
            } catch (IOException ignored) {
            } catch (Exception e) {
                Log.w(TAG, "http request failed", e);
            }
        }, "http-client").start();
    }

    /**
     * Routes on the last path segment, so the API answers both when called directly and when a
     * reverse proxy mounts the server under a prefix. Generated links stay relative for the same reason.
     */
    private void serve(String path, OutputStream out) throws IOException {
        String key = lastSegment(path).toLowerCase(Locale.US);
        String parent = parentSegment(path).toLowerCase(Locale.US);
        if (path.equals("/") || path.endsWith("/") || key.equals("index")) { sendHtml(out, indexPage()); return; }
        if (key.equals("favicon.png") || key.equals("favicon.ico")) { sendFavicon(out); return; }
        if (key.equals("video") || key.equals("stream.mjpg")) { serveMjpeg(out); return; }
        if (key.equals("audio") || key.equals("audio.wav")) { serveAudioWav(out); return; }
        if (key.equals("live.flv")) { serveFlv(out); return; }
        if (key.equals("serverinfo")) { sendText(out, serverInfo()); return; }
        if (key.equals("getsnapshot")) { sendText(out, takeSnapshot()); return; }
        if (key.equals("snapshot.jpg")) { sendJpeg(out); return; }
        if (key.equals("getarchives")) { sendHtml(out, archives()); return; }
        if (key.equals("size")) { sendHtml(out, sizeMenu()); return; }
        if (key.startsWith("size") && isDigits(key.substring(4))) { sendText(out, selectSize(Integer.parseInt(key.substring(4)))); return; }
        if (key.equals("light")) { sendText(out, toggleLight()); return; }
        if (key.equals("camswitch")) { sendText(out, switchCamera()); return; }
        if (key.equals("toggle_motion")) { sendText(out, toggleMotion()); return; }
        if (key.equals("get_motion_status")) { sendText(out, motionStatus()); return; }
        if (parent.equals("get")) { sendSnapshotFile(out, lastSegment(path)); return; }
        sendNotFound(out);
    }

    // ------------------------------------------------------------------ responses

    /** Same cache headers the reference server sends on every non-stream response. */
    private static final String COMMON_HEADERS =
            "Connection: close\r\nMax-Age: 0\r\nExpires: 0\r\nCache-Control: no-cache, private\r\nPragma: no-cache\r\n";

    private static final String NOT_FOUND_PAGE =
            "<!DOCTYPE html PUBLIC \"-//W3C//DTD HTML 4.01//EN\">\n<html>\n<head>\n    <meta charset=\"utf-8\">\n"
                    + "    <title>404</title>\n</head>\n<body>\n<p style=\"text-align:center;\">\n"
                    + "    <span style=\"font-family:arial, helvetica, sans-serif;font-size:72px;\">404</span>\n</p>\n"
                    + "<p style=\"text-align:center;\">\n    <span style=\"font-family:arial, helvetica, sans-serif;font-size:72px;\">:(</span>\n</p>\n"
                    + "<p style=\"text-align:center;\">\n    <span style=\"font-family:arial, helvetica, sans-serif;font-size:16px;\">No&nbsp;Found,&nbsp;未找到！</span>\n</p>\n"
                    + "</body>\n</html>\n";

    private void sendUnauthorized(OutputStream out) throws IOException {
        byte[] body = "401 Unauthorized".getBytes("UTF-8");
        out.write(("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"PocketCam\"\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(body);
        out.flush();
        out.close();
    }

    private void sendBytes(OutputStream out, int code, String status, String contentType, byte[] body) throws IOException {
        out.write(("HTTP/1.0 " + code + " " + status + "\r\nContent-Type: " + contentType
                + "\r\nContent-Length: " + body.length + "\r\n" + COMMON_HEADERS + "\r\n").getBytes("UTF-8"));
        out.write(body);
        out.flush();
        out.close();
    }

    private void sendText(OutputStream out, String text) throws IOException {
        sendBytes(out, 200, "OK", "text/plain;charset=utf-8", text.getBytes("UTF-8"));
    }

    private void sendHtml(OutputStream out, String html) throws IOException {
        sendBytes(out, 200, "OK", "text/html;charset=utf-8", html.getBytes("UTF-8"));
    }

    private void sendNotFound(OutputStream out) throws IOException {
        sendBytes(out, 404, "NOT FOUND", "text/html;charset=utf-8", NOT_FOUND_PAGE.getBytes("UTF-8"));
    }

    private void serveMjpeg(OutputStream out) throws IOException {
        // Camera may be released while idle; wake it before promising a stream.
        requestCamera();
        if (!awaitCamera(10000)) {
            String reason = cameraError.length() > 0 ? cameraError : "摄像头启动超时";
            sendBytes(out, 503, "Service Unavailable", "text/plain;charset=utf-8", reason.getBytes("UTF-8"));
            return;
        }
        out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace;boundary=frame\r\nCache-Control: no-cache\r\n\r\n".getBytes("UTF-8"));
        out.flush();
        synchronized (mjpegClients) { mjpegClients.add(out); }
    }

    /**
     * Streams raw microphone PCM wrapped in a WAV container. Browsers play this directly via
     * an {@code <audio src="audio.wav">} element. The data chunk size is set to 0xFFFFFFFF so
     * the browser treats the body as an unbounded live stream.
     */
    private void serveAudioWav(OutputStream out) throws IOException {
        startAacEncoderIfNeeded();
        // The AAC encoder owns the microphone; if it failed to start there is nothing to stream.
        if (aac == null) {
            sendBytes(out, 503, "Service Unavailable", "text/plain;charset=utf-8", "麦克风不可用".getBytes("UTF-8"));
            return;
        }
        out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
        out.write(wavHeader());
        out.flush();
        synchronized (audioClients) { audioClients.add(out); }
    }

    /** 44-byte WAV header for 44.1 kHz mono 16-bit PCM with an unbounded data chunk. */
    private static byte[] wavHeader() {
        int sampleRate = AacEncoder.SAMPLE_RATE;
        int channels = 1;
        int bits = 16;
        int byteRate = sampleRate * channels * bits / 8;
        int blockAlign = channels * bits / 8;
        java.io.ByteArrayOutputStream h = new java.io.ByteArrayOutputStream(44);
        try {
            h.write("RIFF".getBytes("US-ASCII"));
            h.write(intLe(0xFFFFFFFF));           // file size: unknown (streaming)
            h.write("WAVE".getBytes("US-ASCII"));
            h.write("fmt ".getBytes("US-ASCII"));
            h.write(intLe(16));                   // fmt chunk size
            h.write(shortLe(1));                  // PCM
            h.write(shortLe(channels));
            h.write(intLe(sampleRate));
            h.write(intLe(byteRate));
            h.write(shortLe(blockAlign));
            h.write(shortLe(bits));
            h.write("data".getBytes("US-ASCII"));
            h.write(intLe(0xFFFFFFFF));           // data size: unknown (streaming)
        } catch (IOException ignored) { }
        return h.toByteArray();
    }

    private static byte[] intLe(int v) {
        return new byte[]{ (byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24) };
    }

    private static byte[] shortLe(int v) {
        return new byte[]{ (byte) v, (byte) (v >>> 8) };
    }

    /** Fans one PCM chunk out to every WAV audio client synchronously on the encoder thread. */
    private void broadcastAudioPcm(byte[] pcm, int length) {
        List<OutputStream> clients;
        synchronized (audioClients) {
            if (audioClients.isEmpty()) return;
            clients = new ArrayList<>(audioClients);
        }
        for (int i = clients.size() - 1; i >= 0; i--) {
            OutputStream out = clients.get(i);
            try {
                out.write(pcm, 0, length);
                out.flush();
            } catch (IOException e) {
                try { out.close(); } catch (IOException ignored) { }
                synchronized (audioClients) { audioClients.remove(out); }
            }
        }
    }

    /**
     * FLV live stream, compatible with the reference server's /live.flv: H.264 video
     * plus an AAC audio track when the microphone is available. The connection stays
     * open and receives FLV tags until the client goes away.
     */
    private void serveFlv(OutputStream out) throws IOException {
        requestCamera();
        if (!awaitCamera(10000)) {
            String reason = cameraError.length() > 0 ? cameraError : "摄像头启动超时";
            sendBytes(out, 503, "Service Unavailable", "text/plain;charset=utf-8", reason.getBytes("UTF-8"));
            return;
        }
        // The encoder is created on demand; SPS/PPS appear once it starts producing.
        startH264EncoderIfNeeded(true);
        long deadline = System.currentTimeMillis() + 8000;
        while ((h264Sps == null || h264Pps == null) && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
        }
        if (h264Sps == null || h264Pps == null) {
            sendBytes(out, 503, "Service Unavailable", "text/plain;charset=utf-8", "编码器未就绪".getBytes("UTF-8"));
            return;
        }
        // Audio is optional; give the microphone a moment to deliver its config.
        startAacEncoderIfNeeded();
        long audioDeadline = System.currentTimeMillis() + 2000;
        while (aacConfig == null && aac != null && System.currentTimeMillis() < audioDeadline) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
        }
        byte[] asc = aacConfig;
        boolean hasAudio = asc != null;
        out.write("HTTP/1.1 200 OK\r\nContent-Type: video/x-flv\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
        FlvClient client = new FlvClient();
        client.out = out;
        synchronized (client) {
            out.write(FlvMuxer.header(hasAudio));
            out.write(FlvMuxer.metadata(previewWidth, previewHeight, hasAudio));
            out.write(FlvMuxer.sequenceHeader(h264Sps, h264Pps));
            if (hasAudio) out.write(FlvMuxer.audioSequenceHeader(asc));
            out.flush();
            synchronized (flvClients) { flvClients.add(client); }
        }
    }

    /** Fans one Annex-B access unit out to every FLV client as a video tag. */
    private void broadcastFlv(byte[] annexB, long ptsUs) {
        List<FlvClient> clients;
        synchronized (flvClients) {
            if (flvClients.isEmpty()) return;
            clients = new ArrayList<>(flvClients);
        }
        boolean keyFrame = FlvMuxer.isKeyFrame(annexB);
        for (FlvClient client : clients) {
            synchronized (client) {
                if (!client.videoStarted) {
                    if (!keyFrame) continue; // start each client on a keyframe so decode begins cleanly
                    if (client.basePtsUs < 0) client.basePtsUs = ptsUs;
                    client.videoStarted = true;
                }
                long tsMs = (ptsUs - client.basePtsUs) / 1000;
                if (tsMs < 0) tsMs = 0; // audio may have anchored the clock slightly later
                byte[] tag = FlvMuxer.videoTag(annexB, tsMs, keyFrame);
                if (tag == null) continue;
                try {
                    client.out.write(tag);
                    client.out.flush();
                } catch (IOException e) {
                    try { client.out.close(); } catch (IOException ignored) { }
                    synchronized (flvClients) { flvClients.remove(client); }
                }
            }
        }
    }

    /** Fans one raw AAC frame out to every FLV client as an audio tag. */
    private void broadcastFlvAudio(byte[] frame, long ptsUs) {
        List<FlvClient> clients;
        synchronized (flvClients) {
            if (flvClients.isEmpty()) return;
            clients = new ArrayList<>(flvClients);
        }
        for (FlvClient client : clients) {
            synchronized (client) {
                if (client.basePtsUs < 0) client.basePtsUs = ptsUs;
                long tsMs = (ptsUs - client.basePtsUs) / 1000;
                if (tsMs < 0) tsMs = 0;
                try {
                    client.out.write(FlvMuxer.audioTag(frame, tsMs));
                    client.out.flush();
                } catch (IOException e) {
                    try { client.out.close(); } catch (IOException ignored) { }
                    synchronized (flvClients) { flvClients.remove(client); }
                }
            }
        }
    }

    private void sendJpeg(OutputStream out) throws IOException {
        byte[] frame = awaitFrame(10000);
        if (frame == null) {
            String reason = cameraError.length() > 0 ? cameraError : "摄像头启动超时";
            sendBytes(out, 503, "Service Unavailable", "text/plain;charset=utf-8", reason.getBytes("UTF-8"));
            return;
        }
        sendBytes(out, 200, "OK", "image/jpeg", frame);
    }

    // ------------------------------------------------------------------ endpoints

    private String serverInfo() {
        int clients;
        synchronized (mjpegClients) { clients = mjpegClients.size(); }
        RtspServer r = rtsp;
        if (r != null) clients += r.connectedCount();
        String state = clients == 0 ? "没有连接的客户端" : "有 " + clients + " 个客户端连接";
        int level = 0;
        boolean charging = false;
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int raw = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (raw >= 0 && scale > 0) level = raw * 100 / scale;
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        }
        return state + "  电量: " + level + "% 充电: " + (charging ? "YES" : "NO");
    }

    /** Saves the current frame into the archive directory and returns its name, like the reference server. */
    private String takeSnapshot() {
        byte[] frame = awaitFrame(10000);
        if (frame == null) return cameraError.length() > 0 ? cameraError : "快照失败：摄像头未就绪";
        String name = "IPC_" + new SimpleDateFormat("yyyy-MM-dd.HH.mm.ss.SSS", Locale.US).format(new Date()) + ".jpg";
        try {
            FileOutputStream file = new FileOutputStream(new File(snapshotDir(), name));
            try { file.write(frame); } finally { file.close(); }
            return "快照 " + name;
        } catch (IOException e) {
            return "快照失败：" + e.getMessage();
        }
    }

    private String archives() {
        File[] snapshots = listFiles(snapshotDir());
        StringBuilder html = new StringBuilder();
        html.append("<li><strong>存档 (").append(snapshots.length).append(")</strong></li>");
        for (File file : snapshots)
            html.append("<li><a href=\"get/").append(file.getName()).append("\">").append(file.getName()).append("</a></li>");
        File[] videos = listFiles(motionDir());
        if (videos.length > 0) {
            html.append("<li><strong>运动检测 (").append(videos.length).append(")</strong></li>");
            for (File file : videos)
                html.append("<li><a href=\"media/md/").append(file.getName()).append("\">").append(file.getName()).append("</a></li>");
        }
        return html.toString();
    }

    private String sizeMenu() {
        List<int[]> sizes = supportedSizes();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int currentWidth = prefs.getInt("camera_width", 320);
        int currentHeight = prefs.getInt("camera_height", 240);
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < sizes.size(); i++) {
            int[] size = sizes.get(i);
            boolean selected = size[0] == currentWidth && size[1] == currentHeight;
            String label = size[0] + "x" + size[1];
            html.append("<li><a href=\"#\"");
            if (selected) html.append(" data-sel=\"yes\"");
            html.append(" onclick=\"return onItemClick('size").append(i).append("')\">");
            html.append(selected ? "<strong>" + label + "</strong>" : label);
            html.append("</a></li>");
        }
        return html.toString();
    }

    private String selectSize(int index) {
        if (motionEnabled()) return "打开运动检测时不可用";
        List<int[]> sizes = supportedSizes();
        if (index < 0 || index >= sizes.size()) return "";
        int[] size = sizes.get(index);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (size[0] == prefs.getInt("camera_width", 320) && size[1] == prefs.getInt("camera_height", 240)) return "";
        prefs.edit().putInt("camera_width", size[0]).putInt("camera_height", size[1]).apply();
        // The preview size can only change while the camera is closed.
        closeCamera();
        return ensureCamera() ? "" : cameraError;
    }

    private String toggleLight() {
        if (!ensureCamera()) return cameraError;
        Camera cam = camera;
        Camera.Parameters params = cam.getParameters();
        List<String> modes = params.getSupportedFlashModes();
        if (modes == null || !modes.contains(Camera.Parameters.FLASH_MODE_TORCH)) return "当前摄像头不支持手电筒";
        boolean turnOff = Camera.Parameters.FLASH_MODE_TORCH.equals(params.getFlashMode());
        params.setFlashMode(turnOff ? Camera.Parameters.FLASH_MODE_OFF : Camera.Parameters.FLASH_MODE_TORCH);
        try {
            cam.setParameters(params);
        } catch (RuntimeException e) {
            return "手电筒切换失败：" + e.getMessage();
        }
        return "";
    }

    private String switchCamera() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int facing = prefs.getInt("camera_facing", Camera.CameraInfo.CAMERA_FACING_BACK);
        int next = facing == Camera.CameraInfo.CAMERA_FACING_BACK
                ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
        if (findCameraId(next) < 0) return "设备没有可切换的摄像头";
        prefs.edit().putInt("camera_facing", next).apply();
        closeCamera();
        if (ensureCamera()) return "";
        prefs.edit().putInt("camera_facing", facing).apply();
        return cameraError;
    }

    private String toggleMotion() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putBoolean("motion_detection", !motionEnabled()).apply();
        return "";
    }

    private String motionStatus() {
        if (Camera.getNumberOfCameras() == 0) return "-1";
        return motionEnabled() ? "1" : "0";
    }

    private boolean motionEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean("motion_detection", false);
    }

    // ------------------------------------------------------------------ helpers

    private byte[] awaitFrame(long timeoutMs) {
        requestCamera();
        awaitCamera(timeoutMs);
        return latestJpeg;
    }

    private File snapshotDir() {
        File dir = new File(getFilesDir(), "snapshots");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File motionDir() {
        File dir = new File(getFilesDir(), "motion");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File[] listFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return new File[0];
        Arrays.sort(files, (a, b) -> {
            long delta = b.lastModified() - a.lastModified();
            return delta < 0 ? -1 : (delta > 0 ? 1 : 0);
        });
        return files;
    }

    private void sendSnapshotFile(OutputStream out, String name) throws IOException {
        if (!name.matches("IPC_[0-9.\\-]+jpg")) { sendNotFound(out); return; }
        File file = new File(snapshotDir(), name);
        if (!file.isFile()) { sendNotFound(out); return; }
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            byte[] body = new byte[(int) file.length()];
            int read = 0;
            while (read < body.length) {
                int n = in.read(body, read, body.length - read);
                if (n < 0) break;
                read += n;
            }
            sendBytes(out, 200, "OK", "image/jpeg", body);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    private List<int[]> supportedSizes() {
        List<int[]> sizes = new ArrayList<>();
        String cached = PreferenceManager.getDefaultSharedPreferences(this).getString("camera_sizes", "");
        for (String item : cached.split(",")) {
            String[] wh = item.split("x");
            if (wh.length != 2) continue;
            try { sizes.add(new int[]{Integer.parseInt(wh[0]), Integer.parseInt(wh[1])}); } catch (NumberFormatException ignored) { }
        }
        if (sizes.isEmpty()) {
            Camera cam = camera;
            if (cam != null) for (Camera.Size size : cam.getParameters().getSupportedPreviewSizes()) sizes.add(new int[]{size.width, size.height});
        }
        java.util.Collections.sort(sizes, (a, b) -> a[0] * a[1] - b[0] * b[1]);
        return sizes;
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
        return true;
    }

    /** Path segment after the last slash, ignoring a trailing slash. */
    private static String lastSegment(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') end--;
        int start = path.lastIndexOf('/', end - 1) + 1;
        return path.substring(start, end);
    }

    /** Path segment right before the last one, or "" when there is only one. */
    private static String parentSegment(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') end--;
        int slash = path.lastIndexOf('/', end - 1);
        if (slash <= 0) return "";
        int start = path.lastIndexOf('/', slash - 1) + 1;
        return path.substring(start, slash);
    }

    /** Runs for the service lifetime; frames simply stall while the camera is released. */
    private void startEncoderThread() {
        encoderThread = new Thread(() -> {
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(64 * 1024);
            while (serving) {
                byte[] data;
                try {
                    data = pendingFrame.take();
                } catch (InterruptedException e) { return; }
                Camera cam = camera;
                if (cam == null) continue; // Camera closed while idle: drop the stale buffer.
                try {
                    byte[] frameData = data;
                    if (flip180) {
                        // Reused across frames; encoders never retain the array, so this is safe.
                        if (flipBuffer == null || flipBuffer.length != data.length) flipBuffer = new byte[data.length];
                        rotate180(data, flipBuffer, previewWidth, previewHeight);
                        frameData = flipBuffer;
                    }
                    stampOverlay(frameData, previewWidth, previewHeight);
                    jpeg.reset();
                    YuvImage yuv = new YuvImage(frameData, ImageFormat.NV21, previewWidth, previewHeight, null);
                    yuv.compressToJpeg(new android.graphics.Rect(0, 0, previewWidth, previewHeight), 70, jpeg);
                    latestJpeg = jpeg.toByteArray();
                    H264Encoder encoder = h264;
                    if (encoder != null) encoder.submit(frameData, System.nanoTime() / 1000);
                    byte[] frame = latestJpeg;
                    synchronized (mjpegClients) {
                        for (int i = mjpegClients.size() - 1; i >= 0; i--) {
                            try {
                                OutputStream out = mjpegClients.get(i);
                                out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n").getBytes("UTF-8"));
                                out.write(frame); out.write("\r\n".getBytes("UTF-8")); out.flush();
                            } catch (IOException e) { mjpegClients.remove(i); }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "encode failed", e);
                } finally {
                    // Every camera callback buffer must find its way back, otherwise a slow
                    // encoder or one bad frame permanently starves the preview.
                    try { cam.addCallbackBuffer(data); } catch (RuntimeException ignored) { }
                }
            }
        }, "jpeg-encoder");
        encoderThread.start();
    }

    /** Rotates an NV21 frame 180° into dst: the luma plane is reversed, and the interleaved
     *  VU pairs are reversed as pairs (each pair keeps its byte order). Size is unchanged. */
    private static void rotate180(byte[] src, byte[] dst, int width, int height) {
        int ySize = width * height;
        for (int i = 0; i < ySize; i++) dst[i] = src[ySize - 1 - i];
        int pairs = (src.length - ySize) / 2;
        for (int p = 0; p < pairs; p++) {
            int from = ySize + 2 * (pairs - 1 - p);
            dst[ySize + 2 * p] = src[from];
            dst[ySize + 2 * p + 1] = src[from + 1];
        }
    }

    /**
     * Burns the info overlay into the luma plane only: the mask's alpha decides how far each
     * pixel's brightness moves toward the text colour, so chroma (and colour) is untouched.
     * The same NV21 array is JPEG-compressed for MJPEG/in-app preview and handed to the H.264
     * encoder, so MJPEG, FLV, RTSP and the preview all show the same overlay.
     */
    private void stampOverlay(byte[] nv21, int width, int height) {
        Bitmap mask = overlayMask(width, height);
        if (mask == null) return;
        int maskWidth = mask.getWidth();
        int maskHeight = mask.getHeight();
        int[] pixels = new int[maskWidth * maskHeight];
        mask.getPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight);
        for (int row = 0; row < maskHeight; row++) {
            int frameBase = row * width;
            int maskBase = row * maskWidth;
            for (int x = 0; x < maskWidth; x++) {
                int color = pixels[maskBase + x];
                int alpha = color >>> 24;
                if (alpha == 0) continue;
                int luma = ((color >> 16 & 0xFF) * 299 + (color >> 8 & 0xFF) * 587 + (color & 0xFF) * 114) / 1000;
                int old = nv21[frameBase + x] & 0xFF;
                nv21[frameBase + x] = (byte) ((old * (255 - alpha) + luma * alpha) / 255);
            }
        }
    }

    /** Enabled overlay lines (time, battery, lens, custom text), re-rasterized at most once a second. */
    private Bitmap overlayMask(int width, int height) {
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean showTime = prefs.getBoolean("overlay_show_time", true);
        boolean showBattery = prefs.getBoolean("overlay_show_battery", true);
        boolean showLens = prefs.getBoolean("overlay_show_lens", true);
        String custom = prefs.getString("overlay_text", "").trim();
        String spec = custom + "|" + showTime + showBattery + showLens;
        long now = System.currentTimeMillis();
        if (overlayMask != null && overlayMaskWidth == width && overlayMaskHeight == height
                && now - overlayMaskBuiltAt < 1000 && spec.equals(overlayMaskText)) return overlayMask;

        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        StringBuilder first = new StringBuilder();
        if (showTime) first.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        String battery = showBattery ? batteryText() : "";
        if (!battery.isEmpty()) { if (first.length() > 0) first.append("  "); first.append(battery); }
        if (first.length() > 0) lines.add(first.toString());
        StringBuilder second = new StringBuilder();
        if (showLens) second.append(lensLabel());
        if (!custom.isEmpty()) { if (second.length() > 0) second.append("  "); second.append(custom); }
        if (second.length() > 0) lines.add(second.toString());

        overlayMaskText = spec;
        overlayMaskWidth = width;
        overlayMaskHeight = height;
        overlayMaskBuiltAt = now;
        if (lines.isEmpty()) { overlayMask = null; return null; }

        int textSize = Math.max(14, height / 20);
        float lineHeight = textSize * 1.4f;
        // The mask used to end exactly on the last baseline, so the lower half of the second
        // line (descenders and the outline stroke) was cut off, e.g. "后置镜头" lost its bottom.
        int maskHeight = Math.min(height, (int) Math.ceil(lineHeight * lines.size() + textSize * 0.45f));
        Bitmap bitmap = Bitmap.createBitmap(width, maskHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(textSize);
        float x = textSize * 0.5f;
        for (int i = 0; i < lines.size(); i++) {
            float baseline = lineHeight * (i + 1);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, textSize / 6f));
            paint.setColor(Color.BLACK);
            canvas.drawText(lines.get(i), x, baseline, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawText(lines.get(i), x, baseline, paint);
        }
        overlayMask = bitmap;
        return bitmap;
    }

    private String batteryText() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return "";
        int raw = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (raw < 0 || scale <= 0) return "";
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        return raw * 100 / scale + "%" + (charging ? " 充电中" : "");
    }

    private String lensLabel() {
        return cameraFacing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "前置镜头" : "后置镜头";
    }

    @Override public void onPreviewFrame(byte[] data, Camera cam) {
        if (!pendingFrame.offer(data)) {
            // Drop the oldest queued frame but hand its buffer straight back to the camera.
            byte[] dropped = pendingFrame.poll();
            if (dropped != null) cam.addCallbackBuffer(dropped);
            if (!pendingFrame.offer(data)) cam.addCallbackBuffer(data);
        }
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        // Tapping the notification must not push a second main screen onto the task.
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 11 ? new Notification.Builder(this) : null;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("cam", "Camera stream", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "cam");
        }
        Notification n = b.setContentTitle("PocketCam 摄像头服务运行中")
                .setContentText("端口 " + port + " 正在提供视频流")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentIntent(pi)
                .setOngoing(true).build();
        return n;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // Swiping the task away does not stop a foreground service by design, but some ROMs
        // kill it anyway. Ask the watchdog for an immediate check if the user still wants it up.
        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("service_should_run", false)) {
            KeepAlive.requestImmediateCheck(this);
        }
    }

    @Override public void onDestroy() {
        active = null;
        FrpcController.stop(); // Local proxy target is gone; any frpc mapping must stop too.
        PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean("service_running", false).apply();
        release();
        super.onDestroy();
    }

    private void release() {
        serving = false;
        if (orientationListener != null) orientationListener.disable();
        if (idleThread != null) idleThread.interrupt();
        if (launchHandler != null) {
            if (launchRunnable != null) { launchHandler.removeCallbacks(launchRunnable); launchRunnable = null; }
            if (returnRunnable != null) { launchHandler.removeCallbacks(returnRunnable); returnRunnable = null; }
        }
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
            wakeLock = null;
        }
        synchronized (PREVIEW_LOCK) { previewHolders = 0; }
        if (rtsp != null) { rtsp.stop(); rtsp = null; }
        for (Thread accept : acceptThreads) accept.interrupt();
        acceptThreads.clear();
        List<ServerSocket> listeners = servers;
        servers = null;
        if (listeners != null) for (ServerSocket listener : listeners) {
            try { listener.close(); } catch (IOException ignored) { }
        }
        if (encoderThread != null) encoderThread.interrupt();
        synchronized (mjpegClients) {
            for (OutputStream out : mjpegClients) try { out.close(); } catch (IOException ignored) { }
            mjpegClients.clear();
        }
        synchronized (audioClients) {
            for (OutputStream out : audioClients) try { out.close(); } catch (IOException ignored) { }
            audioClients.clear();
        }
        synchronized (flvClients) {
            for (FlvClient client : flvClients) try { client.out.close(); } catch (IOException ignored) { }
            flvClients.clear();
        }
        closeCamera();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
