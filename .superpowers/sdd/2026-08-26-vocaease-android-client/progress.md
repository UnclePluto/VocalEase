# SDD ledger — plan: /Users/nick/my_dev/workout/VocaEase/docs/superpower/plans/2026-08-26-vocaease-android-client.md

Baseline: 6d4753237c0bba8f534427374b4c4cadf85a63dc

## Preflight consistency scan

| Task | Consumes | Produces | Shared interfaces / consistency notes | Status |
| --- | --- | --- | --- | --- |
| 1 | Fixed dependency set, JDK 17, SDK 37, domestic repositories | Reproducible single-module Compose shell, Gradle wrapper, dependency verification, no-GMS audit | Establishes all later build files and manifest baseline | Complete |
| 2 | Task 1 shell, PEN design tokens and two-tab information architecture | Theme, routes, navigation shell, manual `AppContainer` | `AppContainer`, `VocaEaseApp`, manifest/build wiring are intentionally shared integration surfaces for later tasks | Complete |
| 3 | Finalized patient API at server HEAD `6d47532`, local OpenAPI generation | DTOs, Retrofit API, error mapping, OpenAPI fixture | Defines network contracts consumed by Tasks 4, 6, 7, 10, 11, 12 | Complete |
| 4 | Task 3 auth DTOs/client, Task 2 routing/container | Secure auth/session flow and forced password change | Replaces relevant Task 2 stubs; supplies account scope to Task 5 | Complete |
| 5 | Task 4 account scope, Room/Keystore dependencies | Account-scoped database and encrypted draft storage | Persistent contract used by training, upload, history and logout | Complete |
| 6 | Tasks 2/3/5 | Catalog, treatment progress home, profile shell | Uses exact confirmed PEN tokens; feeds selected song into Task 7 | Complete |
| 7 | Tasks 3/5/6 | Preparation checks and online idempotent session creation | Persists stable ids before handing off to Task 8 | Complete |
| 8 | Tasks 5/7, CameraX/Media3 | Sole-mic front-camera recorder and extracted audio | Produces encrypted audio/video artifacts for Tasks 9/10 | In progress |
| 9 | Tasks 5/8 | Review, retake and interruption recovery | Converts valid draft into uploadable task; rejects incomplete clips | Pending |
| 10 | Tasks 3/5/9, Qiniu SDK | Durable resumable two-media upload and analysis submission state machine | Extends shared worker/container/manifest wiring; source of pending status for Tasks 6/11/12 | Pending |
| 11 | Tasks 3/10 | History and server-backed results/replay | Must honor score omissions and `is_mock` display rules exactly | Pending |
| 12 | Tasks 4/5/10 | Settings, logout decisions and strict patient isolation | Coordinates auth revocation with encrypted draft retention/locking | Pending |
| 13 | Tasks 1–12 | E2E, privacy/no-GMS gates, device and visual acceptance evidence | Final integration task; external Qiniu/device matrix acceptance remains environment-dependent | Pending |

Ruling: The API adaptation is complete and locally validated at `6d47532`, but no deployed integration endpoint was supplied. Task 3 will generate and pin the OpenAPI fixture from the local server and use deterministic HTTP fixtures; live-environment checks remain Task 13 gates. Cost: live server behavior is not proven until an integration endpoint is available.

Ruling: Later tasks may minimally update shared integration files such as `AppContainer`, `VocaEaseApp`, Gradle configuration and `AndroidManifest.xml` even when an abbreviated task file list omits them. Cost: reviews must distinguish required wiring from unrelated scope expansion.

Ruling: JDK 17, SDK platforms 29/37 and AVDs `VocaEase_API_29`/`Pixel_7` are available. No device is currently connected; instrumentation tests will start the Android 10 AVD when first required. Cost: emulator startup adds execution time but is not an implementation blocker.

Ruling: The available Android 29 emulator image includes Google APIs, but the app itself must have zero GMS/Firebase runtime dependencies and pass the dependency audit. Task 13 must separately document/execute a no-GMS-network acceptance path where possible. Cost: an emulator image alone cannot certify the full domestic device matrix.

Ruling: Real Qiniu private-space credentials and Xiaomi/Honor/OPPO/vivo/Huawei hardware were not supplied. Implement deterministic SDK boundaries, recovery tests and a reproducible acceptance runbook; actual external credential/device acceptance must be reported truthfully as an environment gate. Cost: the code can be completed, but production acceptance cannot be claimed without those external resources.

Ruling: Pencil MCP is not callable in the current tool surface. The implementation will use the confirmed `design-m.pen`-derived tokens, page structure and spec already recorded under `docs/superpower`; visual screenshot checks will use those fixed requirements. Cost: pixel-level reinspection of the live PEN canvas cannot be performed in this session unless its MCP tool becomes available.

Ruling: The preceding Pencil-unavailable ruling is superseded for Task 6 onward: the VS Code Pencil MCP became callable and the controller directly inspected `pUYpg` (`01A-歌曲列表页-治疗进度版`) and `f004p` (`04-我的`) from `docs/design-m.pen`, including exact 390×844 geometry, variables, node trees and screenshots. Cost: Android implementation must now be reviewed against these live design values, while direct user requirements still override stale canvas content.

Ruling: The Task 6 brief's homepage failed-upload hint is superseded by the user's explicit requirement “暂不提供提醒，只在首页展示治疗进度和目标次数”. The homepage will not show reminder/pending-upload banners; pending uploads may remain an entry in “我的” because it is navigation/state, not a homepage reminder. Cost: the planned Compose assertion for a homepage failure banner is intentionally omitted/replaced with a negative assertion.

Ruling: Global Gradle is 9.6.1 on the default JDK 11, while the plan fixes Gradle 9.4.1/JDK 17. Task commands will explicitly use `/opt/homebrew/opt/openjdk@17` and generate the 9.4.1 wrapper, without changing global Java configuration. Cost: bootstrap uses the global Gradle executable only to generate the pinned wrapper.

Ruling: Task 3 pins its OpenAPI fixture from the final local server state at `6d47532`; all DTO decisions must follow that fixture rather than assumptions from the abbreviated plan examples. Cost: future server schema changes require an explicit fixture refresh and contract review.

Ruling: Task 5 uses one non-exportable AndroidKeyStore AES-256 media master key per hashed account scope directly for chunk encryption, rather than storing an exportable software master key wrapped by a second Keystore key. Cost: each chunk performs a Keystore cipher operation and may be slower on vendor hardware; benefit: no wrapped-key blob or plaintext master key ever exists in app storage/memory. This is accepted only if independent review confirms seek correctness and practical behavior.

Ruling: The preceding direct-Keystore-master ruling is superseded after independent review rejected its Android 10 vendor compatibility and per-chunk KeyMint cost. Task 5 will use a Keystore KEK to wrap a random account media master, with explicit in-memory zeroization and key lifecycle semantics. Cost: the unwrapped master exists transiently in process memory during an active reader/writer, but per-chunk crypto stays in the software provider and avoids repeated KeyMint calls.

## Task ledger

### Task 1 — 建立可复现且无 GMS 的 Android 工程

- Status: Complete

- Base: `6d4753237c0bba8f534427374b4c4cadf85a63dc`
- Implementer: `/root/android_task1_impl`
- Implementation commit: `d049dca25061d34c5f2be9f405ad2bd0feede7bb`
- Evidence: RED failed on unresolved `BuildConfig.MIN_SUPPORTED_API`; GREEN unit test and debug assembly passed; dependency verification covers 623 external components; no-GMS audit passed; release URL negative/positive validation passed.
- Reviewer: `/root/android_task1_review`
- Review result: Not accepted — 4 Important, 0 Critical, 0 Minor.
- Findings:
  1. Fresh Gradle cache strict verification fails for three missing metadata artifacts (`guava-parent`, `junit-bom`, `kotlinx-coroutines-bom`).
  2. Aggregate `:app:assemble` bypasses release URL validation and emits a release APK with `https://invalid.invalid/`.
  3. Qiniu 8.9.0 injects `ACCESS_WIFI_STATE`, `READ_PHONE_STATE`, `READ_EXTERNAL_STORAGE`, and `WRITE_EXTERNAL_STORAGE` into merged manifests.
  4. Debug base URL uses HTTP `10.0.2.2`, but no debug-only network security policy permits that host on target SDK 36.
- Fix rounds: 1 completed (original implementer)
- Fix commit: `a90431956a8c50b2a7802b96bb7d784043d83c90`
- Fix evidence: four RED cases reproduced; second fresh cache passed strict debug and aggregate builds; release URL negative/positive gates, merged-manifest/APK exact platform permission audit, debug-only network policy audit, unit tests, debug assembly and no-GMS audit passed.
- Scoped re-review: 3 findings addressed; release URL remains not addressed.
- Remaining Important: shell validator accepts `https://:443/` because it checks authority instead of a real host, and wiring every release build to a POSIX script breaks the supported `gradlew.bat` path.
- Fix rounds: 2 completed (original implementer)
- Second fix commit: `d3b5337b1b38ef175f71eabc376783e78fd700cc`
- Second fix evidence: release validation moved from production shell dependency to a cross-platform `buildSrc` Gradle task; invalid-host/userinfo/query/fragment/missing-slash cases fail, IPv4/DNS/IPv6 pass; aggregate lifecycle, configuration-cache store/reuse, fresh strict cache, unit/build/no-GMS/permission/network audits pass.
- Second scoped re-review: accepted; all remaining URL/cross-platform findings addressed, no new Critical/Important.
- Status: Complete

### Task 2 — 实现设计令牌、应用容器和双标签导航壳

- Status: Complete
- Base: `d3b5337b1b38ef175f71eabc376783e78fd700cc`
- Implementer: `/root/android_task2_impl`
- Implementation commit: `c91c253c50fa49350994cec4e41b16efe1e4cbaf`
- Evidence: navigation RED failed on missing Task 2 types; API 34 connected test 1/1 passed; unit/debug/androidTest builds, lint, no-GMS, permission, debug network and release URL gates passed; official Noto Sans SC font pinned with source commit/SHA/OFL.
- Environment note: API 29 AVD failed to reach adb under four graphics/acceleration modes due QEMU main-loop/CPU hang; API 34 `Pixel_7` completed instrumentation.
- Fix rounds: 0
- Reviewer: `/root/android_task2_review`
- Review result: Accepted — 0 Critical, 0 Important, 1 Minor.
- Minor: navigation test verifies bottom-bar disappearance but not destination title/tab switching/back-stack; implementation was manually verified and later navigation coverage should strengthen this.
- Status: Complete

### Task 3 — 建立精确的 API DTO、错误映射和网络客户端

- Status: In review
- Base: `c91c253c50fa49350994cec4e41b16efe1e4cbaf`
- Implementer: `/root/android_task3_impl`
- Implementation commit: `14d56d3f4da386a613cb00038708d91da312876c`
- Evidence: three RED/GREEN cycles (missing DTO/network types, object-shaped validation data, direct HttpException mapping); 8 tests passed; locally generated 4024-line OpenAPI validated; cold strict dependency verification and forced rerun passed; build/lint/no-GMS/permission/network/release gates passed.
- Contract note: generated OpenAPI omits list query parameters that server serializers accept; Retrofit exposes the verified server behavior. Untyped session snapshots/payload were narrowed from current server serializers/services and remain private to network mapping.
- Reviewer: `/root/android_task3_review`
- Review result: Not accepted — 5 Important, 0 Critical, 1 Minor.
- Findings:
  1. Error mapping does not match real `validation_error.data` shape; non-JSON bodies lose HTTP status semantics, code can override 401/403, and no unified transport/serialization throwable entry exists.
  2. Public diagnostic path accepts arbitrary strings and only filters characters, so expanded UUID/query/private values can be logged if a caller misuses it.
  3. Contract tests do not actually issue/assert every endpoint, method, query, header and request body; change-password/logout and several body/error branches are missing.
  4. Confirm/grant/status request types allow server-invalid states and summary/page/mutation UUID/date boundaries lack domain validation.
  5. Analysis payload mapper rejects `is_mock=false`/unknown payloads, which would make valid future real results unusable.
- Minor: server OpenAPI omits list query parameters even though current views support them; fixture cannot detect future query drift.
- Fix rounds: 1 completed (original implementer)
- Fix commit: `d5e8d22a1381fd35d2f8396fb47bb0e5b12cf2d0`
- Fix evidence: 20/20 tests; all 18 Retrofit methods executed through MockWebServer with method/path/query/header/body assertions; real error/status/non-JSON cases, controlled endpoint diagnostics, invalid request/domain boundaries and non-mock/unknown payload fallbacks covered; OpenAPI regenerated identically; full and cold strict gates passed.
- Scoped re-review: original 5 Important addressed; one new Important introduced.
- New Important: catch-all `Throwable` mapping turns coroutine cancellation and programming exceptions into `Malformed`, risking swallowed cancellation and hidden defects.
- Remaining Minor: list query parameters are absent from server OpenAPI; preview request body-size assertion is implicit.
- Fix rounds: 2 completed (original implementer)
- Second fix commit: `5a1a2a0395017b9018bfc5935d39dc9dc0c300da`
- Second fix evidence: cancellation, unknown RuntimeException and Error RED cases added; mapper now rethrows original cancellation/unknown throwables and maps only HttpException/IOException/SerializationException; preview body-size assertion added; 23/23 full tests and all gates passed.
- Second scoped re-review: accepted; cancellation/unknown throwable propagation and preview body assertion addressed, no new Critical/Important/Minor.
- Remaining historical Minor: server OpenAPI omits list query parameters supported by views.
- Status: Complete

### Task 4 — 实现认证、强制改密和 Keystore 会话

- Status: In review
- Base: `5a1a2a0395017b9018bfc5935d39dc9dc0c300da`
- Implementer: `/root/android_task4_impl`
- Implementation commit: `48f7efe9e306295ac99646bcaa4a1e16aafad9af`
- Evidence: 39 JVM tests and API34 connected 6/6 passed; 20 concurrent 401s caused one refresh, stale generation zero; Keystore AES-256-GCM non-exportable key, encrypted 0600 file and corruption/version/key-loss cleanup verified on emulator; full/cold/offline/security gates passed.
- Reviewer: `/root/android_task4_review`
- Review result: Not accepted — 4 Important, 0 Critical, 2 Minor.
- Findings:
  1. Production OkHttp/API wiring never routes protected calls through `RefreshCoordinator`; 401 recovery exists only in direct unit tests.
  2. Vault file/state operations and login/refresh/logout are not linearized; a refresh can republish tokens after logout.
  3. Storage/contract exceptions can escape before cleanup, leaving Loading state or a recoverable refresh ciphertext.
  4. Password-change/logout cancellation can cancel the suspending cleanup in `finally`, leaving local credentials alive.
  5. Minor: empty in-memory access does not remove a pre-existing Authorization header.
  6. Minor: concurrent refresh failure can emit repeated SessionExpired events.
- Fix rounds: 1 completed (original implementer)
- Fix commit: `9219d5172e6fe96d90e27dcc1e286f6fe5e1b234`
- Fix evidence: real production graph 20 concurrent 401s -> one refresh and one retry/request; single snapshot epoch/CAS blocks login-refresh-logout resurrection; failed refresh emits one clear/event; cancellation and post-commit cancellation leave no credentials; API34 connected 8/8 and 51/51 unit plus all security/offline gates passed.
- Scoped re-review: original 4 Important and 2 Minor addressed; one new Important introduced.
- New Important: real vault self-invalidation advances epoch before throwing, so coordinator CAS cleanup returns false and suppresses SessionExpired, leaving repository/UI authenticated with no token.
- Remaining Minor: cached old failed epoch can mask a new login token; password-change operation briefly becomes Idle before cleanup; debug network audit references a nonexistent AGP 9 release manifest path and can falsely pass.
- Fix rounds: 2 completed (original implementer)
- Second fix commit: `7d9c0deb20e2a5af19815b2482741aa591965ec8`
- Second fix evidence: real AndroidTokenVault + production graph four self-invalidation cases emit exactly one SessionExpired and log out; explicit invalidation ownership replaces epoch guessing; stale failure cache/new login, password-change Loading window and AGP9 fail-closed network audit fixed; 53/53 JVM and API34 connected 12/12 plus all gates passed.
- Second scoped re-review: normal invalidation path and all three Minor addressed; one ownership TOCTOU Important remains.
- Remaining Important: after ownership check, event emission/listener state update is not linearized with login/logout; an old invalidation can overwrite a newly authenticated state or emit a stale expiry notice.
- Fix rounds: 3 completed (original implementer)
- Third fix commit: `86d2a8e7218296f8407105499cdc4db9ee8f9d5a`
- Third fix evidence: shared `SessionLifecycleArbiter` linearizes vault mutations, auth state and invalidation claim; real API34 login/logout TOCTOU REDs now pass; ordered single-consumer lifecycle channel survives three slow-consumer events without replay; 54/54 JVM, connected 14/14 and all gates passed.
- Third scoped re-review: accepted; ownership TOCTOU closed, no new Critical/Important or blocking Minor.
- Status: Complete

### Task 5 — 实现 Room 与账户隔离的加密草稿

- Status: In review
- Base: `86d2a8e7218296f8407105499cdc4db9ee8f9d5a`
- Implementer: `/root/android_task5_impl`
- Implementation commit: `4be1cba26e19bf4cc9a00d780c8e9ab12332c1f4`
- Evidence: core connected 20/20, full connected 34/34, JVM 54/54; Room v1 schema/MigrationTestHelper, account composite FKs, VEF1 seek/tamper/poison tests, direct non-exportable per-account Keystore master and full cold/offline/security gates passed.
- Reviewer: `/root/android_task5_review`
- Review result: Not accepted — 6 Important, 0 Critical, 5 Minor.
- Findings:
  1. Encrypted file paths lack an internal hashed account namespace, allowing cross-account overwrite/DoS for the same logical path.
  2. Direct per-chunk AndroidKeyStore master use is not operationally equivalent to the planned wrapped software media master on mainland Android 10 devices and lacks key lifecycle semantics.
  3. Critical value constraints exist only in Kotlin constructors; raw SQL and DAO update methods can persist invalid values because SQLite has no checks/triggers.
  4. Inserts and production wiring are not bound to the currently authenticated account; AppContainer exposes the whole database.
  5. UploadJob lacks callback-receipt/confirm and overall safe checkpoints required for Task 10 restart recovery.
  6. Same-path writers and post-move failures violate reliable atomic publication semantics; non-atomic fallback and missing directory sync can report failure after destructive replacement.
- Minor themes: DataSource listener exception leaks reader; sync encryption lacks dispatcher boundary; intermediate directory permissions incomplete; cross-account crypto test can fail only because B key is missing; constraint tests do not exercise raw SQLite.
- Fix rounds: 1 completed (original implementer)
- Fix commit: `38fdedf` (`强化患者草稿隔离与加密存储`)
- Fix evidence: account-hashed immutable namespace, VMK1 Keystore-wrapped software master, versioned SQLite triggers/raw-SQL constraints, authenticated lease facade, full upload checkpoints and fail-closed atomic publication implemented; API34 connected 47/47, core 33, strict 48 tasks and all gates passed.
- Scoped re-review: 3 of 6 Important addressed; 4 Important remain.
- Remaining Important:
  1. Authenticated storage scope uses low-entropy/reusable login ID although `/patient/me` provides stable patient UUID.
  2. Per-chunk `SecretKeySpec` clones cannot actually be destroyed and active readers survive account-key destruction.
  3. Triggers allow negative confirm timestamps and lack cross-column/account idempotency-key uniqueness.
  4. Facade validates lease only at call entry; in-flight reads/writes can complete after account switch.
- Fix rounds: 2 completed (original implementer)
- Second fix commit: `2bc3617` (`绑定患者UUID并强化存储撤销`)
- Second fix evidence: login/restore resolve stable patient UUID; Wipeable key and active-reader revocation; complete timestamp/idempotency triggers; account-incarnation facade linearized with auth arbiter; JVM/full connected 54/54/core 40 and all cold/security gates passed.
- Second scoped re-review: not accepted — 2 Important, 0 Critical; stable patient UUID、可销毁密钥/reader、账户切换线性化均已解决，但旧 v1 trigger 正文不会被 `CREATE TRIGGER IF NOT EXISTS` 替换，且 refresh 返回 `must_change_password=true` 时未撤销已有 storage lease。
- Residual Minor: `UUID.fromString` 接受非 canonical 缩写；API 29 实机覆盖仍是环境门禁；长 I/O 持有会话 mutex 可能延迟切号；极端 canonical-path 异常未统一包装。
- Fix rounds: 3 completed (original implementer)
- Third fix commit: `66cea0c` (`升级数据库约束并同步改密刷新`)
- Third fix evidence: 旧 v1 同名 trigger 重开替换测试与 must-change refresh/UUID canonical RED 均稳定复现；事务化 DROP→CREATE→sqlite_master 正文校验、refresh 状态同步/lease 撤销和严格 UUID 校验完成。JVM 62/62、API34 core 41/41、full connected 55/55，Room schema、assemble/lint、无 GMS/权限/网络/release、全新缓存 strict 81/81 与 offline strict 全部通过。
- Third scoped re-review: not accepted — trigger 事务升级、must-change refresh 和 canonical UUID 均已解决；仍有 1 Important：患者 A 的迟到 refresh 在 CAS 被患者 B 新登录 supersede 后被包装为普通 Success，导致 A 的旧受保护请求可能使用 B token 重试。另有 1 Minor：未故障注入验证 trigger DROP 后 CREATE 失败时旧六项完整回滚。
- Fix rounds: 4 completed (fresh implementer `/root/android_task5_fix4`)
- Fourth fix commit: `d6a0a1e` (`阻止旧会话跨账号重试`)
- Fourth fix evidence: 新增 epoch 绑定的 `Superseded`/`SessionChangedException`；A→B、logout 后同/异账号登录、迟到失败、不可证明同源代际均不再复用当前 token，normal/must-change 与 20 并发单次 refresh 保持安全。JVM 65/65、API34 core 41/41、full 55/55、全部常规门禁、全新缓存 strict 109 tasks 与 offline strict 均通过。
- Fourth scoped re-review: not accepted — CAS 前被新登录 supersede 已解决，但仍有 1 Important：A refresh CAS 成功并释放锁后、实际 retry 的 AuthInterceptor 读取 token 前，B 可登录，使 A 请求携带 B token；source/terminal epoch 未绑定到实际请求头线性化点。1 Minor：`SessionChangedException` 未进入 AuthRepository 安全错误映射。
- Fix rounds: 5 completed (fresh higher-capability implementer `/root/android_task5_fix5`; final Task 5 fix round)
- Fifth fix commit: `5988d0e` (`绑定刷新重试凭据与会话代际`)
- Fifth fix evidence: refresh-success retry 使用请求级捕获 token+terminal epoch，AuthInterceptor 在线性化点 fail-closed，network interceptor 一次性发送守卫阻止 redirect/follow-up 复用；登录/restore 会话变更安全映射完成。生产 graph TOCTOU 与 302 RED 均复现后转绿；JVM 69/69、API34 core 41/41、full 55/55、assemble/lint 101 tasks、全部安全门禁、fresh strict 111 tasks 与 offline strict rerun 111/111 通过。
- Fifth scoped re-review: accepted — 0 Critical, 0 Important, 0 Minor；request-level retry token/epoch binding、拦截器线性化点、一次性 physical-send guard、异常映射和并发/改密/lease 语义均通过复核。审查定向 JVM 45/45、完整 JVM 69/69；Task 5 可以关闭。
- Status: Complete

### Task 6 — 实现治疗进度首页、曲库和两标签主页面

- Status: In review
- Base: `5988d0ec08ff0bb08dbd48384049bdbe3a63bd39`
- Implementer: `/root/android_task6_impl`
- Implementation commit: `71899382717b2dceed27f1f5523a65e009617750`
- Evidence: 直接通过 Pencil MCP 只读核对 `pUYpg`/`f004p` 和设计变量；PatientRepository、固定20条自有分页、失败保留旧列表、最新搜索 generation+Mutex、治疗进度/无计划禁用、患者终身汇总、双标签与详情返回完成。JVM 76/76、API34 connected 58/58、assemble/lint、Room schema、无GMS/权限/网络/release、fresh strict 与 offline strict 57/57 均通过。
- Visual evidence: `app/build/reports/screenshots/catalog-390x844.png` 与 `profile-390x844.png` 为 390×844，人工对照 PEN 无裁剪；首页 UI 负向测试保证无“今日推荐/待上传/上传失败/提醒”。系统栏/手势避让与 PEN 内绘 chrome 有轻微平台差异，图标使用现有 Compose 能力近似；历史列表留给 Task 11。
- Reviewer: `/root/android_task6_review`
- Review result: Not accepted — 0 Critical, 3 Important, 2 Minor.
- Important findings: (1) 全局 Patient/Song cache 未绑定 `AuthenticatedAccountLease`，A→B 换号可泄漏资料/计划并错误放行训练；(2) `/patient/me` Loading/Error/NoPlan 混为 null，失败误报联系医生且无重试，Profile 同样静默空值；(3) SongRepository refresh/retry 读取关键词与提升 generation 分属两次锁，仍可由旧操作覆盖最新搜索。
- Minor findings: pending count 生产链路缺真实 A/B/终态/旧 lease 测试；设置按钮和底栏语义/Role.Tab、歌曲卡边框等可访问性/视觉细节不完整。
- Visual review: Pencil MCP 与本地两张 390×844 截图对照通过，无裁剪，首页无提醒 Banner，双标签正确；仅系统栏、图标近似和细边框属轻微差异。
- Fix rounds: 1 completed (original implementer)
- Fix commit: `786ab40` (`修复患者缓存隔离与加载状态`)
- Fix evidence: Patient/Song cache 绑定不透明 lease+incarnation并在撤销时同步清空，迟到结果用 lease+generation 裁决；Patient/Profile 四态与重试、Song 单临界区 generation、真实 Room pending A/B/终态/旧lease、Role.Tab/设置路由/边框和取消收尾完成。JVM 85/85、API34 connected 63/63、全部构建/安全/release/schema门禁、fresh strict 与 offline strict 57/57 通过；两张390×844截图复核无裁剪。
- Scoped re-review: not accepted — 原3 Important的时序隔离/状态机/搜索临界区与Room/Role.Tab/设置路由均已解决；仍有1 Important：`/patient/me`发布边界未比较响应 `profile.id` 与 lease `patientId`，串号响应会被当前账户发布并影响训练权限。
- Residual Minor: 搜索按钮缺中文contentDescription，若干40dp触控目标低于48dp；PEN无stroke的两张彩色统计卡被误加灰色边框。
- Fix rounds: 2 completed (original implementer)
- Second fix commit: `ad200ca` (`校验患者身份并完善触控语义`)
- Second fix evidence: `/patient/me`发布在session lease保护+repository同步临界区共同校验lease identity、generation和profile UUID；mismatch不发布、不替换旧安全资料且不泄露UUID。搜索/设置/重试48dp触控与中文语义完成，彩色统计卡移除错误边框。JVM89/89、API34 connected64/64、全部常规门禁、fresh/offline strict57/57及390×844截图复核通过。
- Second scoped re-review: accepted — 0 Critical, 0 Important, 0 Minor；profile UUID fail-closed发布、锁序/取消、48dp语义与PEN视觉均通过复核。定向JVM20/20、API34 Compose7/7；Task6可以关闭。
- Status: Complete

### Task 7 — 实现歌曲私有试听、准备检查和在线创建会话

- Status: In review
- Base: `ad200cafbfbc235a22ff7066b8f98bff19bd0823`
- Implementers: `/root/android_task7_impl`（环境刷新前）与 `/root/android_task7_resume`（接管验证/提交）
- Implementation commit: `92a1082ac8c65cc2310f398bc4b0fca85e86786f`
- Evidence: PEN `f0021` 只读对照；歌词仅“歌词暂未提供”；Preflight硬门禁/耳机warning、Media3 READY与私有URL一次刷新/seek/release、稳定creationKey/single-flight/超时恢复/A-B与取消迟到、一次性导航完成。Room v2不重建v1三表，仅新增preparation_drafts和canonical triggers；preview URL/expiry不落库。JVM104/104、API34 connected71/71、debug/release assemble+lint105 tasks、全部安全/release门禁、fresh/offline strict109/109及390×844截图通过。
- Environment note: API29/国产真机与实时服务联调保留为Task13外部门禁。
- Reviewer: `/root/android_task7_review`
- Review result: Not accepted — 0 Critical, 5 Important, 0 Minor.
- Important findings: (1) IO/UI协程以非原子`value.copy`写PreparationUiState，且最后一次countdown后无operation/lease复核，可能覆盖状态或迟到导航；(2) Preview只有prepare/seek，无实际play/pause和试听控件；(3) Preview Error被UI显示为缓冲且无重试；(4) 无Lifecycle resume/网络观察，系统设置授权或离线→在线后门禁不恢复；(5) pending draft发现只依赖SavedStateHandle，异常终止丢失saved state时会创建新draft/key。
- Confirmed good: PEN 390×844/唯一“歌词暂未提供”、Room v2迁移/触发器/无URL持久化、HTTP401/403 cause-chain识别、JVM104/104、connected71/71和无GMS均通过。
- Fix rounds: 1 completed (takeover implementer `/root/android_task7_resume` because original process was lost during environment refresh)
- Fix commit: `30b4e1a` (`修复演唱准备恢复与试听状态`)
- Fix evidence: Preparation state/reducer与最后tick租约复核、真实试听play/pause/错误重试、Lifecycle+网络恢复、Room v3 active状态与DB发现恢复完成；HANDOFF_PENDING在Task8 ack前保持可恢复。定向JVM21/21、API34 13/13；全JVM113/113、connected75/75；Room v1/v2→v3、唯一active/崩溃恢复/schema、390×844/PEN、构建/lint/release/安全与严格在线/离线门禁通过。
- Scoped re-review: not accepted — 原5 Important均已闭环；仍有1 Important：PreviewPlayer的release/play/pause使用engineLock，但onEngineEvent/publishError使用另一Mutex，回调可在release后复活Buffered/Playing/Error；fake同步回调未覆盖真实Main/IO交错。
- Fix rounds: 2 completed (takeover implementer)
- Second fix commit: `47f8fba` (`统一试听播放器并发状态`)
- Second fix evidence: PreviewPlayer改为单一Channel actor，state/engine操作统一串行，grant/refresh/listener携generation，release停止接纳并排队终结；Exo操作Main.immediate顺序化。三类确定性RED后转绿，额外覆盖迟到401、play/pause-release顺序和同步重入。Preview14/14、VM13/13、全JVM119/119、connected75/75、构建/lint/release/Room schema/全部安全与严格在线离线门禁通过。
- Second scoped re-review: not accepted — 单actor/generation/release隔离已解决；仍有1 Important：prepare调用方取消仅中断await，独立actorScope grant仍可获取URL并engine.load，较基线结构化取消回归。1 Minor：ViewModel.onCleared主线程调用runBlocking release等待队列，存在卡顿风险。
- Fix rounds: 3 completed (takeover implementer)
- Third fix commit: `d1875f3` (`修复试听准备取消与非阻塞释放`)
- Third fix evidence: PreparationToken恢复caller结构化取消，actor在token+generation线性化点前阻断迟到grant/event；cancel ack后原样传播CancellationException。release改为非阻塞admission close+actor终结，awaitReleased单独等待资源关闭，onCleared不阻塞。Preview19/19、VM14/14连续5轮；全JVM125/125、connected75/75、最终合并143 tasks、全部安全/release/strict在线离线与schema门禁通过。
- Third scoped re-review: not accepted — 普通caller取消与onCleared非阻塞已解决；仍有2 Important：(1) awaitReleased在engine.release异步投Main、commands.close/actorScope.cancel后立即完成，未等待真实player.release、actor退出及NonCancellable grant子任务；(2)已有active A时，预取消的Prepare B在actor处理会先cancel A再跳过B，留下A状态/mediaLoaded但A token失效。
- Fix rounds: 4 completed (fresh higher-capability implementer `/root/android_task7_fix4`)
- Fourth fix commit: `a311f90` (`等待试听真实释放并保护预取消`)
- Fourth fix evidence: awaitReleased等待tracked grant/refresh、Main线程真实Exo release、actor退出与owned scope关闭；completion由actor finalizer发布。Prepare B先原子claim再替换A，预取消B不影响A。确定性RED后Preview24/24、JVM130/130、connected75/75、构建/安全/release/fresh/offline strict111/111与schema门禁通过。
- Fourth scoped re-review: accepted — 0 Critical, 0 Important, 0 Minor；awaitReleased真实资源/子任务/actor终结与A/B预取消接管均闭环，定向JVM38/38；Task7可以关闭。
- Status: Complete

### Task 8 — 实现前摄同步录制、单麦克风约束和音轨抽取

- Status: In review
- Base: `a311f909080d6511ba52e0bd8fb8cb5478337710`
- Implementer: `/root/android_task8_impl`
- Implementation commit: `1d8069c6b2539cb1005987ae82dbbc23103bedf7`
- Evidence: Pencil `f0036` 对照，禁用音准/歌词文案落实；CameraX前摄Preview+VideoCapture唯一`withAudioEnabled`、Start后伴奏、单actor事件、主线程位置快照；状态机/取消发布屏障、可回滚handoff、orphan立即清理；MediaExtractor/Muxer无损AAC抽取、真实AVC+AAC fixture、深校验/0600；账户双密文+Room原子发布/回滚完成。JVM162/162、API34 connected89/89、debug/release assemble+lint、无GMS/权限/网络/release/schema/strict在线离线门禁通过。
- Internal review: 三轮只读审查后Ready=Yes；曾发现并修复播放归零、route dispose发布竞态、媒体深校验、CameraX事件顺序/权限、主线程位置、handoff泄漏、orphan清理与UI bounds等问题。
- Environment note: API29/国产Android10实体设备的真实前摄+麦克风/CameraX编码/厂商兼容与最终时长抖动保留Task13门禁。
- Reviewer: `/root/android_task8_review`
- Review result: Not accepted — 0 Critical, 4 Important, 1 Minor.
- Important findings: (1) RecordingViewModel ticker/事件在IO并发非原子`value.copy`，旧快照可覆盖Reviewable/导航/常亮；(2) RecordingCoordinator进入Finalizing/Interrupted后duration返回0，错误/取消草稿持久化0ms；(3) extractor接受任意video/*而Task8严格契约需AVC+AAC；(4)全局theme系统栏为浅色，Recording路由未切PEN深色系统chrome，测试只验792dp内容。
- Minor: 关闭按钮仅“×”无“关闭并取消录制”中文语义。
- Confirmed good: 前摄+唯一withAudioEnabled/无AudioRecord、Start后播放/rewind/handoff、CameraX actor、0600/AAC无损、B帧PTS、双密文Room事务/取消租约、用户覆盖文案、无GMS均通过。
- Fix rounds: 1 completed (original implementer)
- Fix commit: `2b5c9b90e8198ec3f7d190d070e5761f4ace5423` (`修复录制终态与沉浸式系统栏`)
- Fix evidence: VM 状态写入统一原子 update 并以 generation/Coordinator 双校验阻止旧 ticker 覆盖终态；Coordinator 冻结单调录制时长；提取器严格验证 MP4+AVC+AAC 并拒绝 HEVC；录制路由设置/恢复 `#06100B` 深色系统栏，API29 合成过渡以严格 ±4 实际像素轮询同步；关闭按钮具备中文语义、Role.Button 与 48dp 触控。JVM 165/165、API34 connected 91/91、构建/lint/schema/安全/fresh/offline 门禁通过；API29 全91中本轮 chrome 通过，剩9项为既有 Room SQL/固定尺寸兼容问题并转入后续门禁。
- Scoped re-review: accepted — 0 Critical, 0 Important, 0 Minor；原4 Important和1 Minor全部闭环。独立复审定向JVM26/26、API34媒体/页面/系统栏9/9、assembleDebugAndroidTest与diff-check通过。
- Status: Complete

### Task 9 — 实现本地回看、重录和中断草稿恢复

- Status: In progress
- Base: `2b5c9b90e8198ec3f7d190d070e5761f4ace5423`
- Brief: `.superpowers/sdd/2026-08-26-vocaease-android-client/task-9-brief.md`
- Implementer: `/root/android_task9_impl`
- Design reference: Pencil MCP `f005s`，仅复用顶部栏/350×192回放卡/色彩语言；用户裁决要求提交前回看不展示任何评分或分析。
- Known later gate: API29 既有4项Room迁移SQLite语法和5项固定390尺寸测试失败转入兼容门禁；Task9不得用扩大截图容差掩盖新增问题。
- Implementation commit: `29f72f905c8f1e6d8bf1876d4d0af22b57b38605` (`实现演唱回看与中断草稿恢复`)
- Evidence: REVIEW_READY/INTERRUPTED双密文回看、单Media3 player线性化切换；Room原子幂等入队且零网络；INVALID+reader revoke重录补偿；VRS1 0600/fsync/原子sidecar与24h真实AVC+AAC恢复；7天状态白名单清理及无网络无通知one-time WorkManager；真实Review路由与f005s绿白视觉完成。JVM178/178、API34 connected108/108、API29 Task9 45/45、assemble/lint/schema/安全/fresh/offline门禁通过。
- Visual evidence: `.superpowers/sdd/2026-08-26-vocaease-android-client/evidence/task9-review-390x844.png`（忽略目录，未纳入提交）；Pencil MCP只读复核`f005s`，页面不含评分/分析/禁用标识。
- Reviewer: `/root/android_task9_review`
- Review result: Not accepted — 1 Critical, 7 Important, 2 Minor.
- Critical: 完整准备态真实为 `HANDED_OFF`，重录尝试 `HANDED_OFF -> BOUND` 但生产 trigger 未允许；媒体先 INVALID/删除后第二事务必然失败，真实重录无法自愈，现有测试因未建立 PreparationDraft 而假绿。
- Important findings: (1) lifecycle/audio-focus/CameraX source inactive 错误直接清理本可解析片段；(2) invalid/expired/binding-mismatch recovery 先删 staging 再吞 DB 失败；(3) Media3 engine 回调读取可变 currentSourceId，source generation 过滤无效；(4) 当前账户扫描会在解析失败时删除其他账户损坏 sidecar/video；(5) cleanup worker 末次 lease 检查与 append 间可被登出/同患者重登竞态复活旧链；(6) 重录/删除 A 使用账户级 reader revoke，误中断同账户 B；(7) 视频/音频 tab clickable 语义仅40dp。
- Minor findings: exact 24h 与未来时间戳边界不安全；Review player release 不注销账户 lease listener，反复进页泄漏。
- Confirmed good: 回看UI/Pencil/禁词、零网络入队、核心JVM/API34/API29定向、无GMS与工作树检查均通过，但测试未覆盖上述真实时序。
- Fix rounds: 1 completed (original implementer)
- Fix commit: `b5baacc73ea69b4240d59da8a1ec08dfa1fcd6a6` (`修复草稿重录与恢复竞态`)
- Fix evidence: 生产trigger安全放行同绑定`HANDED_OFF→BOUND`完整重录；错误Finalize/lifecycle/audio-focus可解析片段双加密为INTERRUPTED；恢复DB-first与跨账户损坏sidecar非破坏；Media3不可变load代际；清理调度共享线性化屏障；路径级reader撤销；48dp tab；24h/future边界与listener注销完成。JVM183/183、API34 connected118/118、API29 Task9 38/38+完整链4/4、schema/constraint/migration12/12及所有构建/安全/fresh/offline门禁通过。
- Scoped re-review: not accepted — 原1 Critical/7 Important/2 Minor全部闭环，但修复引入1 Critical：焦点拒绝/ON_STOP可在Countdown/Starting前先置Interrupted并消耗stopIssued，随后`onCountdownFinished`仍start CameraX，迟到Started仍播放伴奏，后续stop/interrupt无法再次停止，形成终态后持续采集的隐私与资源风险。
- Fix rounds: 2 completed (original implementer)
- Second fix commit: `320763e1aece40403d3de60f1c820775896fb2ea` (`阻止录制终态后的迟到启动`)
- Second fix evidence: Countdown终态后不创建sidecar/start；Starting中断后迟到Started不play且按start generation补发有效stop；closed/Reviewable不复活；环境actor ready barrier覆盖同步focus拒绝与预先ON_STOP。JVM187/187、API34 connected118/118、API29 Task9 55/55、确定性测试连续5轮及所有构建/安全/fresh/offline门禁通过。
- Second scoped re-review: accepted — 0 Critical, 0 Important, 0 Minor；Countdown/Starting/late Started/close/start与环境ready barrier均线性化，未发现新问题。独立复审JVM30/30、API34 Task9 55/55、API29关键3/3、无GMS/diff/status通过。
- Status: Complete

### Task 10 — 实现七牛 V2 断点续传和持久上传状态机

- Status: In progress
- Base: `320763e1aece40403d3de60f1c820775896fb2ea`
- Brief: `.superpowers/sdd/2026-08-26-vocaease-android-client/task-10-brief.md`
- Implementer: `/root/android_task10_impl`
- User constraint: 中国大陆无GMS；仅主动上传的前台持续任务通知，不得产生治疗提醒、首页上传Banner或第三底部标签。
- Implementation commit: `8f1f281d293f3703783ad8a4ae034188f446bbf9` (`实现七牛断点续传与上传队列`)
- Evidence: 固定write-before-side-effect双媒体状态机、精确幂等键、可信callback等待、submit409裁决、Qiniu8.9.0 V2/HTTPS/FixedZone/FileRecorder、0600明文lease、Room v4/raw trigger、账户代际Worker、dataSync通知与自适应待上传页完成。JVM199/199、API34 connected129/129、API29 Task10 45/45及构建/安全/fresh/offline门禁通过。
- Environment note: 无真实七牛凭证/大陆bucket callback，留Task13；API29全项目仍有5项Task9固定viewport旧测试失败，Task10真实viewport门禁45/45。
- Reviewer: `/root/android_task10_review`
- Review result: Not accepted — 2 Critical, 8 Important, 1 Minor.
- Critical: (1) 每次Worker恢复生成随机明文文件，Qiniu8.9.0 sourceId=`filename_lastModified`变化，FileRecorder记录无法复用，实际从0上传；(2) ANALYZING清理无持久cleanup intent/checkpoint，崩溃在落盘/INVALID/逐文件/删行任一点后恢复会直接完成或因load要求VALID而永久卡住/残留。
- Important findings: callback/confirm/submit阶段暂停被transition拒绝；2/5/10/30 deadline未持久化；grant/confirm归属校验不完整；上传host仅验HTTPS可泄露token/媒体到任意域；删除无持久intent；attemptCount/nextRetryAt被丢弃；网络失败UI状态与FAILED立即重试错误；缺逐checkpoint崩溃与真实SDK恢复/竞态测试。
- Minor: 待上传页未明确“7天”提示。
- Confirmed good: 基础状态机/Qiniu配置/lease/Work/UI/Room/API29迁移/无GMS等定向测试通过，但高风险恢复未覆盖。
- Fix rounds: 1 completed (original implementer)
- Fix commit: `a908b20a79c6cb3c7014f7fef387642f4fa85b3f` (`修复上传断点与崩溃恢复`)
- Fix evidence: 真实Qiniu8.9.0稳定sourceId/recorder跨重建；Room v5持久cleanup/delete intent全checkpoint收敛；全阶段暂停/持久deadline+attempt；grant前/confirm后session detail绑定；官方host allowlist；WAITING_NETWORK/FAILED重试；SDK取消/迟到回调原子门；准确7天文案完成。末次还修复稳定历史mtime导致新lease被误判>1h，改用job目录活动时间。JVM207/207、API29 Task10 50/50、API34 connected143/143、assemble/lint/安全/offline门禁通过；最终commit的第二套fresh online/offline证据运行中。
- Scoped re-review: Not accepted — 2 Critical, 3 Important, 0 Minor. 原C2/I2/I3/I5/I6/M1闭环、C1生产逻辑闭环但缺真实剩余分片测试；新Critical：(1) orchestrator load验stage与Room checkpoint分两事务，pause可插入后被迟到grant/upload/progress/confirm/submit覆盖；(2)对象上传+可信callback成功但WAITING_RECEIPT落盘前崩溃，重启UPLOADING会重grant，而READY资产返回空URL使DTO失败/insertOnly阻止重传，永久retry。Important：SDK默认跟随跨域307/308且初始host allowlist不覆盖最终目标；WAITING_NETWORK立即重试未清deadline；真实SDK测试未证明只续剩余分片。
- Fix rounds: 2 completed (original implementer)
- Second fix commit: `9bbba2b7d098941b144b869da0ed106c9a7b831b` (`线性化上传暂停与对象恢复`)
- Second fix evidence: Room v6 operationVersion+全状态CAS阻止pause被所有迟到结果覆盖；UPLOADING恢复先detail，READY零grant/零SDK推进，UPLOADING同键resume；SafeQiniuRequestClient禁redirect；真实8.9.0 V2 5MiB分片在删除/重建0600明文后仅续剩余分片，内容换代不复用；WAITING_NETWORK manualRetry清deadline且0 delay。JVM208/208、API29 Task10 59/59、API34 connected153/153、v1/v4/v5→v6/rawSQL及构建/安全/fresh/offline门禁通过。
- Second scoped re-review: Not accepted — 1 Critical, 0 Important, 0 Minor. 原2C/3I全部闭环，但全局失败catch仍存在ABA：旧外部请求在pause(v+1)→resume(v+2)后返回contract/retryable/terminal失败，catch丢弃原expected并reload v+2，可成功写FAILED/WAITING_NETWORK覆盖新operation。现有测试只覆盖pause后不resume。
- Fix rounds: 3 completed (original implementer)
- Third fix commit: `c8a6877d6cfc63cbb00fda5a536a15b520f42468` (`绑定上传失败回调的操作代际`)
- Third fix evidence: 删除顶层load-latest失败写入，所有外部/验证失败以`ExpectedOperationFailure(expected,error)`绑定原operationVersion并只CAS expected；pause→continue后的旧contract/retryable/terminal以及grant-detail/Qiniu/confirm/detail/submit/conflict-detail失败均superseded，N+2完整记录与调度不变。JVM208/208、API29 Task10 63/63、API34 connected157/157、多阶段真实Room ABA16/16及构建/安全/fresh/offline门禁通过。
- Third scoped re-review: accepted — 0 Critical, 0 Important, 0 Minor；所有外部/验证失败均绑定正确不可变expected，progress使用串行writer最后持久版本，CAS=0以superseded取消旧worker且无副作用。独立验证JVM208/208、API34关键31/31（Room ABA16/16）、API29关键31/31、真实续传/redirect/Room v6/noGMS/diff/status通过。
- Status: Complete

### Task 11 — 实现分析同步、历史列表和演唱回顾

- Status: In progress
- Base: `c8a6877d6cfc63cbb00fda5a536a15b520f42468`
- Brief: `.superpowers/sdd/2026-08-26-vocaease-android-client/task-11-brief.md`
- Implementer: `/root/android_task9_impl/android_task11_impl` (fresh nested agent; coordinator `/root/android_task9_impl`)
- User result rulings: `is_mock=true`仅“演示结果”；false/null无标识；绝不“模拟分析/非临床结论”；缺分项“暂无单项评分”；仅真实time-series绘图。
- Ruling: 历史列表不新增持久表，直接按当前账户读取并合并既有 drafts/preparation_drafts/upload_jobs 与远端 sessionId；分析轮询另设最小 account-scoped checkpoint 实体，仅保存状态、generation、轮询步进、deadline 与版本，不保存结果 payload、私有 URL 或患者标识。原因：历史缓存可从两个可信来源重建，而跨进程轮询 deadline 必须独立持久；若裁决错误，代价是离线仅能看到未完成本地任务、已完成远端历史需联网重取。
- Implementation evidence (pending independent review): 历史以 sessionId 去重合并本地/远端状态，终态优先且绑定账户 lease/incarnation/generation；Room v7 新增最小 analysis checkpoint，v1 fresh constraints 与 v6→v7 迁移均验证。后台 WorkManager 使用 account/session 唯一 one-time 链与 10/30/60/300s 持久 deadline，无 foreground/无新通知；前台 3s 轮询、single-flight、terminal 停止、retry generation/409 裁决已接入 Task10 ANALYZING 恢复与新完成路径。严格 mapper 仅使用合法服务端总分与 pitch，不推导分项/百分位；私有视频 URL 仅存播放器内存，401/403 仅刷新一次并保留位置，退出等待真实 Media3 release 且拒绝迟到回调。
- UI/visual evidence (pending independent review): 通过 Pencil VSCode MCP 只读复核 `f004p`/`f005s`；“我的”最多四行历史与真实点击路由、结果页 350×192 视频卡/分项/pitch 卡完成，主导航仍仅“去唱歌/我的”两标签。API34 390×844 与 API29 真实 viewport 滚动测试无裁剪；production 扫描无“模拟分析/非临床结论/百分位/分享”。
- Verification evidence (pending independent review): JVM 259/259；API34 connected 173/173；API29 Task11/Room/Worker/UI 相关 42/42；debug/release assemble 与 lint 通过。精确权限、无 GMS、debug 网络安全正负例、release URL 正负例均通过；全新 Gradle 缓存 online `--dependency-verification strict clean` 全门禁通过，同缓存 offline strict clean 复跑通过。提交前审计额外以稳定 RED 捕获并修复了三类取消被误吞，以及 ResultRoute 在 player `null→ready` 重组时提前关闭 release scope/停止轮询的生命周期缺陷。
- Implementation commit: `1020748bd114f70e2f931ee28aa078fb9f40d6f5` (`实现演唱历史与结果回顾`)
- Reviewer: `/root/android_task10_impl/task11_fresh_reviewer`（首个 reviewer 在最终报告阶段连接失败，未计为正式审查）
- Review result: Not accepted — 0 Critical, 12 Important, 2 Minor.
- Important findings: (1) 同患者新 incarnation 无法接管旧 checkpoint；(2) account cancel 与 worker start/append 未线性化；(3) 429/5xx 被永久拒绝且前台已有内容时错误不可见；(4) 生产前后台未接共享 single-flight；(5) terminal FAILED 重试未原子创建新 generation/checkpoint/后台链；(6) ResultMapper 可选择旧 generation；(7) Media3 旧 source 的迟到错误可冒充当前 source；(8) 私有播放器吞取消且切换前不清旧媒体；(9) retry 适配器会拼出未经 mutation/detail 交叉验证的一致响应；(10) 最大重试文案不渲染且按钮仍可点；(11) 加载更多非原子导致并发跳页；(12) checkpoint 保存 raw patient UUID，违反仅最小 account scope 的 ledger 裁决。
- Minor findings: 上传失败与分析失败文案未区分；历史元信息、3px 得分描边与 pitch 卡底色存在非阻塞 Pencil 差异。
- Fix rounds: 1 completed (original implementer)
- Fix commit: 本次修复提交（`修复演唱历史与结果回顾审查问题`）
- Fix evidence: 12项Important均闭环：Room v8 checkpoint改为hash scope+incarnation proof并支持同患者新代际CAS接管，cancel/start/append以account epoch线性化；408/429/5xx与Retry-After统一可恢复，前台保留内容并显示安全错误；AppContainer前后台共享同一account/session single-flight；FAILED重试以更高generation原子创建或推进checkpoint并REPLACE后台链，409采用detail实际generation；mapper只取当前generation；Media3错误按不可变mediaPeriod/source归属，切换/取消先清旧媒体且迟到回调不复活；retry mutation/detail严格交叉验证task id、status、generation；第三次后隐藏重试并显示“暂时无法重新分析，请联系医生”；分页以mutex串行。Minor有效部分闭环：本地上传失败显示“上传失败”，服务端分析失败保持“分析失败”；历史元信息按“今天 HH:mm · m:ss”/“M 月 d 日 · m:ss”，得分描边3dp。Pencil所称pitch底色差异经MCP核对为设计稿同值`#EAF4EE`，未做无依据修改。末次设备/构建证据：JVM276/276；API34 connected177/177；API29 Task11 Room/schema/Worker/Compose/导航24/24；assembleDebug/Release、lintDebug/Release、安全权限/无GMS/debug网络/release URL正负合约均通过；隔离缓存online strict clean 112 tasks及同缓存offline strict clean 112 tasks均exit 0。API34 `f004p`/`f005s`截图均390×844并完成禁词检查；末次Pencil插件因VSCode transport断开未能重连，沿用本轮此前MCP节点核验与保存截图证据。未写正式接受结论。
