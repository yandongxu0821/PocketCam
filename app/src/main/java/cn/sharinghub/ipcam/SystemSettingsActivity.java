package cn.sharinghub.ipcam;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.MenuItem;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

/** System page: boot auto-start helpers and the theme mode switches. */
public class SystemSettingsActivity extends Activity {
    private SharedPreferences prefs;
    private Switch autoStart, autoStartFrpc, autoTheme, dark;
    private TextView autostartInfo;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_system_settings);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        autoStart = (Switch) findViewById(R.id.switch_autostart);
        autoStartFrpc = (Switch) findViewById(R.id.switch_autostart_frpc);
        autoTheme = (Switch) findViewById(R.id.switch_theme_auto);
        dark = (Switch) findViewById(R.id.switch_dark);
        autostartInfo = (TextView) findViewById(R.id.autostart_info);

        findViewById(R.id.btn_oem_autostart).setOnClickListener(v -> OemAutoStart.open(this));
        findViewById(R.id.btn_accessibility).setOnClickListener(v ->
                startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btn_root_start).setOnClickListener(v -> new Thread(() -> {
            boolean ok = RootUtil.suStartCameraService();
            runOnUiThread(() -> android.widget.Toast.makeText(this,
                    ok ? "已请求 root 启动服务" : "root 启动失败", android.widget.Toast.LENGTH_SHORT).show());
        }, "root-start").start());

        autoStart.setChecked(prefs.getBoolean("auto_start", false));
        autoStartFrpc.setChecked(prefs.getBoolean("auto_start_frpc", false));
        autoStartFrpc.setEnabled(autoStart.isChecked());
        autoStart.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("auto_start", checked).apply();
            autoStartFrpc.setEnabled(checked);
            syncBootHook(checked);
        });
        autoStartFrpc.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("auto_start_frpc", checked).apply());

        boolean auto = ThemeUtil.MODE_AUTO.equals(ThemeUtil.mode(this));
        autoTheme.setChecked(auto);
        dark.setChecked(ThemeUtil.isDark(this));
        dark.setEnabled(!auto);
        autoTheme.setOnCheckedChangeListener((b, checked) -> {
            if (checked == ThemeUtil.MODE_AUTO.equals(ThemeUtil.mode(this))) return; // no change
            ThemeUtil.setMode(this, checked ? ThemeUtil.MODE_AUTO
                    : (dark.isChecked() ? ThemeUtil.MODE_DARK : ThemeUtil.MODE_LIGHT));
            recreate(); // theme must be chosen before setContentView
        });
        dark.setOnCheckedChangeListener((b, checked) -> {
            if (autoTheme.isChecked()) return;
            ThemeUtil.setMode(this, checked ? ThemeUtil.MODE_DARK : ThemeUtil.MODE_LIGHT);
            recreate();
        });

        probeRoot();
        updateBootStatus();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    /** Root probe runs off the UI thread; result decides which restore paths to offer. */
    private void probeRoot() {
        new Thread(() -> {
            final boolean root = RootUtil.isRootAvailable();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                findViewById(R.id.btn_root_start).setVisibility(root ? View.VISIBLE : View.GONE);
                autostartInfo.setText(root
                        ? "已检测到 root，可用 root 方式开机恢复"
                        : "未检测到 root，请允许多数厂商自启动或使用无障碍辅助");
            });
        }, "root-probe").start();
    }

    /** Keeps the init.d boot hook in sync with the toggle on rooted devices. */
    private void syncBootHook(final boolean enable) {
        new Thread(() -> {
            if (!RootUtil.isRootAvailable()) return;
            boolean ok = enable ? RootUtil.installBootHook(this) : RootUtil.removeBootHook();
            if (ok) prefs.edit().putBoolean("boot_hook_installed", enable).apply();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (enable) autostartInfo.setText(ok ? "已安装 root 开机脚本" : "root 开机脚本安装失败");
            });
        }, "boot-hook").start();
    }

    private void updateBootStatus() {
        String status = prefs.getString("boot_restore_status", "");
        if (status.isEmpty()) return;
        String suffix = status.endsWith(":root") ? "上次开机已用 root 恢复"
                : status.endsWith(":broadcast") ? "上次开机已用广播恢复"
                : "上次开机恢复失败";
        autostartInfo.append("\n" + suffix);
    }
}
