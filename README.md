# ScreenCast Demo — NearHub WebCast Android Client

基于 WebRTC 的 Android 屏幕投射客户端，对接 NearHub WebCast 协议。

## 项目结构

```
ScreenCastDemo/
├── app/                              # Android 客户端
│   └── src/main/kotlin/.../
│       ├── MainActivity.kt           # 入口：流程控制、权限、页面导航
│       ├── ScreenCastApp.kt          # Application
│       ├── service/
│       │   └── ScreenCaptureService.kt  # 前台服务（投屏必需）
│       ├── webrtc/
│       │   ├── NearHubSignalingClient.kt # NearHub WebSocket 信令协议
│       │   └── WebRtcManager.kt          # WebRTC 两阶段连接 + DataChannel
│       └── ui/screens/
│           ├── HomeScreen.kt         # 配对码输入页
│           └── SessionScreen.kt      # 会话页：建连状态/开始投屏/停止
├── signaling-server/                 # 本地测试用信令（不接 NearHub 时）
│   ├── server.py
│   ├── viewer.html
│   └── requirements.txt
└── README.md
```

## 核心流程

对接 NearHub WebCast 协议，完整流程如下：

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. WebSocket 连接                                                │
│    Android → wss://cast.nearhub.us/?role=sender                  │
│    Server  → reconnection_token                                  │
├─────────────────────────────────────────────────────────────────┤
│ 2. 加入房间                                                      │
│    Android → join {pairCode, name}                               │
│    Server  → joined {roomId, userId, hostUserId}                 │
│           or join_failed {reason}                                │
├─────────────────────────────────────────────────────────────────┤
│ 3. 首次 P2P 建连（无媒体流）                                      │
│    Android: createPeerConnection + createDataChannel             │
│    Android → offer (via WebSocket)                               │
│    Server relay → Receiver                                       │
│    Receiver → answer (via WebSocket)                             │
│    双方交换 ICE candidates                                        │
│    → P2P 建立，DataChannel 打开                                   │
├─────────────────────────────────────────────────────────────────┤
│ 4. 开始投屏（重新协商）                                            │
│    Android: MediaProjection → ScreenCapturerAndroid              │
│    Android: addTrack → renegotiate (offer/answer)                │
│    → 优先 DataChannel，回退 WebSocket                              │
│    Receiver 收到远端流 → 播放                                      │
├─────────────────────────────────────────────────────────────────┤
│ 5. 结束                                                          │
│    Android → DataChannel leave + WebSocket peer_leave            │
│    或 Receiver 踢出/关闭房间                                      │
└─────────────────────────────────────────────────────────────────┘
```

## 快速开始

### 前提条件

- Android Studio (Arctic Fox+)
- Android 手机 (API 24+, Android 7.0+)
- NearHub 接收端设备（或测试环境）

### 运行步骤

1. 用 Android Studio 打开 `ScreenCastDemo` 目录
2. 等待 Gradle 同步完成
3. 连接 Android 手机，运行 App
4. 在 NearHub 接收端上查看投屏码（pairCode）
5. 在 App 中输入投屏码，点击 **Join Room**
6. P2P 连接建立后，点击 **Start Screen Cast**
7. 授权录屏权限，开始投屏

### 本地测试（不接 NearHub）

如果暂时没有 NearHub 设备，可以用本地信令测试：

```bash
cd signaling-server
pip install websockets
python server.py
```

注意：本地信令服务器的协议格式与 NearHub 不同，
需要临时将 `NearHubSignalingClient` 的 URL 改为 `ws://<IP>:8080`。

## 关键文件说明

### NearHubSignalingClient.kt

实现 NearHub WebCast 的完整 WebSocket 信令协议：

- 连接：`wss://cast.nearhub.us/?role=sender[&token=...]`
- join / joined / join_failed
- offer / answer / ice-candidate
- peer_leave / room_closed
- reconnection_token / restored
- ping / pong 心跳

### WebRtcManager.kt

两阶段 WebRTC 管理器：

- **Phase 1** (`connectP2P`)：创建 PeerConnection + DataChannel，发送首个 offer（无媒体流）
- **Phase 2** (`startScreenCapture`)：MediaProjection 采集屏幕，addTrack，重新协商

DataChannel 用于：
- 投屏开始后的重新协商信令（优先于 WebSocket）
- 控制消息（leave 等）

### ScreenCaptureService.kt

Android 10+ 要求 MediaProjection 在前台服务中运行。

## 待完善

- [ ] DataChannel 消息格式需对照协议文档第二部分确认
- [ ] 断线重连（token 自动携带重连）
- [ ] ICE restart
- [ ] 换流（replaceTrack + switch-source）
- [ ] TURN 服务器配置（如果 NearHub 提供）
- [ ] 更完善的错误处理和 UI 提示

## 依赖

- [stream-webrtc-android](https://github.com/GetStream/webrtc-android) 1.3.9
- OkHttp 4.12.0
- Jetpack Compose (Material3)
