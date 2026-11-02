package cn.sharinghub.ipcam;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.MenuItem;
import android.widget.TextView;

/** Launch page: which app to raise after the service starts, plus its delay and stay time. */
public class LaunchSettingsActivity extends Activity {
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_launch_settings);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        updateBadges();
        updateBgPermissionCard();
        findViewById(R.id.nav_launch_app).setOnClickListener(v -> showAppPicker());
        findViewById(R.id.nav_launch_delay).setOnClickListener(v -> showNumberInput(
                "拉起延时（秒）", "launch_app_delay", 10, 1, 600, "延时范围 1–600 秒"));
        findViewById(R.id.nav_launch_stay).setOnClickListener(v -> showNumberInput(
                "前台停留时间（秒）", "launch_app_stay", 20, -1, 600,
                " -1 表示一直停留；0 表示不停留立即返回"));
        findViewById(R.id.btn_bg_permission).setOnClickListener(v -> openBackgroundPermission());
    }

    @Override protected void onResume() {
        super.onResume();
        updateBgPermissionCard();
    }

    /**
     * Shows the hint only after the service actually failed to come back: the block is silent,
     * and OEM switches such as vivo's "后台弹出界面" cannot be read from inside the app.
     */
    private void updateBgPermissionCard() {
        findViewById(R.id.card_bg_permission).setVisibility(
                prefs.getBoolean("bg_popup_missing", false) ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /** Opens the page that holds the background-popup switch for this app. */
    private void openBackgroundPermission() {
        if (Build.MANUFACTURER != null
                && Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT).contains("vivo")) {
            Intent detail = new Intent();
            detail.setComponent(new android.content.ComponentName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"));
            detail.putExtra("packagename", getPackageName());
            detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(detail); return; } catch (Exception ignored) { }
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
            return;
        } catch (Exception ignored) { }
        OemAutoStart.open(this);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void updateBadges() {
        String label = prefs.getString("launch_app_label", "");
        ((TextView) findViewById(R.id.launch_badge)).setText(label.isEmpty() ? "未选择" : label);
        ((TextView) findViewById(R.id.launch_delay_badge)).setText(prefs.getInt("launch_app_delay", 10) + " 秒");
        ((TextView) findViewById(R.id.launch_stay_badge)).setText(stayLabel(prefs.getInt("launch_app_stay", 20)));
    }

    private static String stayLabel(int sec) {
        if (sec < 0) return "不切回";
        return sec == 0 ? "立即切回" : sec + " 秒";
    }

    /** Lists launchable installed apps (no special permission below targetSdk 30). */
    private void showAppPicker() {
        java.util.List<android.content.pm.ResolveInfo> infos = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        java.util.LinkedHashMap<String, String> apps = new java.util.LinkedHashMap<>();
        for (android.content.pm.ResolveInfo info : infos) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || apps.containsKey(pkg)) continue;
            apps.put(pkg, String.valueOf(info.loadLabel(getPackageManager())));
        }
        final String[] pkgs = apps.keySet().toArray(new String[0]);
        String[] labels = new String[pkgs.length + 1];
        labels[0] = "不拉起";
        for (int i = 0; i < pkgs.length; i++) labels[i + 1] = apps.get(pkgs[i]);
        new android.app.AlertDialog.Builder(this).setTitle("服务启动后拉起的应用")
                .setItems(labels, (d, which) -> {
                    if (which == 0) prefs.edit().putString("launch_app_pkg", "").putString("launch_app_label", "").apply();
                    else prefs.edit().putString("launch_app_pkg", pkgs[which - 1]).putString("launch_app_label", labels[which]).apply();
                    updateBadges();
                }).show();
    }

    /** Shared numeric editor for the launch settings; min may be negative (e.g. -1 = disabled). */
    private void showNumberInput(String title, String key, int fallback, int min, int max, String hint) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(String.valueOf(prefs.getInt(key, fallback)));
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 0);
        box.addView(input, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        android.widget.TextView note = new android.widget.TextView(this);
        note.setText(hint);
        note.setTextSize(12);
        note.setPadding(0, 8, 0, 0);
        box.addView(note);
        new android.app.AlertDialog.Builder(this).setTitle(title)
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    int value;
                    try { value = Integer.parseInt(input.getText().toString().trim()); } catch (Exception e) { value = min - 1; }
                    if (value < min || value > max) {
                        android.widget.Toast.makeText(this, "取值 " + min + "–" + max, android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putInt(key, value).apply();
                    updateBadges();
                })
                .setNegativeButton("取消", null).show();
    }
}
