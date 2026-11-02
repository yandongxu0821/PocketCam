package cn.sharinghub.ipcam;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Switch;

/** Server page: HTTP/RTSP ports and the Basic auth credentials. */
public class ServerSettingsActivity extends Activity {
    private EditText httpPortField, rtspPortField, userField, passwordField;
    private Switch rtspSwitch, rtspUdpSwitch;
    private View rtspOptions, rtspOptionsInner;
    private Animator rtspAnim;
    private boolean loaded;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_server_settings);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        httpPortField = (EditText) findViewById(R.id.edit_http_port);
        rtspPortField = (EditText) findViewById(R.id.edit_rtsp_port);
        userField = (EditText) findViewById(R.id.edit_user);
        passwordField = (EditText) findViewById(R.id.edit_password);
        rtspSwitch = (Switch) findViewById(R.id.switch_rtsp);
        rtspUdpSwitch = (Switch) findViewById(R.id.switch_rtsp_udp);
        rtspOptions = findViewById(R.id.rtsp_options);
        rtspOptionsInner = findViewById(R.id.rtsp_options_inner);

        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        httpPortField.setText(String.valueOf(p.getInt("http_port", 8080)));
        rtspPortField.setText(String.valueOf(p.getInt("rtsp_port", 8554)));
        userField.setText(p.getString("http_user", ""));
        passwordField.setText(p.getString("http_password", ""));
        rtspSwitch.setChecked(p.getBoolean("rtsp_enabled", false));
        rtspUdpSwitch.setChecked(p.getBoolean("rtsp_udp", false));

        attachAutoSave();
        // Initial state applied without animation; only user toggles animate.
        rtspOptions.setVisibility(rtspSwitch.isChecked() ? View.VISIBLE : View.GONE);
        loaded = true;
    }

    /** Animate the options container height so collapsing content is clipped by the shrinking
     *  boundary instead of fading on top of the card below (no fade, no overlap). */
    private void updateRtspVisibility(boolean enabled) {
        if (rtspAnim != null) { rtspAnim.removeAllListeners(); rtspAnim.cancel(); }
        if (enabled) expand(rtspOptions);
        else collapse(rtspOptions);
    }

    private void expand(final View v) {
        final int target = pinInner();
        int start = v.getVisibility() == View.VISIBLE ? v.getLayoutParams().height : 0;
        if (start < 0) start = 0; // WRAP_CONTENT etc.
        v.getLayoutParams().height = start;
        v.setVisibility(View.VISIBLE);
        ValueAnimator a = ValueAnimator.ofInt(start, target);
        a.addUpdateListener(anim -> {
            v.getLayoutParams().height = (Integer) anim.getAnimatedValue();
            v.requestLayout();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { restoreWrap(v); }
        });
        a.setDuration(250);
        rtspAnim = a;
        a.start();
    }

    private void collapse(final View v) {
        pinInner();
        ValueAnimator a = ValueAnimator.ofInt(v.getHeight(), 0);
        a.addUpdateListener(anim -> {
            v.getLayoutParams().height = (Integer) anim.getAnimatedValue();
            v.requestLayout();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                v.setVisibility(View.GONE);
                restoreWrap(v);
            }
        });
        a.setDuration(250);
        rtspAnim = a;
        a.start();
    }

    /** Pin the inner content at its natural height so children keep stable measure specs and are
     *  clipped by the outer boundary instead of being re-measured (squashed) every frame. */
    private int pinInner() {
        ViewGroup parent = (ViewGroup) rtspOptions.getParent();
        int width = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        rtspOptionsInner.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int natural = rtspOptionsInner.getMeasuredHeight();
        rtspOptionsInner.getLayoutParams().height = natural;
        return natural;
    }

    private void restoreWrap(View v) {
        v.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        rtspOptionsInner.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        v.requestLayout();
    }

    /** Ensure any in-progress edit is persisted before the page is left (back / navigation). */
    @Override protected void onPause() {
        View focused = getCurrentFocus();
        if (focused instanceof EditText) focused.clearFocus(); // blur listener saves
        super.onPause();
    }

    private SharedPreferences prefs() { return PreferenceManager.getDefaultSharedPreferences(this); }

    private void attachAutoSave() {
        rtspSwitch.setOnCheckedChangeListener((v, checked) -> {
            updateRtspVisibility(checked);
            if (loaded) prefs().edit().putBoolean("rtsp_enabled", checked).apply();
        });
        rtspUdpSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (loaded) prefs().edit().putBoolean("rtsp_udp", checked).apply();
        });
        View.OnFocusChangeListener blurSave = (v, hasFocus) -> {
            EditText et = (EditText) v;
            if (hasFocus) et.setError(null);
            else if (loaded) save();
        };
        httpPortField.setOnFocusChangeListener(blurSave);
        rtspPortField.setOnFocusChangeListener(blurSave);
        userField.setOnFocusChangeListener(blurSave);
        passwordField.setOnFocusChangeListener(blurSave);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void save() {
        String user = userField.getText().toString();
        String pw = passwordField.getText().toString();
        if (user.trim().length() == 0) { userField.setError("用户名不能为空"); return; }
        if (user.indexOf(':') >= 0) { userField.setError("用户名不能包含冒号"); return; }
        if (pw.length() < 8) { passwordField.setError("密码至少 8 个字符"); return; }
        int http = parsePort(httpPortField.getText().toString());
        if (http <= 0) { httpPortField.setError("端口范围应为 1–65535"); return; }
        int rtsp = parsePort(rtspPortField.getText().toString());
        if (rtsp <= 0) { rtspPortField.setError("端口范围应为 1–65535"); return; }
        if (http == rtsp) { rtspPortField.setError("不能与 HTTP 端口相同"); return; }

        prefs().edit()
                .putInt("http_port", http).putInt("rtsp_port", rtsp)
                .putBoolean("rtsp_enabled", rtspSwitch.isChecked())
                .putBoolean("rtsp_udp", rtspUdpSwitch.isChecked())
                .putString("http_user", user).putString("http_password", pw)
                .putBoolean("auth_configured", true).apply();
    }

    private static int parsePort(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value >= 1 && value <= 65535 ? value : -1;
        } catch (Exception e) { return -1; }
    }
}
