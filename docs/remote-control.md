# Android 接收端反控

本机开关默认开启；实际输入仍要求用户开启 NearHub Cast 无障碍服务、服务已连接、手机已解锁、媒体会话有效、AXTP 握手完成、接收端目标有效，并通过整屏来源校准。Android API 24–25 保留投屏，实时反控要求 API 26。

## 模块与媒体边界

- `touchback/` 独立实现协议、输入状态、手势规划、来源校准和无障碍执行器；`RemoteControlState` 分别表达本机开关、系统授权、服务连接、校准、输入连接和清理状态。
- `WebRtcManager` 复用现有有序可靠 `control` DataChannel，分流二进制 AXTP 和 `nearcast-input-target/source`，收到二进制时先复制缓冲区。原有重新协商、leave、视频与系统音频链路保留。
- 原始视频帧先交给现有 WebRTC observer。校准只在有界时间内异步保留至多一帧读取 I420 亮度，不替换轨道、不重绘视频、不建立第二条采集链路。
- `RemoteInputService` 只声明执行手势能力，不读取无障碍节点内容。校准使用不可触摸、不可聚焦的 `TYPE_ACCESSIBILITY_OVERLAY`，无需普通悬浮窗权限。
- 首次分享、重新选择、旋转、内容可见性或系统授权变化均撤销旧绑定。随机四角标记全部匹配且连续两帧通过后，才绑定整屏坐标；两秒结束校准并移除标记。单应用、标记缺失、映射不确定均继续仅投屏。

## 协议锁定与输入范围

当前消费与 Desktop TX 相同的生成快照：

- AXTP：`0.18.0-dev.0082a3428b1e`
- contract fingerprint：`0082a3428b1e8038e74c2883ab4372cf2169c7b3c1c9407d41eeb70f87b324e8`
- 官方语料 SHA-256：`f4ec706d34353b2532f84f3cd9a228145671d7c9345258293a9bff0b6d637645`

`protocol/touchback-contract.lock.json` 锁定完整来源元数据；`AxtpContract.kt` 和测试语料由 `node scripts/sync-touchback-contract.mjs <匹配锁定快照的 Desktop-TX 目录>` 生成。该命令核对 source/runtime/Axent commit、版本、指纹、发布状态、协议和语料哈希，任一不匹配都在写入前退出。正常 Android 构建不依赖相邻桌面仓库。升级协议应显式审核并更新锁定文件，再生成常量，不能手改生成物。

2026-11-17 现场 RX 日志使用 `0082a342…`，旧 Android 使用 `feac6fa2…`，导致 `TARGET_CONTRACT_MISMATCH`。本次对比输入 wire 定义、官方语料、CONTROL/RPC/TLV/错误码及事件掩码后，通过生成器升级至 RX 对应快照；输入 wire 与语料未变，注册方法增加输入模式及反控总开关四项。旧指纹不会被作为兼容别名放行。锁定来源为 AXTP `5b855a27…`、runtime `ce4c9c99…`、Axent `6829dc79…`。

协议不兼容或目标描述无效会显示独立状态，保留媒体投屏。该诊断不会被后续定时刷新改写成普通等待；有效目标或新通道会清除旧失败。Logcat 的 Touchback 状态变化记录握手、校准、来源就绪、输入状态和失败原因，不记录配对码或输入内容。

实现 StandardFrame/CRC16、有界分片重组、CONTROL OPEN/ACCEPT、Hello/Identify/Identified、心跳和 RPC；STREAM 必须在 APP_READY 后进入。输入流按 sid、runtimeInstanceId、target、sourceBindingId 关联，只给当前来源创建 owner。输入流 ID 在同一 sid 内不复用，commandId 幂等；连续采样检查完整触点集合、序号、无符号 cursor 和已协商能力。

支持点击、长按、连续拖拽和最多 10 个触点。鼠标向系统执行无修饰键的左键与 `detent120` 滚轮：每 120 单位映射屏幕短边 10% 的有界滑动，时长 120ms。右键消息受控接收并过滤，以兼容 RX 手势模式；右键及悬停不向系统注入触摸。键盘、触笔、中键、组合键、连续滚轮、单应用反控不在本版范围。

队列有数量、字节、触点记录和年龄上限；连续移动可合并，相邻且相容的离散滚轮按累计增量合并，保留 down/up 等处理边界。停止、隐藏、锁屏、旋转、断连、关开关和撤销授权立即冻结旧输入；串行释放原 owner 的手势。超时只取消等待，实际系统操作继续受进程级 owner 保护。释放未获确认则保持 `cleanup_blocked`，禁止新 owner 接管，不以重连或新 controller 绕过清理。

输入协议故障撤销输入会话，媒体和共享 DataChannel 保留；接收端可用新的 CONTROL OPEN 恢复逻辑输入会话。

## RX 身份识别与请求确认

`nearcast-source` 与 `nearcast-input-source` 是两个独立声明。前者在已认证的 control 通道建立后发送 version、roomId、senderUserId、receiverUserId、十进制字符串 revision 及四字段 source：`platform=android`、`clientKind=mobile`、`product=nearhub-cast`、`displaySurface`。RX 已支持该 Android 原生分类，无需使用 Windows 身份。

未分享、隐藏或未通过可靠整屏校准时，displaySurface 为 unknown；可见且校准通过后才为 monitor。`nearcast-share-state` 独立公布实际共享状态。新来源或新通道增加 revision，身份声明不携带反控授权。输入声明的 geometryRevision 保持字符串，与 RX 校验一致。

RX 的 `nearcast-input-target.data.controlRequest` 包含 requestId、timeoutMs=8000、cancel。只有匹配当前 runtime/target 的通道能提交请求。默认开启时，在当前无障碍授权和输入绑定就绪后以 `controlConsent={requestId,status:accepted}` 响应；等待、关闭或超时分别表达 pending、denied、timeout。重复请求不能延长截止时间，取消及目标变更清除旧请求；输入绑定不变时仍刷新确认状态。关开关、撤权、旋转或来源变化继续执行原有冻结与清理。

### 从发送端定位远端 RX 的阻断环节

`sourceReady=true` 仅说明 Android 本地校准、授权与绑定通过；DataChannel 的 send=true 表示消息已进入有序可靠队列，不能代替 RX 应用层确认。观察流程依次为：AXTP APP_READY → 整屏校准 → 来源声明入队 → RX 门控状态 → controlRequest / controlConsent → input.openStream / RPC 响应 → STREAM 采样 → 无障碍手势执行。

来源声明入队后，Android 在同一已配对的 AXTP sid 中每两秒至多发起一次 `cast.getTouchbackState` 只读查询，参数严格为当前 target，不访问另一台电脑的控制端口，也不改变 RX 的反控总开关或逐路授权。返回 target 必须匹配当前绑定，结果投影为 receiverOptIn、controllerRequested、effectiveEnabled、lifecycleState、diagnosticReason；当前会话中的旧目标响应不能覆盖新状态。

诊断 RPC 有独立请求 ID、并发和字节预算、五秒超时及退役取消。查询不受支持、超时或返回错误不会终止输入或媒体；不支持时停止该会话的轮询。Logcat 中 `TX input-source queued`、`RX feedback`、`RX controlRequest parsed`、`RX request method=input.openStream` 分别定位声明、接收端门控、确认和开流阶段。Android 卡片显示来自 RX 的具体原因，避免把接收端逐路未授权误报成本机无障碍权限问题。

2026-11-17 19:27 的真实远端 RX 会话：Android 整屏校准通过并发出 `available=true`；RX 先返回 `RECEIVER_OPT_IN_REQUIRED`，用户在 RX 开启后返回 `receiverOptIn=true`、`controllerRequested=false`、`effectiveEnabled=false`、`lifecycleState=faulted`、`diagnosticReason=INPUT_MODE_UNSUPPORTED`，Android 尚未收到 `input.openStream`。RX 默认 `touch-to-mouse`（Gesture control），开流前要求鼠标左右键及离散滚轮；当时 Android 仅公布左键及离散滚轮，需切到 `native`（Native）。后续兼容策略见“右键过滤”，身份始终保持 Android。

### 触屏滑动后退出反控

同日 19:46，真实 RX 切到 Native 后，`input.openStream` 成功、输入状态进入 active。第一次触屏滑动时，仅一个样本完成系统提交，首个续接触摸被 `onCancelled` 取消，Android 因 `Native submission unconfirmed` 冻结并关闭输入，随后 RX 收到来源撤销并退出反控。因此 RX 后续显示的 `INPUT_SOURCE_CHANGED` 是本机输入失败后的结果。

Pixel 7 自有测试页复现：按下后提交同坐标 Move，第二个手势立即被取消。Android [MotionEventInjector 实现](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/MotionEventInjector.java) 在续接没有产生任何 MotionEvent 时返回失败。`GesturePlanner` 现对整组触点检查：所有笔画均为已有触点、没有结束、屏幕像素位置均不变时，保留已按下的系统笔画，不重复 dispatch；实际移动、Down、Up、Cancel 及增减手指仍提交完整触点快照。该处理也覆盖量化到同一像素的细微移动和鼠标左键静止移动。

两项回归测试先失败后通过，覆盖静止 Touch/Mouse、多指静止及手指增减；本次完整 JVM 测试为 66 项。真机自有 ScrollView 测试包含 12 次静止 Move、连续移动及抬起，修复后记录 Down=1、Up=1、Cancel=0，实际滚动 480 像素。2026-11-18，用户确认真实远端 RX 的 Native 原生操控已经可用。

RX 的“手势操控”是 `touch-to-mouse`：接收端把触摸手势转换成鼠标点击、右键、滚轮和拖拽。Native 模式直接传递 Touch，支持本版的滑动和多指操作；两种模式都保留原有无障碍授权和整屏校准要求。

输入失败日志保留最后八个样本的序号、cursor、类型和阶段计数及提交/合并计数，不记录坐标或画面内容；系统取消与拒绝分别记录。可直接运行 debug 专用自有列表测试，无需启动 instrumentation 或重新授予无障碍：

```powershell
adb shell am start -W -n com.auditoryworks.nearcast.dev/com.auditoryworks.nearcast.touchback.InputProbeActivity --activity-no-animation --ez stationaryRegression true
adb logcat -d -s NearCastNativeRegression:I '*:S'
```

测试页等待服务、窗口布局与焦点后才注入；不能获取独立 native owner 时直接失败，不接管正在使用的输入会话。页面及命令入口只存在于 debug APK。

### 右键过滤

2026-11-18，按用户要求增加 `AndroidMousePolicy`：Mouse `buttonsMask=3` 表示接收左、右键状态，系统执行掩码仍为左键 `1`。RX 可协商只含 Mouse 的手势模式输入流；右键按下、移动和释放不产生点击、长按、系统返回或其他系统动作。修饰键、组合按钮及连续滚轮仍未启用。

原始样本先验证序号、cursor、类型、协商掩码及按钮生命周期，再仅在系统手势规划层过滤右键。被过滤的消息仍推进流的最后序号，避免下一条左键或滚轮被误判为序号断档。右键不占用原生触控域，其 Mouse Cancel 也不会释放正在执行的 Native Touch；左键已有的释放边界和真正移动保持原样。右键按住时，独立滚轮仍按现有有界滑动执行。

回归测试先失败后通过，本次完整 72 项 JVM 测试、lintDebug、assembleDebug 通过。Pixel 7 自有测试页验证 Mouse-only 协商、前后两轮右键过滤、左键拖动及滚轮：右键触摸事件为 0，实际 Down=2、Up=2、Cancel=0，左键拖动滚动 415 像素、滚轮再滚动 110 像素，连续消费至 seq=21，输入流保持 active。随后真实 RX 的 Gesture control 已成功开流；其连续滚动排队问题与修复记录见下一节。

```powershell
adb shell am start -W -n com.auditoryworks.nearcast.dev/com.auditoryworks.nearcast.touchback.InputProbeActivity --activity-no-animation --ez mouseFilterRegression true
adb logcat -d -s NearCastNativeRegression:I '*:S'
```

此策略只改变 Android 的消息接收和系统执行策略，不修改 AXTP framing、指纹、官方语料或 RX 代码。界面说明鼠标右键会被忽略；接收端长按或两指轻点若被转换成右键，也将没有系统动作，Native Touch 的长按与多指仍正常执行。

### 手势滚轮突发与排队

2026-11-18 10:36，真实 RX 的手势模式已经成功开流，双方进入 active；随后 RX 约每 6–36ms 发送一次 Mouse Wheel，而 Android 对每条消息串行执行 120ms 滑动，完成约三个样本后因 `Input queue age` 关闭输入。无障碍与整屏校准均正常，媒体继续；此处退出源于排队积压。

`InputEngine` 现在对当前代次内相邻、同 stream、同锚点、同按钮及修饰键、同方向的 `detent120` 滚轮累加 X/Y 增量。原始样本仍逐条校验，系统执行使用累计增量和最新序号/cursor；不跨触控、按键、方向反转、锚点变化等边界合并。先用 Long 求和，超出 Int32 范围时保留独立消息，避免溢出反向。原有 120ms 执行时长、每 120 单位对应短边 10% 的换算和路径边界保持不变。

合并保留最早的排队时间，250ms 过期限制没有放宽；真正陈旧的输入仍被停止。`coalescedWheelSamples` 单独统计合并消息，`stream.getState` 的本地诊断记录最后已提交序号、系统提交数及合并数。被合并的独立 Deferred 返回 false，其增量保留在后续批次，最新批次的 Deferred 跟踪系统执行；这些本地结果不是线上逐样本 ACK。

四项回归覆盖突发累加及流持续有效、处理边界、最早截止时间和 Int32 溢出；完整 76 项 JVM 测试、lintDebug、assembleDebug 通过。Pixel 7 自有测试页以 6–30ms 间隔发送 40 个滚轮包，合并 29 次，累计处理至 seq=61，右键触摸事件 0、Down=13、Up=13、Cancel=0，突发滚动 437 像素，流保持 active。

2026-11-18 10:55 真实 RX 复测中，用户反馈“滚动正常，反控保持开启”。RX 持续查询的输入状态为 active，已提交序号到 136，滚轮合并计数到 80，未再出现 `Input queue age`。10:55:40 RX 主动发送 `input.closeStream`，Android 以 error=null 正常释放；该次收尾由接收端请求发起，没有触发本机超时。

```powershell
adb shell am start -W -n com.auditoryworks.nearcast.dev/com.auditoryworks.nearcast.touchback.InputProbeActivity --activity-no-animation --ez wheelBurstRegression true
adb logcat -d -s NearCastNativeRegression:I '*:S'
adb shell am start -W --activity-clear-top --activity-single-top -n com.auditoryworks.nearcast.dev/com.auditoryworks.nearcast.MainActivity
```

最后一条命令清除上方测试页面并返回已有主界面，保留无障碍服务与应用数据。返回后核实前台为 MainActivity；仅把应用任务调到前台可能仍显示测试页。

## 授权体验

首页与会话页复用“接收端反控”卡片与中英文文案。用户始终可以关闭反控或继续仅投屏。

1. 同一媒体会话首次需要授权时解释点击、滑动、多指能力，提供“去开启无障碍”及“继续仅投屏”。跳过不修改默认开关，也不重复弹出引导。
2. 通过公开 `ACTION_ACCESSIBILITY_SETTINGS` 打开系统页面，说明寻找“NearHub Cast 接收端反控”。没有匹配设置页时显示手动路径。
3. 返回后同时检查系统启用状态和实际服务连接，最多短暂等待绑定。只有原会话及原投屏请求仍有效时才继续；未授权时保留操作入口，不自动循环拉起设置。
4. “无法开启？”提供应用详情入口，并按系统实际提供的选项说明“允许受限设置”，随后回到无障碍设置。[Android 官方说明](https://support.google.com/android/answer/12623953)
5. 校准失败、等待接收端、系统输入不可用、输入故障和清理阻塞分别显示状态，并提供校准或释放重试。运行中撤销授权只停止输入。

通知权限为尽力请求，拒绝后仍继续投屏且不反复强制请求。音频权限先解释现有系统播放声音用途，拒绝后提供重试，永久拒绝提供应用详情入口。每次新建采集均使用系统屏幕分享选择器；取消不会自动再次启动采集。

## 构建与设备验证

使用 JDK 17 和 Android SDK，只为当前构建进程设置 `JAVA_HOME`、`ANDROID_HOME` / `ANDROID_SDK_ROOT`。可用以下任务验证：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest '-PdebugAppIdSuffix=.dev'
git diff --check
```

`debugAppIdSuffix` 可选；`.dev` 调试包与正式包并存。安装前比较签名，签名不兼容时不能卸载正式包以覆盖数据。`debug/` 的设备测试页面不进入 release，正式启动页及信令地址不受测试环境影响。

JVM 测试覆盖独立官方 25 个有效帧、13 个无效场景、握手与禁止普通样本 ACK、完整采样、目标绑定、能力协商、幂等、队列合并、迟到的系统操作、清理阻塞及授权返回时序；同时运行现有投屏状态和占位画面测试。

2026-11-17，Pixel 7 / Android 17 验证记录：

| 场景 | 结果 |
| --- | --- |
| 首次授权说明、用户在系统设置开启并返回 | 服务实际连接，首页状态正确 |
| 整屏分享 | 两帧四角校准通过，1080×2400 视频与输入流有效 |
| 点击、拖拽、双指缩放、鼠标左键及滚轮 | 浏览器独立编码 AXTP，经真实 DataChannel 注入；自有测试窗口收到系统触摸事件 |
| 长按、拖拽、双指按下与抬起 | `NativeInputDeviceTest` 通过，长按至少 650ms，触点生命周期完整 |
| 按住时旋转 | 旧触点释放、旧流关闭；2400×1080 重新校准并建立新流 |
| 按住时停止或关闭本机反控 | 触点释放；停止仅结束采集，本机关闭仅结束输入 |
| 按住时锁屏 | 触点抬起，输入关闭；平台随后停止采集，进入原有重新选择状态 |
| 撤销无障碍授权、恢复授权 | 输入关闭且媒体继续；恢复后新绑定及点击有效 |
| 单应用分享、隐藏、返回 | 校准失败保持仅投屏；隐藏切占位画面，返回恢复视频 |
| 隐藏系统栏的全屏单应用分享 | 独立测试 APK，解码 1080×2400，与整屏相同；两次校准均失败，输入流未开放，媒体继续 |
| 损坏 CRC 且有触点按住 | 收到抬起事件，输入会话关闭；同一 PeerConnection、DataChannel 和媒体继续 |
| 协议故障后重新握手 | 新 sid、新来源绑定，真实输入恢复 |
| 按住时关闭控制通道 | 收到抬起事件，输入关闭，视频继续传输 |

本轮 28 项 JVM 测试、1 项真实手势设备测试通过；`lintDebug` 无错误（43 项警告，含现有及调试诊断警告），debug 与 androidTest APK 均可构建。调试包独立安装，原正式版及其数据保留。本轮没有发布。

WebRTC 回归使用仓库中的独立浏览器测试接收端，并非 NearHub 硬件接收端：验证 H.264 解码、Opus 接收字节持续增加、轨道不重复，以及输入错误时媒体继续；未播放测试音，因此不将音频包计数等同于声音听感验收。API 24/25 的仅投屏兼容由代码门控与 lint 检查，未在这些旧系统真机运行。

权限跳过、未授权返回、原会话失效与避免重复启动由状态测试覆盖；本轮实际操作了授权开启、返回、运行中撤销及恢复，未逐项实机触发所有厂商设置缺失、永久拒绝与设备管理策略分支。受限设置帮助依照官方路径，不绕过系统授权。

## 重现设备测试

`scripts/device-peer.mjs` 使用外部安装的 `playwright@1.56.1`、`ws@8.18.3` 和 Edge，只监听电脑回环地址；通过 `TOUCHBACK_TEST_NODE_MODULES` 指向依赖目录。不要将这些工具加入正式应用依赖。

```powershell
node scripts/device-peer.mjs
adb reverse tcp:18888 tcp:18888
adb shell am start -n com.auditoryworks.nearcast.dev/com.auditoryworks.nearcast.touchback.CaptureProbeActivity
```

在自有设备测试页面选择 Share，使用系统选择器授权。`http://127.0.0.1:18889/state` 返回协议和媒体状态；POST `/command` 支持 `tap`、`drag`、`pinch`、`mouse`、`hold`、`badCrc`、`resetInput`、`disconnect`。只在自有测试窗口前台时发送手势。GET `/evidence` 保存到被 Git 忽略的 `build/device-evidence/peer.json`。

全屏单应用负例使用 `androidTest` APK 的 `FullscreenAppProbeActivity`，以独立包提供隐藏系统栏的自有窗口。在启动系统分享选择器前先打开该页面，然后在选择器中选其最近任务；发送端本身的最近任务会被系统排除。

```powershell
adb shell am instrument -w -e class com.auditoryworks.nearcast.touchback.NativeInputDeviceTest com.auditoryworks.nearcast.dev.test/androidx.test.runner.AndroidJUnitRunner
```

设备测试使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`。Pixel 的 Android 17 在 instrumentation 强停被测进程后会将其服务列为 crashed；若遇此状态，在测试等待期通过公开设置将已经授权的服务关闭再开启，并返回测试窗口。测试等待服务连接及自有窗口焦点后才注入，测试结束后也应检查服务是否需要恢复。[UiAutomation 文档](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
