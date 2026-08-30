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

    private fun coordinator(uploader: BlockingUploader, scheduler: FakeScheduler) = UploadCoordinator(
        context = context,
        storageProvider = provider,
        remoteFactory = { FakeRemote() },
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

    private class FakeRemote : UploadRemote {
        override suspend fun grant(request: UploadGrantRequest) = UploadGrant(
            binding = UploadBinding(
                request.sessionId,
                "asset-${request.kind.name.lowercase()}",
                "object-${request.kind.name.lowercase()}",
                request.media.mimeType,
                request.media.sizeBytes,
            ),
            expiresAtEpochMillis = Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(),
            uploadUrl = "https://upload.qiniup.com",
            uploadToken = "ephemeral",
        )
        override suspend fun confirm(request: UploadConfirmRequest) = UploadConfirmResult.Confirmed(request.binding)
        override suspend fun submit(sessionId: String, idempotencyKey: String) = UploadSubmitResult.Accepted(sessionId)
        override suspend fun sessionDetail(sessionId: String) = error("本测试不应读取详情")
    }

}
