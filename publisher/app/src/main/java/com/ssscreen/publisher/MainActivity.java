package com.ssscreen.publisher;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 推流端界面：填写服务器（可选服务器地址，端口固定 55564）、频道名，
 * 调节帧率(1-60)、JPEG 画质(10-100)、分辨率档位，一键开始/停止。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_CAPTURE = 100;

    // 分辨率档位：最长边像素，0 = 屏幕原始分辨率
    private final int[] resEdges = {1280, 1920, 0};

    private EditText etHost;
    private EditText etChannel;
    private EditText etPwd;
    private SeekBar sbFps;
    private SeekBar sbQuality;
    private Spinner spRes;
    private Button btnToggle;
    private TextView tvFps;
    private TextView tvQuality;
    private TextView tvStatus;

    private int fps = 15;
    private int quality = 55;
    private boolean streaming = false;

    private String pendingHost;
    private String pendingChannel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etHost = findViewById(R.id.et_host);
        etChannel = findViewById(R.id.et_channel);
        etPwd = findViewById(R.id.et_pwd);
        sbFps = findViewById(R.id.sb_fps);
        sbQuality = findViewById(R.id.sb_quality);
        spRes = findViewById(R.id.sp_res);
        btnToggle = findViewById(R.id.btn_toggle);
        tvFps = findViewById(R.id.tv_fps);
        tvQuality = findViewById(R.id.tv_quality);
        tvStatus = findViewById(R.id.tv_status);

        etChannel.setText("default");
        tvStatus.setMovementMethod(new ScrollingMovementMethod());

        // 输入限制：频道名/密码仅允许数字和字母（无符号、无空格）；
        // 服务器地址必须保留 IP 的点号"."，否则无法输入 IPv4 地址
        InputFilter alnumOnly = (src, start, end, dst, ds, de) -> {
            for (int i = start; i < end; i++) {
                char c = src.charAt(i);
                if (!Character.isLetterOrDigit(c)) {
                    return "";
                }
            }
            return null;
        };
        InputFilter hostFilter = (src, start, end, dst, ds, de) -> {
            for (int i = start; i < end; i++) {
                char c = src.charAt(i);
                if (!Character.isLetterOrDigit(c) && c != '.') {
                    return "";
                }
            }
            return null;
        };
        etHost.setFilters(new InputFilter[]{hostFilter});
        etChannel.setFilters(new InputFilter[]{alnumOnly});
        etPwd.setFilters(new InputFilter[]{alnumOnly});

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.res_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spRes.setAdapter(adapter);

        sbFps.setMax(59);
        sbFps.setProgress(fps - 1);
        sbFps.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                fps = p + 1;
                tvFps.setText(getString(R.string.label_fps, fps));
                CaptureService.sFps = fps;
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });

        sbQuality.setMax(90);
        sbQuality.setProgress(quality - 10);
        sbQuality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                quality = p + 10;
                tvQuality.setText(getString(R.string.label_quality, quality));
                CaptureService.sQuality = quality;
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });

        btnToggle.setOnClickListener(v -> toggle());
    }

    @Override
    protected void onResume() {
        super.onResume();
        CaptureService.setListener(new CaptureService.StateListener() {
            @Override
            public void onState(String state) {
                runOnUiThread(() -> appendState(state));
            }

            @Override
            public void onKick(String reason) {
                runOnUiThread(() -> showKickDialog(reason));
            }
        });
    }

    /** 频道踢出弹窗，确认后停止推流、复位界面。 */
    private void showKickDialog(String reason) {
        setStreaming(false);
        new AlertDialog.Builder(this)
                .setTitle("推流结束")
                .setMessage("频道踢出")
                .setCancelable(false)
                .setPositiveButton("确定", null)
                .show();
        appendState("已被服务器踢出频道");
    }

    @Override
    protected void onDestroy() {
        CaptureService.setListener(null);
        super.onDestroy();
    }

    private void toggle() {
        if (streaming) {
            Intent stop = new Intent(this, CaptureService.class);
            stop.setAction(CaptureService.ACTION_STOP);
            startService(stop);
            setStreaming(false);
            appendState("已停止推流");
            return;
        }
        String h = etHost.getText().toString().trim();
        String ch = etChannel.getText().toString().trim();
        if (h.isEmpty()) {
            Toast.makeText(this, "请填写服务器 IP", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ch.isEmpty()) {
            ch = "default";
        }
        pendingHost = h;
        pendingChannel = ch;

        MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
        // Android 14/15/16：每次开始都必须重新向用户申请授权
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "未授予投屏权限，无法推流", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent svc = new Intent(this, CaptureService.class);
        svc.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        svc.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        svc.putExtra(CaptureService.EXTRA_HOST, pendingHost);
        svc.putExtra(CaptureService.EXTRA_CHANNEL, pendingChannel);
        svc.putExtra(CaptureService.EXTRA_FPS, fps);
        svc.putExtra(CaptureService.EXTRA_QUALITY, quality);
        svc.putExtra(CaptureService.EXTRA_MAX_EDGE,
                resEdges[spRes.getSelectedItemPosition()]);
        svc.putExtra(CaptureService.EXTRA_PWD,
                etPwd.getText().toString());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        setStreaming(true);
        appendState("开始请求投屏...");
    }

    private void setStreaming(boolean on) {
        streaming = on;
        btnToggle.setText(on ? R.string.btn_stop : R.string.btn_start);
        etHost.setEnabled(!on);
        etChannel.setEnabled(!on);
        etPwd.setEnabled(!on);
        spRes.setEnabled(!on);
    }

    private void appendState(String s) {
        tvStatus.append(s);
        tvStatus.append("\n");
        int l = tvStatus.getLayout() == null ? 0
                : tvStatus.getLayout().getLineTop(tvStatus.getLineCount())
                - tvStatus.getHeight();
        if (l > 0) {
            tvStatus.scrollTo(0, l);
        } else {
            tvStatus.scrollTo(0, tvStatus.getLayout() == null ? 0
                    : tvStatus.getLayout().getHeight());
        }
    }
}
