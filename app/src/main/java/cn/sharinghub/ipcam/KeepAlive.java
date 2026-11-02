package cn.sharinghub.ipcam;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Non-root keep-alive: a periodic alarm wakes the app up to check whether the camera
 * service is still alive and restarts it when the ROM killed it. This is the only part
 * that still works after the process is gone, since the alarm belongs to the system.
 */
public class KeepAlive {
    private static final long INTERVAL_MS = 5 * 60 * 1000L;
    private static final long IMMEDIATE_DELAY_MS = 2000;

    /** Arms the periodic check; calling it again just replaces the existing alarm. */
    static void start(Context context) {
        schedule(context, SystemClock.elapsedRealtime() + INTERVAL_MS, INTERVAL_MS);
    }

    static void stop(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pendingIntent(context));
    }

    /** One-shot check shortly from now, used right after the task is swiped away. */
    static void requestImmediateCheck(Context context) {
        schedule(context, SystemClock.elapsedRealtime() + IMMEDIATE_DELAY_MS, -1);
    }

    private static void schedule(Context context, long triggerAt, long intervalMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pendingIntent(context);
        if (intervalMs > 0) am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, intervalMs, pi);
        else am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, WatchdogReceiver.class);
        intent.setAction(WatchdogReceiver.ACTION_CHECK);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
