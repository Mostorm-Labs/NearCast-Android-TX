# 应用名称与更新兼容

2026-11-18，Android 应用显示名由 NearCast 改为 **NearHub Cast**，调试版显示为 **NearHub Cast Debug**。首页、系统应用标签、通知、共享占位文案、无障碍服务名称与授权说明使用新名称。

Android 的 `android:label` 是用户可读名称，与安装身份不同。覆盖更新需要延续 application ID 和签名身份，后续发布的 versionCode 应递增。参考 [Android 应用标签](https://developer.android.com/guide/topics/manifest/application-element#label)、[application ID 与更新](https://developer.android.com/build/configure-app-module#set-application-id)、[版本管理](https://developer.android.com/studio/publish/versioning)。

| 项目 | 本次处理 | 对更新的意义 |
| --- | --- | --- |
| 显示名 | NearHub Cast；调试版追加 Debug | 改变用户看到的名称，不创建新的安装身份 |
| 正式包 application ID / namespace | 保留 `com.auditoryworks.nearcast` | 现有安装继续识别为同一应用 |
| 独立调试包 | 保留可选 `debugAppIdSuffix=.dev` | 调试包与正式包继续并存，不互相覆盖数据 |
| 签名配置与 CI 签名校验 | 保持原配置 | 后续更新仍由同一签名身份发布 |
| Activity、无障碍服务组件名 | 保留完整类名 | 不因改名迁移组件或授权对象 |
| FileProvider | 保留 `${applicationId}.fileprovider` | APK 安装 URI 与已有代码继续匹配 |
| SharedPreferences | 保留 `remote-control`、`permission-ui`、`receiver-trust-v1` | 反控开关、引导记录与接收端信任记录继续使用 |
| Mosapi | 保留 `nearcast-tx-android`、product ID 627、folder ID 546 | 老客户端与新客户端仍查询同一更新产品 |
| APK 文件前缀 | 保留 `NearCast-Android-TX-v…` | 现有 CI 定位、上传及下载资产流程继续匹配 |
| 协议身份 | 保留 `_nearcast._tcp.`、`nearcast-*` 消息和 `product=nearhub-cast` | 设备发现、已配对 RX 与反控协议不受品牌文案影响 |

`UpdateManager` 按固定产品 slug 查询，并比较 VERSION_NAME；不会按应用显示名搜索。改名本身不会创建可发现的新更新。下一次发布仍应使用比现有版本更高的版本名称，并由原 CI 产生更高的 versionCode；当前 CI 以 `100000 + GITHUB_RUN_NUMBER` 生成安装版本码。

本次不迁移包名、不变更签名或存储键、不调整更新比较规则与渠道。当前开发构建使用的 `.dev` 是独立安装包；正式更新包不会成为该调试包的原位升级。发布流程仍由版本标签或显式 workflow_dispatch 触发，普通分支推送不发布 APK。

应用更新时进程和无障碍服务可能短暂重连；系统设置与通知界面可在重载后刷新新标签。仅修改显示名不要求创建新的权限对象。发布验收应核对安装包名称、application ID、签名、版本码以及既有数据是否保留。

本轮验证：76 项 JVM 测试、lintDebug、Debug / Release / AndroidTest APK 构建通过。打包检查确认正式标签为 NearHub Cast、调试标签为 NearHub Cast Debug，正式清单不含调试测试入口。Pixel 7 / Android 17 对 `.dev` 包执行覆盖安装后，首次安装时间保留，接收端信任记录哈希一致，无障碍授权仍启用并连接，服务标签刷新为 NearHub Cast 接收端反控。该真机检查验证调试签名下的覆盖更新；正式升级继续依赖 CI 的原发布签名和递增版本码。
