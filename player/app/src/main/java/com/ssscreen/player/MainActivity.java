package com.ssscreen.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 拉流端界面：填写服务器（可选服务器地址，端口固定 55564）、频道名、密码。
 *
 * 低延迟双线程：
 *   接收线程：仅读包/拆包，帧放入 latestFrame(深度1)，不做重活，socket 不积压；
 *   解码线程：只取最新帧解码显示，解码慢时自动跳过中间帧，永不累积延迟。
 * 右下角放大按钮：一键全屏（沉浸式）/还原。
 */
public class MainActivity extends AppCompatActivity {

    private EditText etHost;
    private EditText etChannel;
    private EditText etPwd;
    private Button btnToggle;
    private ImageView imageView;
    private ImageButton btnFullscreen;
    private TextView tvInfo;
    private TextView tvStatus;
    private LinearLayout controlPanel;

    private volatile boolean connected = false;
    private Thread netThread;

    // 接收线程 -> 解码线程 的最新帧槽（深度1，自动丢旧帧）
    private final AtomicReference<byte[]> latestFrame = new AtomicReference<>();

    // 统计
    private volatile int frameCount = 0;
    private volatile int byteCount = 0;
    private volatile int curW = 0;
    private volatile int curH = 0;
    private volatile Socket activeSocket = null;

    private boolean fullscreen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        controlPanel = findViewById(R.id.panel_control);
        etHost = findViewById(R.id.et_host);
        etChannel = findViewById(R.id.et_channel);
        etPwd = findViewById(R.id.et_pwd);
        btnToggle = findViewById(R.id.btn_toggle);
        imageView = findViewById(R.id.iv_screen);
        btnFullscreen = findViewById(R.id.btn_fullscreen);
        tvInfo = findViewById(R.id.tv_info);
        tvStatus = findViewById(R.id.tv_status);

        etChannel.setText("default");
        tvStatus.setMovementMethod(new ScrollingMovementMethod());

        // 输入限制：频道名/密码仅数字和字母（无符号、无空格）；
        // 服务器地址保留 IP 必需的点号"."
        InputFilter alnumOnly = (src, start, end, dst, ds, de) -> {
            for (int i = start; i < end; i++) {
                if (!Character.isLetterOrDigit(src.charAt(i))) {
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

        btnToggle.setOnClickListener(v -> toggle());
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());
    }

    @Override
    protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    private void toggle() {
        if (connected) {
            disconnect();
            appendStatus("已断开连接");
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
        connect(h, ch, etPwd.getText().toString());
    }

    // ---------------- 连接管理 ----------------
    private void connect(final String host, final String channel, final String pwd) {
        connected = true;
        btnToggle.setText(R.string.btn_disconnect);
        etHost.setEnabled(false);
        etChannel.setEnabled(false);
        etPwd.setEnabled(false);
        frameCount = 0;
        byteCount = 0;
        latestFrame.set(null);

        netThread = new Thread(() -> ioLoop(host, channel, pwd), "ss-player-net");
        netThread.start();

        // 解码线程：永远只解码最新帧
        Thread decoder = new Thread(() -> {
            while (connected) {
                byte[] pl = latestFrame.getAndSet(null);
                if (pl == null) {
                    sleep(3);
                    continue;
                }
                decodeAndShow(pl);
            }
        }, "ss-player-decoder");
        decoder.setDaemon(true);
        decoder.start();

        // 统计线程
        Thread stat = new Thread(() -> {
            while (connected) {
                sleep(1000);
                int fps = frameCount;
                int kbps = byteCount * 8 / 1000;
                frameCount = 0;
                byteCount = 0;
                final String info = getString(R.string.info_format,
                        curW, curH, fps, kbps);
                runOnUiThread(() -> tvInfo.setText(info));
            }
        }, "ss-player-stat");
        stat.setDaemon(true);
        stat.start();
    }

    private void disconnect() {
        connected = false;
        latestFrame.set(null);
        closeQuietly(activeSocket);
        activeSocket = null;
        Thread t = netThread;
        if (t != null) {
            t.interrupt();
            netThread = null;
        }
        btnToggle.setText(R.string.btn_connect);
        etHost.setEnabled(true);
        etChannel.setEnabled(true);
        etPwd.setEnabled(true);
    }

    // ---------------- 网络接收（只读包，不做解码） ----------------
    private void ioLoop(String host, String channel, String pwd) {
        while (connected) {
            Socket socket = null;
            try {
                postStatus("正在连接 " + host + ":" + StreamProtocol.PORT + " ...");
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.connect(new InetSocketAddress(host, StreamProtocol.PORT), 5000);
                activeSocket = socket;
                socket.setSoTimeout(120000);

                final Socket s = socket;
                final OutputStream out = new BufferedOutputStream(s.getOutputStream(), 16 * 1024);
                BufferedInputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);

                Thread heartbeat = new Thread(() -> {
                    while (connected && !s.isClosed()) {
                        sleep(15000);
                        try {
                            out.write(StreamProtocol.ping());
                            out.flush();
                        } catch (Exception e) {
                            return;
                        }
                    }
                }, "ss-player-heartbeat");
                heartbeat.setDaemon(true);
                heartbeat.start();

                out.write(StreamProtocol.hello(
                        StreamProtocol.T_HELLO_SUB, channel, pwd));
                out.flush();
                postStatus("已发送订阅请求，频道 '" + channel + "'");

                while (connected && !socket.isClosed()) {
                    int[] h;
                    try {
                        h = StreamProtocol.readHeader(in);
                    } catch (SocketTimeoutException te) {
                        continue;
                    }
                    if (h == null) {
                        throw new java.io.IOException("服务器关闭了连接");
                    }
                    int type = h[0];
                    int len = h[1];
                    if (len > 64 * 1024 * 1024) {
                        throw new java.io.IOException("报文过大");
                    }
                    byte[] pl = new byte[len];
                    if (!StreamProtocol.readFully(in, pl)) {
                        throw new java.io.IOException("读包不完整");
                    }
                    if (type == StreamProtocol.T_FRAME) {
                        // 仅放入最新帧槽，旧帧直接替换 —— 接收线程始终保持最快
                        latestFrame.set(pl);
                    } else if (type == StreamProtocol.T_MSG) {
                        handleMsg(pl, socket);
                    }
                    // PONG / 其它：忽略
                }
            } catch (Exception e) {
                if (connected) {
                    postStatus("连接异常: " + e.getMessage() + "，3 秒后重连");
                }
            } finally {
                if (activeSocket == socket) {
                    activeSocket = null;
                }
                closeQuietly(socket);
            }
            for (int i = 0; i < 30 && connected; i++) {
                sleep(100);
            }
        }
    }

    private void handleMsg(byte[] pl, Socket socket) {
        try {
            JSONObject o = new JSONObject(new String(pl, StandardCharsets.UTF_8));
            String level = o.optString("level", "info");
            String msg = o.optString("msg", "");
            if ("kick".equals(level)) {
                runOnUiThread(() -> showKickDialog(msg));
                disconnect(); // 阻止自动重连
                return;
            }
            if (!msg.contains("握手成功")) {
                postStatus("服务器: " + msg);
            }
            if ("error".equals(level)) {
                closeQuietly(socket); // 触发重连
            }
        } catch (Exception ignored) {
        }
    }

    /** 频道踢出弹窗。 */
    private void showKickDialog(String reason) {
        if (fullscreen) {
            toggleFullscreen(); // 退出全屏回到正常界面
        }
        new AlertDialog.Builder(this)
                .setTitle("拉流结束")
                .setMessage("频道踢出")
                .setCancelable(false)
                .setPositiveButton("确定", null)
                .show();
        appendStatus("已被服务器踢出频道");
    }

    // ---------------- 解码并显示（解码线程） ----------------
    private void decodeAndShow(byte[] pl) {
        if (pl.length < StreamProtocol.FRAME_HEAD_SIZE) {
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(pl, 0, StreamProtocol.FRAME_HEAD_SIZE)
                .order(java.nio.ByteOrder.BIG_ENDIAN);
        /* long ts = */ bb.getLong();
        curW = bb.getInt();
        curH = bb.getInt();

        Bitmap bmp = BitmapFactory.decodeByteArray(
                pl, StreamProtocol.FRAME_HEAD_SIZE,
                pl.length - StreamProtocol.FRAME_HEAD_SIZE);
        if (bmp != null) {
            frameCount++;
            byteCount += pl.length;
            runOnUiThread(() -> imageView.setImageBitmap(bmp));
        }
    }

    // ---------------- 右下角放大/全屏 ----------------
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        if (fullscreen) {
            controlPanel.setVisibility(View.GONE);
            tvStatus.setVisibility(View.GONE);
            btnFullscreen.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            enterImmersive();
        } else {
            controlPanel.setVisibility(View.VISIBLE);
            tvStatus.setVisibility(View.VISIBLE);
            btnFullscreen.setImageResource(android.R.drawable.ic_menu_crop);
            exitImmersive();
        }
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void exitImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullscreen) {
            enterImmersive();
        }
    }

    // ---------------- 辅助 ----------------
    private void postStatus(String s) {
        runOnUiThread(() -> appendStatus(s));
    }

    private void appendStatus(String s) {
        tvStatus.append(s);
        tvStatus.append("\n");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.shutdownInput();
        } catch (Exception ignored) {
        }
        try {
            s.shutdownOutput();
        } catch (Exception ignored) {
        }
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }
}
