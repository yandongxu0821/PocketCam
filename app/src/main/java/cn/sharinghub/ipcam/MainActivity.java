package cn.sharinghub.ipcam;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** Home page: service status plus one entry per standalone settings page. */
public class MainActivity extends Activity {
    private static final int CAMERA_REQUEST = 42;
    private static final String STATE_SCROLL_Y = "main_scroll_y";
    /**
     * Time of the last resume of the home screen. The service compares it with the moment it
     * tried to switch back, because a blocked background start fails without any error.
     */
    static volatile long lastResumeAt;
    private final Handler handler = new Handler();
    /** Scroll offset of the home list, kept across child pages and activity recreation. */
    private int savedScrollY;
    private boolean darkApplied;
    private View statusDot;
    private TextView statusText, statusDetail, frpcBadge, launchBadge;
    private CameraStatusIcon cameraIcon;
    private Button toggle;
    private boolean running;
    private final Runnable statusTicker = new Runnable() {
        @Override public void run() {
            updateCameraIcon();
            updateFrpcBadge(); // kernel state can change on its own (reconnect, error)
            updateLaunchBadge();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ThemeUtil.apply(this);
        darkApplied = ThemeUtil.isDark(this);
        setContentView(R.layout.activity_main);
        // A recreated activity starts scrolled to the top; restore the offset the user left.
        if (saved != null) savedScrollY = saved.getInt(STATE_SCROLL_Y, 0);
        bindUi();
        restoreScroll();
    }

    /** Pins the remembered offset until the layout settles, so focus restoration cannot scroll to the top. */
    private void restoreScroll() {
        final RestorableScrollView scroll = (RestorableScrollView) findViewById(R.id.main_scroll);
        if (scroll == null || savedScrollY <= 0) return;
        scroll.pin(savedScrollY);
        handler.postDelayed(scroll::unpin, 600);
    }

    private void rememberScroll() {
        RestorableScrollView scroll = (RestorableScrollView) findViewById(R.id.main_scroll);
        if (scroll != null) savedScrollY = scroll.getScrollY();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        rememberScroll();
        out.putInt(STATE_SCROLL_Y, savedScrollY);
    }

    /** Binds the current content view; re-run when the palette is swapped in place. */
    private void bindUi() {
        statusDot = findViewById(R.id.status_dot);
        statusText = (TextView) findViewById(R.id.status_text);
        statusDetail = (TextView) findViewById(R.id.status_detail);
        frpcBadge = (TextView) findViewById(R.id.frpc_badge);
        launchBadge = (TextView) findViewById(R.id.launch_badge);
        toggle = (Button) findViewById(R.id.btn_toggle);
        cameraIcon = (CameraStatusIcon) findViewById(R.id.camera_icon);

        toggle.setOnClickListener(v -> {
            if (running) { setService(false); running = false; render(); }
            else if (!prefs().getBoolean("auth_configured", false)) {
                statusText.setText("请先在服务器设置中填写访问凭据");
            } else requestCameraAndStart();
        });
        findViewById(R.id.nav_camera).setOnClickListener(v -> startActivity(new Intent(this, CameraSettingsActivity.class)));
        findViewById(R.id.nav_server).setOnClickListener(v -> startActivity(new Intent(this, ServerSettingsActivity.class)));
        findViewById(R.id.nav_system).setOnClickListener(v -> startActivity(new Intent(this, SystemSettingsActivity.class)));
        findViewById(R.id.nav_frpc).setOnClickListener(v -> startActivity(new Intent(this, FrpcConfigActivity.class)));
        findViewById(R.id.nav_launch).setOnClickListener(v -> startActivity(new Intent(this, LaunchSettingsActivity.class)));
    }

    /** Swaps the palette without recreating the activity: the layout is re-inflated against
     *  the target theme, so nothing is torn down and no blank frame is shown. */
    private void applyThemeInPlace(boolean dark) {
        final int scrollY = ((RestorableScrollView) findViewById(R.id.main_scroll)).getScrollY();
        final int theme = dark ? R.style.Theme_PocketCam_Dark : R.style.Theme_PocketCam_Light;
        setTheme(theme); // later inflations and dialogs pick up the new palette
        android.view.ContextThemeWrapper themed = new android.view.ContextThemeWrapper(this, theme);
        setContentView(android.view.LayoutInflater.from(themed).inflate(R.layout.activity_main, null));
        bindUi();
        styleWindowChrome(themed);
        render();
        final RestorableScrollView scroll = (RestorableScrollView) findViewById(R.id.main_scroll);
        scroll.pin(scrollY);
        handler.postDelayed(scroll::unpin, 600);
    }

    /** Recolors the parts that live outside the content view and would otherwise keep the old theme. */
    private void styleWindowChrome(Context themed) {
        getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(ThemeUtil.color(themed, R.attr.colorWindow)));
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(ThemeUtil.color(themed, R.attr.colorStatusBar));
        }
        ThemeUtil.applyStatusBarIcons(this, ThemeUtil.isDark(this));
        if (getActionBar() != null) {
            getActionBar().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(ThemeUtil.color(themed, R.attr.colorTitleBar)));
        }
        // The action bar title TextView keeps the colour baked in at inflate time.
        TextView title = findActionBarTitle();
        if (title != null) title.setTextColor(ThemeUtil.color(themed, R.attr.colorTextPrimary));
    }

    private TextView findActionBarTitle() {
        View decor = getWindow().getDecorView();
        TextView byId = (TextView) decor.findViewById(android.R.id.title);
        if (byId != null) return byId;
        // Some ROMs build the framework action bar title without android:id/title.
        return findTextOutsideContent(decor, getTitle());
    }

    private static TextView findTextOutsideContent(View v, CharSequence text) {
        if (v.getId() == android.R.id.content) return null;
        if (v instanceof TextView && text.equals(((TextView) v).getText())) return (TextView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findTextOutsideContent(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private SharedPreferences prefs() { return PreferenceManager.getDefaultSharedPreferences(this); }

    private void setService(boolean start) {
        Intent i = new Intent(this, CameraStreamService.class);
        if (start) {
            startService(i);
        } else {
            // Stop on purpose: clear the flag first so the watchdog does not bring it back.
            prefs().edit().putBoolean("service_should_run", false).apply();
            KeepAlive.stop(this);
            stopService(i);
        }
    }

    private void requestCameraAndStart() {
        if (Build.VERSION.SDK_INT >= 23) {
            java.util.List<String> needed = new java.util.ArrayList<>();
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                needed.add(android.Manifest.permission.CAMERA);
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                needed.add(android.Manifest.permission.RECORD_AUDIO);
            if (!needed.isEmpty()) {
                if (shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA))
                    statusDetail.setText("需要摄像头与麦克风权限");
                requestPermissions(needed.toArray(new String[0]), CAMERA_REQUEST);
                return;
            }
        }
        setService(true); running = true; statusText.setText("正在启动…");
        handler.postDelayed(new Runnable() {
            int attempts;
            @Override public void run() {
                running = CameraStreamService.isRunning();
                if (running || prefs().getString("service_error", "").length() > 0 || ++attempts >= 10) render();
                else { statusText.setText("正在启动…"); handler.postDelayed(this, 500); }
            }
        }, 500);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_REQUEST) {
            // Audio is optional: the service still runs video-only when the mic is denied.
            boolean cameraGranted = Build.VERSION.SDK_INT < 23
                    || checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            if (cameraGranted) requestCameraAndStart();
            else statusDetail.setText("未授予摄像头权限，服务未启动");
        }
    }

    private void render() {
        toggle.setText(running ? "停止摄像头服务" : "启动摄像头服务");
        toggle.setBackgroundResource(running ? R.drawable.btn_danger : R.drawable.btn_primary);
        setDot(running ? R.attr.colorStatusOk : R.attr.colorStatusOff);
        String error = prefs().getString("service_error", "");
        if (running) {
            statusText.setText("服务运行中");
            String ip = localIp();
            if (ip == null) {
                statusDetail.setText("未连接局域网");
            } else {
                int httpPort = prefs().getInt("http_port", 8080);
                StringBuilder detail = new StringBuilder("本机地址：http://" + ip + ":" + httpPort + "/\n视频流：http://" + ip + ":" + httpPort + "/stream.mjpg");
                int rtsp = CameraStreamService.rtspPort();
                if (rtsp > 0) detail.append("\nRTSP：rtsp://").append(ip).append(':').append(rtsp).append("/live");
                String rtspError = prefs().getString("rtsp_error", "");
                if (rtspError.length() > 0) detail.append("\n").append(rtspError);
                statusDetail.setText(detail.toString());
            }
        } else {
            statusText.setText("服务已停止");
            statusDetail.setText(error);
        }
        updateFrpcBadge();
        updateLaunchBadge();
        updateCameraIcon();
    }

    /** Three visible states: no config, configured but idle, or actually running. */
    private void updateFrpcBadge() {
        if (!FrpcController.hasConfig(prefs())) { frpcBadge.setText("未配置"); return; }
        String status = FrpcController.status();
        if (status.equals("connected")) frpcBadge.setText("运行中");
        else if (status.equals("starting") || status.equals("reconnecting")) frpcBadge.setText("连接中");
        else if (status.equals("error")) frpcBadge.setText("启动失败");
        else frpcBadge.setText("未启动");
    }

    private void updateLaunchBadge() {
        String label = prefs().getString("launch_app_label", "");
        launchBadge.setText(label.isEmpty() ? "未选择" : label);
    }

    /** Grey = released, green = capturing, red = camera failed to open. Polled: the idle
     *  watcher may release the camera while this screen is visible. */
    private void updateCameraIcon() {
        int state = CameraStreamService.cameraState();
        cameraIcon.setState(state == CameraStreamService.CAMERA_ON ? CameraStatusIcon.ON
                : state == CameraStreamService.CAMERA_ERROR ? CameraStatusIcon.ERROR : CameraStatusIcon.OFF);
    }

    private void setDot(int colorAttr) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ThemeUtil.color(this, colorAttr));
        dot.setSize(1, 1);
        statusDot.setBackground(dot);
    }

    @Override protected void onResume() {
        super.onResume();
        lastResumeAt = SystemClock.elapsedRealtime();
        // The theme can be switched on the system page, so follow the stored preference.
        boolean dark = ThemeUtil.isDark(this);
        if (dark != darkApplied) { darkApplied = dark; applyThemeInPlace(dark); }
        running = CameraStreamService.isRunning();
        if (prefs().getBoolean("service_running", false) != running) {
            // The persisted flag can be stale (process kill, reinstall); real state wins.
            prefs().edit().putBoolean("service_running", running).apply();
        }
        render();
        handler.post(statusTicker);
        restoreScroll();
    }

    @Override protected void onPause() {
        rememberScroll();
        handler.removeCallbacks(statusTicker);
        super.onPause();
    }

    private String localIp() {
        try { Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces(); while (nics.hasMoreElements()) { Enumeration<java.net.InetAddress> ips = nics.nextElement().getInetAddresses(); while (ips.hasMoreElements()) { java.net.InetAddress ip = ips.nextElement(); if (!ip.isLoopbackAddress() && ip instanceof Inet4Address) return ip.getHostAddress(); } } } catch (Exception ignored) {}
        return null;
    }
}
