# Android QA 设备矩阵

验收记录日期：2026-09-04。代码基线：`160b42c`；Task 13 最终提交：本次提交（`完成安卓患者闭环验收`）。

## 本环境已实测

| 环境 | 设备标识 | 自动化范围 | 结论 |
| --- | --- | --- | --- |
| Android 10 / API 29 模拟器 | `emulator-5556` | Task 13 患者闭环及关键全链定向 70/70，0 skipped | 已实测；不是国产真机 |
| Android 14 / API 34 模拟器 | `emulator-5554` | Task 13 患者闭环及 connected 全量 205/205，0 skipped | 已实测；不是国产真机 |

模拟器证据只覆盖应用/domain、数据库、加密媒体、MockWebServer 和确定性上传边界，不替代摄像头、耳机、来电、厂商后台策略或无 GMS 真机验收。

API 34 全量命令：

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew --offline --dependency-verification strict \
  :app:connectedDebugAndroidTest
```

API 29 的 70 项关键全链命令：

```bash
ANDROID_SERIAL=emulator-5556 ./gradlew --offline --dependency-verification strict \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vocaease.patient.e2e.PatientClosedLoopTest,com.vocaease.patient.ProductionVaultInvalidationTest,com.vocaease.patient.feature.auth.AuthFlowTest,com.vocaease.patient.feature.catalog.CatalogScreenTest,com.vocaease.patient.feature.training.RecordingSystemChromeTest,com.vocaease.patient.feature.training.ReviewScreenTest,com.vocaease.patient.feature.upload.PendingUploadsScreenTest,com.vocaease.patient.feature.history.HistoryAndResultTest,com.vocaease.patient.ui.AppNavigationTest,com.vocaease.patient.core.database.PendingUploadCounterIntegrationTest,com.vocaease.patient.core.media.Mp4AudioTrackExtractorTest,com.vocaease.patient.feature.upload.PlaintextUploadLeaseTest,com.vocaease.patient.feature.upload.UploadCoordinatorAccountTest,com.vocaease.patient.feature.upload.UploadWorkAndroidTest,com.vocaease.patient.core.database.DatabaseConstraintTest
```

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

视觉基准为 390×844，另测常见宽度、字体 1.3×、键盘遮挡、TalkBack 描述和 48dp 触控区。Pencil VS Code MCP 本轮为 `transport closed`，登录、首页、准备、录制、我的、回看、回顾的只读逐页复核待工具恢复；不凭记忆修改 UI，也不新增设置页画板。前序已保留的 f004p/f005s 与 390×844 证据不等同于本轮复核。
