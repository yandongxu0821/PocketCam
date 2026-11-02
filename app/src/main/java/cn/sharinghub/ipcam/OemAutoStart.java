package cn.sharinghub.ipcam;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/** Opens the OEM auto-start / background management page for this app, per manufacturer. */
public class OemAutoStart {
    private static final String[][] CANDIDATES = {
            // {package, class}
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},                 // vivo (Funtouch)
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},  // MIUI
            {"com.meizu.safe", "com.meizu.safe.permission.PermissionMainActivity"},                    // Flyme
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"}, // OPPO
            {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},         // Samsung
    };

    /** Tries OEM pages, then app details, then generic settings. Always opens something. */
    public static void open(Activity activity) {
        for (String[] candidate : CANDIDATES) {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(candidate[0], candidate[1]));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(intent);
                return;
            } catch (Exception ignored) { }
        }
        try {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(details);
            return;
        } catch (Exception ignored) { }
        activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
    }
}
