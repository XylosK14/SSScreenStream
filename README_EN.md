# SSScreenStream

A self-built, three-part screen streaming system for Android and Linux.
**The transport protocol and frame pipeline are implemented from scratch — no RTMP, RTSP, WebRTC, FFmpeg, or GStreamer code is used.**

| Component | Platform / Form | Role |
|---|---|---|
| **publisher** | Android app | Captures the phone screen. Adjustable **frame rate (1–60 fps)**, **JPEG quality (10–100)** and resolution presets. Supports an optional **stream password**. Sends to a chosen server (configurable server address). |
| **player** | Android app | Pulls the stream from a chosen server and renders it live at the corresponding quality. Requires the channel password. A **button at the bottom-right toggles fullscreen**. |
| **server** | Linux, headless terminal | Manages publisher/subscriber registration, password verification, requests and frame relay. Listens on **TCP 55564**. |

Compatibility: **Android 7.0 (API 24) through Android 16 (API 36)** — `minSdk 24`, `targetSdk 36`.

---

## Features

- Custom binary protocol over a single TCP connection (port 55564)
- Channel-based rooms: one publisher per channel, unlimited players
- Optional per-channel password, verified by the server
- Live, in-app tuning of frame rate and JPEG quality (applied immediately while streaming)
- Resolution presets: Smooth 720p / Clear 1080p / Original
- Low-latency, frame-stable three-stage pipeline (see below)
- Fullscreen immersive viewing mode
- Automatic reconnect; heartbeats and idle timeouts
- Server-side admin commands, including `kick <channel>`

---

## Quick start

### 1. Start the Linux server (headless)

```bash
python3 server/ssserver.py                  # default 0.0.0.0:55564
python3 server/ssserver.py --port 55564     # --host/--port are configurable
```

Terminal commands: `list`, `clients`, `kick <channel>`, `help`, `quit`.

Run in the background:

```bash
nohup python3 server/ssserver.py > ssserver.log 2>&1
```

Stop with `Ctrl+C` or `kill <PID>`.

### 2. Publisher (Android)

1. Install `SSPublisher-debug.apk`.
2. Enter the **server IP** (port is fixed to 55564), a **channel name**, and an optional **password**. Channel names and passwords accept **letters and digits only** (no symbols or spaces); the server address keeps the dots required by IPv4.
3. Drag the sliders to set **frame rate** and **quality** (changes apply live). Choose a resolution preset.
4. Tap **Start streaming** and grant the screen-capture permission (Android 14/15/16 ask every time by design).
5. Switch to any app to stream; tap the button again to stop. A server kick shows a dialog titled "Streaming ended" with the message "Channel kicked".

### 3. Player (Android)

1. Install `SSPlayer-debug.apk`.
2. Enter the same **server IP**, the **same channel name** and the **same password**, then tap **Connect**.
3. The live view shows `resolution / fps / bitrate (kbps)` on top.
4. Tap the **bottom-right button to enter fullscreen (immersive)**; tap again to restore.
5. Reconnects automatically. Publisher presence and password errors are shown as status messages. A server kick shows a dialog titled "Playback ended" with the message "Channel kicked", and no further reconnect is attempted.

> Works out of the box on a LAN. Across networks, ensure TCP port 55564 on the server is reachable (open the firewall / cloud security group).

---

## Custom protocol (big-endian)

Common header (7 bytes):

```
MAGIC(2 bytes, 'S''S') | TYPE(1 byte) | LEN(4 bytes) | PAYLOAD(LEN bytes)
```

| TYPE | Name | Payload |
|---|---|---|
| 1 | HELLO_PUB | UTF-8 JSON `{"v":1,"ch":"channel","pwd":"password"}` |
| 2 | HELLO_SUB | same as above |
| 3 | FRAME | `u64 timestamp ms` `u32 width` `u32 height` `JPEG bytes` |
| 4 / 5 | PING / PONG | `u64 timestamp ms` (echoed back) |
| 6 | SERVER_MSG | UTF-8 JSON `{"level":"info/error/kick","msg":"..."}` |
| 7 / 8 | LIST_REQ / LIST_RESP | empty / channel status JSON |

Rules:

- Only one publisher per channel; a second publisher receives an error and is rejected.
- **Password verification**: players joining after the publisher are checked immediately; players waiting before the publisher store the password and are checked once the publisher comes online (wrong passwords are kicked).
- **Kick**: when an admin runs `kick <channel>`, the server sends every publisher and player in that channel a `level=kick` "Channel kicked" message and closes the connection. Clients show a dialog and do not reconnect.
- Heartbeat every 15 s; the server disconnects clients silent for 90 s.

---

## Low-latency, stable-frame design

1. **Publisher three-stage pipeline**: capture thread (Image → Bitmap) → dedicated encoder thread (JPEG compression) → IO thread (sending). Each stage has depth 1 and never blocks the others.
2. **Player receive/decode split**: the receive thread only reads packets (very fast) and places frames into a depth-1 slot; the decode thread always decodes the **newest frame**, skipping intermediate frames so latency never accumulates.
3. **Clock-aligned capture**: frames are picked on wall-clock time slots (`now / (1000/fps)`), keeping the frame rate steady.
4. **Server relay queue capped at 3 frames** (~200 ms upper bound); bursts drop old frames. Socket send/receive buffers are tightened.
5. `TCP_NODELAY` is enabled across the whole chain; 720p is the default balance of clarity and performance.

---

## Project layout

```
screen-stream/
├── server/ssserver.py        # Linux headless relay server
├── publisher/                # Android publisher (Android Studio project)
│   └── app/src/main/java/com/ssscreen/publisher/
│       ├── MainActivity.java     # UI: server / channel / password / fps / quality
│       ├── CaptureService.java   # capture–encode–send pipeline
│       └── StreamProtocol.java   # custom protocol
├── player/                   # Android player (Android Studio project)
│   └── app/src/main/java/com/ssscreen/player/
│       ├── MainActivity.java     # receive/decode threads, fullscreen toggle
│       └── StreamProtocol.java
├── tests/loop_test.py        # protocol self-test (17 checks)
├── build.sh                  # one-click build (auto-increments versionCode)
└── build-outputs/            # built APKs
```

## Building

- Open `publisher` or `player` in Android Studio, sync Gradle, and run; or
- From the command line: `bash build.sh` (requires JDK 17 and Android SDK Platform 36; repeated builds auto-increment `versionCode`).

## Self-test

`python3 tests/loop_test.py` — **17/17 checks pass**, covering frame relay byte integrity, multiple players, immediate rejection of wrong/empty passwords, password verification of waiting players, channel isolation, channel listing, duplicate-publisher rejection, disconnect notifications, PING/PONG, and server-side kicks received by both publishers and players.
