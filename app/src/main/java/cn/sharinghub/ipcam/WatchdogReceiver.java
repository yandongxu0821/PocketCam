package cn.sharinghub.ipcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

/** Alarm entry point: restarts the camera service if it was killed while the user wants it running. */
public class WatchdogReceiver extends BroadcastReceiver {
    static final String ACTION_CHECK = "cn.sharinghub.ipcam.WATCHDOG_CHECK";
    private static final String TAG = "PocketCam";

    @Override public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean("service_should_run", false)) {
            KeepAlive.stop(context); // the user stopped the service on purpose; stand down
            return;
        }
        if (CameraStreamService.isRunning()) return;
        try {
            context.startService(new Intent(context, CameraStreamService.class));
            Log.i(TAG, "watchdog restarted the camera service");
        } catch (Exception e) {
            Log.w(TAG, "watchdog restart failed", e);
        }
    }
}
