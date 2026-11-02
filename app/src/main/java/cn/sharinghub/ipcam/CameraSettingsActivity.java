package cn.sharinghub.ipcam;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;

/** Camera page: preview entry, resolution, title, stream overlay and idle auto-close. */
public class CameraSettingsActivity extends Activity {
    private EditText webTitleField, overlayTextField;
    private Switch showTimeSwitch, showBatterySwitch, showLensSwitch, idleCloseSwitch;
    private Spinner resolutionSpinner;
    private TextView resolutionMessage;
    private final ArrayList<Resolution> sizes = new ArrayList<>();
    private boolean loaded;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_camera_settings);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        webTitleField = (EditText) findViewById(R.id.edit_web_title);
        overlayTextField = (EditText) findViewById(R.id.edit_overlay_text);
        showTimeSwitch = (Switch) findViewById(R.id.switch_overlay_time);
        showBatterySwitch = (Switch) findViewById(R.id.switch_overlay_battery);
        showLensSwitch = (Switch) findViewById(R.id.switch_overlay_lens);
        idleCloseSwitch = (Switch) findViewById(R.id.switch_idle_close);
        resolutionSpinner = (Spinner) findViewById(R.id.spinner_resolution);
        resolutionMessage = (TextView) findViewById(R.id.resolution_message);

        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        webTitleField.setText(p.getString("web_title", ""));
        overlayTextField.setText(p.getString("overlay_text", ""));
        showTimeSwitch.setChecked(p.getBoolean("overlay_show_time", true));
        showBatterySwitch.setChecked(p.getBoolean("overlay_show_battery", true));
        showLensSwitch.setChecked(p.getBoolean("overlay_show_lens", true));
        idleCloseSwitch.setChecked(p.getBoolean("camera_idle_close", true));

        loadSizes();
        findViewById(R.id.nav_preview).setOnClickListener(v -> startActivity(new Intent(this, PreviewActivity.class)));
        attachAutoSave();
        loaded = true;
    }

    /** Ensure any in-progress edit is persisted before the page is left (back / navigation). */
    @Override protected void onPause() {
        View focused = getCurrentFocus();
        if (focused instanceof android.widget.EditText) focused.clearFocus(); // blur listener saves
        super.onPause();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private SharedPreferences prefs() { return PreferenceManager.getDefaultSharedPreferences(this); }

    /** Reads the device's supported preview sizes, falling back to the last cached list. */
    private void loadSizes() {
        sizes.clear();
        String cached = prefs().getString("camera_sizes", "");
        if (CameraStreamService.isRunning()) {
            // The service owns the camera. Opening it here makes newer devices evict the
            // service's client, which silently kills the preview and the video stream, so the
            // list the service cached when it opened the camera is used instead.
            readCachedSizes(cached);
            resolutionMessage.setText(cached.length() > 0
                    ? "服务运行中，显示已缓存的分辨率"
                    : "服务运行中，停止服务后可读取分辨率");
        } else {
            try {
                Camera camera = Camera.open();
                try {
                    StringBuilder list = new StringBuilder();
                    for (Camera.Size s : camera.getParameters().getSupportedPreviewSizes()) {
                        sizes.add(new Resolution(s.width, s.height));
                        if (list.length() > 0) list.append(',');
                        list.append(s.width).append('x').append(s.height);
                    }
                    prefs().edit().putString("camera_sizes", list.toString()).apply();
                } finally { camera.release(); }
                resolutionMessage.setText("高分辨率可能降低帧率");
            } catch (Exception e) {
                readCachedSizes(cached);
                resolutionMessage.setText(cached.length() > 0
                        ? "相机忙，显示上次读取的尺寸"
                        : "暂时读不到尺寸，请停止服务后重试");
            }
        }
        Collections.sort(sizes, (a, b) -> a.width * a.height - b.width * b.height);
        showSizes();
    }

    private void readCachedSizes(String cached) {
        if (cached.length() == 0) return;
        for (String item : cached.split(",")) {
            try {
                String[] wh = item.split("x");
                sizes.add(new Resolution(Integer.parseInt(wh[0]), Integer.parseInt(wh[1])));
            } catch (Exception ignored) { }
        }
    }

    private void showSizes() {
        ArrayList<String> labels = new ArrayList<>();
        for (Resolution size : sizes) labels.add(size.width + " × " + size.height);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(adapter);
        int width = prefs().getInt("camera_width", 320);
        int height = prefs().getInt("camera_height", 240);
        for (int i = 0; i < sizes.size(); i++) {
            if (sizes.get(i).width == width && sizes.get(i).height == height) resolutionSpinner.setSelection(i);
        }
        if (sizes.isEmpty()) {
            resolutionSpinner.setEnabled(false);
            resolutionMessage.setText("没有可用尺寸，请停止服务后重试");
        }
    }

    private void attachAutoSave() {
        showTimeSwitch.setOnCheckedChangeListener((v, checked) -> { if (loaded) saveSwitch("overlay_show_time", checked); });
        showBatterySwitch.setOnCheckedChangeListener((v, checked) -> { if (loaded) saveSwitch("overlay_show_battery", checked); });
        showLensSwitch.setOnCheckedChangeListener((v, checked) -> { if (loaded) saveSwitch("overlay_show_lens", checked); });
        idleCloseSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (!loaded) return;
            saveSwitch("camera_idle_close", checked);
            if (!checked) CameraStreamService.requestCameraOpen(); // "always on" means open now
        });
        resolutionSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!loaded || sizes.isEmpty()) return;
                Resolution size = sizes.get(position);
                prefs().edit().putInt("camera_width", size.width).putInt("camera_height", size.height).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        View.OnFocusChangeListener blurSave = (v, hasFocus) -> { if (!hasFocus && loaded) saveText(); };
        webTitleField.setOnFocusChangeListener(blurSave);
        overlayTextField.setOnFocusChangeListener(blurSave);
    }

    private void saveSwitch(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    private void saveText() {
        prefs().edit()
                .putString("web_title", webTitleField.getText().toString().trim())
                .putString("overlay_text", overlayTextField.getText().toString().trim())
                .apply();
    }

    private static class Resolution {
        final int width, height;
        Resolution(int w, int h) { width = w; height = h; }
    }
}
