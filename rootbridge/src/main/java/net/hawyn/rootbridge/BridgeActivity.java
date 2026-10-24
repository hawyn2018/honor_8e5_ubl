package net.hawyn.rootbridge;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public final class BridgeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView result;
    private BridgeEngine engine;
    private String currentJob;
    private boolean active;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        engine = BridgeEngine.get(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad * 2, pad, pad * 2);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.addView(column); setContentView(scroll);
        TextView title = text("HONOR Root Bridge", 25);
        title.setTypeface(null, Typeface.BOLD); column.addView(title);
        column.addView(text("net.hawyn.rootbridge · 1.0.0\n\n普通应用 Root 桥，由本应用申请 Root 后执行 ADB 提交的命令。仅允许 ADB Shell（UID 2000）或 Root（UID 0）调用；无网络监听。\n\n请在 Root 管理器中授予 HONOR Root Bridge 权限。桥应用将保留，使用后可在此关闭桥。", 16));
        Switch enabled = new Switch(this);
        enabled.setText("启用 ADB Root 桥"); enabled.setChecked(engine.isEnabled());
        enabled.setOnCheckedChangeListener((button, checked) -> engine.setEnabled(checked));
        column.addView(enabled);
        Button check = new Button(this); check.setText("申请 Root / 检查 Root");
        column.addView(check);
        result = text("等待检查。检查仅执行 id -u。", 15); result.setTextIsSelectable(true); column.addView(result);
        check.setOnClickListener(view -> {
            try {
                JSONObject status = engine.submit("id -u"); currentJob = status.getString("id");
                result.setText("正在申请并检查 Root…\n" + currentJob); poll();
            } catch (Exception e) { result.setText("检查失败：" + e.getMessage()); }
        });
        if (state != null) currentJob = state.getString("currentJob");
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        int space = (int) (14 * getResources().getDisplayMetrics().density);
        view.setPadding(0, space, 0, space); return view;
    }

    @Override protected void onResume() { super.onResume(); active = true; poll(); }
    @Override protected void onPause() { active = false; handler.removeCallbacksAndMessages(null); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("currentJob", currentJob); super.onSaveInstanceState(out); }

    private void poll() {
        if (!active || currentJob == null) return;
        handler.removeCallbacksAndMessages(null);
        try {
            JSONObject status = engine.status(currentJob);
            if (status.optBoolean("finished")) {
                String stdout;
                String stderr;
                try (FileInputStream input = new FileInputStream(engine.output(currentJob, "stdout"))) {
                    stdout = BridgeEngine.readUtf8(input, 65536).trim();
                }
                try (FileInputStream input = new FileInputStream(engine.output(currentJob, "stderr"))) {
                    stderr = BridgeEngine.readUtf8(input, 65536).trim();
                }
                boolean root = status.optInt("exitCode", -1) == 0 && "0".equals(stdout);
                result.setText((root ? "Root 检查成功：UID 0" : "Root 检查失败") + "\n\n" + status.toString(2)
                    + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr);
                return;
            }
            result.setText("正在申请并检查 Root…\n状态：" + status.optString("state") + "\n" + currentJob);
            handler.postDelayed(this::poll, 500);
        } catch (Exception e) { result.setText("检查结果读取失败：" + e.getMessage()); }
    }
}
