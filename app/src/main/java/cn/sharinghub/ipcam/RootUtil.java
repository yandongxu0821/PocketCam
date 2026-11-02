package cn.sharinghub.ipcam;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Minimal su helpers. Root shell (uid 0) bypasses the service export check, so it can
 *  start the camera service even on ROMs that block app-side background starts. */
public class RootUtil {
    private static Boolean cached;

    /** Cached root probe; never blocks the caller for long. */
    public static boolean isRootAvailable() {
        synchronized (RootUtil.class) {
            if (cached != null) return cached;
        }
        String id = run(new String[]{"su", "-c", "id"}, 8000);
        boolean result = id != null && id.contains("uid=0");
        synchronized (RootUtil.class) {
            if (cached == null) cached = result;
        }
        return result;
    }

    /** Starts the camera service via root shell. Returns true when the command exited 0. */
    public static boolean suStartCameraService() {
        return run(new String[]{"su", "-c", "am startservice -n cn.sharinghub.ipcam/.CameraStreamService"}, 8000) != null;
    }

    // ---- Boot hook via /data/local/userinit.sh (rooted devices whose ROM blocks
    //      BOOT_COMPLETED and refuses /system remount; init.d's 90userinit runs this) ----

    private static final String HOOK_PATH = "/data/local/userinit.sh";
    private static final String HOOK_BLOCK =
            "# pocketcam-boot-begin\n"
            + "LOG=/data/local/pocketcam-boot.log\n"
            + "echo \"hook fired $(date)\" >> $LOG\n"
            + "while [ \"$(getprop sys.boot_completed)\" != \"1\" ]; do sleep 2; done\n"
            + "sleep 5\n"
            + "pm path cn.sharinghub.ipcam >/dev/null 2>&1 || { echo \"package missing\" >> $LOG; exit 0; }\n"
            + "am startservice -n cn.sharinghub.ipcam/.CameraStreamService >> $LOG 2>&1\n"
            + "echo \"am exit=$? $(date)\" >> $LOG\n"
            + "# pocketcam-boot-end\n";

    public static boolean hasBootHook() {
        String out = run(new String[]{"su", "-c", "grep -c pocketcam-boot-begin " + HOOK_PATH + " 2>/dev/null"}, 8000);
        return out != null && out.trim().startsWith("1");
    }

    /** Adds our block to userinit.sh without disturbing other tools' content. */
    public static boolean installBootHook(android.content.Context context) {
        try {
            java.io.File stage = new java.io.File(context.getFilesDir(), "pocketcam-boot.sh");
            java.io.FileOutputStream out = new java.io.FileOutputStream(stage);
            out.write(HOOK_BLOCK.getBytes("UTF-8"));
            out.close();
            String cmd = "F=" + HOOK_PATH + "; touch $F; "
                    + "sed '/# pocketcam-boot-begin/,/# pocketcam-boot-end/d' $F > $F.new; "
                    + "cat " + stage.getAbsolutePath() + " >> $F.new; "
                    + "mv $F.new $F; chmod 755 $F; grep -c pocketcam-boot-begin $F";
            String result = run(new String[]{"su", "-c", cmd}, 15000);
            stage.delete();
            return result != null && result.trim().startsWith("1");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean removeBootHook() {
        String cmd = "F=" + HOOK_PATH + "; "
                + "if [ -f $F ]; then sed '/# pocketcam-boot-begin/,/# pocketcam-boot-end/d' $F > $F.new; "
                + "if [ -s $F.new ]; then mv $F.new $F; else rm -f $F.new $F; fi; fi";
        return run(new String[]{"su", "-c", cmd}, 15000) != null;
    }

    /** Runs a command with a hard timeout; returns stdout, or null on failure/timeout. */
    private static String run(String[] command, long timeoutMs) {
        final Process process;
        try {
            process = Runtime.getRuntime().exec(command);
        } catch (Exception e) {
            return null;
        }
        final String[] output = new String[1];
        Thread reader = new Thread(() -> {
            try {
                output[0] = readAll(process.getInputStream());
                process.waitFor();
            } catch (Exception ignored) { }
        }, "su-wait");
        reader.start();
        try { reader.join(timeoutMs); } catch (InterruptedException ignored) { }
        if (reader.isAlive()) {
            process.destroy();
            return null;
        }
        try {
            return process.exitValue() == 0 ? (output[0] == null ? "" : output[0]) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }
}
