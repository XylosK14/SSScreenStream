#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SSScreenStream Server  (自研屏幕推流中继服务器)
================================================
无 UI 终端程序。监听 TCP 55564，管理"推流者(publisher)"与
"拉流者(subscriber)"，按频道(channel)中继视频帧。

协议(全部大端字节序):
  通用包头: MAGIC(2B, 0x53 0x53) | TYPE(1B) | LEN(4B) | PAYLOAD(LEN B)
  TYPE:
    1 HELLO_PUB   payload=UTF8 JSON {"v":1,"ch":"频道名"}
    2 HELLO_SUB   payload=UTF8 JSON {"v":1,"ch":"频道名"}
    3 FRAME       payload=u64时间戳ms | u32宽 | u32高 | JPEG数据
    4 PING        payload=u64发送方时间戳ms(可空)
    5 PONG        payload=原样回显
    6 SERVER_MSG  payload=UTF8 JSON
    7 LIST_REQ    payload=空
    8 LIST_RESP   payload=UTF8 JSON

用法:
  python3 ssserver.py                 # 监听 0.0.0.0:55564
  python3 ssserver.py --port 55564 --host 0.0.0.0
终端命令: help / list / quit
"""

import argparse
import collections
import json
import os
import signal
import socket
import socketserver
import struct
import sys
import threading
import time

# ---------------- 协议常量 ----------------
MAGIC = b"SS"
T_HELLO_PUB = 1
T_HELLO_SUB = 2
T_FRAME = 3
T_PING = 4
T_PONG = 5
T_MSG = 6
T_LIST_REQ = 7
T_LIST_RESP = 8

HEADER = struct.Struct(">2sBI")   # magic, type, length
HEADER_SIZE = HEADER.size         # 7
FRAME_HEAD = struct.Struct(">QII")  # ts, width, height -> 16

IDLE_TIMEOUT = 90.0     # 90 秒无任何报文 -> 断开
SUB_QUEUE_MAX = 3       # 拉流者待发帧队列上限(超出丢旧帧, 约200ms内)
FPS_WINDOW = 3.0        # fps 统计窗口(秒)


def log(msg):
    ts = time.strftime("%H:%M:%S")
    print(f"[{ts}] {msg}", flush=True)


def make_packet(t, payload=b""):
    return HEADER.pack(MAGIC, t, len(payload)) + payload


def make_msg(text, level="info"):
    return make_packet(T_MSG, json.dumps(
        {"level": level, "msg": text}, ensure_ascii=False).encode("utf-8"))


def read_exact(conn, n):
    """从 conn 精确读取 n 字节；对端关闭返回 None。"""
    buf = bytearray()
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            return None
        buf.extend(chunk)
    return bytes(buf)


class Channel:
    def __init__(self, name):
        self.name = name
        self.publisher = None          # Client 或 None
        self.subscribers = set()       # Client 集合
        self.pwd = ""                  # 推流端设定的频道密码
        self._frame_times = collections.deque()
        self.total_frames = 0
        self.total_bytes = 0

    def add_frame_stat(self, size):
        now = time.monotonic()
        self._frame_times.append(now)
        self.total_frames += 1
        self.total_bytes += size
        while self._frame_times and now - self._frame_times[0] > FPS_WINDOW:
            self._frame_times.popleft()

    def fps(self):
        now = time.monotonic()
        while self._frame_times and now - self._frame_times[0] > FPS_WINDOW:
            self._frame_times.popleft()
        return len(self._frame_times) / FPS_WINDOW if self._frame_times else 0.0


class Hub:
    """频道与客户端的全局管理(线程安全)。"""

    def __init__(self):
        self.lock = threading.RLock()
        self.channels = {}          # name -> Channel
        self.clients = set()        # 所有在线 Client

    # ---- 频道 ----
    def get_or_create_channel(self, name):
        with self.lock:
            ch = self.channels.get(name)
            if ch is None:
                ch = Channel(name)
                self.channels[name] = ch
            return ch

    def drop_channel_if_empty(self, name):
        with self.lock:
            ch = self.channels.get(name)
            if ch and ch.publisher is None and not ch.subscribers:
                del self.channels[name]

    def snapshot(self):
        with self.lock:
            return [{
                "name": c.name,
                "has_pub": c.publisher is not None,
                "subs": len(c.subscribers),
                "fps": round(c.fps(), 1),
                "frames": c.total_frames,
            } for c in self.channels.values()]

    # ---- 角色注册，返回 (ok, err) ----
    def register_publisher(self, client, name, pwd):
        ch = self.get_or_create_channel(name)
        with self.lock:
            if ch.publisher is not None and ch.publisher is not client:
                return False, f"频道 '{name}' 已有推流者在线"
            ch.publisher = client
            ch.pwd = pwd or ""
            client.channel = ch
            client.role = "pub"
            # 推流者上线：通知密码正确的拉流者，密码不符的标记踢出
            for sub in list(ch.subscribers):
                if ch.pwd and getattr(sub, "pending_pwd", "") != ch.pwd:
                    sub.server_close = ("error", "密码错误，已被踢出频道")
                else:
                    sub.enqueue_control(make_msg(
                        f"推流者已加入频道 '{name}'"))
        return True, None

    def register_subscriber(self, client, name, pwd):
        ch = self.get_or_create_channel(name)
        with self.lock:
            pwd = pwd or ""
            if ch.publisher is not None:
                # 频道已有密码：立即校验
                if ch.pwd and pwd != ch.pwd:
                    return False, "密码错误"
            # 无推流者时先暂存密码，推流者上线再校验
            client.pending_pwd = pwd
            ch.subscribers.add(client)
            client.channel = ch
            client.role = "sub"
            if ch.publisher is None:
                client.enqueue_control(make_msg(
                    f"已进入频道 '{name}'，等待推流者..."))
            else:
                client.enqueue_control(make_msg(
                    f"已进入频道 '{name}'，正在接收画面"))
        return True, None

    def broadcast_frame(self, channel, packet):
        with self.lock:
            subs = list(channel.subscribers)
        for sub in subs:
            sub.enqueue_frame(packet)

    def kick_channel(self, name):
        """管理员 kick：踢掉该频道所有推流/拉流者。返回被踢人数。"""
        with self.lock:
            ch = self.channels.get(name)
            if ch is None:
                return 0
            targets = []
            if ch.publisher is not None:
                targets.append(ch.publisher)
            targets.extend(list(ch.subscribers))
            for c in targets:
                c.server_close = ("kick", "频道踢出")
            return len(targets)

    def remove_client(self, client):
        with self.lock:
            if client not in self.clients:
                return
            self.clients.discard(client)
            ch = client.channel
            if ch is not None:
                if ch.publisher is client:
                    ch.publisher = None
                    for sub in list(ch.subscribers):
                        sub.enqueue_control(make_msg(
                            "推流者已离开，等待重连..."))
                ch.subscribers.discard(client)
                name = ch.name
            else:
                name = None
        if name:
            self.drop_channel_if_empty(name)


class Client:
    def __init__(self, conn, addr, hub):
        self.conn = conn
        self.addr = addr
        self.hub = hub
        self.role = None           # None / 'pub' / 'sub'
        self.channel = None
        self.pending_pwd = ""      # 拉流者握手时提供的密码
        # 非空时读循环发出 (level,text) 消息后断开：
        #   error=密码类拒绝, kick=管理员 kick
        self.server_close = None
        self.send_q = collections.deque()
        self.send_cond = threading.Condition()
        self.alive = True
        self.last_active = time.monotonic()
        self.writer = threading.Thread(target=self._write_loop,
                                       name=f"writer-{addr[1]}", daemon=True)
        self.reader = threading.Thread(target=self._read_loop,
                                       name=f"reader-{addr[1]}", daemon=True)

    def start(self):
        self.writer.start()
        self.reader.start()

    # -------- 发送侧 --------
    def enqueue_control(self, packet):
        """控制消息优先入队(不丢弃)。"""
        with self.send_cond:
            if not self.alive:
                return
            self.send_q.append((packet, False))
            self.send_cond.notify()

    def enqueue_frame(self, packet):
        """视频帧入队；队列积压过多时丢弃最旧帧，保证实时性。"""
        with self.send_cond:
            if not self.alive:
                return
            self.send_q.append((packet, True))
            while len(self.send_q) > SUB_QUEUE_MAX:
                self.send_q.popleft()
            self.send_cond.notify()

    def _write_loop(self):
        while True:
            with self.send_cond:
                while not self.send_q and self.alive:
                    self.send_cond.wait(timeout=1.0)
                if not self.alive and not self.send_q:
                    return
                packet, _is_frame = self.send_q.popleft()
            try:
                self.conn.sendall(packet)
            except OSError:
                self._die()
                return

    # -------- 接收侧 --------
    def _read_loop(self):
        self.conn.settimeout(1.0)
        try:
            while self.alive:
                if self.server_close:
                    level, text = self.server_close
                    self.enqueue_control(make_msg(text, level))
                    break
                try:
                    head = read_exact(self.conn, HEADER_SIZE)
                except socket.timeout:
                    head = False  # 超时标记；None 专指对端关闭
                if head is None:
                    break  # 对端已关闭
                if not head:
                    if not self.alive:
                        break
                    if time.monotonic() - self.last_active > IDLE_TIMEOUT:
                        log(f"{self.addr[0]} 心跳超时，断开")
                        break
                    continue
                magic, t, length = HEADER.unpack(head)
                if magic != MAGIC or length > 64 * 1024 * 1024:
                    log(f"{self.addr[0]} 非法报文，断开")
                    break
                try:
                    payload = read_exact(self.conn, length) if length else b""
                except socket.timeout:
                    continue
                if payload is None:
                    break
                self.last_active = time.monotonic()
                if not self._handle(t, payload):
                    break
        finally:
            self._close()

    def _handle(self, t, payload):
        """处理一条报文，返回 False 表示断开连接。"""
        if t == T_PING:
            self.enqueue_control(make_packet(T_PONG, payload))
            return True

        if t == T_LIST_REQ:
            data = json.dumps({"channels": self.hub.snapshot()},
                              ensure_ascii=False).encode("utf-8")
            self.enqueue_control(make_packet(T_LIST_RESP, data))
            return True

        if t in (T_HELLO_PUB, T_HELLO_SUB):
            if self.role is not None:
                self.enqueue_control(make_msg("重复握手，拒绝", "error"))
                return False
            try:
                info = json.loads(payload.decode("utf-8"))
                name = str(info.get("ch", "")).strip()
                pwd = str(info.get("pwd", ""))
                ver = info.get("v", 1)
            except (UnicodeDecodeError, json.JSONDecodeError):
                name, pwd, ver = "", "", 1
            if not name:
                self.enqueue_control(make_msg("频道名无效", "error"))
                return False
            if t == T_HELLO_PUB:
                ok, err = self.hub.register_publisher(self, name, pwd)
                if not ok:
                    self.enqueue_control(make_msg(err, "error"))
                    log(f"{self.addr[0]} 推流被拒: {err}")
                    return False
                log(f"{self.addr[0]} 推流者上线 -> 频道 '{name}'")
            else:
                ok, err = self.hub.register_subscriber(self, name, pwd)
                if not ok:
                    self.enqueue_control(make_msg(err, "error"))
                    log(f"{self.addr[0]} 拉流被拒: {err}")
                    return False
                log(f"{self.addr[0]} 拉流者上线 -> 频道 '{name}'")
            self.enqueue_control(make_msg("握手成功"))
            return True

        if t == T_FRAME:
            if self.role != "pub" or self.channel is None:
                self.enqueue_control(make_msg("未建立推流频道", "error"))
                return False
            if len(payload) < FRAME_HEAD.size:
                return True  # 丢弃坏帧，不断开
            ts, w, h = FRAME_HEAD.unpack_from(payload, 0)
            self.channel.add_frame_stat(len(payload))
            # 头+载荷整体原样转发，服务器不做二次编解码
            self.hub.broadcast_frame(
                self.channel, HEADER.pack(MAGIC, T_FRAME, len(payload)) + payload)
            return True

        # 未知类型：忽略
        return True

    def _die(self):
        self.alive = False
        with self.send_cond:
            self.send_cond.notify_all()

    def _close(self):
        self._die()
        # 先关读端；写端保持，确保队列中的 error/通知能发出去
        try:
            self.conn.shutdown(socket.SHUT_RD)
        except OSError:
            pass
        self.writer.join(timeout=1.5)
        try:
            self.conn.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.conn.close()
        except OSError:
            pass
        self.hub.remove_client(self)
        role = self.role or "未握手"
        ch = self.channel.name if self.channel else "-"
        log(f"{self.addr[0]} 断开(角色={role}, 频道={ch})")


class StreamServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, addr, hub):
        self.hub = hub
        super().__init__(addr, StreamHandler)

    def handle_error(self, request, client_address):
        # 连接重置是正常现象，不打印堆栈
        exc = sys.exc_info()[1]
        if not isinstance(exc, (ConnectionResetError, BrokenPipeError)):
            super().handle_error(request, client_address)


class StreamHandler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        # 收紧收发缓冲，避免数据在内核缓冲中堆积形成延迟
        try:
            self.request.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 256 * 1024)
            self.request.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 128 * 1024)
        except OSError:
            pass
        client = Client(self.request, self.client_address, self.server.hub)
        with self.server.hub.lock:
            self.server.hub.clients.add(client)
        log(f"{self.client_address[0]} 接入")
        client.start()
        # 等待读线程结束(写线程为 daemon)
        client.reader.join()


def print_channels(hub):
    chans = hub.snapshot()
    if not chans:
        log("当前无活动频道")
        return
    log("活动频道列表:")
    for c in chans:
        state = "推流中" if c["has_pub"] else "无推流"
        log(f"  # {c['name']:<16} {state}  拉流={c['subs']}  "
            f"fps={c['fps']}  累计帧={c['frames']}")


def stdin_loop(hub, server, stop):
    help_text = ("终端命令: list(频道列表) / clients(在线数) "
                 "/ kick <频道名>(踢出该频道所有人) / help / quit")
    log(help_text)
    for line in sys.stdin:
        cmd = line.strip()
        low = cmd.lower()
        if low in ("list", "channels"):
            print_channels(hub)
        elif low == "clients":
            log(f"在线连接数: {len(hub.clients)}")
        elif low == "help":
            log(help_text)
        elif low.startswith("kick"):
            parts = cmd.split(None, 1)
            if len(parts) < 2 or not parts[1].strip():
                log("用法: kick <频道名>")
                continue
            name = parts[1].strip()
            n = hub.kick_channel(name)
            if n:
                log(f"已执行 kick '{name}'，共踢出 {n} 个客户端")
            else:
                log(f"kick 失败：频道 '{name}' 不存在")
        elif low in ("quit", "exit", "q"):
            stop.set()
            try:
                server.shutdown()
            except Exception:
                pass
            return


def main():
    ap = argparse.ArgumentParser(description="SSScreenStream 中继服务器")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=55564)
    args = ap.parse_args()

    hub = Hub()
    server = StreamServer((args.host, args.port), hub)
    stop = threading.Event()

    def shutdown(*_):
        stop.set()
        server.shutdown()

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    si = threading.Thread(target=stdin_loop, args=(hub, server, stop),
                          daemon=True)
    si.start()

    log(f"SSScreenStream 服务器已启动: {args.host}:{args.port}")
    log(f"进程 PID={os.getpid()}，等待推流/拉流客户端连接...")

    try:
        while not stop.is_set():
            time.sleep(0.5)
    finally:
        log("服务器正在关闭...")
        try:
            server.server_close()
        except Exception:
            pass
        log("已退出，再见。")


if __name__ == "__main__":
    main()
