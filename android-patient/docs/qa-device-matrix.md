# Android QA 设备矩阵

验收记录日期：2026-09-05。代码基线：`8c8a1b7`；本轮在该基线上补充 Pencil 复核与 Android 17/API37 兼容性修复。

## 本环境已实测

| 环境 | 设备标识 | 自动化范围 | 结论 |
| --- | --- | --- | --- |
| Android 10 / API 29 模拟器 | `emulator-5556` | connected 全量 206/206，0 skipped；覆盖三键导航真实可视区 | 已实测；不是国产真机 |
| Android 14 / API 34 模拟器 | `emulator-5554` | connected 全量 206/206，0 skipped | 已实测；不是国产真机 |
| Android 17 / API 37 模拟器 | `emulator-5558` / `VocaEase_API_37` | connected 全量 206/206，0 skipped；覆盖强制 edge-to-edge 录制页系统栏 | 已实测系统兼容；Google APIs 镜像，不作为无 GMS 证据 |

模拟器证据只覆盖应用/domain、数据库、加密媒体、MockWebServer 和确定性上传边界，不替代摄像头、耳机、来电、厂商后台策略或无 GMS 真机验收。

API 34 全量命令：

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew --offline --dependency-verification strict \
  :app:connectedDebugAndroidTest
```

API 37 全量命令与 API 34 相同，仅将 `ANDROID_SERIAL` 改为 `emulator-5558`。首次运行 203/205，稳定复现两项 Android 17 edge-to-edge 旧假设；新增真实导航壳回归并修复录制页透明系统栏背景后，最终 206/206。

API 29 的 71 项关键全链命令：

```bash
ANDROID_SERIAL=emulator-5556 ./gradlew --offline --dependency-verification strict \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vocaease.patient.e2e.PatientClosedLoopTest,com.vocaease.patient.ProductionVaultInvalidationTest,com.vocaease.patient.feature.auth.AuthFlowTest,com.vocaease.patient.feature.catalog.CatalogScreenTest,com.vocaease.patient.feature.training.RecordingEdgeToEdgeTest,com.vocaease.patient.feature.training.RecordingSystemChromeTest,com.vocaease.patient.feature.training.ReviewScreenTest,com.vocaease.patient.feature.upload.PendingUploadsScreenTest,com.vocaease.patient.feature.history.HistoryAndResultTest,com.vocaease.patient.ui.AppNavigationTest,com.vocaease.patient.core.database.PendingUploadCounterIntegrationTest,com.vocaease.patient.core.media.Mp4AudioTrackExtractorTest,com.vocaease.patient.feature.upload.PlaintextUploadLeaseTest,com.vocaease.patient.feature.upload.UploadCoordinatorAccountTest,com.vocaease.patient.feature.upload.UploadWorkAndroidTest,com.vocaease.patient.core.database.DatabaseConstraintTest
```

API 29 在 390×844/160dpi 与三键导航下另跑 connected 全量，最终 206/206 通过。录制页同时保留 390×792 的 Pencil 组件基准测试，并通过真实 `AuthenticatedApp`/`Scaffold` 验证自适应后的关闭与结束录制控件仍完整位于可视区；不会再以固定高度掩盖系统导航栏占用。

Gradle HTML 证据入口为 `android-patient/app/build/reports/androidTests/connected/debug/index.html`，JUnit XML 为 `android-patient/app/build/outputs/androidTest-results/connected/debug/TEST-<设备>-_app-.xml`。Gradle 每次 connected 运行会覆盖该目录，因此归档时必须同时保存命令、设备 API、XML 和 HTML，不得用后一次单例运行冒充上述计数。

## 外部待验收

下表所有空白项都必须在真实设备填写日期、系统版本、构建号、结果和缺陷号。当前环境没有这些设备，均为“待验收”，不得据模拟器结果改为通过。

| 厂商/系统 | Android 10/API29 | Android 14/API34 | Android 17/API37 | 无 GMS 全闭环 | 有线耳机 | 蓝牙耳机 | 扬声器串音提示 | 前摄方向 | 来电 | 切后台 | 锁屏 | 低存储 | 重启恢复 | 后台限制 | 通知拒绝 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 小米 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 |
| 荣耀 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 |
| OPPO | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 |
| vivo | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 |
| 华为兼容 Android APK 机型 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 | 待验收 |

视觉基准为 390×844，另测常见宽度、字体 1.3×、键盘遮挡、TalkBack 描述和 48dp 触控区。Pencil VS Code MCP 已在本轮恢复，并只读渲染/复核 `pUYpg`（01A 治疗进度首页）、`f0021`（演唱准备）、`f0036`（演唱）、`f004p`（我的）、`f005s`（演唱回顾）；没有修改 `.pen`。本地重新生成登录、首页、准备、录制、本地回看、我的、演唱回顾七张 390×844 截图，保存在忽略的 `android-patient/build/visual-qa/`，逐页确认无裁剪、溢出或层级断裂：首页仅显示治疗进度/目标次数，主导航仅“去唱歌/我的”，录制页不伪造音准与歌词，结果页仅 `is_mock=true` 展示“演示结果”。登录和本地回看在 PEN 中没有独立画板，按现有同一设计系统验收；没有新增画板。

## 2026-10-06 演唱体验回归

- API29 arm64 模拟器：真实 AudioRecord/AAC、单视频/患者AAC合并、Room10→11、加密元数据与账户撤销、390×844音高/录制界面、320×568引导与停止控件已运行；闭环状态机（固定媒体/网络夹具）通过，共11项；实际人类演唱不是此状态机夹具的证据。
- API34只读Pixel_7模拟器：390×844、字体1.3×，同11项全部通过；未保存对原虚拟设备的数据修改。API37与目标国产真机未运行。
- 目标真机必须另测：48k→44.1k回退、白点采样至显示≤150ms、10秒/3分钟/完整歌曲首尾音视频及伴奏偏差≤100ms、录制中切换不重启麦克风、退出账户时合并/上传不跨账户。
- 用标定目标核对预览、安卓回看与医生录像共用取景/镜像；模拟器界面测试不能替代这个物理摄像头证据。
- 新元数据以账户AES-GCM文件加密，仅Room保存相对路径/版本；重试提交复用同一草稿元数据，不能重新推算播放时间。

2026-10-06评审修复后：API34只读模拟器390×844/字体1.3×，最终12/12（含CameraXFrameTimeTest真实首帧PTS与250ms事件延迟）；343单元测试、严格依赖校验及release隐私验证通过。API29此前11/11不代表最新首帧适配已在API29重验。整曲、国产真机和物理取景仍待。
