# SSScreenStream —— 自研三端屏幕推流系统（v2）

三个互通软件，**完全自研传输协议与帧处理算法，未使用 RTMP/RTSP/WebRTC/FFmpeg/GStreamer 等任何现成推拉流项目源码**。

| 软件 | 平台/形态 | 作用 |
|---|---|---|
| **publisher** | 安卓 App | 采集手机屏幕，软件内可调**帧率(1-60fps)**、**JPEG 画质(10-100)**、分辨率档位，可**设置推流密码**，推送到指定服务器（**可选服务器**地址） |
| **player** | 安卓 App | 从指定服务器（**可选服务器**地址）拉取画面，按相应画质实时显示；**输入频道密码**；**右下角放大按钮一键全屏** |
| **server** | Linux 无 UI 终端 | 管理推/拉流的注册、密码校验、请求与帧中继，监听 **TCP 55564** |

兼容范围：**Android 7.0（API 24）～ Android 16（API 36）及以下**，minSdk 24 / targetSdk 36。

---

## 一、快速开始

### 1. 启动 Linux 服务器（无 UI 终端）

```bash
python3 server/ssserver.py                     # 默认 0.0.0.0:55564
python3 server/ssserver.py --port 55564        # 可指定 --host/--port
```

终端实时打印上线/断开/频道信息，支持命令：`list`、`clients`、**`kick <频道名>`（踢出该频道所有推/拉流者）**、`help`、`quit`。
后台运行：`nohup python3 server/ssserver.py > ssserver.log 2>&1`；停止：`Ctrl+C` 或 `kill <PID>`。

### 2. 推流端（Publisher）

1. 安装 `SSPublisher-debug.apk`；
2. 填写**服务器 IP**（端口固定 55564）、**频道名**（推拉流一致）、**推流密码**（留空=不设密码）；频道名与密码**只能输入数字和字母（无符号、无空格）**，服务器地址保留 IP 必需的点号；
3. 拖动滑块设置**帧率**与**画质**（运行中随时调整、立即生效）；分辨率可选 流畅720p / 清晰1080p / 原始；
4. 点"开始推流" → 系统弹窗授予投屏权限（Android 14/15/16 每次开始重新询问，属正常安全机制）；
5. 切到任意界面即开始推送，再次点按钮停止；被 kick 时弹窗提示"频道踢出"。

### 3. 拉流端（Player）

1. 安装 `SSPlayer-debug.apk`；
2. 填写同一**服务器 IP**、**相同频道名**、**相同密码**（频道名/密码仅数字字母），点"连接拉流"；
3. 画面实时显示，顶部展示 `分辨率 / fps / 码率(kbps)`；
4. **点画面右下角放大按钮进入全屏（沉浸式），再点还原**；
5. 断线自动重连；推流者上下线、密码错误均有状态提示；被 kick 时弹窗提示"频道踢出"且不再重连。

> 同一局域网直接可用；跨网段需保证到服务器 55564 端口可达（云服务器放行安全组/防火墙）。

---

## 二、自研协议（大端字节序）

通用包头（7 字节）：

```
MAGIC(2B, 'S''S') | TYPE(1B) | LEN(4B) | PAYLOAD(LEN 字节)
```

| TYPE | 名称 | Payload |
|---|---|---|
| 1 | HELLO_PUB | UTF-8 JSON `{"v":1,"ch":"频道","pwd":"密码"}` |
| 2 | HELLO_SUB | 同上 |
| 3 | FRAME | `u64 时间戳ms` `u32 宽` `u32 高` `JPEG 数据` |
| 4 / 5 | PING / PONG | `u64 时间戳ms`（原样回显） |
| 6 | SERVER_MSG | UTF-8 JSON `{"level":"info/error/kick","msg":"..."}` |
| 7 / 8 | LIST_REQ / LIST_RESP | 空 / 频道状态 JSON |

规则：
- 同一频道只允许一个推流者，第二个推流者收到 error 并被拒；
- **密码校验**：推流者设定频道密码；拉流者在推流者之后进入则立即校验，密码错误立即拒绝；拉流者先进入等待则暂存密码，推流者上线时统一校验、错误者被踢；
- **kick**：管理员终端执行 `kick <频道>`，服务器向该频道所有推/拉流者下发 `level=kick` 的"频道踢出"消息并断开；客户端弹窗提示，且不再自动重连；
- 拉流者数量不限；心跳 15s，90s 无报文服务器断开。

## 三、v2 低延迟 / 稳帧设计

v1 延迟大、帧率不稳的根因与修复：

1. **推流端三级流水线**：采集线程（Image→Bitmap）→ 独立编码线程（耗时 JPEG 压缩）→ IO 线程（发送）。v1 中压缩与采集串行，压缩一慢就丢帧/抖动；现在各环节深度 1、互不阻塞。
2. **拉流端接收/解码拆分**：接收线程只读包拆包（极快，内核 socket 不积压），帧放入深度 1 槽位；解码线程只解码**最新帧**。v1"读一帧解一帧"，解码一慢就逐帧累积延迟；现在自动跳过中间帧，始终追最新画面。
3. **帧节拍对齐墙钟**：采集按 `时间槽 = now/(1000/fps)` 取帧，帧率稳定不漂移。
4. **服务器队列收紧到 3 帧**（约 200ms 上限），突发即丢旧帧；并收紧 socket 收发缓冲，避免数据堆在内核缓冲。
5. 全链路 `TCP_NODELAY`；默认 720p 平衡清晰度与性能。

## 四、目录结构

```
screen-stream/
├── server/ssserver.py        # Linux 无 UI 终端服务器
├── publisher/                # 安卓推流端完整 Android Studio 工程
│   └── app/src/main/java/com/ssscreen/publisher/
│       ├── MainActivity.java     # 界面：服务器/频道/密码/帧率/画质
│       ├── CaptureService.java   # 采集-编码-发送三级流水线
│       └── StreamProtocol.java   # 自研协议
├── player/                   # 安卓拉流端完整工程
│   └── app/src/main/java/com/ssscreen/player/
│       ├── MainActivity.java     # 接收/解码双线程、全屏切换
│       └── StreamProtocol.java
├── tests/loop_test.py        # 协议自测（14 项）
├── build.sh                  # 一键构建（重复编译版本号自动 +1）
└── build-outputs/            # 构建产物 APK
```

## 五、自行编译

- Android Studio 打开 `publisher` 或 `player`，Gradle 同步后运行；
- 命令行：`bash build.sh`（需 JDK 17、Android SDK Platform 36；重复构建 versionCode 自动递增）。

## 六、自测结果（v2）

`python3 tests/loop_test.py`：**17/17 通过**——帧转发字节完整性、多拉流、错误/空密码立即拒绝、等待者密码校验与踢出、频道隔离、频道列表、重复推流者拒绝、断线通知、PING/PONG、**kick 后推/拉流者均收到"频道踢出"**。两个 APK 均实际编译通过（versionCode 4）。
