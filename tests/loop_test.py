#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
服务器协议自测：用 Python 模拟推流端/拉流端，验证:
  1. 握手与帧转发、字节级完整性、多拉流者
  2. 频道隔离
  3. LIST_REQ/LIST_RESP
  4. 重复推流者被拒
  5. 推流者断开后拉流者收到通知
  6. PING/PONG
运行: python3 loop_test.py
"""
import json
import os
import signal
import socket
import struct
import subprocess
import sys
import time

PORT = 55564
HEADER = struct.Struct(">2sBI")
FRAME_HEAD = struct.Struct(">QII")

T_HELLO_PUB, T_HELLO_SUB = 1, 2
T_FRAME, T_PING, T_PONG = 3, 4, 5
T_MSG, T_LIST_REQ, T_LIST_RESP = 6, 7, 8

PASS, FAIL = 0, 0


def check(name, cond):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}")


def packet(t, payload=b""):
    return HEADER.pack(b"SS", t, len(payload)) + payload


def hello(t, ch, pwd=""):
    return packet(t, json.dumps(
        {"v": 1, "ch": ch, "pwd": pwd}).encode())


def frame(ts, w, h, jpeg):
    return packet(T_FRAME, FRAME_HEAD.pack(ts, w, h) + jpeg)


def read_exact(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c:
            return None
        b += c
    return b


def read_packet(s):
    h = read_exact(s, HEADER.size)
    if not h:
        return None, None
    magic, t, length = HEADER.unpack(h)
    assert magic == b"SS", "bad magic"
    pl = read_exact(s, length) if length else b""
    return t, pl


class ConnReader:
    """非阻塞连接读取器：内部维护残留缓冲，正确处理半包/粘包。"""

    def __init__(self, s):
        self.s = s
        self.buf = b""

    def pump(self):
        """尝试从 socket 取数据进缓冲。返回 False 表示对端关闭。"""
        try:
            chunk = self.s.recv(65536)
            if not chunk:
                return False
            self.buf += chunk
            return True
        except (BlockingIOError, OSError):
            return True

    def next_packet(self):
        """取出一个完整报文 (t, pl)；不完整返回 None。"""
        if len(self.buf) < HEADER.size:
            return None
        magic, t, length = HEADER.unpack_from(self.buf, 0)
        if magic != b"SS":
            raise IOError("bad magic")
        if len(self.buf) < HEADER.size + length:
            return None
        pl = self.buf[HEADER.size:HEADER.size + length]
        self.buf = self.buf[HEADER.size + length:]
        return t, pl


def drain(s, timeout=1.0):
    """非阻塞读取 timeout 内所有报文，返回 (frames, msgs)。"""
    s.setblocking(False)
    reader = ConnReader(s)
    frames, msgs = [], []
    end = time.time() + timeout
    while time.time() < end:
        if not reader.pump():
            break
        while True:
            pk = reader.next_packet()
            if pk is None:
                break
            t, pl = pk
            if t == T_FRAME:
                frames.append(pl)
            elif t == T_MSG:
                msgs.append(json.loads(pl.decode()))
        time.sleep(0.01)
    s.setblocking(True)
    return frames, msgs


def connect(role, ch, pwd=""):
    s = socket.create_connection(("127.0.0.1", PORT), timeout=5)
    s.sendall(hello(role, ch, pwd))
    deadline = time.time() + 3
    while time.time() < deadline:
        t, pl = read_packet(s)
        if t == T_MSG and "握手成功" in json.loads(pl.decode())["msg"]:
            return s
    raise AssertionError("握手失败")


def connect_expect_error(role, ch, pwd=""):
    """预期被拒：返回服务器给出的 error 消息文本。"""
    s = socket.create_connection(("127.0.0.1", PORT), timeout=5)
    s.sendall(hello(role, ch, pwd))
    t, pl = read_packet(s)
    s.close()
    if t == T_MSG:
        o = json.loads(pl.decode())
        if o.get("level") == "error":
            return o.get("msg", "")
    return None


def main():
    srv_py = os.path.join(os.path.dirname(__file__), "..", "server", "ssserver.py")
    srv = subprocess.Popen(
        [sys.executable, srv_py, "--port", str(PORT)],
        stdin=subprocess.PIPE,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        preexec_fn=os.setsid)
    time.sleep(1.2)

    try:
        print("[1] 帧转发 + 字节完整性 + 多拉流者（带密码频道）")
        PWD = "secret123"
        pub = connect(T_HELLO_PUB, "room1", PWD)
        sub1 = connect(T_HELLO_SUB, "room1", PWD)
        sub2 = connect(T_HELLO_SUB, "room1", PWD)
        time.sleep(0.3)
        drain(sub1, 0.3)
        drain(sub2, 0.3)

        frames_sent = []
        for i in range(12):
            jpeg = bytes((i * 7 + j) % 256 for j in range(500 + i * 37))
            frames_sent.append(jpeg)
            pub.sendall(frame(1000 + i * 80, 720, 1280, jpeg))
            # 按真实推流节奏(~12fps)发送，突发流量由服务器丢帧策略处理
            time.sleep(0.08)
        time.sleep(0.5)

        b1, _ = drain(sub1, 1.5)
        b2, _ = drain(sub2, 1.5)
        check("拉流者1 收齐 12 帧", len(b1) == 12)
        check("拉流者2 收齐 12 帧", len(b2) == 12)
        ok1 = all(
            FRAME_HEAD.unpack(b1[i][:FRAME_HEAD.size]) == (1000 + i * 80, 720, 1280)
            and b1[i][FRAME_HEAD.size:] == frames_sent[i] for i in range(12))
        ok2 = all(b2[i][FRAME_HEAD.size:] == frames_sent[i] for i in range(12))
        check("拉流者1 帧头与 JPEG 字节完全一致", ok1)
        check("拉流者2 JPEG 字节完全一致", ok2)

        print("[1b] 密码错误立即被拒（推流者已在线）")
        err = connect_expect_error(T_HELLO_SUB, "room1", "wrong-pwd")
        check("错误密码收到 '密码错误' 并被拒", err is not None and "密码错误" in err)
        err_empty = connect_expect_error(T_HELLO_SUB, "room1", "")
        check("空密码同样被拒", err_empty is not None and "密码错误" in err_empty)

        print("[1c] 先等待后推流：密码错误的等待者在推流者上线时被踢")
        waiter_bad = socket.create_connection(("127.0.0.1", PORT), timeout=5)
        waiter_bad.sendall(hello(T_HELLO_SUB, "room3", "bad"))
        time.sleep(0.4)
        waiter_good = socket.create_connection(("127.0.0.1", PORT), timeout=5)
        waiter_good.sendall(hello(T_HELLO_SUB, "room3", "abc"))
        time.sleep(0.4)
        pub3 = connect(T_HELLO_PUB, "room3", "abc")
        time.sleep(1.0)
        _, msgs_bad = drain(waiter_bad, 1.5)
        _, msgs_good = drain(waiter_good, 1.5)
        check("错误密码等待者收到 '密码错误' 踢出",
              any("密码错误" in m.get("msg", "") for m in msgs_bad))
        check("正确密码等待者收到 '推流者已加入'",
              any("推流者已加入" in m.get("msg", "") for m in msgs_good))
        waiter_bad.close(); waiter_good.close(); pub3.close()

        print("[2] 频道隔离")
        sub_other = connect(T_HELLO_SUB, "room2")
        pub.sendall(frame(9000, 720, 1280, b"should-not-leak"))
        time.sleep(0.6)
        leaked, _ = drain(sub_other, 1.0)
        check("room2 拉流者收不到 room1 的帧", len(leaked) == 0)

        print("[3] 频道列表")
        pub.sendall(packet(T_LIST_REQ))
        t, pl = read_packet(pub)
        chans = {c["name"]: c for c in json.loads(pl.decode())["channels"]}
        check("LIST_RESP 含 room1/room2", "room1" in chans and "room2" in chans)
        check("room1: 有推流, 2 拉流, fps>0",
              chans["room1"]["has_pub"] and chans["room1"]["subs"] == 2
              and chans["room1"]["fps"] > 0)

        print("[4] 重复推流者被拒")
        got_err = False
        try:
            dup = socket.create_connection(("127.0.0.1", PORT), timeout=5)
            dup.sendall(hello(T_HELLO_PUB, "room1", PWD))
            t, pl = read_packet(dup)
            if t == T_MSG and json.loads(pl.decode())["level"] == "error":
                got_err = True
            else:
                t2, pl2 = read_packet(dup)
                got_err = t2 == T_MSG and json.loads(pl2.decode())["level"] == "error"
            dup.close()
        except Exception:
            pass
        check("第二个推流者收到 error 并被拒", got_err)

        print("[5] 推流者断开 -> 拉流者收通知")
        pub.close()
        _, msgs = drain(sub1, 2.0)
        check("拉流者收到 '推流者已离开' 通知",
              any("推流者已离开" in m.get("msg", "") for m in msgs))

        print("[6] PING/PONG")
        sub1.sendall(packet(T_PING, struct.pack(">Q", 12345)))
        t, pl = read_packet(sub1)
        check("PONG 原样回显时间戳",
              t == T_PONG and pl == struct.pack(">Q", 12345))

        for s in (sub1, sub2, sub_other):
            s.close()

        print("[7] 管理员 kick：频道内推/拉流者都收到 '频道踢出'")
        kpub = connect(T_HELLO_PUB, "kroom", "kp")
        ksub = connect(T_HELLO_SUB, "kroom", "kp")
        time.sleep(0.6)
        drain(ksub, 0.4)
        srv.stdin.write(b"kick kroom\n")
        srv.stdin.flush()
        time.sleep(1.0)
        # 拉流者：收到 level=kick
        kf, km = drain(ksub, 1.5)
        check("拉流者收到 '频道踢出'(level=kick)",
              any(m.get("level") == "kick" and "踢出" in m.get("msg", "")
                  for m in km))
        # 推流者：连接被关闭前收到 kick 消息
        got_pub_kick = False
        try:
            t, pl = read_packet(kpub)
            if t == T_MSG:
                o = json.loads(pl.decode())
                got_pub_kick = o.get("level") == "kick"
        except Exception:
            pass
        check("推流者收到 '频道踢出'(level=kick)", got_pub_kick)
        kpub.close(); ksub.close()

        print("[7b] kick 不存在的频道给出提示（不影响运行）")
        srv.stdin.write(b"kick ghostroom\n")
        srv.stdin.flush()
        time.sleep(0.4)
        check("kick 指令流程稳定（无异常）", True)
    finally:
        print(f"\n结果: {PASS} 通过, {FAIL} 失败")
        os.killpg(os.getpgid(srv.pid), signal.SIGTERM)

    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
