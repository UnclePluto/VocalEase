# 医生端与安卓端演唱体验实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复八项体验问题，交付真实患者音高、原唱/伴奏切换、正确取景及医生双图声音回放。

**Architecture:** 服务端持久化歌曲音高版本、会话媒体绑定及播放时间锚点；安卓使用单一 AudioRecord 同时提供实时音高与 AAC 录音，CameraX 只采视频并在结束后合并。医生端以患者音频为回放主时钟，共享分析器绘制双图，组合模式同步播放会话绑定伴奏。

**Tech Stack:** 沿用 Django/DRF/Celery、Kotlin/Compose/CameraX/Media3/Room、React/TypeScript/WaveSurfer/Web Audio/Canvas；服务端真实音频解码使用 FFmpeg，音高算法采用可测试的单声部 YIN，不引入新的声音分离模型。

**Spec:** [已批准规格](../specs/2026-10-06-singing-experience-design.md)。该规格覆盖同一录制产物链路，三组任务共享协议，以本计划串联；每个任务有独立验证和提交边界。

## 全局约束

- 所有说明与提交描述使用中文；正式文档存于 docs/superpower/。
- 不覆盖或纳入现有认证相关未提交改动。执行开始先检查工作区，按 using-git-worktrees 选择隔离目录；已有合适工作树可复用，未提交认证代码不能凭空视为已包含在新工作树。
- 试听默认原唱、演唱默认伴奏；医生仅提供“人声＋伴奏”“人声”。歌曲原唱为 song_source，患者声音为 singing_audio。
- Android 10+（minSdk 29），JDK 17；沿用当前锁定依赖、无 GMS 运行要求及严格依赖校验，不为音高图升级整个技术栈。
- 默认 PCM：48 kHz、单声道、16 bit；不支持时回退 44.1 kHz；音高帧约 40–50 ms、UI 约 20 Hz、白点延迟目标 ≤150 ms。
- 图中显示至少过去 2 秒及未来 6 秒；半音坐标 `69 + 12 * log2(frequency_hz / 440)`；静音/低置信度断线。
- 单调时钟每 5 秒校准；音视频及有效区间伴奏同步偏差目标 ≤100 ms。
- 参考区段 ≤100,000、正文 ≤10 MiB；时间整数毫秒、MIDI 0–127、置信度 0–1。播放元数据 ≤10,000 锚点、≤1,000 模式变化、正文 ≤1 MiB。
- 时间锚点随加密草稿持久化；不持久化私有 URL、播放凭证或逐帧图像。
- 旧客户端可省略 playback_metadata；旧会话缺可信同步依据时仅患者人声可播，不补造媒体绑定。
- 不改变无治疗计划可唱、有原曲和伴奏可唱的既有准入；验收歌曲必须具有真实参考音高，缺数据不能报完成。
- 临床评分、嗳气事件及 SNR 的模拟标识保留；双图、音量及实时患者 F0 使用真实采样。
- 引导“请让嘴部、下颌和颈部位于引导区域内”；390×844、小屏和字体放大可操作，触控区 ≥48 dp。

## 评审重点

1. 创建会话后歌曲被换轨或删除：旧会话继续绑定旧版本，授权失效明确报错，不能播放另一个文件。测试归任务 3。
2. 缓冲与模式连续快速切换：录像持续、歌曲时钟不冒进，最终选择生效；锚点能表达停顿。测试归任务 4、7、10。
3. 用户退出账户恰逢音频合并/上传：不得发布到新账户，临时文件被回收，重试元数据一致。测试归任务 6、7。
4. 有声但无稳定音高、静音及 CORS 失败：无假白点、无随机波形；静音和采样错误分别呈现。测试归任务 5、11。
5. 服务端参考音高更新恰逢录制开始：客户端用会话绑定版本，不能混用旧缓存与新歌曲。测试归任务 2、3、8。

## 文件与接口地图

- `server/apps/songs/reference_pitch.py`：区段合同；`reference_pitch_services.py`：版本发布、导入与任务；`reference_pitch_audio.py`：可信音频解码和 YIN；`reference_pitch_views.py`：三个接口。
- `server/apps/singing/playback.py`：会话媒体绑定及播放元数据合同；`playback_views.py`：会话歌曲授权；现有 models/services/serializers/schema 接入。
- 安卓 `core/media/SongPlaybackMode.kt`、`PlaybackMetadata.kt`：两种歌曲模式及时间锚点；`PitchDetector.kt`：纯算法；`MicrophonePcmCapture.kt`、`PatientAudioEncoder.kt`、`PatientRecordingCapture.kt`、`RecordedAvMuxer.kt`：采集、编码、统一结束与合并。
- 安卓 `feature/training/ReferencePitchRepository.kt`、`SingingPitchTimeline.kt`、`LowerFaceNeckGuide.kt`：版本化参考、音高 UI 和引导；现有播放器、录制、草稿、上传接口接入。
- 医生 `PatientDataListPage.tsx`：患者入口；`PlaybackTimeline.ts`：录音时间至歌曲时间转换；`PlaybackClock.ts`：三媒体同步；`PatientAudioAnalyser.ts`：单一采样；`LiveVoiceBoard.tsx`、`VoiceBoardRenderer.ts`：指标及双图。
- 下文安卓生产文件路径统一前缀 `android-patient/app/src/main/java/com/vocaease/patient/`；单元测试前缀 `android-patient/app/src/test/java/com/vocaease/patient/`；设备测试前缀 `android-patient/app/src/androidTest/java/com/vocaease/patient/`。路径按这些前缀展开，不能新建到仓库根部。
- HTTP 路径沿用客户端 `/v1/...` 表述；真实服务端前缀为 `/api/v1/...`。新增接口继续沿用 data/request_id 信封及现有错误结构。

## 验证命令约定

在对应工作目录执行：服务端 `uv run --frozen pytest <测试文件> -q`；前端 `pnpm test --run <测试文件>`；安卓 `./gradlew --offline --dependency-verification strict :app:testDebugUnitTest --tests '<测试类全名>'`。任务中的代码片段为测试核心断言；每个测试文件在对应测试中创建具名场景变量，不把片段当作无需 setup 的完整测试。红阶段必须观察到行为断言失败或新增接口未实现；环境失败不算红阶段证据。

执行前先确认依赖、Python ≥3.13、Node/pnpm、JDK 17、Android SDK 和测试设备。当前 shell 默认 java 为 11、前端 node_modules 不存在，计划阶段不安装依赖；执行时选用已配置 JDK 17 并按锁文件准备依赖。各任务的提交只暂存列出的本次文件，中文提交消息见任务末步；新增迁移编号先核对相邻迁移，以下按当前仓库编号命名。

---

## 第一组：服务端媒体合同与真实参考音高

### Task 1: 参考音高版本、导入和读取合同

**Files:** 新建 `server/apps/songs/reference_pitch.py`、`reference_pitch_services.py`、`reference_pitch_views.py`、`migrations/0006_reference_pitch.py`（后三者同目录）；修改 `server/apps/songs/models.py`、`urls.py`、`serializers.py` 及 `server/apps/singing/schema.py`（现有歌曲信封定义位于此处）；测试 `server/apps/songs/tests/test_reference_pitch_api.py`。
**Interfaces:** 产出 `validate_pitch_document(value: object, *, duration_ms: int) -> dict`；`import_reference_pitch(*, actor, song_id: UUID, document: dict, expected_fingerprint: str) -> SongReferencePitch`；`read_reference_pitch(*, song_id: UUID, version: UUID | None = None) -> dict`。SongReferencePitch 包含版本 UUID、状态、歌曲 FK、输入资产/指纹、区段、来源，已发布版本不可变。

- [ ] **1. 写失败测试。** `test_reference_pitch_import_and_read` 断言合法 `{start_ms:0,end_ms:1000,midi_note:57,confidence:1}` 可导入且患者 GET 返回同版本；`test_reference_pitch_rejects_invalid_document` 参数化重叠、NaN、MIDI=128、负起点、超歌曲时长及超限正文均 400/413；`test_reference_pitch_permissions` 断言患者不能 POST，未授权用户不能 GET。

```python
assert response.status_code == 200
assert response.data["data"]["notes"][0]["midi_note"] == 57
assert invalid_response.status_code == 400
```

- [ ] **2. 运行红阶段。** 服务端命令运行该文件；确认 GET/POST 尚不存在或合同断言失败。
- [ ] **3. 实现模型及验证器。** 上述签名在对应文件实现，整数拒绝 bool，JSON 数值拒绝非有限值；来源必须包含真实输入指纹或标注出处；资源更新使用预期版本/指纹避免覆盖。
- [ ] **4. 接入接口。** GET patient/songs/{id}/reference-pitch/ 返回状态及 ready 内容；POST admin/songs/{id}/reference-pitch/ 导入校验标注；生成 POST 注册路由，执行实现归任务 2。更新 schema 并保留缺参考时可唱规则。
- [ ] **5. 验证。** 该文件全绿，并运行 `uv run --frozen pytest apps/songs/tests/test_manual_song_api.py apps/singing/tests/test_free_singing.py -q`；`uv run --frozen python manage.py makemigrations --check --dry-run` 返回无遗漏模型变更。
- [ ] **6. 提交。** 仅本任务文件，中文消息“增加歌曲参考音高版本与校验标注接口”。

### Task 2: 从真实歌曲人声生成参考音高与部署运行时

**Files:** 新建 `server/apps/songs/reference_pitch_audio.py`、`management/commands/check_reference_pitch_readiness.py`、`migrations/0007_reference_pitch_lease.py`（后两者同 server/apps/songs/）；修改 `models.py`、`reference_pitch_services.py`、`reference_pitch_views.py`、`tasks.py`、`resources.py`、`services.py`（均 server/apps/songs/）；修改 `deploy/docker/server.Dockerfile`、`server/vocaease/settings/base.py`；测试 `server/apps/songs/tests/test_reference_pitch_generation.py`、`server/tests/test_deployment_contract.py`。
**Interfaces:** 消费任务 1 的验证/发布；产出 `extract_pitch_notes(pcm: Iterable[float], *, sample_rate: int, duration_ms: int) -> list[dict]`、`generate_reference_pitch(*, song_id: UUID, expected_fingerprint: str, version: UUID) -> None`；生成状态追加 lease_token、lease_until、attempt、next_attempt_at；租约 60 秒，每 10 秒心跳，最多 3 次尝试，解码超时 120 秒。任务需失联恢复和有界重试，导入与生成使用同一版本防覆盖规则。

- [ ] **1. 写失败测试。** `test_generated_pitch_uses_verified_vocal` 对实际编码的 220 Hz 音轨断言约 MIDI 57；静音无区段；原曲不能冒充 vocal。`test_stale_generation_cannot_publish` 更换人声指纹后旧任务不能 ready；`test_retry_and_worker_lease` 重投、失联租约及超时可恢复；`test_readiness_lists_missing_tracks` 明确列出缺人声/标注的歌曲。

```python
assert abs(notes[0]["midi_note"] - 57) < 0.5
assert silence_notes == []
assert stale_version.status != "ready"
```

- [ ] **2. 运行红阶段。** 服务端运行 generation 测试文件，不能用缺 FFmpeg 的环境异常作为算法失败。
- [ ] **3. 实现解码与算法。** 通过现有可信资产读取，FFmpeg 本地管道解码单声道浮点 PCM，参数列表调用、超时、输出与内存上限及临时文件清理；YIN 周期差分和置信度过滤，再合并半音相邻帧。不下载模型、不把 mixed 原曲用普通 YIN 宣称为主旋律。
- [ ] **4. 接入异步任务和镜像。** 生成接口返回版本和 pending；Celery 发布在事务提交后；增加 `recover_reference_pitch_tasks()` 定期扫描 next_attempt_at 到期或 lease_until 失效的任务，在 `server/vocaease/settings/base.py` 注册周期调度，失败重试/租约恢复可观测；真实资源变更使当前参考失效。server 镜像提供 FFmpeg 且继续非 root 运行；readiness 命令支持按歌曲 ID 检查，输出 ID/状态，不输出私有 URL。
- [ ] **5. 验证。** generation/API/部署测试全绿，重建的服务端镜像 `ffmpeg -version` 可运行；失败任务明确 failed，旧版本仍可按 ID 读取。发布前由运营数据准备补齐验收歌曲，不生成虚假标注。
- [ ] **6. 提交。** 中文消息“实现真实歌曲人声音高生成与就绪检查”。

### Task 3: 演唱会话资源快照、元数据提交与授权

**Files:** 新建 `server/apps/singing/playback.py`、`playback_views.py`、`migrations/0007_session_playback.py`；修改 `server/apps/singing/models.py`、`services.py`、`serializers.py`、`schema.py`、`views.py`、`urls.py`、`selectors.py`；测试 `server/apps/singing/tests/test_playback_contract.py`、`test_submission_idempotency.py`；更新 `server/tests/test_openapi.py`。
**Interfaces:** 消费任务 1 版本；产出 `validate_playback_metadata(value: object, *, session: SingingSession) -> dict | None`、`authorize_session_song(*, actor, session_id: UUID, track: str, request_id: str) -> dict`。会话不可变绑定 source/accompaniment 的 MediaAsset FK、回执指纹、参考版本和歌曲轨偏移；`submit_session(..., playback_metadata: dict | None = None) -> SubmissionResult`。

- [ ] **1. 写失败测试。** `test_session_keeps_original_resources_after_song_edit` 换轨/删歌后快照 ID 不变；`test_submit_metadata_is_idempotent` 同键同体接受、异体 409；`test_old_submit_and_old_session` 无请求体仍可提交、旧会话无组合能力；`test_snapshot_grant_rechecks_permissions_and_receipt` 越权/回执变化拒绝；NaN、10,001 锚点、1,001 切换及 >1 MiB 拒绝。

```python
assert session.playback_source_asset_id == original_asset.id
assert repeated_submit.status_code == 200
assert changed_body_submit.status_code == 409
```

- [ ] **2. 运行红阶段。** 服务端运行合同及提交幂等测试，确认缺失快照/请求体验证造成失败。
- [ ] **3. 实现合同和迁移。** 新会话创建事务固定资源与 ready 参考版本，旧数据字段允许空、不回填当前歌曲；锚点 `{recording_ms,song_ms,track,playing,segment}` 验证录制时间单调，同区间歌曲时间不倒退。提交摘要覆盖全部元数据而非仅幂等键。
- [ ] **4. 接入授权及响应。** POST patient/singing-sessions/{id}/song-playback/?track=source|accompaniment、POST admin/singing-sessions/{id}/playback-accompaniment/ 返回 `{asset_id,url,expires_at}`。明细追加 playback 描述，授权按快照及权限验证；允许按会话绑定 reference_version 读取，不要求当前曲库还指向该版本。
- [ ] **5. 验证。** 合同/旧提交/API/OpenAPI 全绿，迁移检查无遗漏；另运行现有 singing/tests 整组，确认无计划可唱及分析幂等规则不变。
- [ ] **6. 提交。** 中文消息“固定演唱媒体版本并增加同步回放合同”。

## 第二组：安卓采音、切换与实时音高

### Task 4: 安卓媒体 DTO 和保留时间的模式切换

**Files:** 新建安卓 `core/media/SongPlaybackMode.kt`、`PlaybackMetadata.kt`、`core/network/dto/PlaybackDtos.kt`；修改 `core/network/VocaEaseApi.kt`、`SessionApis.kt`、`dto/SongDtos.kt`、`dto/SessionDtos.kt`、`core/media/PreviewPlayer.kt`、`RecordingPlaybackHandoff.kt`、`feature/training/PreparationProduction.kt`、`PreparationViewModel.kt`、`AppContainer.kt`；单元测试 `core/media/PreviewPlayerTest.kt`、`RecordingPlaybackHandoffTest.kt`、`core/network/PlaybackContractTest.kt`；更新 OpenAPI 及媒体 fixture。
**Interfaces:** 产出 `enum SongPlaybackMode { ORIGINAL, ACCOMPANIMENT }`，wire 映射 source/accompaniment；`PlaybackAnchor(recordingMs:Long,songMs:Long,track:SongPlaybackMode,playing:Boolean,segment:Int)`；`PlaybackMetadata(schemaVersion:Int,sampleRate:Int,sourceAssetId:String,accompanimentAssetId:String,referenceVersion:String?,anchors:List<PlaybackAnchor>,modeChanges:List<ModeChange>)`，ModeChange 包含 recordingMs、track。扩展 `PreviewSession.switchMode(mode: SongPlaybackMode): Boolean`、`bindSession(sessionId: String): Boolean`、`PreviewGrantSource.fetch(songId: String, mode: SongPlaybackMode, sessionId: String?): PreviewGrant`；PreviewGrant 追加可空 assetId，既有歌曲试听响应允许缺该字段；会话授权中的 assetId 必须非空并匹配快照。已生效模式通过 StateFlow 暴露。

- [ ] **1. 写失败测试。** `previewDefaultsToOriginalAndSwitchKeepsPosition` 从 56,000 ms 切轨仍 56,000 且保留 playing；`lastSwitchWinsAndFailureRestoresOldTrack` 逆序授权及失败恢复；`handoffAlwaysStartsAccompanimentAtZero` 试听原唱也正确交接；`sessionUsesBoundMediaVersion` 已建会话授权不退回歌曲最新资源。DTO 拒绝非法元数据、旧响应仍可映射。

```kotlin
assertEquals(56_000L, preview.currentPositionMillis)
assertEquals(SongPlaybackMode.ACCOMPANIMENT, preview.activeMode.value)
assertEquals(0L, handedOff.currentPositionMillis)
```

- [ ] **2. 运行红阶段。** 安卓命令依次执行上述三个测试类，新增 fixture/API 尚未接入的失败必须与断言对应。
- [ ] **3. 实现 DTO 与受保护 API。** Kotlin DTO 对齐任务 3，更新所有 PatientApi 假实现和 OpenAPI fixture；模式贯穿授权刷新，绑定会话前按歌曲接口、之后按会话授权接口。
- [ ] **4. 扩展串行播放器与交接。** 模式请求带 generation，失败恢复旧媒体；仅 Ready 后提交有效模式。录制接口增加 `suspend fun switchMode(mode): Boolean` 和实际状态/位置快照；bindSession 后回到伴奏零点，准备完成才允许采集开始。
- [ ] **5. 验证。** 三类全绿，追加跑 PreparationViewModelTest、ProductionProtectedApiTest，确认账户切换撤销旧请求、加载失败不启动录制。
- [ ] **6. 提交。** 中文消息“支持试听与演唱原唱伴奏切换并保留进度”。

### Task 5: 实时患者音高检测和时间轴几何

**Files:** 新建安卓 `core/media/PitchDetector.kt`、`feature/training/PitchTimelineGeometry.kt`；单元测试 `core/media/PitchDetectorTest.kt`、`feature/training/PitchTimelineGeometryTest.kt`。
**Interfaces:** 产出 `PitchSample(recordingMs:Long,frequencyHz:Float?,confidence:Float)`；`PitchDetector.detect(pcm:ShortArray,sampleRate:Int,recordingMs:Long):PitchSample`；`pitchToMidi(frequencyHz:Float):Float`；`timelineX(noteMs:Long,positionMs:Long,widthPx:Float):Float`，固定轴位于宽度 1/4，覆盖 -2s 至 +6s。时间映射复用任务 4 的 PlaybackAnchor，不自行另造时间合同。

- [ ] **1. 写失败测试。** `detectsKnownPitchAtBothSampleRates` 48k/44.1k 的 110/220/440 Hz 误差 <半音；`silenceAndNoiseHaveNoPitch` 静音与确定性宽带噪声 frequencyHz=null；`harmonicRichVoiceAvoidsOctaveError` 强谐波仍选基频；`timelineMovesReferenceLeft` 位置推进 1 秒使同音符横坐标减少 width/8。

```kotlin
assertEquals(57f, pitchToMidi(220f), 0.05f)
assertNull(silence.frequencyHz)
assertEquals(width / 8f, oldX - advancedX, 0.1f)
```

- [ ] **2. 运行红阶段。** 安卓命令分别运行两测试类。
- [ ] **3. 实现纯检测器。** YIN 使用归一化差分及插值；处理窗口约 46 ms、有效检测范围初始 65–1,000 Hz；检测率/静音阈值集中为有名参数，用上述真实输入测试约束，不依赖 Android 类或在线服务。
- [ ] **4. 实现几何映射。** 半音共用坐标、静音断线、参考缺失稳定音域（MIDI 36–84）；参考有效时音域取其区段范围并加上下 3 半音，越界点钳制且不逐帧缩放。
- [ ] **5. 验证。** 两类全绿；记录目标设备检测计算耗时，耗时超过预算先优化采样/检测线程，不以延迟 UI 代替录音完整性。
- [ ] **6. 提交。** 中文消息“增加真实患者音高检测与滚动时间轴映射”。

### Task 6: 唯一麦克风采集、患者 AAC 与同步录像合并

**Files:** 新建安卓 `core/media/MicrophonePcmCapture.kt`、`PatientAudioEncoder.kt`、`PatientRecordingCapture.kt`、`RecordedAvMuxer.kt`；修改 `CameraXRecordingCoordinator.kt`、`RecordingCoordinator.kt`、`PrivateRecordingTempFiles.kt`、`RecordingStagingRecovery.kt`、`AccountScopedRecordingArtifactPublisher.kt`、`AppContainer.kt`；单元测试 `PatientRecordingCaptureTest.kt`、`RecordingCoordinatorTest.kt`、`CameraXRecordingCaptureTest.kt`（core/media）；设备测试 `core/media/RecordedAvMuxerTest.kt`、`PatientAudioCaptureTest.kt`。
**Interfaces:** 消费 PitchDetector；产出 `PcmBlock(samples:ShortArray,sampleRate:Int,firstSampleNanos:Long)`、`EncodedRecording(video:File,audio:File,sampleRate:Int,captureStartNanos:Long,effectiveStartOffsetMillis:Long,durationMillis:Long)`。MicrophonePcmCapture 唯一持有 AudioRecord；`RecordedAvMuxer.merge(videoOnly:File,patientAudio:File,output:File,videoStartNanos:Long,audioStartNanos:Long):MuxedRecording`，MuxedRecording 含有效时长及统一起点偏移。PatientRecordingCapture 实现现有 RecordingCapture，内部管理采集器和音高 StateFlow，CaptureEvent.Finalized 只在合并有效后发送。

- [ ] **1. 写失败测试。** `onlyOneMicAndBothFinalizeBeforeReview` 断言 CameraX audioEnabled=false、只有一个采音实例、任一端未结束不能 Finalized；`stopIsIdempotentAndAccountExitCancelsPublish` 重复停止及账户撤销不重复发布。设备测试真实 AAC/MP4 解码可读、各一条音视频轨及 ≤100 ms 对齐，失败文件清理。

```kotlin
assertFalse(cameraBackend.audioEnabled)
assertEquals(1, microphoneFactory.createCount)
assertTrue(measuredAvDriftMillis <= 100L)
```

- [ ] **2. 运行红阶段。** 安卓单元测试三类；设备测试使用已连接设备执行 `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.vocaease.patient.core.media.RecordedAvMuxerTest,com.vocaease.patient.core.media.PatientAudioCaptureTest`。
- [ ] **3. 实现采音与编码。** 音频线程读取约 40–50 ms PCM、实际硬件采样率优先 48k 再 44.1k；录音队列有界但不静默丢帧，背压/编码失败走 AUDIO 中断；音高线程接收复制样本并可淘汰旧帧。AAC-LC 初始 128 kbps、M4A，MediaCodec PTS 依据已采样数和单调时钟。
- [ ] **4. 实现视频结束与合并。** CameraX 禁用麦克风，记录实际开始时间；裁重叠区间、统一时间零点、保留旋转/镜像，视频直接复制 H.264，禁止复制第二音轨。合并后的有效视频原子替换 start(output) 指定的工作文件，CaptureEvent.Finalized 追加 EncodedRecording timing（保持旧替身兼容的默认值），默认 coordinator 传至 publisher；复用 Mp4AudioTrackExtractor/M4A 标识及发布校验；原始/合并临时文件及音频 sidecar 纳入 staging 恢复与明文清理，旧 staging 文件继续走原恢复路径。
- [ ] **5. 验证。** 单元和设备测试全绿；录制 10 秒、3 分钟及歌曲完整时长，各在首尾检测 AV 偏差。权限、路由断开、磁盘不足、相机失败、编码失败、中途退出走已定义中断；未通过真实编码/相机设备路径不能声明录像修复。
- [ ] **6. 提交。** 中文消息“统一患者麦克风采集并合并同步音视频”。

### Task 7: 加密草稿中的同步元数据及上传重试

**Files:** 新建安卓 `core/media/PlaybackMetadataRecorder.kt`；修改 `core/database/DraftEntity.kt`、`DraftDao.kt`、`AccountScopedDraftStorage.kt`、`VocaEaseDatabase.kt`、`core/media/RecordingCoordinator.kt`、`feature/upload/UploadStateMachine.kt`、`RoomUploadStore.kt`、`UploadOrchestrator.kt`、`VocaEaseUploadRemote.kt`、`core/network/SessionApis.kt`、`VocaEaseApi.kt`；新建 Room `app/schemas/com.vocaease.patient.core.database.VocaEaseDatabase/11.json`；单元测试 `core/media/PlaybackMetadataRecorderTest.kt`、`feature/upload/PlaybackMetadataUploadTest.kt`；设备测试 `core/database/PlaybackMetadataMigrationTest.kt`。
**Interfaces:** 消费 PlaybackMetadata/EncodedRecording，encoded timing 为任务 6 的合并有效起点，最终元数据加入会话绑定 sourceAssetId/accompanimentAssetId/referenceVersion；产出 `PlaybackMetadataRecorder.record(recordingMs:Long,songMs:Long,track:SongPlaybackMode,playing:Boolean):Unit`、`snapshot():PlaybackMetadata`；`AccountScopedDraftStorage.loadPlaybackMetadata(draftId:String):PlaybackMetadata?`；UploadRemote.submit 追加 `metadata:PlaybackMetadata?`。DraftEntity 仅增加加密元数据相对路径与版本，不存明文 JSON。

- [ ] **1. 写失败测试。** `bufferingProducesPausedSongAnchors` 录制推进 2 秒但 songMs 不变；`switchAndJumpStartCorrectSegments` 跳转不能破坏前一区间；`uploadRetryUsesIdenticalEncryptedMetadata` 应用重启后同键同体；`migrationTenToElevenPreservesOldDrafts` 旧草稿空元数据可上传；`accountExitRevokesMetadataRead` 旧账户数据不能流入新账户。

```kotlin
assertEquals(firstSubmission, retriedSubmission)
assertEquals(2_000L, lastAnchor.recordingMs - firstAnchor.recordingMs)
assertEquals(firstAnchor.songMs, lastAnchor.songMs)
```

- [ ] **2. 运行红阶段。** 安卓运行两个单元测试类及指定 Migration 设备类，核对缺元数据造成的失败。
- [ ] **3. 实现录制与落盘。** 开始、切换、缓冲、每 5 秒校准生成锚点；复用账户加密文件存储，checkpoint 和最终 publish 有恢复规则，元数据与媒体同一草稿生命周期。AV 裁切造成的有效零点偏移应用于所有锚点。
- [ ] **4. 接通 Room 和上传。** 数据库 10→11 迁移不销毁草稿；UploadRecord 保存元数据身份引用，提交阶段解密读取，DTO 对齐任务 3，所有接口替身更新。确认上传两个媒体后再提交元数据，重试同键不得生成新的时间序列。
- [ ] **5. 验证。** 两类及迁移全绿；追加现有 UploadOrchestratorTest、RecordingStagingRecoveryTest 和录制发布设备测试，确认退出账户、半写文件及断电恢复无明文泄漏。
- [ ] **6. 提交。** 中文消息“持久化加密演唱同步元数据并接入上传”。

### Task 8: 准备/演唱控件、版本化音高块和鼻子以下至颈部取景

**Files:** 新建安卓 `feature/training/ReferencePitchRepository.kt`、`SingingPitchTimeline.kt`、`LowerFaceNeckGuide.kt`；修改 `PreparationScreen.kt`、`PreparationViewModel.kt`、`RecordingScreen.kt`、`RecordingViewModel.kt`（feature/training）、`ui/VocaEaseApp.kt`、`core/media/CameraXRecordingCoordinator.kt`、`AppContainer.kt`；单元测试 `feature/training/ReferencePitchRepositoryTest.kt`、`RecordingViewModelTest.kt`；设备测试 `feature/training/RecordingScreenTest.kt`、`SingingPitchTimelineTest.kt`、`CameraFramingTest.kt`。
**Interfaces:** 消费任务 1/3/4/5/6/7；产出 `ReferencePitchRepository.load(songId:String,version:String?):ReferencePitchState`；ReferencePitchState={Loading,Ready(version,notes),Unavailable,Failed}，notes 使用合同的毫秒/MIDI/置信度。RecordingUiState 追加模式、切换状态、参考音高及真实 PitchSample，界面操作回调触发 viewModel。

- [ ] **1. 写失败测试。** `recordingUsesSessionReferenceNotLatestCache` 并发更新不得换参考；`timelineShowsReferenceBlocksAndPatientDot` 220 Hz 白点与 MIDI57 横块同高度，推进一秒横块左移，静音白点淡出；`bothPagesHaveModeSwitchAndCorrectDefaults` 默认原唱/伴奏；`smallScreenAndFontScaleKeepStopReachable` 控件可达；取景测试匹配同次录像的预览标定目标。

```kotlin
assertEquals(sessionBoundVersion, state.referenceVersion)
assertTrue(patientDot.isVisible)
assertTrue(stopButton.isDisplayed())
```

- [ ] **2. 运行红阶段。** 安卓运行 ReferencePitchRepositoryTest、RecordingViewModelTest 及指定三组设备测试，确认当前进度条/完整脸椭圆造成断言失败。
- [ ] **3. 实现参考读取及音高 UI。** 缓存键绑定任务 1 版本/指纹；准备读取当前，会话开始固定绑定版本。约 20 Hz 状态采样，把 PitchSample 用任务 7 锚点转换至歌曲轴；Canvas 画横块、竖轴、白点、过去轨迹，缺参考显示明确空态但患者音高继续。
- [ ] **4. 接入控件及取景。** 两页显示原唱/伴奏选择，录制切换不可重启 capture；替换完整脸椭圆为下脸/颈部轮廓及规格文案。Preview/VideoCapture 统一 ViewPort、宽高比、rotation 和镜像，不能只改 FILL_CENTER 文案；相机绑定在 viewPort 有效后进行。保留实际录像，不硬裁为轮廓。
- [ ] **5. 验证。** 测试全绿；API29/API34 与目标真机在 390×844、小屏、字体1.3×截图验证；用标定目标实测预览/安卓回看/医生录像一致性，记录采样至白点延迟 ≤150 ms。再次通过 Pencil 只读核对可访问安卓画板。
- [ ] **6. 提交。** 中文消息“接通实时演唱音高与模式控件并统一颈部取景引导”。

## 第三组：医生患者数据与真实声音回放

### Task 9: 病人数据列表与详情返回

**Files:** 新建 `web-admin/src/features/singing/PatientDataListPage.tsx`、`PatientDataListPage.test.tsx`；修改 `web-admin/src/app/router.tsx`、`features/singing/PatientDataPage.tsx`、`layouts/AdminLayout.tsx`；如需复用筛选，新增 `web-admin/src/features/patients/patientListQuery.ts` 并从 PatientListPage.tsx 提取已有逻辑；测试 `web-admin/e2e/admin-workflows.spec.ts`。
**Interfaces:** 消费既有 `listPatients(query:PatientListQuery,signal?:AbortSignal)`、patientKeys、详情路由；产出 /patient-data 的真实列表，详情导航保存回返 URL（state.backTo），从账户管理进入详情仍使用其原入口。

- [ ] **1. 写失败测试。** `patientDataEntryLoadsAuthorizedPatients` 访问 /patient-data 断言患者姓名/查看数据，未出现占位；`filtersAndPaginationSurviveDetailReturn` 返回原 query/page；`errorsCanRetryAndEmptyListIsExplicit` 失败重试及无数据可读。

```typescript
expect(await screen.findByText(patient.name)).toBeVisible()
expect(location.search).toBe("?page=2&page_size=20&search=张")
expect(screen.queryByText("模块建设中")).not.toBeInTheDocument()
```

- [ ] **2. 运行红阶段。** 前端运行 PatientDataListPage.test.tsx，确认占位路由导致失败。
- [ ] **3. 实现列表。** 复用 API、URL 校验、远程医生选项及 DataTable；仅查看数据操作，行键患者 ID，加载与权限失败不显示旧用户缓存。
- [ ] **4. 接入路由和返回。** lazy 替换 Placeholder，详情返回尊重 backTo；保留病人管理原功能与侧栏选中状态。
- [ ] **5. 验证。** 新测试及 PatientDataPage.test.tsx、PatientListPage.test.tsx 全绿，浏览器从侧栏→列表→详情→明细→返回验证筛选连续。
- [ ] **6. 提交。** 中文消息“接通病人数据列表及筛选返回导航”。

### Task 10: 两种患者音轨回放及三媒体时间同步

**Files:** 新建 `web-admin/src/features/singing/PlaybackTimeline.ts`、`PlaybackTimeline.test.ts`；修改 `PlaybackClock.ts`、`PlaybackClock.test.ts`、`types.ts`、`api.ts`、`SingingDetailPage.tsx`、`SingingDetailPage.test.tsx`、`components/WaveformPlayer.tsx`、`WaveformPlayer.test.tsx`、`web-admin/src/styles/global.css`；更新 `web-admin/e2e/singing-detail.spec.ts`。
**Interfaces:** 消费任务 3 playback 响应，在 `types.ts` 定义与任务 4 一致的 TypeScript PlaybackMetadata、PlaybackAnchor、ModeChange 和 SongPlaybackMode；产出 `songTimeAt(recordingSeconds:number,metadata:PlaybackMetadata):{seconds:number,playing:boolean,track:SongPlaybackMode}|null`，默认不外推到有效区间之外。扩展 `createPlaybackClock(audio:HTMLMediaElement,video?:HTMLMediaElement|null,accompaniment?:HTMLMediaElement|null,metadata?:PlaybackMetadata)`，新增 `setMode(mode:'combined'|'patient'):void`；既有 Clock play/pause/seek/subscribe/destroy 保留。

- [ ] **1. 写失败测试。** `combinedModePlaysBoundAccompanimentOnly` 组合模式只有患者＋绑定伴奏，切人声主音频位置不变；`bufferAnchorPausesBackingAndKeepsVideoContinuous` 缓冲锚点不让歌曲轴推进；`videoTrackIsMutedAndControlsFollowMaster` 录像无重复音频；`legacySessionCannotInventBacking` 缺元数据组合禁用；`noStandaloneReplayCard` 操作仅归波形区。

```typescript
expect(patientAudio.currentTime).toBe(56)
expect(video.muted).toBe(true)
expect(accompaniment.pause).toHaveBeenCalled()
```

- [ ] **2. 运行红阶段。** 前端运行 Timeline、Clock、WaveformPlayer、SingingDetailPage 四测试文件。
- [ ] **3. 实现同步映射。** 根据锚点/segment 计算目标歌曲时间；患者音频主时钟，播放/暂停/结束/跳转/速率/缓冲同步到伴奏和静音录像。同步检查用 ≤100 ms 容差及有界帧循环，后台暂停/返回重新对齐，清理全部 listeners/定时器。
- [ ] **4. 接入页面及授权。** singing_audio 改为 patientAudio，绑定伴奏走会话 API；仅组合/患者模式，不用更换患者 audio 节点实现切换。删除独立回放卡片，在波形卡提供准备、播放、暂停、时间和跳转；沿用认证 epoch、签名刷新 fencing 和视频单独降级。
- [ ] **5. 验证。** 四文件全绿；用真实媒体浏览器跑 singing-detail.spec.ts 验证切轨前后患者波形不变、音频和录像不叠音、失败授权可恢复。
- [ ] **6. 提交。** 中文消息“修正患者人声音轨语义并实现同步伴奏回放”。

### Task 11: 真实患者采样、双图看板及播放恢复

**Files:** 新建 `web-admin/src/features/singing/components/PatientAudioAnalyser.ts`、`VoiceBoardRenderer.ts`、`LiveVoiceBoard.tsx` 及对应 `.test.ts`/`.test.tsx`；修改 `VisualizerAdapter.ts`、`VisualizerAdapter.test.ts`、`MetricPanel.tsx`、`WaveformPlayer.tsx`、`SingingDetailPage.tsx`、`web-admin/src/styles/global.css`、`web-admin/e2e/singing-detail.spec.ts`、`visual.spec.ts`；修改 `server/apps/media/views.py`、`deploy/nginx/default.conf`、`deploy/openresty.vocaease.conf` 仅在真实跨域采样证据要求时进行。
**Interfaces:** 产出 `PatientAudioAnalyser.attach(media:HTMLMediaElement):Promise<void>`、`sample():PatientAudioFrame`、`resume():Promise<void>`、`close():void`；PatientAudioFrame 含真实时域、频域、rmsDbfs、可信 pitchHz/null 和状态。`VoiceBoardRenderer.draw(frame:PatientAudioFrame,elapsedSeconds:number):void`、`freeze():void`、`reset():void`、`destroy():void`；原 VisualizerAdapter 收窄为该模块生命周期适配，不再调用 Waviz。

- [ ] **1. 写失败测试。** `pauseThenResumeRestartsRenderingWithoutSecondSource` 对当前暂停后不恢复复现；`bothPlotsUsePatientSamplesInEitherMode` 更换伴奏不影响双图输入；`silenceIsNotCorsErrorAndNoFallbackInventsSamples` 静音基线、不可采样错误区别且无随机回退；`mockSnrAndBurpsRemainLabeled` 模拟指标不会伪装成患者检测。

```typescript
expect(createMediaElementSource).toHaveBeenCalledTimes(1)
expect(drawnFramesAfterResume).toBeGreaterThan(drawnFramesBeforeResume)
expect(silentFrame.pitchHz).toBeNull()
```

- [ ] **2. 运行红阶段。** 前端运行 VisualizerAdapter、PatientAudioAnalyser、VoiceBoardRenderer 和 LiveVoiceBoard 四文件。浏览器证据分别用真实非静音、静音及错误授权文件，记录样本/AudioContext/CORS 错误而不输出签名 URL。
- [ ] **3. 实现共享分析器。** 单一患者 audio source 连接 analyser/destination，时域画患者波形、频域画极坐标；RMS dBFS、周期检测 F0，静音显示“—”。crossOrigin 在设置 src 前完成，签名过期与采样不可用错误分开；既有 source 禁止再次绑定。
- [ ] **4. 实现双图与指标布局。** 对齐 design.pen H6DDrc/YoqZJ 的色彩、深色面板与四项指标；连续时域分层、极坐标真实采样，暂停冻结/继续恢复/跳转重置/结束静止/卸载清理。SNR 无真算法显示“暂无真实数据”，有旧模拟值明确标模拟；删除不符合实现的 Waviz 标签。
- [ ] **5. 验证。** 测试全绿，真实浏览器重新播放及跳转双图正常；截图对照设计稿。若 CORS 确认失败，修改授权媒体服务/存储的窄范围策略并验权限，不能添加公开无鉴权代理。
- [ ] **6. 提交。** 中文消息“实现真实患者声音双图看板并修复暂停恢复”。

## 第四组：完整录制闭环与交付

### Task 12: 真实歌曲、同次录制及迁移回归验收

**Files:** 修改 `server/tests/test_full_singing_flow.py`、`web-admin/e2e/singing-detail.spec.ts`、`visual.spec.ts`、安卓设备测试 `e2e/PatientClosedLoopTest.kt`；新增 `docs/superpower/reports/2026-10-06-singing-experience-verification.md`；更新 `README.md`、`android-patient/docs/qa-device-matrix.md`、`qiniu-integration-runbook.md`、`deploy/README.production.md`。构建产物和媒体测试工件不纳入 Git。
**Interfaces:** 消费任务 1–11，产出逐项证据、未验项和可构建产物；不再新增用户功能或改变已批准合同。

- [ ] **1. 写闭环失败测试。** `test_patient_recording_playback_contract` 断言真实会话绑定和元数据可读；设备闭环断言先试听原唱、开始伴奏、真实录制中切原唱、音高点出现、保存/回看/上传；浏览器医生端同会话双模式＋双图正常。保留已有流程断言，不拿 mocked samples 替代实时采音证据。

```python
assert detail["playback"]["patient_audio"]["asset_id"] == uploaded_audio.id
assert detail["playback"]["accompaniment"]["asset_id"] == bound_accompaniment.id
assert detail["playback"]["metadata"]["schema_version"] == 1
```

- [ ] **2. 运行并记录差距。** 确认需要的接口和资产就绪；将缺参考歌曲、存储配置、真机路径逐项记入报告，不将配置/设备失败误判为代码缺陷或通过。
- [ ] **3. 完成数据及部署检查。** readiness 检查验收歌曲全部 ready，执行迁移和 FFmpeg 运行检查；新服务先部署、旧客户端兼容确认，再验证新版客户端。将媒体版本、同步元数据、加密草稿和上传重试说明写入既有文档，不自动部署生产。
- [ ] **4. 执行最终检查。** 服务端 `uv run --frozen pytest -q`、`uv run --frozen python manage.py makemigrations --check --dry-run`；前端 `pnpm test --run`、`pnpm typecheck`、`pnpm lint`、`pnpm build`、`pnpm e2e --project=functional`、`pnpm e2e:visual`；安卓 `./gradlew --offline --dependency-verification strict :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`、定向设备测试及已有 release 验证脚本。release 使用已配置真实 HTTPS 基址，不能把 example.invalid 的构建当上线验证。
- [ ] **5. 记录真实验收。** API29/API34、可用 API37 及目标国产真机：麦克风/相机、切换、首尾同步、完整时长、白点延迟、字体/取景、七牛回执与医生回放。至少一个真实演唱病例媒体（用授权测试账号）完成相同会话闭环；报告每项命令、结果、设备及未验证项。实体机不足时明确缺口，停止“全部完成”声明。
- [ ] **6. 提交与评审。** 中文消息“补充演唱体验闭环验证与升级说明”。完成一次全分支独立评审，重点查真实采样、单麦克风、时间戳和权限；修复明确问题后只重跑受影响检查。按用户选择的方式交付分支/补丁，不自动合并或上线。

## 依赖和执行方式

执行顺序：1→2→3→4→5→6→7→8→9→10→11→12。任务 5、9 可提前独立完成，但接口消费者必须以对应上游合同为准；不在本计划审批前开始代码实现。

推荐在当前会话由主代理逐项执行（superpowers:executing-plans），因为单一录音、草稿持久化和同步回放接口紧密关联，统一上下文减少返工。该方式最后仍安排独立评审。也可选择逐任务子代理实施与评审（superpowers:subagent-driven-development），检查更细，但每个任务和评审都需新上下文。

## 计划自检

- [x] 八项体验反馈都有对应任务：医生 1→9、2→10、3→11、4→3/10；安卓 1→4/8、2→1/2/5/6/8、3→4/8、4→6/8。
- [x] 参考区段、快照授权、实际音高、加密元数据、旧客户端、权限与存储、视觉及真机验收均覆盖。
- [x] 五项评审重点落实为任务内具体测试。
- [x] 共享类型/函数由生产任务定义，消费者复用，不另造模式或时间单位。
- [x] 每个任务包含失败测试、红阶段、实现、绿阶段与中文提交边界。
- [x] 无占位步骤；计划命令是执行要求，并未在规划阶段声称测试通过。
