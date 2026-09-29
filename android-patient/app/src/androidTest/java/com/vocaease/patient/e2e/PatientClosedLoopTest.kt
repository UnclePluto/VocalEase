package com.vocaease.patient.e2e

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.media.MediaExtractor
import android.media.MediaFormat
import android.system.Os
import androidx.camera.core.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.vocaease.patient.AppClock
import com.vocaease.patient.AppMonotonicClock
import com.vocaease.patient.AndroidAppContainer
import com.vocaease.patient.AndroidAppDependencies
import com.vocaease.patient.RecordingCaptureFactory
import com.vocaease.patient.QiniuUploaderFactory
import com.vocaease.patient.VocaEaseApplication
import com.vocaease.patient.core.cleanup.DailyDraftCleanupScheduler
import com.vocaease.patient.core.cleanup.DraftCleanupWorkContract
import com.vocaease.patient.core.media.AccountScopedRecordingArtifactPublisher
import com.vocaease.patient.core.media.CaptureEvent
import com.vocaease.patient.core.media.DefaultRecordingCoordinator
import com.vocaease.patient.core.media.PreviewState
import com.vocaease.patient.core.media.PrivateRecordingTempFiles
import com.vocaease.patient.core.media.RecordingCapture
import com.vocaease.patient.core.network.NetworkModule
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.core.database.DraftState
import com.vocaease.patient.core.database.MediaType
import com.vocaease.patient.feature.auth.AuthState
import com.vocaease.patient.feature.catalog.CatalogViewModel
import com.vocaease.patient.feature.history.AccountScopedHistoryLocalSource
import com.vocaease.patient.feature.history.AnalysisAndroidWorkContract
import com.vocaease.patient.feature.history.HistoryRepository
import com.vocaease.patient.feature.history.ProductionAnalysisSessionSynchronizer
import com.vocaease.patient.feature.history.ResultContentState
import com.vocaease.patient.feature.history.ResultViewModel
import com.vocaease.patient.feature.history.VocaEaseHistoryRemoteSource
import com.vocaease.patient.feature.training.AccountScopedLocalReviewStore
import com.vocaease.patient.feature.training.AccountScopedPreparationDraftStoreProvider
import com.vocaease.patient.feature.training.AccountScopedRecordingDraftGateway
import com.vocaease.patient.feature.training.AppConnectivity
import com.vocaease.patient.feature.training.DeviceReadiness
import com.vocaease.patient.feature.training.DraftRepository
import com.vocaease.patient.feature.training.LocalUploadQueueSignals
import com.vocaease.patient.feature.training.PermissionReadiness
import com.vocaease.patient.feature.training.PreparationEnvironmentMonitor
import com.vocaease.patient.feature.training.PreparationViewModel
import com.vocaease.patient.feature.training.RecordingViewModel
import com.vocaease.patient.feature.training.ReviewMediaKind
import com.vocaease.patient.feature.training.ReviewViewModel
import com.vocaease.patient.feature.training.SavedStatePreparationState
import com.vocaease.patient.feature.training.VocaEasePreparationSongSource
import com.vocaease.patient.feature.training.VocaEaseTrainingSessionCreator
import com.vocaease.patient.feature.upload.QiniuUploadRequest
import com.vocaease.patient.feature.upload.QiniuUploadResult
import com.vocaease.patient.feature.upload.QiniuUploader
import com.vocaease.patient.feature.upload.UploadMediaKind
import com.vocaease.patient.feature.upload.UploadCoordinator
import com.vocaease.patient.feature.upload.UploadWorkContract
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PatientClosedLoopTest {
    private val targetContext: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private lateinit var previewServer: MockWebServer
    private lateinit var context: Context
    private lateinit var testRoot: File
    private var container: AndroidAppContainer? = null
    private lateinit var application: VocaEaseApplication
    private lateinit var installedApplicationContainer: AndroidAppContainer
    private lateinit var clientCertificates: HandshakeCertificates
    private val testWorkNames = linkedSetOf<String>()

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false) }
        server.start()
        previewServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4")
                        .setBody(okio.Buffer().write(fixtureBytes("sample_avc_aac.mp4")))
            }
        }
        previewServer.start()
        application = targetContext as VocaEaseApplication
        installedApplicationContainer = application.container
        testRoot = File(targetContext.cacheDir, "closed-loop-container-${System.nanoTime()}").apply { mkdirs() }
        context = IsolatedAppContext(targetContext, testRoot)
        // 文件目录每轮隔离，但 AndroidKeyStore 按固定测试患者共享，必须同步清理。
        ChunkedAesGcmFileStore(context).destroyAccountEncryption(PATIENT_ID)
    }

    @After
    fun tearDown() {
        cancelAndAwaitTestWork()
        container?.close()
        cancelAndAwaitTestWork()
        installApplicationContainer(installedApplicationContainer)
        DailyDraftCleanupScheduler(application).replaceFor(
            runCatching { installedApplicationContainer.draftStorage.current() }.getOrNull(),
        )
        ChunkedAesGcmFileStore(context).destroyAccountEncryption(PATIENT_ID)
        testRoot.deleteRecursively()
        server.shutdown()
        previewServer.shutdown()
    }

    @Test
    fun 患者从首次登录到结果回顾走完整生产状态机() = runBlocking {
        val backend = ClosedLoopBackend(::fixture, previewServer.url("/fixture-preview.mp4").toString())
        server.dispatcher = backend
        val clock = AppClock { FIXED_NOW }
        val monotonicClock = AppMonotonicClock { FIXED_NANOS }
        val capture = FixtureRecordingCapture(::fixtureBytes)
        val qiniu = DeterministicQiniuUploader(::fixtureBytes)
        val dependencies = AndroidAppDependencies(
            apiBaseUrl = server.url("/").toString(),
            authenticatedClientFactory = { tokenVault ->
                NetworkModule.createAuthenticatedHttpClient(tokenVault).newBuilder()
                    .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                    .build()
            },
            clock = clock,
            monotonicClock = monotonicClock,
            connectivityFactory = { DeterministicConnectivity },
            recordingCaptureFactory = RecordingCaptureFactory { _, _, _ -> capture },
            qiniuUploaderFactory = QiniuUploaderFactory { qiniu },
        )
        val app = AndroidAppContainer(context, dependencies).also { container = it }
        installApplicationContainer(app)

        app.authRepository.login("MR-2026-001", "888888")
        assertEquals(AuthState.MustChangePassword, app.authRepository.state.value)
        assertTrue(app.authRepository.changePassword("888888", "New-password-2026"))
        assertEquals(AuthState.LoggedOut, app.authRepository.state.value)
        app.authRepository.login("MR-2026-001", "New-password-2026")
        assertEquals(AuthState.Authenticated, app.authRepository.state.value)

        val catalog = CatalogViewModel(app.patientRepository, app.songRepository, Dispatchers.Unconfined)
        catalog.refresh()
        assertEquals(4, catalog.state.value.treatmentProgress?.completedCount)
        assertEquals(12, catalog.state.value.treatmentProgress?.targetCount)
        assertEquals("春风", catalog.state.value.songs.single().title)

        val preview = app.mediaFactory.createPreviewSession()
        val savedState = SavedStatePreparationState(SavedStateHandle())
        val preparation = PreparationViewModel(
            songId = SONG_ID,
            songSource = VocaEasePreparationSongSource(app.patientApi),
            readinessSource = { durationSeconds, _ ->
                DeviceReadiness(
                    cameraPermission = PermissionReadiness.GRANTED,
                    audioPermission = PermissionReadiness.GRANTED,
                    availableBytes = 1_000_000_000,
                    durationSeconds = durationSeconds,
                    previewBuffered = true,
                    online = app.connectivity.isOnline(),
                    frontCameraAvailable = true,
                    headphonesConnected = true,
                )
            },
            environmentMonitor = app.connectivity.environmentMonitor(),
            preview = preview,
            storageProvider = AccountScopedPreparationDraftStoreProvider(app.draftStorage),
            sessionCreator = VocaEaseTrainingSessionCreator(app.patientApi),
            savedState = savedState,
            countdownTick = {},
            idFactory = { DRAFT_ID },
            clock = app.clock::nowEpochMilliseconds,
            dispatcher = Dispatchers.Unconfined,
            onPlaybackHandoff = app.recordingPlaybackHandoff::offer,
            onPlaybackHandoffCancelled = app.recordingPlaybackHandoff::discard,
        )
        preparation.load()
        awaitCondition("试听缓冲完成", { "state=${preview.state.value}, requests=${previewServer.requestCount}" }) {
            preview.state.value is PreviewState.Buffered
        }
        assertTrue(preparation.togglePreview())
        awaitCondition("试听开始播放") { preview.state.value is PreviewState.Playing }
        assertTrue(preparation.togglePreview())
        awaitCondition("试听暂停") { preview.state.value is PreviewState.Buffered }
        preparation.startTraining()
        assertEquals(DRAFT_ID, preparation.state.value.navigateToRecordingDraftId)

        val storage = app.draftStorage.current()
        val uploadWork = UploadWorkContract(storage.accountScopeHash, DRAFT_ID)
        val analysisWork = AnalysisAndroidWorkContract(
            storage.accountScopeHash,
            SESSION_ID,
            storage.cleanupScopeToken,
        )
        val cleanupWork = DraftCleanupWorkContract(storage.accountScopeHash, storage.cleanupScopeToken)
        testWorkNames += listOf(uploadWork.uniqueWorkName, analysisWork.uniqueWorkName, cleanupWork.uniqueWorkName)
        cancelAndAwaitTestWork()
        val assembledCapture = app.recordingCaptureFactory.create(context, TestLifecycleOwner, NoSurfaceProvider)
        val recordingPlayback = requireNotNull(app.recordingPlaybackHandoff.take(DRAFT_ID))
        val coordinator = DefaultRecordingCoordinator(
            capture = assembledCapture,
            playback = recordingPlayback,
            clockNanos = app.monotonicClock::nowNanoseconds,
            tempFiles = PrivateRecordingTempFiles(context).also { it.cleanupOrphans() },
            publisher = AccountScopedRecordingArtifactPublisher(storage),
        )
        val recording = RecordingViewModel(
            DRAFT_ID,
            coordinator,
            AccountScopedRecordingDraftGateway(storage),
            countdownTick = {},
            dispatcher = Dispatchers.Unconfined,
        )
        recording.start()
        capture.emit(CaptureEvent.Started)
        awaitCondition("录制音乐播放") { preview.state.value is PreviewState.Playing }
        recording.stop()
        capture.emit(CaptureEvent.Finalized(capture.durationMillis))
        assertEquals(DRAFT_ID, recording.state.value.navigateReviewDraftId)

        val persistedReview = storage.loadReviewDraft(DRAFT_ID)
        assertEquals(DraftState.REVIEW_READY, persistedReview.state)
        assertEquals(setOf(MediaType.AUDIO, MediaType.VIDEO), persistedReview.media.map { it.type }.toSet())
        val persistedPlaintextDigests = roomMediaDigests(context, PATIENT_ID, DRAFT_ID)
        assertEquals(2, roomMediaCount(context, PATIENT_ID, DRAFT_ID))
        val encryptedFiles = persistedReview.media.map { media ->
            File(context.filesDir, "encrypted_media/${storage.accountScopeHash}/${media.encryptedRelativePath}")
        }
        encryptedFiles.forEach { encrypted ->
            assertTrue(encrypted.isFile)
            assertEquals("VEF1", encrypted.readMagic())
            assertEquals(384, Os.stat(encrypted.absolutePath).st_mode and 511)
        }

        val reviewPlayer = app.mediaFactory.createReviewPlayer(storage)
        val review = ReviewViewModel(
            DRAFT_ID,
            DraftRepository(AccountScopedLocalReviewStore(storage), LocalUploadQueueSignals),
            reviewPlayer,
            Dispatchers.Unconfined,
        )
        review.load()
        assertTrue(review.state.value.canPlayback)
        assertTrue(review.state.value.canConfirm)
        review.playPause()
        awaitCondition("视频回看播放") { review.state.value.isPlaying }
        review.seekTo(100)
        review.playPause()
        awaitCondition("视频回看暂停") { !review.state.value.isPlaying }
        review.switchMedia(ReviewMediaKind.AUDIO)
        assertEquals(ReviewMediaKind.AUDIO, review.state.value.selectedMedia)
        review.playPause()
        awaitCondition("音频回看播放") { review.state.value.isPlaying }
        review.playPause()
        awaitCondition("音频回看暂停") { !review.state.value.isPlaying }
        review.confirm()
        assertEquals(DRAFT_ID, review.state.value.navigatePendingUploadDraftId)
        review.leave()
        assertTrue(runCatching { reviewPlayer.play() }.isFailure)

        val upload = app.uploadFactory.create() as UploadCoordinator
        val workContract = uploadWork
        awaitUniqueWorkFinished(workContract)
        assertEquals(setOf("singing_audio", "singing_video"), backend.confirmedMediaKinds())
        assertEquals(setOf(MediaType.AUDIO, MediaType.VIDEO), qiniu.uploadedKinds)
        assertEquals(persistedPlaintextDigests, qiniu.digests)
        assertEquals(1, backend.submitCalls.get())
        assertEquals(DraftState.SUBMITTED, storage.findDraft(DRAFT_ID)?.state)
        assertEquals(0, roomMediaCount(context, PATIENT_ID, DRAFT_ID))
        encryptedFiles.forEach { assertFalse(it.exists()) }

        // 重复调度同一 production unique work 只能走已提交幂等路径。
        upload.schedule(DRAFT_ID)
        awaitUniqueWorkFinished(workContract)
        assertEquals(1, backend.submitCalls.get())
        assertEquals(0, activeUploadWorkCount(workContract))

        val synchronizer = app.repositoryFactory.create("analysis-session-sync") as ProductionAnalysisSessionSynchronizer
        val result = ResultViewModel(
            SESSION_ID,
            synchronizer.resultRemote(storage.accountScopeHash, storage.cleanupScopeToken),
            retryAction = { error("completed 不应重试") },
            dispatcher = Dispatchers.Unconfined,
            delayMillis = {},
        )
        result.start()
        withTimeout(5_000) {
            while (result.state.value.content?.contentState != ResultContentState.COMPLETED) yield()
        }

        val history = HistoryRepository(
            AccountScopedHistoryLocalSource(storage),
            VocaEaseHistoryRemoteSource(app.patientApi, storage.accountScopeHash),
            storage::isLeaseActive,
        )
        history.start()
        history.refresh()
        assertEquals(SESSION_ID, history.state.value.items.single().sessionId)
        assertEquals("88", history.state.value.items.single().score)
        assertTrue(previewServer.requestCount > 0)
        previewServer.takeRequest().also { request ->
            assertEquals("GET", request.method)
            assertEquals("/fixture-preview.mp4", request.requestUrl?.encodedPath)
        }
        backend.assertComplete()

        // 期望值由服务端 fixture 独立手算；RED 阶段曾改为 87 并确认只在此处失败。
        assertEquals("88", result.state.value.content?.overallScore)
    }

    private fun installApplicationContainer(value: AndroidAppContainer) {
        VocaEaseApplication::class.java.getDeclaredField("container").apply {
            isAccessible = true
            set(application, value)
        }
    }

    private suspend fun awaitCondition(
        label: String,
        diagnostics: () -> String = { "" },
        predicate: () -> Boolean,
    ) {
        try {
            withTimeout(20_000) { while (!predicate()) delay(25) }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("等待超时：$label ${diagnostics()}", error)
        }
    }

    private suspend fun awaitUniqueWorkFinished(contract: UploadWorkContract) = awaitCondition("上传 WorkManager 终态") {
        val infos = WorkManager.getInstance(application)
            .getWorkInfosForUniqueWork(contract.uniqueWorkName)
            .get(5, TimeUnit.SECONDS)
        infos.isNotEmpty() && infos.all { it.state.isFinished }
    }

    private fun activeUploadWorkCount(contract: UploadWorkContract): Int = WorkManager.getInstance(application)
        .getWorkInfosForUniqueWork(contract.uniqueWorkName)
        .get(5, TimeUnit.SECONDS)
        .count { !it.state.isFinished }

    private fun cancelAndAwaitTestWork() {
        val workManager = WorkManager.getInstance(application)
        testWorkNames.forEach { name ->
            workManager.cancelUniqueWork(name).result.get(30, TimeUnit.SECONDS)
        }
        runBlocking {
            awaitCondition("本测试 WorkManager 全部终止") {
                testWorkNames.all { name ->
                    workManager.getWorkInfosForUniqueWork(name)
                        .get(5, TimeUnit.SECONDS)
                        .all { it.state.isFinished }
                }
            }
        }
    }

    private fun fixture(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/$name")
            .bufferedReader().use { it.readText() }

    private fun fixtureBytes(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
}

private enum class ExpectedRequestBody { EMPTY, JSON }

private class ClosedLoopBackend(
    private val fixture: (String) -> String,
    private val previewUrl: String,
) : Dispatcher() {
    private val loginCalls = AtomicInteger()
    private val resultDetailCalls = AtomicInteger()
    val submitCalls = AtomicInteger()
    private val media = linkedMapOf<String, UploadedMedia>()
    private var passwordChanged = false
    private var sessionCreated = false
    private var submittedSessionId: String? = null
    private var completed = false
    private var historyServed = false

    fun confirmedMediaKinds(): Set<String> =
        media.values.filter(UploadedMedia::confirmed).mapTo(linkedSetOf(), UploadedMedia::kind)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        return when {
            path == "/api/v1/auth/login/" -> {
                expect(request, method = "POST", authenticated = false, idempotencyKey = null)
                val call = loginCalls.incrementAndGet()
                val password = if (call == 1) "888888" else "New-password-2026"
                check(call in 1..2 && (call == 1 || passwordChanged))
                expectJson(request, """{"login_id":"MR-2026-001","password":"$password","client_kind":"android"}""")
                val mustChange = call == 1
                json(fixture("login.json").replace("\"must_change_password\": false", "\"must_change_password\": $mustChange"))
            }
            path == "/api/v1/auth/change-password/" -> {
                check(loginCalls.get() == 1 && !passwordChanged)
                expect(request, method = "POST", authenticated = true, idempotencyKey = null)
                expectJson(request, """{"old_password":"888888","new_password":"New-password-2026"}""")
                passwordChanged = true
                json(fixture("empty.json"))
            }
            path == "/api/v1/patient/me/" -> {
                check(loginCalls.get() == 2 && passwordChanged)
                expect(request, method = "GET", authenticated = true, idempotencyKey = null)
                json(fixture("patient_me.json"))
            }
            path == "/api/v1/patient/songs/" -> {
                check(loginCalls.get() == 2)
                expect(
                    request,
                    method = "GET",
                    authenticated = true,
                    idempotencyKey = null,
                    query = mapOf("page" to "1", "page_size" to "20", "sort" to "-created_at"),
                )
                json(fixture("songs_page.json"))
            }
            path == "/api/v1/patient/songs/$SONG_ID/" -> {
                expect(request, method = "GET", authenticated = true, idempotencyKey = null)
                json(fixture("song.json"))
            }
            path == "/api/v1/patient/songs/$SONG_ID/preview/" -> {
                expect(
                    request,
                    query = mapOf("track" to "accompaniment"),
                    method = "POST",
                    authenticated = true,
                    idempotencyKey = null,
                    expectedBody = ExpectedRequestBody.EMPTY,
                )
                json("""{"code":"ok","message":"","data":{"url":"$previewUrl","expires_at":"2030-01-01T00:00:00Z"},"request_id":"preview"}""")
            }
            path == "/api/v1/patient/singing-sessions/" && request.method == "POST" -> {
                check(!sessionCreated)
                expect(
                    request,
                    method = "POST",
                    authenticated = true,
                    idempotencyKey = "session-create:${PATIENT_SCOPE_HASH}:$DRAFT_ID",
                )
                expectJson(request, """{"song_id":"$SONG_ID"}""")
                sessionCreated = true
                json(session(status = "awaiting_upload"))
            }
            path == "/api/v1/patient/singing-sessions/" && request.method == "GET" -> {
                check(completed && submitCalls.get() == 1)
                expect(
                    request,
                    method = "GET",
                    authenticated = true,
                    idempotencyKey = null,
                    query = mapOf("page" to "1", "page_size" to "20"),
                )
                historyServed = true
                json(dynamicHistory())
            }
            path == "/api/v1/patient/singing-sessions/$SESSION_ID/upload-grants/" -> grant(request)
            path == "/api/v1/patient/singing-sessions/$SESSION_ID/confirm-upload/" -> confirm(request)
            path == "/api/v1/patient/singing-sessions/$SESSION_ID/submit/" -> {
                check(sessionCreated && media.size == 2 && media.values.all(UploadedMedia::confirmed))
                expect(
                    request,
                    method = "POST",
                    authenticated = true,
                    idempotencyKey = "submit:$DRAFT_ID",
                    expectedBody = ExpectedRequestBody.EMPTY,
                )
                submitCalls.incrementAndGet()
                submittedSessionId = SESSION_ID
                json(fixture("session_mutation.json"))
            }
            path == "/api/v1/patient/singing-sessions/$SESSION_ID/" -> {
                check(sessionCreated)
                expect(request, method = "GET", authenticated = true, idempotencyKey = null)
                if (submitCalls.get() == 0) json(session(status = "awaiting_upload"))
                else if (resultDetailCalls.incrementAndGet() == 1) json(session(status = "processing"))
                else {
                    completed = true
                    json(completedSession())
                }
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun grant(request: RecordedRequest): MockResponse {
        check(sessionCreated && submitCalls.get() == 0)
        val body = request.body.readUtf8()
        val payload = JSONObject(body)
        val audio = payload.getString("media_type") == "singing_audio"
        val kind = if (audio) "singing_audio" else "singing_video"
        check(payload.length() == 3 && payload.keys().asSequence().toSet() == setOf("media_type", "mime", "size"))
        check(payload.getString("media_type") in setOf("singing_audio", "singing_video"))
        check(payload.getString("mime") == if (audio) "audio/mp4" else "video/mp4")
        val asset = if (audio) AUDIO_ASSET_ID else VIDEO_ASSET_ID
        val objectKey = "private/session/${if (audio) "audio.m4a" else "video.mp4"}"
        val size = payload.getLong("size").also { check(it > 0) }
        expect(request, method = "POST", authenticated = true, idempotencyKey = "grant:$DRAFT_ID:${if (audio) "audio" else "video"}")
        check(kind !in media)
        media[kind] = UploadedMedia(asset, kind, objectKey, size, confirmed = false)
        return json(
            """{"code":"ok","message":"","data":{"session_id":"$SESSION_ID","asset_id":"$asset","object_key":"$objectKey","expires_at":"2030-01-01T00:00:00Z","upload_url":"https://upload.qiniup.com","upload_token":"test-only-token","fields":{}},"request_id":"grant"}""",
        )
    }

    private fun confirm(request: RecordedRequest): MockResponse {
        check(sessionCreated && submitCalls.get() == 0)
        expect(request, method = "POST", authenticated = true, idempotencyKey = null)
        val payload = JSONObject(request.body.readUtf8())
        check(payload.length() == 2 && payload.keys().asSequence().toSet() == setOf("asset_id", "object_key"))
        val assetId = payload.getString("asset_id")
        val selected = media.values.single { it.assetId == assetId }.also { check(!it.confirmed) }.copy(confirmed = true)
        check(payload.getString("object_key") == selected.objectKey)
        media[selected.kind] = selected
        return json(session(status = "awaiting_upload"))
    }

    fun assertComplete() {
        check(loginCalls.get() == 2 && passwordChanged && sessionCreated)
        check(submitCalls.get() == 1 && submittedSessionId == SESSION_ID && completed && historyServed)
        check(media.keys == setOf("singing_audio", "singing_video") && media.values.all(UploadedMedia::confirmed))
    }

    private fun expect(
        request: RecordedRequest,
        method: String,
        authenticated: Boolean,
        idempotencyKey: String?,
        query: Map<String, String> = emptyMap(),
        expectedBody: ExpectedRequestBody = if (method == "GET") {
            ExpectedRequestBody.EMPTY
        } else {
            ExpectedRequestBody.JSON
        },
    ) {
        check(request.method == method)
        check(request.getHeader("Authorization") == if (authenticated) "Bearer access-secret" else null)
        check(request.getHeader("Idempotency-Key") == idempotencyKey)
        val url = requireNotNull(request.requestUrl)
        check(url.queryParameterNames == query.keys) {
            "query 字段不匹配：path=${url.encodedPath} actual=${url.queryParameterNames} expected=${query.keys}"
        }
        query.forEach { (name, value) -> check(url.queryParameterValues(name) == listOf(value)) }
        when (expectedBody) {
            ExpectedRequestBody.EMPTY -> check(request.bodySize == 0L)
            ExpectedRequestBody.JSON -> {
                check(request.bodySize > 0L)
                check(request.getHeader("Content-Type")?.substringBefore(';') == "application/json")
            }
        }
    }

    private fun expectJson(request: RecordedRequest, expected: String) {
        val actualObject = JSONObject(request.body.readUtf8())
        val expectedObject = JSONObject(expected)
        check(actualObject.length() == expectedObject.length()) {
            "JSON 字段集合不匹配：actual=${actualObject.keys().asSequence().toSet()} expected=${expectedObject.keys().asSequence().toSet()}"
        }
        expectedObject.keys().asSequence().forEach { key ->
            check(actualObject.has(key) && actualObject.get(key) == expectedObject.get(key)) {
                "JSON 字段不匹配：$key"
            }
        }
    }

    private fun completedSession(): String = fixture("session.json")
        .also { check(submittedSessionId == SESSION_ID) }
        .replace("\"size\": 1234", "\"size\": ${media.getValue("singing_audio").size}")
        .replace("\"size\": 5678", "\"size\": ${media.getValue("singing_video").size}")

    private fun dynamicHistory(): String = fixture("sessions_page.json")
        .also { check(submittedSessionId == SESSION_ID) }
        .replace("\"request_id\": \"session-page-1\"", "\"request_id\": \"submitted-$SESSION_ID\"")

    private fun session(status: String): String {
        val mediaJson = media.values.joinToString(",") { item ->
            """{"asset_id":"${item.assetId}","media_type":"${item.kind}","status":"${if (item.confirmed) "ready" else "uploading"}","mime":"${if (item.kind == "singing_audio") "audio/mp4" else "video/mp4"}","size":${item.size},"confirmed_at":${if (item.confirmed) "\"2026-08-27T09:00:30Z\"" else "null"}}"""
        }
        return """{"code":"ok","message":"","data":{"id":"$SESSION_ID","patient":{"id":"$PATIENT_ID","medical_record_no":"MR-2026-001","name":"患者甲"},"song":{"id":"$SONG_ID","title":"春风","artist":"演示歌手","duration_seconds":90},"treatment_plan":{"id":"33333333-3333-4333-8333-333333333333","start_date":"2026-08-01","cycle_weeks":4,"target_session_count":12},"status":"$status","score":null,"burp_count":null,"duration_seconds":90,"is_mock":false,"created_source":"patient_android_api","analysis_generation":0,"submitted_at":null,"completed_at":null,"created_at":"2026-08-27T09:00:00Z","updated_at":"2026-08-27T09:01:00Z","media":[$mediaJson],"analysis_task_ids":[],"analysis_results":[]},"request_id":"session"}"""
    }

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}

private data class UploadedMedia(
    val assetId: String,
    val kind: String,
    val objectKey: String,
    val size: Long,
    val confirmed: Boolean,
)

private object NoOpEnvironmentMonitor : PreparationEnvironmentMonitor {
    override fun start(onChanged: () -> Unit) = Unit
    override fun stop() = Unit
}

private object DeterministicConnectivity : AppConnectivity {
    override fun isOnline(): Boolean = true
    override fun environmentMonitor(): PreparationEnvironmentMonitor = NoOpEnvironmentMonitor
}

private class FixtureRecordingCapture(
    private val fixtureBytes: (String) -> ByteArray,
) : RecordingCapture {
    override var listener: suspend (CaptureEvent) -> Unit = {}
    private lateinit var output: File
    var durationMillis: Long = 0
        private set
    override suspend fun bindFrontCamera() = Unit
    override fun start(output: File) {
        this.output = output
        output.writeBytes(fixtureBytes("sample_avc_aac.mp4"))
        durationMillis = mediaDurationMillis(output)
    }
    override fun stop() = Unit
    suspend fun emit(event: CaptureEvent) = listener(event)
    private fun mediaDurationMillis(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).maxOf { extractor.getTrackFormat(it).getLong(MediaFormat.KEY_DURATION) } / 1_000
        } finally {
            extractor.release()
        }
    }
}

private class DeterministicQiniuUploader(
    private val fixtureBytes: (String) -> ByteArray,
) : QiniuUploader {
    val digests = linkedMapOf<MediaType, String>()
    val uploadedKinds: Set<MediaType> get() = digests.keys

    override suspend fun upload(request: QiniuUploadRequest, onProgress: (Int) -> Unit): QiniuUploadResult {
        val mediaType = if (request.kind == UploadMediaKind.VIDEO) MediaType.VIDEO else MediaType.AUDIO
        check(request.file.isFile && request.file.length() == request.grant.binding.sizeBytes)
        check(request.mimeType == request.grant.binding.mimeType)
        check(request.grant.binding.objectKey.startsWith("private/session/"))
        validateTracks(request.file, mediaType)
        val bytes = request.file.readBytes()
        if (mediaType == MediaType.VIDEO) check(bytes.contentEquals(fixtureBytes("sample_avc_aac.mp4")))
        digests[mediaType] = bytes.sha256()
        onProgress(100)
        return QiniuUploadResult.Completed(request.grant.binding.objectKey)
    }
    override fun cancel() = Unit
}

private object TestLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle = registry
}

private val NoSurfaceProvider = Preview.SurfaceProvider { it.willNotProvideSurface() }

private class IsolatedAppContext(base: Context, private val root: File) : ContextWrapper(base) {
    private val privateFiles = File(root, "files").apply { mkdirs() }
    private val privateCache = File(root, "cache").apply { mkdirs() }
    private val privateDatabases = File(root, "databases").apply { mkdirs() }

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = privateFiles
    override fun getCacheDir(): File = privateCache
    override fun getFileStreamPath(name: String): File = File(privateFiles, name)
    override fun getDatabasePath(name: String): File = File(privateDatabases, name)
    override fun deleteDatabase(name: String): Boolean = getDatabasePath(name).delete()
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

private fun validateTracks(file: File, expectedType: MediaType) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        when (expectedType) {
            MediaType.VIDEO -> check(mimes.count { it == "video/avc" } == 1 && mimes.count { it == "audio/mp4a-latm" } == 1)
            MediaType.AUDIO -> check(mimes == listOf("audio/mp4a-latm"))
        }
    } finally {
        extractor.release()
    }
}

private fun roomMediaCount(context: Context, accountScope: String, draftId: String): Int =
    SQLiteDatabase.openDatabase(context.getDatabasePath("vocaease-patient.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
        database.rawQuery(
            "SELECT COUNT(*) FROM media WHERE account_scope = ? AND draft_id = ?",
            arrayOf(accountScope, draftId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

private fun roomMediaDigests(context: Context, accountScope: String, draftId: String): Map<MediaType, String> =
    SQLiteDatabase.openDatabase(context.getDatabasePath("vocaease-patient.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
        database.rawQuery(
            "SELECT type, sha256 FROM media WHERE account_scope = ? AND draft_id = ? ORDER BY type",
            arrayOf(accountScope, draftId),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(MediaType.valueOf(cursor.getString(0)), cursor.getString(1))
            }
        }
    }

private const val PATIENT_ID = "11111111-1111-4111-8111-111111111111"
private const val PATIENT_SCOPE_HASH = "bd7662a5eeb41614e720d477abfcb2272e19a8a70a93b7e3bc8560d44ad326e9"
private const val SONG_ID = "44444444-4444-4444-8444-444444444444"
private const val SESSION_ID = "55555555-5555-4555-8555-555555555555"
private const val AUDIO_ASSET_ID = "66666666-6666-4666-8666-666666666666"
private const val VIDEO_ASSET_ID = "77777777-7777-4777-8777-777777777777"
private val DRAFT_ID = "closed-loop-${java.util.UUID.randomUUID()}"
private const val FIXED_NOW = 1_777_777_777_000L
private const val FIXED_NANOS = 9_000_000_000L

private fun File.readMagic(): String = inputStream().use { input ->
    val bytes = ByteArray(4)
    check(input.read(bytes) == bytes.size)
    String(bytes, Charsets.US_ASCII)
}
