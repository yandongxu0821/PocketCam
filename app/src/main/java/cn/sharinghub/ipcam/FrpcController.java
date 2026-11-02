package cn.sharinghub.ipcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import java.io.File;
import java.io.FileOutputStream;
import bridge.Bridge;

/** Bridges the UI to the embedded frpc Go kernel. All entry points are safe to call from the UI thread. */
public final class FrpcController {
    private static final String CONFIG_NAME = "frpc-active.toml";

    private FrpcController() { }

    /** Builds the effective TOML: the user supplied full TOML wins over the wizard fields. */
    public static String effectiveToml(SharedPreferences prefs) {
        String custom = prefs.getString("frpc_toml", "").trim();
        if (!custom.isEmpty()) return custom + "\n";
        StringBuilder toml = new StringBuilder();
        toml.append("serverAddr = \"").append(prefs.getString("frpc_server", "").trim()).append("\"\n");
        toml.append("serverPort = ").append(prefs.getString("frpc_server_port", "7000").trim()).append("\n");
        String token = prefs.getString("frpc_token", "").trim();
        if (!token.isEmpty()) {
            // Escape backslash first, then the quote, so tokens with either are preserved verbatim.
            String escaped = token.replace("\\", "\\\\").replace("\"", "\\\"");
            toml.append("auth.token = \"").append(escaped).append("\"\n");
        }
        toml.append("\n[[proxies]]\n");
        toml.append("name = \"pocketcam\"\n");
        String type = prefs.getString("frpc_mapping_type", "tcp");
        toml.append("type = \"").append(type).append("\"\n");
        toml.append("localIP = \"127.0.0.1\"\n");
        toml.append("localPort = ").append(prefs.getInt("http_port", 8080)).append("\n");
        if ("http".equals(type)) {
            toml.append("subdomain = \"").append(prefs.getString("frpc_subdomain", "").trim().replace("\"", "\\\"")).append("\"\n");
        } else {
            toml.append("remotePort = ").append(prefs.getString("frpc_remote_port", "8080").trim()).append("\n");
        }
        return toml.toString();
    }

    public static boolean hasConfig(SharedPreferences prefs) {
        return prefs.getBoolean("frpc_config_saved", false)
                && (!prefs.getString("frpc_toml", "").trim().isEmpty()
                || !prefs.getString("frpc_server", "").trim().isEmpty());
    }

    /** Validates the effective config without connecting. Returns null on success or a readable error. */
    public static String validate(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        try {
            String path = writeConfig(context, effectiveToml(prefs));
            Bridge.validate(path);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** Starts frpc. Returns null on success or a readable error. */
    public static synchronized String start(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!hasConfig(prefs)) return "请先保存 frpc 配置";
        if (!CameraStreamService.isRunning()) return "摄像头服务未运行，请先启动本地摄像头服务";
        try {
            String path = writeConfig(context, effectiveToml(prefs));
            Bridge.start(path);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    public static synchronized void stop() {
        try { Bridge.stop(); } catch (Exception ignored) { }
    }

    /** stopped | starting | connected | reconnecting | error */
    public static String status() {
        try { return Bridge.status(); } catch (Exception e) { return "stopped"; }
    }

    public static String lastError() {
        try { return Bridge.lastError(); } catch (Exception e) { return ""; }
    }

    private static String writeConfig(Context context, String toml) throws Exception {
        File file = new File(context.getFilesDir(), CONFIG_NAME);
        FileOutputStream out = new FileOutputStream(file);
        out.write(toml.getBytes("UTF-8"));
        out.close();
        return file.getAbsolutePath();
    }
}
