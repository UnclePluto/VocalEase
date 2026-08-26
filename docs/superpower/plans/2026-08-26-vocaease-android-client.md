# VocaEase 原生 Android 患者客户端 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox tracking and each task ends with a Chinese Git commit.

**Goal:** 交付一个 Android 10+、竖屏、无 GMS 运行依赖的患者客户端，完整实现登录、治疗进度、选歌、前摄演唱、本地回看、加密草稿、七牛断点续传、分析状态和历史回顾闭环。

**Architecture:** 在单一 `app` Gradle 模块内按 `feature.*` 与 `core.*` 分包。Compose 页面只消费不可变 UI state；ViewModel 通过 repository 协调 Retrofit、Room、Media3、CameraX、Android Keystore 和 WorkManager。训练和上传分别使用显式状态机；本地草稿以账户作用域隔离，网络重试始终复用持久化幂等键。

**Tech Stack:** Kotlin 2.3.21、AGP 9.2.1、Gradle 9.4.1、JDK 17、Jetpack Compose BOM 2026.08.00、CameraX 1.6.1、Media3 1.11.0、Room 2.8.4、WorkManager 2.11.2、Navigation 2.9.8、Retrofit 3.0.0、OkHttp 4.12.0、kotlinx.serialization 1.11.0、kotlinx.coroutines 1.11.0、七牛 Android SDK 8.9.0。

**Spec:** `docs/superpower/specs/2026-08-26-vocaease-android-client-design.md`

## Global Constraints

- 本计划依赖 `2026-08-26-vocaease-patient-api-adaptation.md` 全部完成并部署到联调环境；Android DTO 以其生成的 OpenAPI 为准。
- 实施目录为 `/Users/nick/my_dev/workout/VocaEase/.worktrees/vocaease-rebuild`，分支为 `codex/vocaease-rebuild`；新工程根目录固定为 `android-patient/`。
- `namespace` 和 `applicationId` 固定为 `com.vocaease.patient`；`minSdk=29`、`compileSdk=37`、`targetSdk=36`，JVM toolchain 17，仅支持 portrait phone。
- 运行时网络只允许 VocaEase API、API 返回的私有媒体 URL 和七牛上传地址；不得加入 Firebase、FCM、Play Services、Google 登录、ML Kit、Play Integrity、Play Billing 或 Google 在线接口。
- AndroidX 库可用，因为它们打包在 APK 内，不要求设备安装 Google Play 服务。依赖必须锁版本、启用 Gradle dependency verification，并由国内 Maven 代理或内部缓存提供。
- 不使用 Hilt；用 `AppContainer` 手工装配依赖，避免额外代码生成和不透明运行时边界。
- 新会话只可在线创建；创建成功后允许离线完成录制、回看和草稿保存。
- CameraX 是唯一麦克风采集者；不得同时启动 `AudioRecord` 或第二个 recorder。独立音频在停止后从 MP4 音轨无损抽取。
- 当前分析协议没有歌词文本和音准/节奏/稳定度三个独立分数：准备页显示“歌词暂未提供”的明确空态；回顾页只展示服务端真实字段和音准曲线，缺少分项时显示“暂无单项评分”，不得从总分或随机数推导。
- `is_mock=true` 只显示“演示结果”；`is_mock=false` 不显示标识；不得显示“非临床结论”。
- 所有患者可见文案为简体中文；日志不得包含密码、JWT、refresh token、上传 token、完整私有 URL、手机号、备注或明文媒体路径。
- 每个 Git 提交描述使用中文。

## Fixed Dependency Set

版本依据固定在计划日期，实施时不得自动替换为动态版本：

- Android Gradle Plugin 9.2.1 / Gradle 9.4.1 / JDK 17：[AGP 9.2 compatibility](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
- Compose BOM 2026.08.00：[Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- CameraX 1.6.1、WorkManager 2.11.2、Media3 1.11.0、Room 2.8.4：[CameraX](https://developer.android.com/jetpack/androidx/releases/camera)、[WorkManager](https://developer.android.com/jetpack/androidx/releases/work)、[Media3](https://developer.android.com/jetpack/androidx/releases/media3)、[Room](https://developer.android.com/jetpack/androidx/releases/room)
- 七牛 Android SDK 8.9.0，使用 V2 配置和本地 recorder：[七牛 Android SDK](https://developer.qiniu.com/kodo/1236/android)

---

### Task 1: 建立可复现且无 GMS 的 Android 工程

**Files:**

- Create: `android-patient/settings.gradle.kts`
- Create: `android-patient/build.gradle.kts`
- Create: `android-patient/gradle.properties`
- Create: `android-patient/gradle/libs.versions.toml`
- Create: `android-patient/gradle/wrapper/gradle-wrapper.properties`
- Create: `android-patient/gradlew`
- Create: `android-patient/gradlew.bat`
- Create: `android-patient/gradle/wrapper/gradle-wrapper.jar`
- Create: `android-patient/app/build.gradle.kts`
- Create: `android-patient/app/proguard-rules.pro`
- Create: `android-patient/app/src/main/AndroidManifest.xml`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/VocaEaseApplication.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/MainActivity.kt`
- Create: `android-patient/scripts/check_no_gms.sh`
- Create: `android-patient/app/src/test/java/com/vocaease/patient/BuildContractTest.kt`

**Consumes:** 固定依赖版本、国内 Maven 镜像、Android SDK 37、JDK 17。

**Produces:** 可执行 `assembleDebug`、单模块 Compose 空壳、依赖校验元数据和无 GMS 审计报告。

- [ ] **Step 1: 生成 Gradle wrapper 并固定校验值**

在 `android-patient/` 运行：

```bash
gradle wrapper --gradle-version 9.4.1 --distribution-type bin
```

将 wrapper URL 改为国内镜像：

```properties
distributionUrl=https\://mirrors.huaweicloud.com/gradle/gradle-9.4.1-bin.zip
distributionSha256Sum=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
```

- [ ] **Step 2: 写构建契约失败测试**

`BuildContractTest` 读取 `BuildConfig` 并断言 min API 设计常量和 base URL 非空；先不创建 `BuildConfig.MIN_SUPPORTED_API`，确认测试失败。

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest --tests '*BuildContractTest'`

Expected: FAIL，缺少构建常量或应用类。

- [ ] **Step 3: 配置版本目录和单 app 模块**

`libs.versions.toml` 固定以下版本，不使用 `+`：

```toml
[versions]
agp = "9.2.1"
kotlin = "2.3.21"
ksp = "2.3.9"
composeBom = "2026.08.00"
activity = "1.13.0"
core = "1.19.0"
lifecycle = "2.11.0"
navigation = "2.9.8"
room = "2.8.4"
datastore = "1.2.1"
work = "2.11.2"
camera = "1.6.1"
media3 = "1.11.0"
retrofit = "3.0.0"
okhttp = "4.12.0"
serialization = "1.11.0"
coroutines = "1.11.0"
qiniu = "8.9.0"
junit4 = "4.13.2"
androidxTestJunit = "1.3.0"
espresso = "3.7.0"
```

声明 Compose、Material3、activity-compose、lifecycle-viewmodel-compose、navigation-compose、Room runtime/ktx/compiler、DataStore、WorkManager、CameraX camera2/lifecycle/video/view、Media3 exoplayer/ui、Retrofit、serialization converter、OkHttp、Qiniu 和测试依赖。明确 pin OkHttp 4.12.0，与 Retrofit 3 和七牛 8.9.0 的 4.x 依赖线保持一致。

- [ ] **Step 4: 配置国内仓库与 release 参数校验**

`settings.gradle.kts` 默认只使用阿里云 `google`、`public`、`gradle-plugin` 镜像；仅当 `-PallowOfficialRepositories=true` 时追加 `google()`、`mavenCentral()`、`gradlePluginPortal()`。debug 默认 API 为 `http://10.0.2.2:8000/`；release 必须通过 Gradle property `vocaeaseApiBaseUrl` 提供完整 HTTPS URL 并校验 scheme、host 和尾部 `/`，否则 `assembleRelease` 失败。项目脚本从任务专用环境变量 `VOCAEASE_API_BASE_URL` 读取值并转交该 property。

- [ ] **Step 5: 增加清单最小权限和无 GMS 审计**

Manifest 只声明 `INTERNET`、`ACCESS_NETWORK_STATE`、`CAMERA`、`RECORD_AUDIO`、`WAKE_LOCK`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`；activity 固定 portrait。`check_no_gms.sh` 生成 release runtime dependency 报告并对以下坐标失败：

```bash
rg -i 'com\.google\.android\.gms|com\.google\.firebase|play-services|firebase-' \
  app/build/reports/release-runtime-dependencies.txt
```

- [ ] **Step 6: 启用依赖校验并通过构建**

Run: `cd android-patient && ./gradlew --write-verification-metadata sha256 help`

Expected: 创建 `gradle/verification-metadata.xml`，每个外部 artifact 都有 SHA-256。

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest :app:assembleDebug && VOCAEASE_API_BASE_URL=https://127.0.0.1/ ./scripts/check_no_gms.sh`

Expected: PASS，审计报告无禁止坐标。

- [ ] **Step 7: 提交**

```bash
git add android-patient
git commit -m "初始化无GMS安卓患者工程"
```

---

### Task 2: 实现设计令牌、应用容器和双标签导航壳

**Files:**

- Create: `android-patient/app/src/main/java/com/vocaease/patient/AppContainer.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/ui/VocaEaseApp.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/ui/AppRoute.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/ui/theme/Color.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/ui/theme/Type.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/ui/theme/Shape.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/ui/theme/Theme.kt`
- Create: `android-patient/app/src/main/res/font/noto_sans_sc.ttf`
- Create: `android-patient/THIRD_PARTY_NOTICES.md`
- Create: `android-patient/app/src/androidTest/java/com/vocaease/patient/ui/AppNavigationTest.kt`

**Consumes:** PEN 设计变量和两标签信息架构。

**Produces:** 手工 DI 容器、全局主题、只含“去唱歌/我的”的主导航壳；详情流隐藏底栏。

- [ ] **Step 1: 写 Compose 导航失败测试**

```kotlin
composeRule.setContent { VocaEaseApp(fakeContainer, initialRoute = AppRoute.Catalog) }
composeRule.onNodeWithText("去唱歌").assertExists()
composeRule.onNodeWithText("我的").assertExists()
composeRule.onAllNodes(hasText("首页") or hasText("曲库") or hasText("历史"))
    .assertCountEquals(0)
composeRule.onNodeWithTag("song-card-1").performClick()
composeRule.onNodeWithText("去唱歌").assertDoesNotExist()
```

- [ ] **Step 2: 运行并确认失败**

Run: `cd android-patient && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vocaease.patient.ui.AppNavigationTest`

Expected: FAIL，导航壳尚不存在。

- [ ] **Step 3: 实现 design tokens**

颜色必须逐字匹配：`#28C985`、`#12885A`、`#0B3325`、`#F6FAF7`、`#EAF4EE`、`#FFFFFF`、`#17211D`、`#7A8780`、`#DEE8E2`、`#F0545E`。禁用动态取色。把 [Noto Sans CJK 官方仓库](https://github.com/notofonts/noto-cjk) 的 Simplified Chinese variable TTF 固定为 `noto_sans_sc.ttf`，记录来源 commit、SHA-256 和 OFL 许可到 `THIRD_PARTY_NOTICES.md`；通过 Compose font variation 使用 regular/medium weight。定义 8/12/16/20/28dp 圆角和不小于 48dp 的触控尺寸。

- [ ] **Step 4: 实现 route 与底栏规则**

`AppRoute` 使用 `@Serializable` route：`Login`、`ChangePassword`、`Catalog`、`Profile`、`Preparation(songId)`、`Recording(draftId)`、`Review(draftId)`、`PendingUploads`、`History`、`Result(sessionId)`、`Settings`。只有 Catalog/Profile destinations 渲染底栏。

- [ ] **Step 5: 实现 AppContainer 空接口**

容器暴露 clock、dispatcher、repository/media/upload factory；此任务先用不可调用的 stub 实现，后续任务逐一替换，页面通过 CompositionLocal 获取容器但不直接调用具体 SDK。

- [ ] **Step 6: 运行测试与提交**

Run: `cd android-patient && ./gradlew :app:connectedDebugAndroidTest :app:lintDebug`

Expected: PASS。

```bash
git add android-patient/app
git commit -m "建立安卓设计系统与双标签导航"
```

---

### Task 3: 建立精确的 API DTO、错误映射和网络客户端

**Files:**

- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/network/ApiEnvelope.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/network/ApiError.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/network/VocaEaseApi.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/network/NetworkModule.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/network/dto/AuthDtos.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/network/dto/PatientDtos.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/network/dto/SongDtos.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/network/dto/SessionDtos.kt`
- Create: `android-patient/app/src/test/java/com/vocaease/patient/core/network/ApiContractTest.kt`

**Consumes:** 服务端适配计划生成的 OpenAPI JSON。

**Produces:** Kotlin serialization DTO、Retrofit suspend API、稳定中文错误类别、request-id 诊断信息。

- [ ] **Step 1: 保存契约 fixture**

从联调 server 运行：

```bash
cd server
uv run python manage.py spectacular --file ../android-patient/app/src/test/resources/openapi.json --format openapi-json --validate
```

fixture 纳入版本控制，供契约测试检查字段和 enum。

- [ ] **Step 2: 写解析失败测试**

使用 MockWebServer 返回登录、me、歌曲分页、grant、session detail 和 error envelope；先写 DTO 引用并确认编译失败。示例：

```kotlin
val me = json.decodeFromString<ApiEnvelope<PatientMeDto>>(fixture("patient_me.json"))
assertEquals("33.33", me.data.treatmentProgress?.progressPercent)
assertEquals(3180, me.data.singingSummary.totalDurationSeconds)
```

- [ ] **Step 3: 实现 DTO 和严格 JSON 设置**

```kotlin
@Serializable
data class ApiEnvelope<T>(
    val code: String,
    val message: String,
    val data: T,
    @SerialName("request_id") val requestId: String,
)

val apiJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    exceptionsWithDebugInfo = false
}
```

所有 UUID 暂用 `String` 并在 domain mapper 校验；时间用 ISO-8601 字符串在 mapper 转 `Instant`。不要把 `JsonElement` 泄漏到 feature 层。

- [ ] **Step 4: 定义 Retrofit 接口**

精确声明 auth、patient/me、songs list/detail/preview、singing session create/list/detail/grant/confirm/submit/cancel/retry、patient media private-url。创建/上传/提交/重试方法显式接收 `@Header("Idempotency-Key")`。

- [ ] **Step 5: 实现错误映射和日志脱敏**

把 401/403/404/409/429/503、`validation_error`、`song_unavailable`、`singing_*_conflict` 映射为 sealed `ApiFailure`。日志只记录 method、模板化 path、status、code、requestId；禁记 request/response body 和 URL query。

- [ ] **Step 6: 运行测试与提交**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest --tests '*ApiContractTest'`

Expected: PASS，OpenAPI 必需字段与 DTO 一致。

```bash
git add android-patient/app
git commit -m "建立安卓患者接口客户端"
```

---

### Task 4: 完成安全会话、登录和首次强制改密

**Files:**

- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/security/TokenVault.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/security/AndroidTokenVault.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/network/AuthInterceptor.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/network/RefreshCoordinator.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/auth/AuthRepository.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/auth/AuthViewModel.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/auth/LoginScreen.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/auth/ChangePasswordScreen.kt`
- Create: `android-patient/app/src/test/java/com/vocaease/patient/feature/auth/AuthRepositoryTest.kt`
- Create: `android-patient/app/src/androidTest/java/com/vocaease/patient/feature/auth/AuthFlowTest.kt`

**Consumes:** Android auth body refresh 协议和 `must_change_password`。

**Produces:** access token 仅内存、refresh token Keystore 加密落盘、并发请求单次刷新、强制改密后清会话返回登录。

- [ ] **Step 1: 写认证状态机失败测试**

覆盖无会话、有效会话、过期 access+可刷新、刷新失败、must-change、改密成功五条路径。并发发起 20 个 401 请求，断言 refresh endpoint 只调用一次。

- [ ] **Step 2: 运行并确认失败**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest --tests '*AuthRepositoryTest'`

Expected: FAIL，repository 和 vault 尚不存在。

- [ ] **Step 3: 实现 TokenVault**

用 Android Keystore 生成不可导出的 AES-256-GCM key `vocaease.refresh.v1`；refresh token 以 version+IV+ciphertext 存入 app-private 文件。access token 只存在 `AtomicReference<String?>`。解密失败立即删除密文并视为登出，绝不 fallback 明文。

- [ ] **Step 4: 实现刷新协调器**

`RefreshCoordinator` 用 `Mutex` 和 token generation 防止刷新风暴；仅对首次 401 刷新一次，refresh 请求自身不经过 authenticator。刷新成功原子替换 access/refresh；失败广播 `SessionExpired`，保留加密草稿但暂停上传。

- [ ] **Step 5: 实现登录和改密 Compose UI**

登录页只有病历号、密码、登录按钮和无注册说明。发送 `client_kind="android"`、`remember_me=false`。`must_change_password=true` 时只能进入强制改密；成功后调用 logout/清除 token 并提示“密码已修改，请重新登录”。密码字段不写入 saved state。

- [ ] **Step 6: 运行单元和 UI 测试**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vocaease.patient.feature.auth.AuthFlowTest`

Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add android-patient/app
git commit -m "实现患者安全登录与强制改密"
```

---

### Task 5: 建立账户隔离的 Room 数据库和分块加密文件存储

**Files:**

- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/database/VocaEaseDatabase.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/database/DraftEntity.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/database/MediaEntity.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/database/UploadJobEntity.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/database/DraftDao.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/database/UploadDao.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/security/ChunkedAesGcmFileStore.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/media/EncryptedMediaDataSource.kt`
- Create: `android-patient/app/src/androidTest/java/com/vocaease/patient/core/database/DatabaseIsolationTest.kt`
- Create: `android-patient/app/src/androidTest/java/com/vocaease/patient/core/security/EncryptedFileStoreTest.kt`

**Consumes:** 已登录患者 ID、本地 draft UUID、服务端 session ID、两个媒体文件和上传状态。

**Produces:** 可迁移 Room v1 schema、账户作用域约束、可 seek 的分块 AES-GCM 媒体格式、Media3 解密 DataSource。

- [ ] **Step 1: 写数据库和加密失败测试**

测试同一个 draft ID 不能跨 account scope 查询；上传 job 必须关联同账户 draft；写入 3.5MiB 样本后密文不含明文片段，随机 seek 解密结果与原始 bytes 一致，篡改单块 tag 必须失败。

- [ ] **Step 2: 运行并确认失败**

Run: `cd android-patient && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.vocaease.patient.core`

Expected: FAIL，Room schema 和 file store 尚不存在。

- [ ] **Step 3: 定义 Room schema**

`DraftEntity` 至少保存 `draftId`、`accountScope`、`songId`、`sessionId`、`creationKey`、`state`、`durationMs`、`createdAt`、`expiresAt`、`interruptionReason`；`MediaEntity` 保存 type、encrypted path、mime、size、sha256、validation state；`UploadJobEntity` 保存每一步状态、audio/video grant key、submit key、asset/object key、attempt、nextRetryAt、lastSafeError。所有 DAO 查询必须同时接收 `accountScope`。

- [ ] **Step 4: 实现分块加密格式**

格式固定为 magic `VEF1`、chunk size 1MiB、原始长度、每块随机 12-byte nonce、ciphertext+16-byte GCM tag。每个账户的 media master key 由 Keystore 包装，别名含 SHA-256 后的 account scope，不包含病历号明文。`EncryptedMediaDataSource` 用 chunk index 支持 Media3 seek。

- [ ] **Step 5: 导出 Room schema 并运行测试**

启用 `room.schemaDirectory("$projectDir/schemas")`，将 `app/schemas/com.vocaease.patient.core.database.VocaEaseDatabase/1.json` 纳入版本控制。

Run: `cd android-patient && ./gradlew :app:connectedDebugAndroidTest`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add android-patient/app
git commit -m "建立患者草稿数据库与加密存储"
```

---

### Task 6: 实现治疗进度首页、曲库和两标签主页面

**Files:**

- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/catalog/PatientRepository.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/catalog/SongRepository.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/catalog/SongPagingSource.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/catalog/CatalogViewModel.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/catalog/CatalogScreen.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/catalog/TreatmentProgressCard.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/profile/ProfileViewModel.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/profile/ProfileScreen.kt`
- Create: `android-patient/app/src/test/java/com/vocaease/patient/feature/catalog/CatalogViewModelTest.kt`
- Create: `android-patient/app/src/androidTest/java/com/vocaease/patient/feature/catalog/CatalogScreenTest.kt`

**Consumes:** patient/me 的 `treatment_progress`、`singing_summary`，歌曲分页 API，Room 待上传计数。

**Produces:** 与 PEN `01A` 一致的“去唱歌”首页、搜索分页、无计划禁用态、“我的”统计和轻量待处理提示。

- [ ] **Step 1: 写首页 reducer 和 Compose 失败测试**

覆盖 8/24、33.33%、第 3 周；无计划时显示联系医生且歌曲不可点击；Room 有失败上传时 Banner 下方显示提示并跳 PendingUploads。Compose 测试使用 `testTag("treatment-progress")`、`song-card-{id}`。

- [ ] **Step 2: 运行并确认失败**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest --tests '*CatalogViewModelTest' :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vocaease.patient.feature.catalog.CatalogScreenTest`

Expected: FAIL。

- [ ] **Step 3: 实现 repository 和分页**

`PatientRepository.refreshMe()` 同步治疗进度和终身汇总；`SongPagingSource` 发送 keyword/page/page_size=20/sort=-created_at，refresh key 固定回第一页。API 错误保留旧列表并显示可重试状态。

- [ ] **Step 4: 实现 PEN 首页**

顶部问候、“今天唱什么？”、搜索框、治疗进度卡、热门歌曲列表和双标签底栏按 390×844 基准实现。进度圆环语义值使用 0..100 且显示服务端已封顶百分比。不得恢复“今日推荐”Banner。

- [ ] **Step 5: 实现“我的”概览**

显示患者姓名、终身完成歌曲数、总时长、历史入口、治疗计划、待上传记录和设置。总时长由 server summary 提供，不从当前分页估算。

- [ ] **Step 6: 测试、截图与提交**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest`

Expected: PASS；保存 390×844 测试截图到 `android-patient/app/build/reports/screenshots/` 供人工对照，不提交 build 产物。

```bash
git add android-patient/app
git commit -m "实现治疗进度首页与患者主页"
```

---

### Task 7: 实现歌曲私有试听、准备检查和在线创建会话

**Files:**

- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/media/PreviewPlayer.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/training/TrainingPreflight.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/training/PreparationViewModel.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/training/PreparationScreen.kt`
- Create: `android-patient/app/src/test/java/com/vocaease/patient/feature/training/PreparationViewModelTest.kt`
- Create: `android-patient/app/src/androidTest/java/com/vocaease/patient/feature/training/PreparationScreenTest.kt`

**Consumes:** song detail/preview、网络状态、相机/麦克风权限、存储空间、耳机状态、session create 幂等接口。

**Produces:** PEN 准备页、过期 URL 自动刷新、预缓冲完成门禁、稳定 create key 和服务端 session ID。

- [ ] **Step 1: 写准备状态机失败测试**

状态必须依次满足 `PermissionsGranted + StorageEnough + Buffered + Online` 才能开始。耳机缺失只产生 warning，不阻断。preview 401/过期后只刷新 URL 一次。创建超时重试复用 `session-create:{accountScopeHash}:{draftId}`。

- [ ] **Step 2: 运行并确认失败**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest --tests '*PreparationViewModelTest'`

Expected: FAIL。

- [ ] **Step 3: 实现 PreviewPlayer**

Media3 ExoPlayer 缓冲到 `STATE_READY` 才上报 ready；保存 URL expiry 但不落 Room。播放因 401/403 失败时重新请求 preview、seek 到原位置并重新缓冲；第二次失败才显示操作错误。

- [ ] **Step 4: 实现 preflight 和会话创建**

检查 CAMERA/RECORD_AUDIO、`StatFs.availableBytes >= max(512MiB, songDurationSeconds*8MiB)`、网络和 front camera。点击开始先创建本地 draft，再以持久 creationKey 调 API；只有拿到 session ID 后进入 3 秒倒计时。

- [ ] **Step 5: 实现准备页空态和权限处理**

保持歌曲信息、面部完整露出、耳机建议、设备状态和开始按钮。由于当前 API 无歌词文本，歌词区域显示“歌词暂未提供”，不得内置或抓取版权歌词。永久拒绝权限时提供系统设置入口。

- [ ] **Step 6: 运行测试与提交**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest`

Expected: PASS。

```bash
git add android-patient/app
git commit -m "实现演唱准备与会话创建"
```

---

### Task 8: 实现前摄同步录制、单麦克风约束和音轨抽取

**Files:**

- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/media/RecordingCoordinator.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/media/CameraXRecordingCoordinator.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/media/Mp4AudioTrackExtractor.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/training/RecordingStateMachine.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/training/RecordingViewModel.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/training/RecordingScreen.kt`
- Create: `android-patient/app/src/androidTest/assets/sample_avc_aac.mp4`
- Create: `android-patient/app/src/test/java/com/vocaease/patient/feature/training/RecordingStateMachineTest.kt`
- Create: `android-patient/app/src/androidTest/java/com/vocaease/patient/core/media/Mp4AudioTrackExtractorTest.kt`

**Consumes:** 已预缓冲 player、服务端 session ID、front camera、CameraX Recorder 输出 MP4。

**Produces:** 同步播放+录制、一个麦克风来源、`video/mp4` 和 `audio/mp4` 两个经验证媒体、加密草稿。

- [ ] **Step 1: 写状态机和 extractor 失败测试**

状态固定为 `Countdown -> Starting -> Recording -> Finalizing -> Reviewable|Interrupted`。重复 stop 幂等；歌曲结束自动 stop；用户提前结束进入 Reviewable；camera/audio error 进入 Interrupted。extractor 测试断言输出只有 AAC audio track、duration 在 50ms 容差内、无 video track。

- [ ] **Step 2: 生成固定媒体 fixture**

```bash
ffmpeg -y -f lavfi -i color=c=black:s=320x240:r=25 \
  -f lavfi -i sine=frequency=440:sample_rate=48000 -t 2 \
  -c:v libx264 -pix_fmt yuv420p -c:a aac \
  android-patient/app/src/androidTest/assets/sample_avc_aac.mp4
```

- [ ] **Step 3: 运行并确认失败**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest --tests '*RecordingStateMachineTest' :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vocaease.patient.core.media.Mp4AudioTrackExtractorTest`

Expected: FAIL。

- [ ] **Step 4: 实现 CameraX 唯一录音源**

绑定 `Preview + VideoCapture<Recorder>` 到前置镜头，调用 `withAudioEnabled()`；代码库不得引用 `AudioRecord`。Media3 在 CameraX `VideoRecordEvent.Start` 后开始播放并记录单调时钟偏移。保持屏幕常亮，退出页面必须走 coordinator.stop()。

- [ ] **Step 5: 实现无损音轨抽取和加密落盘**

CameraX 先写 app-private `.recording` 临时 MP4；finalize 后 `MediaExtractor` 定位 `audio/*` track，用 `MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4` 复制 sample，不解码重编码。验证两文件可读、duration/size 合法后，分别写入 `ChunkedAesGcmFileStore` 并删除明文临时文件；验证失败标记 Interrupted，不可提交。

- [ ] **Step 6: 实现沉浸式录制页**

按 PEN 深色页实现前摄预览、动态歌词空态、录制时间、面部在框提示和红色停止按钮。现阶段“音准轨迹”只作为播放位置导引，不声称是实时音准分析；真实曲线只在服务端结果页显示。

- [ ] **Step 7: 运行测试与提交**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest`

Expected: PASS；真实设备 smoke test 可录制并产出两个 MIME 正确的媒体。

```bash
git add android-patient/app
git commit -m "实现前摄演唱录制与音轨抽取"
```

---

### Task 9: 实现本地回看、重录和中断草稿恢复

**Files:**

- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/training/DraftRepository.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/training/ReviewViewModel.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/training/ReviewScreen.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/core/media/StagingFileRecovery.kt`
- Create: `android-patient/app/src/test/java/com/vocaease/patient/feature/training/ReviewViewModelTest.kt`
- Create: `android-patient/app/src/androidTest/java/com/vocaease/patient/feature/training/ReviewScreenTest.kt`

**Consumes:** 两个加密媒体、draft 状态、Media3 encrypted DataSource。

**Produces:** 本地视频/音频回看、重录、确认入队、来电/切后台/杀进程恢复、7 天清理策略。

- [ ] **Step 1: 写失败测试**

覆盖 reviewable 可播放/可提交；interrupted 只能重录或删除；重录原子删除旧媒体但保留同一 session/create key；确认提交只改变 Room 状态并触发上传，不直接做网络。7 天只清理未入队草稿，失败上传必须保留并提示。

- [ ] **Step 2: 运行并确认失败**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest --tests '*ReviewViewModelTest'`

Expected: FAIL。

- [ ] **Step 3: 实现回看播放器**

视频和独立音频使用同一 Media3 player、不同 media item 切换，底层都走 `EncryptedMediaDataSource`。页面显示 duration、文件校验状态、“重新录制”和“确认提交”。不得展示最终分数。

- [ ] **Step 4: 实现中断与 staging 恢复**

监听 lifecycle stop、audio focus loss、camera error；尽可能 finalize。下次启动扫描 `.recording`：可解析且含音视频 track 则加密并标记 Interrupted；损坏或超过 24 小时的 staging 安全删除。来电中断原因只保存枚举，不保存电话号码。

- [ ] **Step 5: 实现草稿清理和账户锁定**

每天一次 one-time work 链清理超过 7 天的未提交草稿；保留其他账户草稿但不返回路径。退出后 pause job，并要求相同 accountScope 才恢复。

- [ ] **Step 6: 测试与提交**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest`

Expected: PASS。

```bash
git add android-patient/app
git commit -m "实现演唱回看与中断草稿恢复"
```

---

### Task 10: 实现七牛 V2 断点续传和持久上传状态机

**Files:**

- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/upload/UploadStateMachine.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/upload/QiniuUploader.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/upload/QiniuV2Uploader.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/upload/UploadOrchestrator.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/upload/UploadWorker.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/upload/UploadNotifications.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/upload/PendingUploadsViewModel.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/upload/PendingUploadsScreen.kt`
- Modify: `android-patient/app/src/main/AndroidManifest.xml`
- Create: `android-patient/app/src/test/java/com/vocaease/patient/feature/upload/UploadOrchestratorTest.kt`
- Create: `android-patient/app/src/androidTest/java/com/vocaease/patient/feature/upload/UploadWorkerTest.kt`

**Consumes:** Room queued draft、加密音视频、session grant/confirm/submit API、Qiniu upload URL/token/object key。

**Produces:** 可暂停/恢复/重试的 WorkManager 上传；音频和视频双确认后幂等提交；后台持续任务通知。

- [ ] **Step 1: 写 orchestration 失败测试**

固定状态：`WAITING_NETWORK -> REQUESTING_AUDIO_GRANT -> UPLOADING_AUDIO -> WAITING_AUDIO_RECEIPT -> CONFIRMING_AUDIO -> REQUESTING_VIDEO_GRANT -> UPLOADING_VIDEO -> WAITING_VIDEO_RECEIPT -> CONFIRMING_VIDEO -> SUBMITTING -> ANALYZING`。每一步完成后先写 Room 再做下一副作用。网络超时、凭证过期、409 状态冲突、进程重启均从最后安全点恢复。

- [ ] **Step 2: 运行并确认失败**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest --tests '*UploadOrchestratorTest'`

Expected: FAIL。

- [ ] **Step 3: 封装七牛 SDK V2**

用 `Configuration.Builder().useHttps(true).buildV2()` 和 app-private `FileRecorder` 保存断点记录；上传地址优先使用 API 返回的 `upload_url` 配置 FixedZone。Qiniu wrapper 只接收 objectKey/token/plain staging file/progress callback，不持有 AK/SK。固定 SDK 8.9.0，不启用 curl/HTTP3 插件。

- [ ] **Step 4: 安全准备上传文件**

worker 获得账户锁后把一个加密媒体解密到 app-private `cache/upload-lease/{jobId}`，权限仅当前 UID；七牛完成或 worker 退出即删除。启动时清除超过 1 小时的遗留 lease。长期源文件始终是加密版本，日志不输出 lease path。

- [ ] **Step 5: 实现 grant/confirm/submit 幂等链**

键固定为 `grant:{draftId}:audio`、`grant:{draftId}:video`、`submit:{draftId}`。grant 过期时用同键重新申请；上传 SDK 成功只表示对象已上传，confirm 返回 `singing_media_conflict` 时按 2s/5s/10s/30s 等待七牛可信 callback 后重试，不创建新 asset；confirm 可重复。submit 409 时先 GET session detail：processing/completed 视为成功推进，uploaded 才重试 submit，cancelled/归属错误停止。

- [ ] **Step 6: 实现 WorkManager 和通知权限语义**

唯一工作名 `upload:{accountScopeHash}:{draftId}`，`NetworkType.CONNECTED`，指数退避起点 30s，并遵守 WorkManager 的 5 小时上限。长上传调用 `setForeground()`，通知渠道名“演唱上传”，文案只显示进度和暂停操作。Android 13+ 仅在用户确认后台上传时请求通知权限；不创建提醒渠道或定时任务。

- [ ] **Step 7: 实现待上传页面**

展示等待网络、上传百分比、分析中、失败原因、7 天到期提示；支持暂停、继续、立即重试、删除未提交任务。已经 submit 的任务不可删除服务端会话，只能隐藏本地完成记录。

- [ ] **Step 8: 运行单元和 WorkManager 测试**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vocaease.patient.feature.upload.UploadWorkerTest`

Expected: PASS。

- [ ] **Step 9: 提交**

```bash
git add android-patient/app
git commit -m "实现七牛断点续传与上传队列"
```

---

### Task 11: 实现分析同步、历史列表和演唱回顾

**Files:**

- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/history/HistoryRepository.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/history/HistoryPagingSource.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/history/AnalysisSyncWorker.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/history/HistoryViewModel.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/history/HistoryScreen.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/history/ResultViewModel.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/history/ResultScreen.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/history/PitchChart.kt`
- Create: `android-patient/app/src/test/java/com/vocaease/patient/feature/history/ResultMapperTest.kt`
- Create: `android-patient/app/src/androidTest/java/com/vocaease/patient/feature/history/HistoryAndResultTest.kt`

**Consumes:** session list/detail、analysis_results、time_series、media asset ID/private URL、`is_mock`。

**Produces:** 混合本地上传状态与服务端历史的列表、前台/后台分析同步、私有录像回放、真实结果曲线和演示标识。

- [ ] **Step 1: 写 result mapper 失败测试**

断言 `is_mock=true -> "演示结果"`，false/null 不显示；pitch_hz series 转为带 sample interval 的点；缺分项不构造数值；failed 显示重试，processing 不显示虚构分数。私有 URL 不落数据库。

- [ ] **Step 2: 运行并确认失败**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest --tests '*ResultMapperTest'`

Expected: FAIL。

- [ ] **Step 3: 实现历史合并和状态同步**

服务端 history 以 session ID 为主键，本地 queued job 覆盖其暂态显示。提交后 `AnalysisSyncWorker` 以 10s/30s/1m/5m 退避查询 detail，terminal 后停止；结果页前台每 3s 查询，离开页面停止。

- [ ] **Step 4: 实现回顾页**

按 PEN 显示歌曲、总分、录像、状态、可用音准曲线和时间序列摘要。音准/节奏/稳定度区域在协议没有独立分数时显示“暂无单项评分”；不得从 overall score 拆分。只有 `is_mock=true` 在得分旁显示低调“演示结果”。

- [ ] **Step 5: 实现私有视频播放和 URL 刷新**

用 media binding 的 video asset ID 调 private-url；ExoPlayer 401/403 时刷新一次并保留播放位置。URL 仅内存缓存至 expiresAt 前 30s，绝不写 Room/日志。

- [ ] **Step 6: 实现分析失败重试**

按钮生成稳定 `retry:{sessionId}:{analysisGeneration+1}`，调用 retry API。409 时刷新 detail；达到最大次数显示“暂时无法重新分析，请联系医生”。

- [ ] **Step 7: 测试与提交**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest`

Expected: PASS。

```bash
git add android-patient/app
git commit -m "实现演唱历史与结果回顾"
```

---

### Task 12: 完成设置、改密、退出和患者隔离闭环

**Files:**

- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/profile/SettingsViewModel.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/profile/SettingsScreen.kt`
- Create: `android-patient/app/src/main/java/com/vocaease/patient/feature/profile/LogoutCoordinator.kt`
- Create: `android-patient/app/src/test/java/com/vocaease/patient/feature/profile/LogoutCoordinatorTest.kt`
- Create: `android-patient/app/src/androidTest/java/com/vocaease/patient/feature/profile/AccountIsolationFlowTest.kt`

**Consumes:** auth logout/change-password、Room accountScope、WorkManager unique jobs、Keystore keys。

**Produces:** 修改密码、退出时保留/删除草稿选择、同账号恢复、跨账号不可见和不可续传。

- [ ] **Step 1: 写退出矩阵失败测试**

四种组合：无草稿直接退出；保留草稿时 pause+lock；删除草稿时取消 work、删 Room 和加密文件；删除失败时不清 token 并提示重试。账号 B 登录后查询不到账号 A 的 draft/job/path。

- [ ] **Step 2: 运行并确认失败**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest --tests '*LogoutCoordinatorTest'`

Expected: FAIL。

- [ ] **Step 3: 实现修改密码**

设置页复用强制改密表单但显示旧密码。成功后服务端令牌失效，客户端暂停上传、清 token、保留加密草稿并回登录。

- [ ] **Step 4: 实现两阶段退出**

先在数据库事务标记 pause/delete intent 并取消对应 WorkManager；文件操作成功后调用 server logout，最后清 token。若 server 不可达，仍可本地退出，但把旧 refresh 从认证槽原子移动到 Keystore 加密的 revocation-only 槽；该槽不能为 API 认证提供 token。下次联网先尝试吊销旧 refresh family，成功或服务端判定失效后删除该槽。

- [ ] **Step 5: 运行隔离测试**

Run: `cd android-patient && ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vocaease.patient.feature.profile.AccountIsolationFlowTest`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add android-patient/app
git commit -m "完成患者设置与账户隔离"
```

---

### Task 13: 完成端到端、隐私、无 GMS、真机和视觉验收

**Files:**

- Create: `android-patient/app/src/androidTest/java/com/vocaease/patient/e2e/PatientClosedLoopTest.kt`
- Create: `android-patient/scripts/verify_release.sh`
- Create: `android-patient/docs/qa-device-matrix.md`
- Create: `android-patient/docs/privacy-checklist.md`
- Create: `android-patient/docs/qiniu-integration-runbook.md`
- Modify: `README.md`

**Consumes:** 完整 App、联调 server、七牛测试私有空间、固定测试患者与歌曲、PEN 画板。

**Produces:** 自动化闭环证据、release APK、依赖与隐私审计、国产真机和视觉验收记录。

- [ ] **Step 1: 写端到端失败测试**

使用 MockWebServer + fake Camera/Qiniu boundary 跑：登录→强制改密→重新登录→进度→选歌→创建 session→录制 fixture→回看→双上传→submit→processing→completed→历史→结果。先让最后一个结果断言失败，确认测试能捕获断链。

- [ ] **Step 2: 实现可替换边界并通过 E2E**

Camera、Qiniu、Clock、Connectivity 和 API transport 必须由 AppContainer 注入；production 使用真实实现，androidTest 使用 deterministic fake。不得用 test-only 分支改变 domain 状态机。

- [ ] **Step 3: 七牛测试空间实测**

按 runbook 验证 50MiB+ 视频 V2 分片、杀进程恢复、Wi-Fi/移动网络切换、暂停/继续、凭证过期、重复 callback、双 confirm 和 submit 幂等。记录 job/session/asset 的脱敏 ID 后 8 位，不记录 token/object URL。

- [ ] **Step 4: 无 GMS release 验证**

`verify_release.sh` 顺序执行：

```bash
./gradlew --offline :app:testDebugUnitTest :app:lintRelease :app:assembleRelease \
  -PvocaeaseApiBaseUrl="$VOCAEASE_API_BASE_URL"
./scripts/check_no_gms.sh
apkanalyzer manifest permissions app/build/outputs/apk/release/app-release-unsigned.apk
```

Expected: 离线构建 PASS；依赖无 GMS/Firebase；权限只有计划内集合；APK 可在未安装 Play Services 的 Android 10+ 设备启动。

- [ ] **Step 5: 隐私和日志检查**

用 `rg` 扫描 Timber/Log 调用和 Room schema，确认没有 token/body/完整 URL/明文 path。ADB 导出 app-private 数据需 root 测试设备，仅验证媒体主文件是 `VEF1` 密文；测试数据验收后删除。

- [ ] **Step 6: 国产真机矩阵**

至少记录 Android 10/API 29、Android 14/API 34、Android 17/API 37；小米、荣耀、OPPO、vivo、可安装 APK 的华为兼容机。逐项验证有线/蓝牙耳机、扬声器串音提示、前摄方向、来电、切后台、锁屏、低存储、重启、后台限制和通知权限拒绝。

- [ ] **Step 7: PEN 视觉复核**

对登录、01A 首页、准备、录制、我的、回看、回顾页截取 390×844；逐页对照 `docs/design-m.pen`，同时检查常见宽度、字体 1.3x、键盘遮挡、TalkBack 描述和 48dp 触控区。差异只修 Compose，不改已确认 PEN 画板。

- [ ] **Step 8: 完整验证**

Run: `cd android-patient && ./scripts/verify_release.sh`

Expected: PASS。

Run: `cd server && uv run pytest -q`

Expected: PASS，确认 Android 契约未破坏服务端回归。

- [ ] **Step 9: 更新 README 并提交**

README 增加 Android 10+ 构建、国内 Maven、debug/release base URL、无 GMS 约束、测试命令和联调入口。

```bash
git add android-patient README.md
git commit -m "完成安卓患者闭环验收"
```

---

## Plan Completion Verification

- [ ] `cd android-patient && ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintRelease :app:assembleRelease` 全部通过。
- [ ] `cd android-patient && ./scripts/check_no_gms.sh` 无禁止依赖。
- [ ] `cd android-patient && ./gradlew --offline :app:assembleRelease -PvocaeaseApiBaseUrl="$VOCAEASE_API_BASE_URL"` 在预热国内缓存后通过。
- [ ] 七牛私有测试空间的断点、过期凭证、重复回调和双媒体确认均有记录。
- [ ] Android 10 与至少四个国产厂商设备完成核心闭环；无 GMS 设备完成登录至回顾。
- [ ] 主要页面与 PEN 截图完成视觉、字体、触控和无障碍复核。
- [ ] 成功 submit 后本地媒体删除；未提交草稿 7 天清理；跨患者草稿和媒体不可见。
- [ ] `git status --short` 只含已说明改动，`git log -13 --oneline` 的任务提交全部为中文。
