# 视频画质与发送参数

采集保持所选内容的实际尺寸；整屏初始尺寸来自默认屏幕的 real metrics，旋转和 Android 捕获内容尺寸回调沿用现有原地 resize 流程。反控校准只读取现有帧，不缩放、重绘或替换视频轨道。

2026-11-18 的 Pixel 7 现场会话中，物理屏幕、WebRTC source 和 outbound 均为 1080×2400，H.264 使用 `c2.exynos.h264.encoder`，编码目标码率持续为 2,500,000 bit/s，`qualityLimitationReason=none`。原 Android 实现没有设置视频 sender 的编码预算；该值与 [WebRTC 的默认视频码率上限](https://webrtc.googlesource.com/src/+/22dfd42c3a74d3ad240cd95cfc1d289625e3116f/video/config/encoder_stream_factory.cc)一致。现场证据未显示低分辨率采样，低编码预算与 RX 渲染缩放的影响需要分别核对。

`ScreenVideoQuality` 为已存在的视频 encodings 配置：

| 参数 | 当前值 | 作用 |
| --- | --- | --- |
| 最大视频码率 | 8,000,000 bit/s | 与当前 Desktop TX 默认的视频码率上限对齐，实际发送由 WebRTC 自适应 |
| 最低视频码率 | 不指定 | 拥塞控制仍可降低实际发送码率 |
| 最大帧率 | 30 fps | 与现有采集帧率一致 |
| 缩放因子 | 1.0 | 保持原生尺寸 |
| 降级偏好 | MAINTAIN_RESOLUTION | 优先保留文字所需分辨率，必要时降低帧率 |

这里设置发送上限，不强制最低码率；WebRTC 仍可根据网络状态降低发送码率。上限值不代表实际吞吐，实际发送量需通过统计中的累计字节差核对。

策略在媒体 sender 附加后，以及远端 answer 或本地 answer 成功设置后应用。没有 encodings 时等待协商产生真实参数；保留 transaction ID、RID、SSRC、各层的 active 状态和其他协商结果，不虚构新编码层。异步回调验证 PeerConnection 和 sender 仍属于当前会话；配置被拒绝只记录诊断，媒体与反控继续。

`Video stats` 记录采集/发送尺寸、帧率、累计帧数和字节数、编码目标码率、质量限制原因、QP 累计值、codec、编码器、选定网络路径的可用发送带宽和 RTT。统计中的 `bitrate` 是编码目标，累计 `bytes` 的时间差才反映实际吞吐；缺失字段显示 `?`，不推断为零。新增统计不记录候选地址或凭据。

三项 JVM 回归覆盖：原生尺寸与预算、保留协商身份/禁用层、空 encodings 延迟处理。本次完整 69 项测试、lintDebug、assembleDebug 通过；真实 RX 的画质对比需与应用参数日志共同确认，不能仅凭 API 返回成功宣称画面已经变清晰。

2026-11-18 重新安装并连接真实 RX 后，现场发送统计确认 1080×2400、H.264 硬件编码、目标码率 12,000,000 bit/s、`qualityLimitationReason=none`。该记录确认新编码预算实际生效；接收端视觉清晰度仍需与其渲染尺寸共同评估。

2026-11-19 默认视频码率上限由 12 Mbps 调整为 8 Mbps。继续使用 30 fps、原生采集尺寸、无强制最低码率及 MAINTAIN_RESOLUTION；上述 12 Mbps 现场记录保留为调整前的历史证据。
