# 接收端发现、本地投屏与设备信任

首页的“发现接收端”提供：同网发现 → 首次现场核对完整指纹 → 保存设备信任 → 请求接收端显示本地配对码 → 选择“使用此设备的本地配对码” → 手动输入后连接。扫描和核对阶段不打开信令 WebSocket、不自动填码或授予反控权限；明确连接后才建立本地 WSS。

## 协议与职责

协议参考是 Desktop TX 的 `src/discovery/devices.mjs`、`src-tauri/src/discovery.rs`、`trust.rs`、`pinned_tls.rs` 和 `src/webcast/receiver-trust.mjs`。本次只修改 Android 仓库，没有修改或发布桌面端。

| 模块 | 职责 |
| --- | --- |
| `ReceiverDiscovery` | 规范化 `_nearcast._tcp.` / `_nearcast._tcp.local.`，识别 WebCast、指纹和显码能力；拒绝私密 TXT 字段、不安全 URL 和无效地址 |
| `NsdDiscoveryManager` | Android 系统 DNS-SD 浏览；API 34+ 连续服务信息回调，旧系统串行解析和刷新；关闭、后台、丢失服务后清理当前原生路由 |
| `ReceiverDirectory` | 有界列表、实例去重、解析代次；迟到解析不能恢复丢失或前一次扫描的设备 |
| `ReceiverTls` | 原生解析地址上的固定 HTTPS 操作，TLS SPKI 校验先于任何 HTTP 字节；无代理、跳转、Cookie 或环境凭据 |
| `ReceiverActions` | 选择、现场核对、超时、取消、显码、密钥变化和撤销状态机；跨 Activity 串行化信任写入及请求 |
| `ReceiverTrustStore` | 应用私有存储，按精确 deviceId 保存指纹；校验格式、容量和持久化结果，损坏或写入失败时停止信任操作 |
| `DeviceDiscoveryBottomSheet` | 中英文设备列表、完整指纹及明确确认、失败重试、移除信任和手动连接入口 |

只浏览 `_nearcast._tcp.`，不再使用旧 `_webrtc-signal._tcp.`；不将 AirPlay、RAOP 或其他服务伪装成 NearCast 接收端。设备名称来自 display TXT 或实例名，路由只来自系统解析的 A/AAAA 地址和 SRV 端口。TXT 中的 endpoint 可以用于协议格式校验，但不能提供实际目的地址；TXT 里的配对码、token、room、password、secret 等字段使整条记录被拒绝。

列表受系统服务发现、更新和丢失事件管理，最长支持 64 个当前实例，同时限制持续订阅和解析队列。不会用一次性 60 秒 UI 倒计时删除仍有效的 DNS-SD 记录。旧解析回调带实例代次；刷新扫描、实例丢失后重现、关闭及后台均不能被旧回调覆盖。

## 固定 HTTPS 与指纹信任

指纹是叶证书 **SHA256(DER SubjectPublicKeyInfo)**，完整 64 位大写十六进制，按原始 deviceId 区分大小写。与桌面端一致，信任物理核对过的公钥，允许接收端自签名证书、地址变化以及同公钥重新签发证书；其他 HTTPS、更新、云信令客户端继续使用自己的 TLS 策略。

1. 首次选择先进行 TLS 观察，只读取证书公钥并关闭，不发送 HTTP。
2. 用刚观察到的公钥完成新的 TLS 握手后，POST `/nearcast/trust-display`。
3. 接收端返回 shown 后显示完整指纹。用户必须在现场逐组核对并勾选一致，再点击信任。候选一分钟过期，切换、离线或取消立即失效。
4. 保存成功后，POST `/nearcast/code-display`。之后的显码请求必须在 TLS 握手中匹配保存的公钥；公钥变化时，在 HTTP 发送前拒绝，要求重新现场确认。
5. 移除信任会取消本窗口请求并串行等待已经开始的操作；队列中的请求重新读取当前 pin，不继续使用已撤销的旧 pin。离线设备也可移除已保存的信任。

请求格式保持桌面端约定，version 为 1，requestId 每次独立生成：

```json
{"type":"nearcast.trust_display.request","version":1,"deviceId":"receiver-id","requestId":"..."}
{"type":"nearcast.code_display.request","version":1,"deviceId":"receiver-id","requestId":"...","reason":"sender-device-selected"}
```

响应类型分别为 `nearcast.trust_display.result` / `nearcast.code_display.result`。校验版本、requestId、状态及可选设备身份；只投影 shown、unavailable、rate_limited、not_supported 状态。指纹不来自响应或 TXT，配对码不来自发现或 HTTP 响应。指纹响应仅接受四个约定字段。

请求最多 1KiB，响应体最多 4KiB，响应头最多 8KiB，连接及整个操作均有时限。拒绝重定向、Transfer-Encoding、重复或缺失 Content-Length。连接可在原生解析地址之间有界尝试，HTTP 已开始后不在其他地址重复执行同一个请求。取消会关闭实际 Socket，迟到返回不能保存信任、恢复选择或触发显码。

## 权限与连接边界

保留 minSdk 24、compile/target SDK 35。使用现有 INTERNET / ACCESS_WIFI_STATE / ACCESS_NETWORK_STATE，加上普通 CHANGE_WIFI_MULTICAST_STATE 权限；多播锁只在扫描页面处于前台期间持有，不额外要求位置、录音或无障碍授权来发现设备。

扫描失败或系统阻止网络访问时显示可恢复状态，提供重新扫描或应用设置入口；手动配对始终保留。Android 17 的广域本地网络运行时权限针对 target SDK 37+；以后升级 target 时需接入相应权限或系统服务选择器，不能照搬当前兼容行为。[Android NSD 官方说明](https://developer.android.com/reference/android/net/nsd/NsdManager)

“改用云端配对码”单独选择 `https://cast.nearhub.us/`。本地码只发送给所选接收端；连接失败不会把本地码转发到云端。`LocalDirectSignalingClient` 继续只用于旧调试夹具；正式本地连接复用 `NearHubSignalingClient` 的房间、SDP 与 ICE 格式，使用服务器实际分配的 roomId/userId/hostUserId。

## 本地 WSS 与本地显码

`LocalReceiverConnection` 将所选原生解析地址绑定到仅此连接使用的内部 hostname；该名称不会查询系统或公网 DNS。目标端口取自 SRV，SocketFactory 取自解析时的 Android Network。每个 owner 独立创建 TLS context、连接池和 OkHttpClient；在 TLS 握手、HTTP Upgrade 以及发送信令前检查保存的 SPKI。自签名证书及同公钥重签可用；公钥变化或信任撤销会阻断连接，不发送配对码。

本地客户端不使用代理、重定向、Cookie、Authorization 或云端重连 token。只有根路径 `/?role=sender` 可升级到 WSS。信任存储变化会取消当前本地 Socket；关闭 owner 后取消请求、清理连接池、移除订阅。迟到回调不能覆盖后续连接的界面状态。新建连接有十五秒上限，支持取消、错误码提示及重试。

接收端同时公布 `signaling-select-v1`、`code-display-v2` 和 runtime 时，固定 HTTPS 显码请求使用 version=2、backend=local、runtimeInstanceId；响应必须匹配 requestId、deviceId、backend、runtimeInstanceId。旧版接收端沿用 v1，界面明确提示用户确认屏幕显示的是本地码。TXT 的 local-state 只用于可用性提示，不能代替 TLS 信任或房间配对。

本地协议按 RX 的 `src/webcast/LocalWebCastSignaling.cpp`、`LocalWebCastBackend.cpp`、`LocalWebCastDiscovery.cpp` 核对；来源身份按 `WebCastSourceInfo.h`、`WebCastSignaling.cpp`、`WebCastInputPeer.cpp` 核对。接收端参考工作树为 `nearcast-touchback-rx/NearCast`，本次没有修改 RX。

## 验证与测试夹具

测试先验证未实现行为，再逐步实现。JVM 测试使用真实本地 TLS 服务器及三份专用测试证书，检查观察不发 HTTP、变更密钥零应用字节、同公钥证书重签、重定向和响应边界，并覆盖私密 TXT、服务类型、精确设备身份、能力分离、固定请求、代次、确认、取消、离线和撤销。

`app/src/test/resources/discovery/*.p12` 与 `app/src/debug/res/raw/discovery_fixture_*.p12` 都是专用测试密钥，口令 `fixture-only`，不含真实设备或生产凭据。调试夹具和密钥不进入 release 源集。`DiscoveryFixtureActivity` 在真机上注册自有 mDNS 服务并运行 TLS 接收端，可在同一个设备上验证真实 NSD 与 HTTPS，而无需访问私人应用或信任真实接收端。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug '-PdebugAppIdSuffix=.dev'
adb shell am start -n com.auditoryworks.nearcast.dev/com.auditoryworks.nearcast.discovery.DiscoveryFixtureActivity
adb shell am start -n com.auditoryworks.nearcast.dev/com.auditoryworks.nearcast.MainActivity
```

从首页发现并选择“NearCast 测试接收端”。测试证书的完整指纹显示在夹具页面与本机日志中；核对这些专用测试指纹不等同于为真实接收端建立信任。调试专用显式控制广播可以执行 `rotate-key`、`withdraw`、`restore`、`rate_limited`、`unavailable`、`shown`、`stop`；不会控制其他设备。

```powershell
adb shell am broadcast -n com.auditoryworks.nearcast.dev/com.auditoryworks.nearcast.discovery.DiscoveryFixtureControl --es action rotate-key
```

真机截图与夹具操作日志保存于被 Git 忽略的 `build/discovery-evidence/` 与 `build/local-evidence/`。实际硬件接收端的现场指纹核对、不同网段和组播隔离等拓扑需在目标网络验证，本地夹具不替代这些外部验收。

本地媒体夹具可运行 `scripts/device-peer.mjs`，设置 `NEARCAST_LOCAL_WEBCAST_FIXTURE=1`，并用 `adb reverse tcp:18888 tcp:18888`。启动 `DiscoveryFixtureActivity --ez webcast true` 后，调试夹具将自有 TLS WSS 转发到电脑的独立 WebCast 房间服务器。测试码固定为 TEST，仅适用于该自有夹具。浏览器按 RX 的来源身份、整屏、几何版本字符串及请求确认规则检查后，才打开 AXTP 输入流。该代理只在 debug 源集中，正式本地投屏直接连接接收端。

### 2026-11-17 验证记录

- 全部 44 项 JVM 测试通过，包括新增 16 项发现、状态机和真实 TLS 测试，以及原有 28 项投屏／反控测试。
- `lintDebug`、`assembleDebug`、`assembleDebugAndroidTest` 和 `git diff --check` 通过。Lint 为 0 错误、50 项警告；包含已有警告、调试夹具文案及固定操作所用自定义 SPKI 校验器的提示。
- Pixel 7 / Android 17 上实际完成系统 NSD、IPv4/IPv6 地址解析、首次指纹核对后才显码、保存信任后重新选择、公钥更换阻断、核对中撤回服务、离线撤销、恢复发现、限流和暂不可用状态，以及返回首页保持配对码为空。
- 服务从首次显码到四分钟后的再次核对持续有效，未因一次性 UI TTL 消失；撤回广告立即使候选失效，持久化文件保留旧 pin，直到用户明确移除。
- 完整 TLS 和指纹流程使用专用测试接收端。最终清理夹具后，系统 NSD 也实际发现了 `NearCast-Test-00x`（`192.168.18.87`）；本轮未替用户确认该真实设备的指纹或执行其云端配对。
- 最终 `.dev` 调试 APK 已更新安装，原正式版 1.0.16 及其数据保留。测试广播、TLS 服务器及专用测试信任记录已清理；没有发布或修改桌面仓库。

### 本地投屏及 Android 身份增量验收

- 全部 56 项 JVM 测试通过，新增本地 WSS、同公钥重签、变更公钥在 Upgrade 前阻断、撤销、显码 v2 身份绑定、Android 来源声明和八秒请求确认测试。`lintDebug` 为 0 错误、53 警告；`assembleDebug` 通过。
- Pixel 7 / Android 17 使用首页正式本地连接路径完成真实 NSD、已存 pin 的 TLS/WSS、错误配对码提示与重试、正确码加入、P2P 及系统整屏选择器。接收侧收到 1080×2400 视频与 live 系统音频轨道；没有向云端发送该测试码。
- 接收侧明确收到 android/mobile/nearhub-cast；分享前 displaySurface=unknown，四角校准后为 monitor，实际分享状态独立上报。RX 格式请求得到对应 requestId 的 accepted 后，才打开协商 10 点的 touch/mouse 输入流。点击、拖拽、双指缩放、鼠标左键与滚轮均在自有 InputProbeActivity 产生 Android MotionEvent。
- 按住期间旋转产生 ACTION_CANCEL；视频改为 2400×1080，新 sourceBindingId 与几何版本绑定后恢复输入。恢复竖屏后仍可重新绑定。关闭本机反控上报 LOCAL_INPUT_DISABLED / denied，视频继续；关闭 control 通道后 peer 仍 connected，已解码帧继续增加且音视频轨道保持 live。
- 退出后确认 MediaProjection 为 null、旋转模式恢复原来的 free、反控本机开关恢复开启。专用测试 pin、mDNS/TLS 夹具、浏览器接收进程及 adb reverse 已清理。
- 本轮联调为真实 Pixel 7 加独立浏览器／自有本地接收夹具；已逐字段核对 RX 源码，但未操作用户实际 RX 的现场指纹或实际房间，不把夹具验收记作真实 RX 硬件验收。
