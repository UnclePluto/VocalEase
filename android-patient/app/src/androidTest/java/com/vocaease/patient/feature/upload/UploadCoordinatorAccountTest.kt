package com.vocaease.patient.feature.upload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.database.AccountScopedDraftStorage
import com.vocaease.patient.core.database.AccountScopedDraftStorageProvider
import com.vocaease.patient.core.database.DraftEntity
import com.vocaease.patient.core.database.MutableAuthenticatedAccountSession
import com.vocaease.patient.core.database.PreparationDraftSnapshot
import com.vocaease.patient.core.database.PreparationDraftStatus
import com.vocaease.patient.core.database.UploadPipelineStage
import com.vocaease.patient.core.database.UploadOverallState
import com.vocaease.patient.core.database.UploadLocalActionStage
import com.vocaease.patient.core.database.UploadStepState
import com.vocaease.patient.core.database.VocaEaseDatabase
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadCoordinatorAccountTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: VocaEaseDatabase
    private lateinit var session: MutableAuthenticatedAccountSession
    private lateinit var provider: AccountScopedDraftStorageProvider
    private lateinit var root: File
    private lateinit var patientA: String
    private lateinit var patientB: String

    @Before
    fun setUp() {
        database = VocaEaseDatabase.inMemory(context, allowMainThreadQueries = true)
        session = MutableAuthenticatedAccountSession()
        root = context.filesDir.resolve("upload-account-${System.nanoTime()}")
        patientA = UUID.randomUUID().toString()
        patientB = UUID.randomUUID().toString()
        provider = AccountScopedDraftStorageProvider(database, ChunkedAesGcmFileStore(context, root), session)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
        context.cacheDir.resolve("upload-lease").deleteRecursively()
        context.filesDir.resolve("qiniu-upload-recorder").deleteRecursively()
    }

    @Test
    fun 暂停先落PAUSED再取消上传并清理明文且继续恢复原阶段() = runBlocking {
        val storage = prepareQueued(patientA, "pause-draft")
        val uploader = BlockingUploader()
        val scheduler = FakeScheduler()
        val coordinator = coordinator(uploader, scheduler)
        val contract = UploadWorkContract(storage.accountScopeHash, "pause-draft")
        coordinator.schedule(contract.draftId)
        val running = async { coordinator.run(contract) {} }
        val plaintext = uploader.entered.await()
        assertTrue(plaintext.isFile)

        coordinator.pause(contract)

        assertEquals(UploadPipelineStage.PAUSED, storage.findUploadJob(contract.draftId)?.pipelineStage)
        assertFalse(plaintext.exists())
        assertTrue(contract in scheduler.cancelled)
        assertThrows(CancellationException::class.java) { runBlocking { running.await() } }

        coordinator.schedule(contract.draftId)
        assertEquals(UploadPipelineStage.PAUSED, storage.findUploadJob(contract.draftId)?.pipelineStage)
        assertEquals(1, scheduler.enqueued.size)

        coordinator.retry(contract)
        assertEquals(UploadPipelineStage.UPLOADING_AUDIO, storage.findUploadJob(contract.draftId)?.pipelineStage)
        assertTrue(scheduler.enqueued.last().second)
        Unit
    }

    @Test
    fun 等待回调确认中和提交中都先持久暂停并精确恢复原安全阶段() = runBlocking {
        val targets = listOf(
            UploadPipelineStage.WAITING_AUDIO_RECEIPT,
            UploadPipelineStage.CONFIRMING_AUDIO,
            UploadPipelineStage.SUBMITTING,
        )
        targets.forEach { target ->
            val draftId = "pause-${target.name.lowercase()}"
            val storage = prepareQueued(patientA, draftId)
            advanceJobTo(draftId, target)
            val store = RoomUploadStore(storage, draftId) { 1_000 }

            store.pause()
            assertEquals(UploadPipelineStage.PAUSED, storage.findUploadJob(draftId)?.pipelineStage)
            assertEquals(target, storage.findUploadJob(draftId)?.resumePipelineStage)

            store.resumeFromPause()
            assertEquals(target, storage.findUploadJob(draftId)?.pipelineStage)
            assertEquals(null, storage.findUploadJob(draftId)?.resumePipelineStage)
        }
        Unit
    }

    @Test
    fun 可重试FAILED先清理标记再排唯一work而terminal失败保持不动() = runBlocking {
        val retryStorage = prepareQueued(patientA, "retryable-failed")
        val retryInitial = database.uploadDao().find(patientA, "retryable-failed")!!
        checkpointJob(retryInitial.copy(
            overallState = UploadOverallState.WAITING_NETWORK,
            pipelineStage = UploadPipelineStage.WAITING_NETWORK,
        ))
        checkpointJob(database.uploadDao().find(patientA, "retryable-failed")!!.copy(
            overallState = UploadOverallState.FAILED,
            pipelineStage = UploadPipelineStage.FAILED,
            nextRetryAt = 5_000,
            lastSafeError = "网络暂不可用",
        ))
        val scheduler = FakeScheduler()
        val coordinator = coordinator(BlockingUploader(), scheduler)
        val retryContract = UploadWorkContract(retryStorage.accountScopeHash, "retryable-failed")

        coordinator.retry(retryContract)

        val resumed = retryStorage.findUploadJob("retryable-failed")!!
        assertEquals(UploadPipelineStage.WAITING_NETWORK, resumed.pipelineStage)
        assertEquals(null, resumed.nextRetryAt)
        assertEquals(null, resumed.lastSafeError)
        assertEquals(retryContract to true, scheduler.enqueued.single())

        val terminalStorage = prepareQueued(patientA, "terminal-failed")
        val terminalInitial = database.uploadDao().find(patientA, "terminal-failed")!!
        checkpointJob(terminalInitial.copy(
            overallState = UploadOverallState.WAITING_NETWORK,
            pipelineStage = UploadPipelineStage.WAITING_NETWORK,
        ))
        checkpointJob(database.uploadDao().find(patientA, "terminal-failed")!!.copy(
            overallState = UploadOverallState.FAILED,
            pipelineStage = UploadPipelineStage.FAILED,
            nextRetryAt = null,
            lastSafeError = "上传凭证与本地录制不一致",
        ))
        coordinator.retry(UploadWorkContract(terminalStorage.accountScopeHash, "terminal-failed"))
        assertEquals(UploadPipelineStage.FAILED, terminalStorage.findUploadJob("terminal-failed")?.pipelineStage)
        assertEquals(1, scheduler.enqueued.size)
        Unit
    }

    @Test
    fun worker从ANALYZING的半完成cleanup恢复时不读取已INVALID媒体() = runBlocking {
        val storage = prepareQueued(patientA, "analyzing-cleanup")
        advanceJobTo("analyzing-cleanup", UploadPipelineStage.ANALYZING)
        storage.requestSubmittedUploadCleanup("analyzing-cleanup")
        assertThrows(RuntimeException::class.java) {
            runBlocking {
                storage.resumeUploadLocalAction("analyzing-cleanup") { stage, before ->
                    if (stage == UploadLocalActionStage.AUDIO_DELETED && before) throw RuntimeException("模拟崩溃")
                }
            }
        }
        val coordinator = coordinator(BlockingUploader(), FakeScheduler())
        val contract = UploadWorkContract(storage.accountScopeHash, "analyzing-cleanup")

        assertEquals(UploadRunResult.Analyzing, coordinator.run(contract) {})
        assertTrue(database.mediaDao().findAll(patientA, "analyzing-cleanup").isEmpty())
        assertEquals(null, database.uploadLocalActionDao().find(patientA, "analyzing-cleanup"))
        assertEquals(UploadPipelineStage.ANALYZING, storage.findUploadJob("analyzing-cleanup")?.pipelineStage)
        Unit
    }

    @Test
    fun confirm网络异常在真实Room先持久WAITING_NETWORK且不回退步骤状态() = runBlocking {
        val storage = prepareQueued(patientA, "confirm-network")
        advanceJobTo("confirm-network", UploadPipelineStage.CONFIRMING_AUDIO)
        val scheduler = FakeScheduler()
        val coordinator = coordinator(BlockingUploader(), scheduler, FakeRemote(failConfirm = true))
        val contract = UploadWorkContract(storage.accountScopeHash, "confirm-network")

        assertEquals(UploadRunResult.Retry, coordinator.run(contract) {})

        val durable = storage.findUploadJob("confirm-network")!!
        assertEquals(UploadPipelineStage.WAITING_NETWORK, durable.pipelineStage)
        assertEquals(UploadPipelineStage.CONFIRMING_AUDIO, durable.resumePipelineStage)
        assertEquals(UploadStepState.CONFIRMING, durable.audioConfirmState)
        assertEquals(1, durable.attemptCount)
        assertEquals(31_000L, durable.nextRetryAt)
        Unit
    }

    @Test
    fun 换号与同患者新incarnation都使旧worker失效且迟到回调不能推进() = runBlocking {
        listOf(patientB, patientA).forEachIndexed { index, nextPatient ->
            val draftId = "switch-$index"
            val storage = prepareQueued(patientA, draftId)
            val uploader = BlockingUploader()
            val scheduler = FakeScheduler()
            val coordinator = coordinator(uploader, scheduler)
            val contract = UploadWorkContract(storage.accountScopeHash, draftId)
            coordinator.schedule(draftId)
            val running = async { coordinator.run(contract) {} }
            val plaintext = uploader.entered.await()

            session.authenticate(nextPatient)

            assertFalse(plaintext.exists())
            assertThrows(CancellationException::class.java) { runBlocking { running.await() } }
            assertEquals(UploadPipelineStage.UPLOADING_AUDIO, database.uploadDao().find(patientA, draftId)?.pipelineStage)
            assertTrue(contract in scheduler.cancelled)
        }
        Unit
    }

    private suspend fun prepareQueued(patientId: String, draftId: String): AccountScopedDraftStorage {
        session.authenticate(patientId)
        val storage = provider.current()
        val songId = "song-$draftId"
        val sessionId = "session-$draftId"
        storage.insertPreparationDraft(
            PreparationDraftSnapshot(
                accountScopeHash = storage.accountScopeHash,
                draftId = draftId,
                songId = songId,
                songTitle = "练习歌曲",
                songArtist = "本地歌手",
                songDurationSeconds = 120,
                serverSessionId = null,
                creationKey = "session-create:${storage.accountScopeHash}:$draftId",
                status = PreparationDraftStatus.PENDING,
                activeSongId = songId,
                createdAt = 0,
                expiresAt = DraftEntity.MAX_RETENTION_MILLIS,
            ),
        )
        storage.bindPreparationSession(draftId, patientId, sessionId, songId, "练习歌曲", "本地歌手", 120)
        storage.markPreparationHandoffPending(draftId, sessionId) {}
        storage.acknowledgePreparationHandoff(draftId)
        val video = File(root, "$draftId-video.mp4").apply { parentFile?.mkdirs(); writeBytes(ByteArray(32) { 1 }) }
        val audio = File(root, "$draftId-audio.mp4").apply { writeBytes(ByteArray(16) { 2 }) }
        storage.publishRecordingMedia(draftId, video, audio, 1_000)
        storage.enqueueReviewDraft(draftId)
        return storage
    }

    private suspend fun advanceJobTo(draftId: String, target: UploadPipelineStage) {
        val initial = database.uploadDao().find(patientA, draftId)!!
        val waitingNetwork = initial.copy(
            overallState = UploadOverallState.WAITING_NETWORK,
            pipelineStage = UploadPipelineStage.WAITING_NETWORK,
        )
        val requestingAudio = waitingNetwork.copy(
            overallState = UploadOverallState.UPLOADING,
            pipelineStage = UploadPipelineStage.REQUESTING_AUDIO_GRANT,
            audioGrantState = UploadStepState.REQUESTING_GRANT,
        )
        val uploadingAudio = requestingAudio.copy(
            pipelineStage = UploadPipelineStage.UPLOADING_AUDIO,
            audioGrantState = UploadStepState.GRANT_READY,
            audioUploadState = UploadStepState.UPLOADING,
            audioAssetKey = "asset-a",
            audioObjectKey = "object-a",
        )
        val waitingAudio = uploadingAudio.copy(
            overallState = UploadOverallState.WAITING_CALLBACK,
            pipelineStage = UploadPipelineStage.WAITING_AUDIO_RECEIPT,
            audioUploadState = UploadStepState.UPLOADED,
            audioReceiptState = UploadStepState.WAITING_RECEIPT,
            progressPercent = 50,
        )
        val confirmingAudio = waitingAudio.copy(
            overallState = UploadOverallState.CONFIRMING,
            pipelineStage = UploadPipelineStage.CONFIRMING_AUDIO,
            audioConfirmState = UploadStepState.CONFIRMING,
        )
        val requestingVideo = confirmingAudio.copy(
            overallState = UploadOverallState.UPLOADING,
            pipelineStage = UploadPipelineStage.REQUESTING_VIDEO_GRANT,
            audioReceiptState = UploadStepState.RECEIPT_RECEIVED,
            audioConfirmState = UploadStepState.CONFIRMED,
            audioReceipt = "trusted-callback",
            audioConfirmedAt = 10,
            videoGrantState = UploadStepState.REQUESTING_GRANT,
        )
        val uploadingVideo = requestingVideo.copy(
            pipelineStage = UploadPipelineStage.UPLOADING_VIDEO,
            videoGrantState = UploadStepState.GRANT_READY,
            videoUploadState = UploadStepState.UPLOADING,
            videoAssetKey = "asset-v",
            videoObjectKey = "object-v",
        )
        val waitingVideo = uploadingVideo.copy(
            overallState = UploadOverallState.WAITING_CALLBACK,
            pipelineStage = UploadPipelineStage.WAITING_VIDEO_RECEIPT,
            videoUploadState = UploadStepState.UPLOADED,
            videoReceiptState = UploadStepState.WAITING_RECEIPT,
            progressPercent = 100,
        )
        val confirmingVideo = waitingVideo.copy(
            overallState = UploadOverallState.CONFIRMING,
            pipelineStage = UploadPipelineStage.CONFIRMING_VIDEO,
            videoConfirmState = UploadStepState.CONFIRMING,
        )
        val submitting = confirmingVideo.copy(
            overallState = UploadOverallState.SUBMITTING,
            pipelineStage = UploadPipelineStage.SUBMITTING,
            videoReceiptState = UploadStepState.RECEIPT_RECEIVED,
            videoConfirmState = UploadStepState.CONFIRMED,
            videoReceipt = "trusted-callback",
            videoConfirmedAt = 11,
            submitState = UploadStepState.SUBMITTING,
        )
        val analyzing = submitting.copy(
            overallState = UploadOverallState.ANALYZING,
            pipelineStage = UploadPipelineStage.ANALYZING,
            submitState = UploadStepState.SUBMITTED,
        )
        val states = listOf(
            waitingNetwork, requestingAudio, uploadingAudio, waitingAudio, confirmingAudio,
            requestingVideo, uploadingVideo, waitingVideo, confirmingVideo, submitting, analyzing,
        )
        states.takeWhile { state ->
            checkpointJob(state)
            state.pipelineStage != target
        }
        check(database.uploadDao().find(patientA, draftId)?.pipelineStage == target)
    }

    private suspend fun checkpointJob(job: com.vocaease.patient.core.database.UploadJobEntity) {
        check(database.uploadDao().checkpoint(
            accountScope = job.accountScope,
            draftId = job.draftId,
            overallState = job.overallState,
            pipelineStage = job.pipelineStage,
            audioGrantState = job.audioGrantState,
            videoGrantState = job.videoGrantState,
            audioUploadState = job.audioUploadState,
            videoUploadState = job.videoUploadState,
            audioReceiptState = job.audioReceiptState,
            videoReceiptState = job.videoReceiptState,
            audioConfirmState = job.audioConfirmState,
            videoConfirmState = job.videoConfirmState,
            submitState = job.submitState,
            audioGrantKey = job.audioGrantKey,
            videoGrantKey = job.videoGrantKey,
            submitKey = job.submitKey,
            audioAssetKey = job.audioAssetKey,
            videoAssetKey = job.videoAssetKey,
            audioObjectKey = job.audioObjectKey,
            videoObjectKey = job.videoObjectKey,
            audioReceipt = job.audioReceipt,
            videoReceipt = job.videoReceipt,
            audioConfirmedAt = job.audioConfirmedAt,
            videoConfirmedAt = job.videoConfirmedAt,
            attemptCount = job.attemptCount,
            nextRetryAt = job.nextRetryAt,
            lastSafeError = job.lastSafeError,
            progressPercent = job.progressPercent,
            receiptWaitAttempt = job.receiptWaitAttempt,
            resumePipelineStage = job.resumePipelineStage,
        ) == 1)
    }

    private fun coordinator(
        uploader: BlockingUploader,
        scheduler: FakeScheduler,
        remote: UploadRemote = FakeRemote(),
    ) = UploadCoordinator(
        context = context,
        storageProvider = provider,
        remoteFactory = { remote },
        scheduler = scheduler,
        nowEpochMillis = { 1_000 },
        uploaderFactory = { uploader },
        wait = {},
    )

    private class BlockingUploader : QiniuUploader {
        val entered = CompletableDeferred<File>()
        private val result = CompletableDeferred<QiniuUploadResult>()
        override suspend fun upload(request: QiniuUploadRequest, onProgress: (Int) -> Unit): QiniuUploadResult {
            entered.complete(request.file)
            return result.await()
        }
        override fun cancel() { result.complete(QiniuUploadResult.Cancelled) }
    }

    private class FakeScheduler : UploadWorkScheduling {
        val enqueued = mutableListOf<Pair<UploadWorkContract, Boolean>>()
        val cancelled = mutableListOf<UploadWorkContract>()
        override fun enqueue(contract: UploadWorkContract, replace: Boolean) { enqueued += contract to replace }
        override fun cancel(contract: UploadWorkContract) { cancelled += contract }
        override fun cancelAccount(accountScopeHash: String) = Unit
    }

    private class FakeRemote(private val failConfirm: Boolean = false) : UploadRemote {
        private val bindings = mutableMapOf<UploadMediaKind, UploadBinding>()
        private val confirmed = mutableSetOf<UploadMediaKind>()

        override suspend fun grant(request: UploadGrantRequest) = UploadGrant(
            binding = UploadBinding(
                request.sessionId,
                "asset-${request.kind.name.lowercase()}",
                "object-${request.kind.name.lowercase()}",
                request.media.mimeType,
                request.media.sizeBytes,
            ).also { bindings[request.kind] = it },
            expiresAtEpochMillis = Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(),
            uploadUrl = "https://upload.qiniup.com",
            uploadToken = "ephemeral",
        )
        override suspend fun confirm(request: UploadConfirmRequest): UploadConfirmResult {
            if (failConfirm) throw UploadRemoteRetryableException()
            confirmed += request.kind
            return UploadConfirmResult.Confirmed(request.binding)
        }
        override suspend fun submit(sessionId: String, idempotencyKey: String) = UploadSubmitResult.Accepted(sessionId)
        override suspend fun sessionDetail(sessionId: String) = UploadSessionDetail(
            sessionId,
            RemoteSessionState.AWAITING_UPLOAD,
            bindings.map { (kind, binding) ->
                UploadSessionMedia(
                    binding.assetId,
                    kind,
                    binding.mimeType,
                    binding.sizeBytes,
                    if (kind in confirmed) RemoteMediaState.READY else RemoteMediaState.UPLOADING,
                )
            },
        )
    }

}
