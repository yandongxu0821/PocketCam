package cn.sharinghub.ipcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

/** Editor for either simple forwarding fields or a complete frpc TOML file, plus kernel control. */
public class FrpcConfigActivity extends Activity {
    private static final String RISK_NOTICE =
            "通过 frpc 映射后，视频将以未加密的 HTTP 在公网传输。访问凭据不能替代加密，密码和视频内容可能被网络中的第三方看到。\n\n仅在您信任的网络中使用，或自行在 frps 侧配置 TLS。";
    private EditText server, serverPort, token, remotePort, subdomain, complete;
    private Spinner mappingType;
    private TextView status, kernelStatus;
    private Button toggle;
    private android.view.View frpcDot;
    private final Handler handler = new Handler();
    private boolean polling;
    private boolean loaded;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ThemeUtil.apply(this);
        setContentView(R.layout.activity_frpc_config);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        server = (EditText) findViewById(R.id.edit_server);
        serverPort = (EditText) findViewById(R.id.edit_server_port);
        token = (EditText) findViewById(R.id.edit_token);
        remotePort = (EditText) findViewById(R.id.edit_remote_port);
        subdomain = (EditText) findViewById(R.id.edit_subdomain);
        complete = (EditText) findViewById(R.id.edit_toml);
        status = (TextView) findViewById(R.id.status_text);
        kernelStatus = (TextView) findViewById(R.id.kernel_status);
        frpcDot = findViewById(R.id.frpc_dot);
        mappingType = (Spinner) findViewById(R.id.spinner_mapping);
        Button importFile = (Button) findViewById(R.id.btn_import);
        toggle = (Button) findViewById(R.id.btn_toggle);
        TextView risk = (TextView) findViewById(R.id.risk_link);

        String[] types = { "TCP（远程端口）", "HTTP（子域名）" };
        android.widget.ArrayAdapter<String> typeAdapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, types);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mappingType.setAdapter(typeAdapter);
        mappingType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateMappingFields();
                if (!loaded) return;
                boolean http = position == 1;
                PreferenceManager.getDefaultSharedPreferences(FrpcConfigActivity.this).edit()
                        .putString("frpc_mapping_type", http ? "http" : "tcp").apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        load();
        importFile.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT); pick.setType("*/*"); pick.addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(Intent.createChooser(pick, "选择 frpc TOML 配置"), 7); }
            catch (Exception e) { status.setText("无法打开文件选择器"); }
        });
        View.OnFocusChangeListener blurSave = (v, hasFocus) -> { if (!hasFocus && loaded) save(); };
        server.setOnFocusChangeListener(blurSave);
        serverPort.setOnFocusChangeListener(blurSave);
        token.setOnFocusChangeListener(blurSave);
        remotePort.setOnFocusChangeListener(blurSave);
        subdomain.setOnFocusChangeListener(blurSave);
        complete.setOnFocusChangeListener(blurSave);
        toggle.setOnClickListener(v -> {
            if (isActive(FrpcController.status())) { FrpcController.stop(); renderKernel(); }
            else startMapping();
        });
        risk.setOnClickListener(v -> showRisk(false));
        loaded = true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onResume() { super.onResume(); polling = true; poll(); }
    @Override protected void onPause() {
        View focused = getCurrentFocus();
        if (focused instanceof EditText) focused.clearFocus(); // blur listener saves
        super.onPause();
        polling = false;
    }

    private void poll() {
        if (!polling) return;
        renderKernel();
        handler.postDelayed(this::poll, 1000);
    }

    /** TCP maps a remote port; HTTP maps through a subdomain of the frps subdomainHost. */
    private void updateMappingFields() {
        boolean http = mappingType.getSelectedItemPosition() == 1;
        findViewById(R.id.label_remote_port).setVisibility(http ? View.GONE : View.VISIBLE);
        remotePort.setVisibility(http ? View.GONE : View.VISIBLE);
        findViewById(R.id.label_subdomain).setVisibility(http ? View.VISIBLE : View.GONE);
        subdomain.setVisibility(http ? View.VISIBLE : View.GONE);
    }

    private static boolean isActive(String status) {
        return "starting".equals(status) || "connected".equals(status) || "reconnecting".equals(status);
    }

    private void renderKernel() {
        String s = FrpcController.status();
        String text; int color = R.attr.colorStatusOff;
        switch (s) {
            case "starting": text = "连接中…"; break;
            case "connected": text = "已连接"; color = R.attr.colorStatusOk; break;
            case "reconnecting": text = "重连中…"; color = R.attr.colorStatusError; break;
            case "error": text = "错误 - " + FrpcController.lastError(); color = R.attr.colorStatusError; break;
            default: text = "已停止";
        }
        kernelStatus.setText(text);
        boolean active = isActive(s);
        toggle.setText(active ? "停止公网映射" : "启动公网映射");
        if (!active) toggle.setBackgroundResource(R.drawable.btn_primary);
        else toggle.setBackgroundResource(R.drawable.btn_danger);
        // Starting needs the local camera service; stopping stays possible even if it went down.
        toggle.setEnabled(active || CameraStreamService.isRunning());
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ThemeUtil.color(this, color));
        dot.setSize(1, 1);
        frpcDot.setBackground(dot);
    }

    private void startMapping() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.getBoolean("frpc_config_saved", false)) { status.setText("请先保存配置"); return; }
        if (!CameraStreamService.isRunning()) { status.setText("请先启动摄像头服务"); return; }
        if (!prefs.getBoolean("frpc_risk_accepted", false)) { showRisk(true); return; }
        String error = FrpcController.start(this);
        status.setText(error == null ? "frpc 已启动" : "启动失败：" + error);
        renderKernel();
    }

    private void showRisk(final boolean thenStart) {
        new AlertDialog.Builder(this)
                .setTitle("公网传输风险")
                .setMessage(RISK_NOTICE)
                .setPositiveButton(thenStart ? "我已了解，继续启动" : "我知道了", (d, w) -> {
                    PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean("frpc_risk_accepted", true).apply();
                    if (thenStart) startMapping();
                })
                .setNegativeButton(thenStart ? "取消" : null, null)
                .show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 7 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new java.io.IOException("无法读取所选文件");
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int read;
            while ((read = in.read(buffer)) != -1) {
                if (bytes.size() + read > 1024 * 1024) throw new java.io.IOException("配置文件超过 1 MB");
                bytes.write(buffer, 0, read);
            }
            in.close();
            String text = new String(bytes.toByteArray(), "UTF-8");
            if (text.trim().length() == 0) throw new java.io.IOException("配置文件为空");
            complete.setText(text);
            save();
        } catch (Exception e) { status.setText("导入失败：" + e.getMessage()); }
    }

    private void load() {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        server.setText(p.getString("frpc_server", "")); serverPort.setText(p.getString("frpc_server_port", "7000"));
        token.setText(p.getString("frpc_token", "")); remotePort.setText(p.getString("frpc_remote_port", "8080"));
        subdomain.setText(p.getString("frpc_subdomain", ""));
        mappingType.setSelection(p.getString("frpc_mapping_type", "tcp").equals("http") ? 1 : 0);
        complete.setText(p.getString("frpc_toml", ""));
        updateMappingFields();
        status.setText(!p.getBoolean("frpc_config_saved", false) ? "尚未配置"
                : p.getString("frpc_toml", "").trim().length() > 0 ? "配置来源：完整 TOML" : "配置来源：向导字段");
    }

    private void save() {
        String custom = complete.getText().toString().trim();
        boolean http = mappingType.getSelectedItemPosition() == 1;
        if (custom.length() == 0) {
            if (server.getText().toString().trim().length() == 0) { server.setError("请输入服务器地址"); return; }
            try { int port = Integer.parseInt(serverPort.getText().toString().trim()); if (port < 1 || port > 65535) throw new NumberFormatException(); }
            catch (Exception e) { serverPort.setError("端口范围应为 1–65535"); return; }
            if (http) {
                String sub = subdomain.getText().toString().trim();
                if (!sub.matches("[A-Za-z0-9-]+")) { subdomain.setError("子域名只能包含字母、数字和连字符"); return; }
            } else {
                try { int port = Integer.parseInt(remotePort.getText().toString().trim()); if (port < 1 || port > 65535) throw new NumberFormatException(); }
                catch (Exception e) { remotePort.setError("端口范围应为 1–65535"); return; }
            }
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString("frpc_server", server.getText().toString().trim())
                .putString("frpc_server_port", serverPort.getText().toString().trim())
                .putString("frpc_token", token.getText().toString().trim())
                .putString("frpc_remote_port", remotePort.getText().toString().trim())
                .putString("frpc_subdomain", subdomain.getText().toString().trim())
                .putString("frpc_mapping_type", http ? "http" : "tcp")
                .putString("frpc_toml", custom)
                .putBoolean("frpc_config_saved", true).apply();
        String error = FrpcController.validate(this);
        if (error != null) {
            status.setText("已保存，校验未通过：" + error);
            return;
        }
        status.setText(custom.length() > 0 ? "已保存并校验：完整 TOML" : "已保存并校验：向导字段");
    }
}
