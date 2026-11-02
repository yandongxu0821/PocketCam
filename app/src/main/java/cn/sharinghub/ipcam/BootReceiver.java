package cn.sharinghub.ipcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * Boot restore path. The broadcast itself may never arrive on restrictive ROMs
 * (Funtouch/MIUI/Flyme) unless the user allows auto-start in OEM settings; when it does
 * arrive, prefer a root-started service, which OEM background-start limits cannot block.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(final Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean("auto_start", false)) return;
        final boolean startFrpc = prefs.getBoolean("auto_start_frpc", false);
        new Thread(() -> {
            String status;
            if (RootUtil.isRootAvailable() && RootUtil.suStartCameraService()) {
                status = "root";
            } else {
                try {
                    context.startService(new Intent(context, CameraStreamService.class));
                    status = "broadcast";
                } catch (Exception e) {
                    status = "failed";
                }
            }
            prefs.edit().putString("boot_restore_status", System.currentTimeMillis() + ":" + status).apply();
            if (startFrpc) startFrpcAfterService(context);
        }, "boot-restore").start();
    }

    /** Waits for the camera service to come up, then starts frpc if configured. */
    private void startFrpcAfterService(Context context) {
        for (int i = 0; i < 30; i++) { // up to ~15s for the service to open its socket
            if (CameraStreamService.isRunning()) break;
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
        }
        try { FrpcController.start(context); }
        catch (Exception ignored) { }
    }
}
