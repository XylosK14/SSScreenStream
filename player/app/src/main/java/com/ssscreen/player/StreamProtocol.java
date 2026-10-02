package com.ssscreen.player;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.json.JSONObject;

/**
 * 自研屏幕拉流二进制协议（三端共用，大端字节序）。
 *
 * 通用包头: MAGIC(2B='S','S') | TYPE(1B) | LEN(4B) | PAYLOAD(LEN B)
 * TYPE:
 *   1 HELLO_PUB / 2 HELLO_SUB : payload=JSON {"v":1,"ch":"频道名"}
 *   3 FRAME     : payload=u64时间戳ms | u32宽 | u32高 | JPEG
 *   4 PING / 5 PONG : payload=u64时间戳ms
 *   6 SERVER_MSG: payload=JSON
 *   7 LIST_REQ / 8 LIST_RESP
 */
public final class StreamProtocol {

    public static final int PORT = 55564;

    public static final int T_HELLO_PUB = 1;
    public static final int T_HELLO_SUB = 2;
    public static final int T_FRAME = 3;
    public static final int T_PING = 4;
    public static final int T_PONG = 5;
    public static final int T_MSG = 6;
    public static final int T_LIST_REQ = 7;
    public static final int T_LIST_RESP = 8;

    public static final int HEADER_SIZE = 7;
    public static final int FRAME_HEAD_SIZE = 16;

    private StreamProtocol() {
    }

    /** 组装一个完整数据包。 */
    public static byte[] packet(int type, byte[] payload) {
        int len = payload == null ? 0 : payload.length;
        ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE + len)
                .order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) 'S').put((byte) 'S').put((byte) type).putInt(len);
        if (len > 0) {
            bb.put(payload);
        }
        return bb.array();
    }

    /** 握手报文（带频道密码）。 */
    public static byte[] hello(int type, String channel, String pwd) {
        JSONObject o = new JSONObject();
        try {
            o.put("v", 1);
            o.put("ch", channel);
            o.put("pwd", pwd == null ? "" : pwd);
        } catch (Exception ignored) {
        }
        return packet(type, o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static byte[] ping() {
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(System.currentTimeMillis());
        return packet(T_PING, bb.array());
    }

    /** 精确读取 dst.length 字节；对端关闭返回 false。 */
    public static boolean readFully(InputStream in, byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int n = in.read(dst, off, dst.length - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    /** 解析包头，返回 {type, length}；对端关闭返回 null。 */
    public static int[] readHeader(InputStream in) throws IOException {
        byte[] h = new byte[HEADER_SIZE];
        if (!readFully(in, h)) {
            return null;
        }
        if (h[0] != 'S' || h[1] != 'S') {
            throw new IOException("bad magic");
        }
        int type = h[2] & 0xFF;
        int length = ((h[3] & 0xFF) << 24) | ((h[4] & 0xFF) << 16)
                | ((h[5] & 0xFF) << 8) | (h[6] & 0xFF);
        return new int[]{type, length};
    }
}
