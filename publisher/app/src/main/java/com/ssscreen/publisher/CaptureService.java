package com.ssscreen.publisher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 屏幕采集 + 推流前台服务。
 * 流水线：ImageReader(采集/节流/JPEG编码) -> AtomicReference(仅保留最新帧)
 *         -> IO线程(自研协议封包发送, 断线自动重连)
 * 采集线程与网络线程完全解耦，网络抖动时只丢帧不阻塞采集，保证画面流畅。
 */
public class CaptureService extends Service {

    public static final String ACTION_STOP = "com.ssscreen.publisher.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_CHANNEL = "channel";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_MAX_EDGE = "max_edge";
    public static final String EXTRA_PWD = "pwd";

    private static final String CH_ID = "ss_pub_channel";
    private static final int NOTIF_ID = 1001;

    public interface StateListener {
        void onState(String state);

        /** 被服务器 kick 时回调（reason 为提示文本）。 */
        default void onKick(String reason) {
        }
    }

    private static volatile StateListener sListener;

    public static void setListener(StateListener l) {
        sListener = l;
    }

    // 运行时可热调整的参数（Activity 拖动进度条直接生效）
    public static volatile int sFps = 15;
    public static volatile int sQuality = 55;

    // 三级流水线槽位（均只保留最新，慢环节自动追帧）
    private final AtomicReference<Bitmap> rawBmp = new AtomicReference<>();
    private final AtomicReference<byte[]> pending = new AtomicReference<>();
    private volatile boolean running = false;
    private volatile boolean handshakeOk = false;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;

    private Socket socket;
    private OutputStream out;

    private int width;
    private int height;
    private int dpi;
    private long lastSlot = -1;
    private String host;
    private String channel;
    private String svcPwd = "";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void emit(String s) {
        StateListener l = sListener;
        if (l != null) {
            l.onState(s);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_STOP.equals(intent == null ? null : intent.getAction())) {
            stopStreaming();
            return START_NOT_STICKY;
        }

        int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        host = intent.getStringExtra(EXTRA_HOST);
        channel = intent.getStringExtra(EXTRA_CHANNEL);
        svcPwd = intent.getStringExtra(EXTRA_PWD);
        String pwd = svcPwd;
        sFps = intent.getIntExtra(EXTRA_FPS, 15);
        sQuality = intent.getIntExtra(EXTRA_QUALITY, 55);
        int maxEdge = intent.getIntExtra(EXTRA_MAX_EDGE, 1280);

        // Android 14+(target34/35/36)：必须先以前台服务(mediaProjection 类型)起步
        startForegroundCompat();

        running = true;
        handshakeOk = false;

        captureThread = new HandlerThread("ss-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        captureHandler.post(() -> initProjection(code, data, maxEdge));

        Thread encoder = new Thread(() -> encodeLoop(pwd), "ss-encoder");
        encoder.start();

        Thread io = new Thread(this::ioLoop, "ss-io");
        io.start();

        return START_NOT_STICKY;
    }

    // ---------------- 前台服务/通知 ----------------
    private void startForegroundCompat() {
        Notification n = buildNotification("正在推流屏幕画面");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "屏幕推流", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("屏幕推流中")
                .setContentText(text)
                .setOngoing(true);
        return b.build();
    }

    // ---------------- 屏幕采集 ----------------
    private void initProjection(int code, Intent data, int maxEdge) {
        if (!running) {
            return; // 服务已被停止，任务排队过晚
        }
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) {
                emit("无法获取投屏授权");
                stopStreaming();
                return;
            }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    emit("投屏已被系统/用户停止");
                    stopStreaming();
                }
            }, captureHandler);

            calcSize(maxEdge);

            imageReader = ImageReader.newInstance(width, height,
                    PixelFormat.RGBA_8888, 3);
            imageReader.setOnImageAvailableListener(this::onImage, captureHandler);

            if (!running) {
                imageReader.close();
                projection.stop();
                projection = null;
                return;
            }

            virtualDisplay = projection.createVirtualDisplay(
                    "SSVirtualDisplay", width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, captureHandler);

            emit("采集已启动 " + width + "x" + height);
        } catch (SecurityException se) {
            emit("投屏授权失败: " + se.getMessage());
            stopStreaming();
        } catch (Exception e) {
            emit("采集初始化异常: " + e.getMessage());
            stopStreaming();
        }
    }

    private void calcSize(int maxEdge) {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int rw = dm.widthPixels;
        int rh = dm.heightPixels;
        dpi = dm.densityDpi;
        int longEdge = Math.max(rw, rh);
        if (maxEdge > 0 && longEdge > maxEdge) {
            float scale = (float) maxEdge / longEdge;
            width = makeEven(Math.round(rw * scale));
            height = makeEven(Math.round(rh * scale));
        } else {
            width = rw;
            height = rh;
        }
    }

    private static int makeEven(int v) {
        return v % 2 == 0 ? v : v + 1;
    }

    private void onImage(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            long now = System.currentTimeMillis();
            int fps = Math.max(1, sFps);
            long interval = 1000L / fps;
            long slot = now / interval;   // 对齐墙钟时间槽，稳定帧率
            if (slot == lastSlot) {
                return;                  // 同一时间槽内只取一帧
            }

            Bitmap bmp = imageToBitmap(image);
            if (bmp == null) {
                return;
            }
            lastSlot = slot;

            // 采集只负责出位图；若编码线程还在忙，旧位图直接替换并回收
            Bitmap old = rawBmp.getAndSet(bmp);
            if (old != null && old != bmp) {
                old.recycle();
            }
        } catch (Throwable t) {
            emit("采集异常: " + t.getMessage());
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    /**
     * 编码线程：专门做耗时的 JPEG 压缩，与采集解耦。
     * 压缩慢于帧率时自动跳过中间帧，帧率不受阻塞、画面不积压。
     */
    private void encodeLoop(String pwd) {
        while (running) {
            Bitmap bmp = rawBmp.getAndSet(null);
            if (bmp == null) {
                sleep(4);
                continue;
            }
            try {
                long now = System.currentTimeMillis();
                ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
                int q = Math.max(1, Math.min(100, sQuality));
                bmp.compress(Bitmap.CompressFormat.JPEG, q, baos);
                byte[] jpeg = baos.toByteArray();
                byte[] pkt = StreamProtocol.frame(now, width, height, jpeg);
                byte[] oldPkt = pending.getAndSet(pkt);
                if (oldPkt != null) {
                    // 旧帧被替换，无需特殊释放
                }
            } catch (Throwable t) {
                emit("编码异常: " + t.getMessage());
            } finally {
                bmp.recycle();
            }
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buf = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        try {
            if (rowPadding == 0) {
                Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(buf);
                return bmp;
            }
            int paddedWidth = width + rowPadding / pixelStride;
            Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buf);
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
            if (cropped != padded) {
                padded.recycle();
            }
            return cropped;
        } catch (Exception e) {
            emit("位图转换失败: " + e.getMessage());
            return null;
        }
    }

    // ---------------- 网络发送 ----------------
    private void ioLoop() {
        while (running) {
            Socket s = null;
            try {
                emit("正在连接 " + host + ":" + StreamProtocol.PORT + " ...");
                s = new Socket();
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                s.connect(new InetSocketAddress(host, StreamProtocol.PORT), 5000);
                socket = s;
                out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
                InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);

                startReader(in);

                synchronized (this) {
                    handshakeOk = false;
                }
                out.write(StreamProtocol.hello(
                        StreamProtocol.T_HELLO_PUB, channel, svcPwd));
                out.flush();

                if (!waitHandshake(6000)) {
                    throw new IOException("握手超时");
                }

                emit("推流中 " + width + "x" + height + " @ " + sFps
                        + "fps, 画质 " + sQuality + "，频道 '" + channel + "'");

                long lastPing = System.currentTimeMillis();
                while (running && !s.isClosed()) {
                    byte[] pkt = pending.getAndSet(null);
                    if (pkt != null) {
                        out.write(pkt);
                        out.flush();
                    } else {
                        Thread.sleep(4);
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastPing > 15000) {
                        out.write(StreamProtocol.ping());
                        out.flush();
                        lastPing = now;
                    }
                }
            } catch (Exception e) {
                if (running) {
                    emit("连接异常: " + e.getMessage() + "，3 秒后重连");
                }
            } finally {
                closeQuietly(s);
                socket = null;
                out = null;
            }
            for (int i = 0; i < 30 && running; i++) {
                sleep(100);
            }
        }
    }

    private void startReader(final InputStream in) {
        Thread t = new Thread(() -> {
            try {
                while (running) {
                    int[] h = StreamProtocol.readHeader(in);
                    if (h == null) {
                        break;
                    }
                    int type = h[0];
                    int len = h[1];
                    if (len > 64 * 1024 * 1024) {
                        break;
                    }
                    byte[] pl = new byte[len];
                    if (!StreamProtocol.readFully(in, pl)) {
                        break;
                    }
                    if (type == StreamProtocol.T_MSG) {
                        JSONObject o = new JSONObject(
                                new String(pl, StandardCharsets.UTF_8));
                        String level = o.optString("level", "info");
                        String msg = o.optString("msg", "");
                        if (msg.contains("握手成功")) {
                            synchronized (this) {
                                handshakeOk = true;
                            }
                        } else if ("kick".equals(level)) {
                            StateListener ll = sListener;
                            if (ll != null) {
                                ll.onKick(msg);
                            }
                            stopStreaming(); // 阻止自动重连，彻底结束本次推流
                        } else {
                            emit("服务器: " + msg);
                        }
                        if ("error".equals(level)) {
                            Socket cur = socket;
                            if (cur != null) {
                                closeQuietly(cur); // 触发写循环重连
                            }
                        }
                    }
                    // PONG / 其它：忽略
                }
            } catch (Exception ignored) {
                // 写循环负责重连
            }
        }, "ss-reader");
        t.setDaemon(true);
        t.start();
    }

    private boolean waitHandshake(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            synchronized (this) {
                if (handshakeOk) {
                    return true;
                }
            }
            Thread.sleep(50);
        }
        synchronized (this) {
            return handshakeOk;
        }
    }

    // ---------------- 停止/清理 ----------------
    public void stopStreaming() {
        boolean wasRunning = running;
        running = false;
        pending.set(null);
        Bitmap leftover = rawBmp.getAndSet(null);
        if (leftover != null) {
            leftover.recycle();
        }
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (projection != null) {
                projection.stop();
                projection = null;
            }
        } catch (Exception ignored) {
        }
        closeQuietly(socket);
        socket = null;
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
        if (wasRunning) {
            stopForeground(true);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        super.onDestroy();
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
