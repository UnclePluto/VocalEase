package com.vocaease.patient.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.core.security.StoreIoStep
import java.io.File
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AuthenticatedDraftStorageTest {
    private lateinit var database: VocaEaseDatabase
    private lateinit var root: File
    private lateinit var sessions: MutableAuthenticatedAccountSession
    private lateinit var provider: AccountScopedDraftStorageProvider
    private lateinit var fileStore: ChunkedAesGcmFileStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = VocaEaseDatabase.inMemory(context, allowMainThreadQueries = true)
        root = File(context.filesDir, "facade-${System.nanoTime()}")
        sessions = MutableAuthenticatedAccountSession()
        fileStore = ChunkedAesGcmFileStore(context, root)
        provider = AccountScopedDraftStorageProvider(database, fileStore, sessions)
    }

    @After
    fun tearDown() {
        database.close()
        runCatching { fileStore.destroyAccountEncryption(PATIENT_A_UUID) }
        runCatching { fileStore.destroyAccountEncryption(PATIENT_B_UUID) }
        root.deleteRecursively()
    }

    @Test
    fun facadeNeverAcceptsScopeAndOldLeaseFailsImmediatelyAfterLogoutOrSwitch() = runBlocking {
        sessions.authenticate(PATIENT_A_UUID)
        val accountA = provider.current()
        insertDraft(accountA, "shared")

        sessions.clear()
        assertThrows(StaleAccountScopeException::class.java) {
            runBlocking { accountA.findDraft("shared") }
        }

        sessions.authenticate(PATIENT_B_UUID)
        val accountB = provider.current()
        assertEquals(null, accountB.findDraft("shared"))
        assertThrows(StaleAccountScopeException::class.java) {
            runBlocking { accountA.deleteDraft("shared") }
        }
        Unit
    }

    @Test
    fun mediaAndJobInsertionValidateParentInsideBoundAccountTransaction() = runBlocking {
        sessions.authenticate(PATIENT_A_UUID)
        val storage = provider.current()
        insertDraft(storage, "d")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                storage.insertMedia("missing", MediaType.AUDIO, "media/v1/${"a".repeat(32)}.vef", "audio/mp4", 1, "0".repeat(64), MediaValidationState.VALID)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { storage.insertUploadJob("missing", "ag", "vg", "submit") }
        }
        assertTrue(AccountScopedDraftStorage::class.java.methods.none { method -> method.parameters.any { it.name == "accountScope" } })
        Unit
    }

    @Test
    fun wrappedMasterIsRetainedWhileDraftExistsAndDestroyedOnlyAfterExplicitEmptyCheck() = runBlocking {
        sessions.authenticate(PATIENT_A_UUID)
        val storage = provider.current()
        storage.encryptMedia(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        insertDraft(storage, "d")

        assertEquals(false, storage.destroyEncryptionMaterialIfNoDrafts())
        assertEquals(1, storage.deleteDraft("d"))
        assertEquals(true, storage.destroyEncryptionMaterialIfNoDrafts())
        Unit
    }

    @Test
    fun inFlightFindEncryptAndCheckpointLinearizeBeforeLogoutAndAccountSwitch() = runBlocking {
        val linearSession = BlockingAuthenticatedAccountSession()
        val linearProvider = AccountScopedDraftStorageProvider(database, fileStore, linearSession)
        linearSession.authenticate(PATIENT_A_UUID)
        var storage = linearProvider.current()
        insertDraft(storage, "linear")
        storage.insertUploadJob("linear", "audio-linear", "video-linear", "submit-linear")

        suspend fun runWhileSwitchWaits(operation: suspend (AccountScopedDraftStorage) -> Unit) = coroutineScope {
            val oldStorage = storage
            val gate = linearSession.armNextOperation()
            val operationResult = async(Dispatchers.IO) { operation(oldStorage) }
            gate.entered.await()
            val switch = async(Dispatchers.Default) {
                linearSession.clear()
                linearSession.authenticate(PATIENT_B_UUID)
            }
            delay(100)
            assertFalse("账户切换不能越过已进入的存储操作", switch.isCompleted)
            gate.release.complete(Unit)
            operationResult.await()
            switch.await()
            assertThrows(StaleAccountScopeException::class.java) {
                runBlocking { oldStorage.findDraft("linear") }
            }
        }

        runWhileSwitchWaits { assertEquals("linear", it.findDraft("linear")?.draftId) }
        linearSession.authenticate(PATIENT_A_UUID)
        storage = linearProvider.current()
        lateinit var encrypted: EncryptedMediaAsset
        runWhileSwitchWaits { encrypted = it.encryptMedia(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3) }
        assertTrue(fileStore.encryptedMediaExists(PATIENT_A_UUID, encrypted.relativePath))
        assertFalse(fileStore.encryptedMediaExists(PATIENT_B_UUID, encrypted.relativePath))

        linearSession.authenticate(PATIENT_A_UUID)
        storage = linearProvider.current()
        runWhileSwitchWaits { assertEquals(1, it.checkpointUpload("linear", pendingCheckpoint())) }
        Unit
    }

    @Test
    fun samePatientUuidReloginIssuesNewIncarnationAndInvalidatesOldFacade() = runBlocking {
        sessions.authenticate(PATIENT_A_UUID)
        val old = provider.current()
        insertDraft(old, "same-patient")

        sessions.clear()
        sessions.authenticate(PATIENT_A_UUID)

        assertEquals("same-patient", provider.current().findDraft("same-patient")?.draftId)
        assertThrows(StaleAccountScopeException::class.java) {
            runBlocking { old.findDraft("same-patient") }
        }
        Unit
    }

    @Test
    fun 双媒体密文验证与Room发布保持原子且失败回滚新密文() = runBlocking {
        sessions.authenticate(PATIENT_A_UUID)
        val storage = provider.current()
        insertDraft(storage, "recording-success")
        val successVideo = plainFile("success-video", 257)
        val successAudio = plainFile("success-audio", 129)

        storage.publishRecordingMedia("recording-success", successVideo, successAudio, 2_000)

        assertEquals(DraftState.REVIEW_READY, storage.findDraft("recording-success")?.state)
        val successMedia = database.mediaDao().observeForDraft(PATIENT_A_UUID, "recording-success").first()
        assertEquals(setOf(MediaType.AUDIO, MediaType.VIDEO), successMedia.map { it.type }.toSet())
        assertTrue(successMedia.all { it.validationState == MediaValidationState.VALID })
        assertEquals(successVideo.sha256(), successMedia.single { it.type == MediaType.VIDEO }.sha256)

        insertDraft(storage, "recording-failure")
        val existing = storage.encryptMedia(ByteArrayInputStream(byteArrayOf(9, 8, 7)), 3)
        storage.insertMedia(
            "recording-failure", MediaType.AUDIO, existing.relativePath, "audio/mp4", 3,
            byteArrayOf(9, 8, 7).sha256(), MediaValidationState.VALID,
        )
        val before = root.walkTopDown().count { it.extension == "vef" }
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            runBlocking {
                storage.publishRecordingMedia(
                    "recording-failure",
                    plainFile("failed-video", 311),
                    plainFile("failed-audio", 211),
                    1_000,
                )
            }
        }

        assertEquals(DraftState.RECORDING, storage.findDraft("recording-failure")?.state)
        assertEquals(before, root.walkTopDown().count { it.extension == "vef" })
        assertEquals(
            listOf(MediaType.AUDIO),
            database.mediaDao().observeForDraft(PATIENT_A_UUID, "recording-failure").first().map { it.type },
        )
        Unit
    }

    @Test
    fun 换号与同UUID新incarnation后迟到Finalize都不能发布() = runBlocking {
        sessions.authenticate(PATIENT_A_UUID)
        val old = provider.current()
        insertDraft(old, "late-finalize")
        val video = plainFile("late-video", 64)
        val audio = plainFile("late-audio", 64)

        sessions.authenticate(PATIENT_B_UUID)
        assertThrows(StaleAccountScopeException::class.java) {
            runBlocking { old.publishRecordingMedia("late-finalize", video, audio, 1_000) }
        }
        sessions.authenticate(PATIENT_A_UUID)
        assertThrows(StaleAccountScopeException::class.java) {
            runBlocking { old.publishRecordingMedia("late-finalize", video, audio, 1_000) }
        }
        assertEquals(DraftState.RECORDING, provider.current().findDraft("late-finalize")?.state)
        Unit
    }

    @Test
    fun 取消与发布竞态时ReviewReady会原子回滚为失败并删除双密文() = runBlocking {
        sessions.authenticate(PATIENT_A_UUID)
        val storage = provider.current()
        insertDraft(storage, "cancel-after-publish")
        storage.publishRecordingMedia(
            "cancel-after-publish",
            plainFile("cancel-video", 257),
            plainFile("cancel-audio", 129),
            2_000,
        )
        val before = root.walkTopDown().count { it.extension == "vef" }

        storage.markRecordingInterrupted("cancel-after-publish", 1_000, "录制已取消")

        assertEquals(DraftState.INTERRUPTED, storage.findDraft("cancel-after-publish")?.state)
        assertTrue(database.mediaDao().observeForDraft(PATIENT_A_UUID, "cancel-after-publish").first().isEmpty())
        assertEquals(before - 2, root.walkTopDown().count { it.extension == "vef" })
        Unit
    }

    @Test
    fun 回看加载要求当前账户恰好一份有效可读的音视频() = runBlocking {
        sessions.authenticate(PATIENT_A_UUID)
        val storage = provider.current()
        insertDraft(storage, "review")
        storage.publishRecordingMedia("review", plainFile("review-video", 257), plainFile("review-audio", 129), 2_000)

        val snapshot = storage.loadReviewDraft("review")

        assertEquals(DraftState.REVIEW_READY, snapshot.state)
        assertEquals(setOf(MediaType.VIDEO, MediaType.AUDIO), snapshot.media.map { it.type }.toSet())
        assertTrue(snapshot.media.all { it.validationState == MediaValidationState.VALID && it.readableLength == it.sizeBytes })

        database.mediaDao().delete(PATIENT_A_UUID, "review", MediaType.AUDIO)
        assertThrows(ReviewMediaInvalidException::class.java) {
            runBlocking { storage.loadReviewDraft("review") }
        }
        Unit
    }

    @Test
    fun 确认提交原子转换并创建稳定幂等上传入口() = runBlocking {
        sessions.authenticate(PATIENT_A_UUID)
        val storage = provider.current()
        insertDraft(storage, "enqueue")
        storage.publishRecordingMedia("enqueue", plainFile("enqueue-video", 257), plainFile("enqueue-audio", 129), 2_000)

        assertTrue(storage.enqueueReviewDraft("enqueue"))
        assertFalse(storage.enqueueReviewDraft("enqueue"))

        assertEquals(DraftState.READY_TO_UPLOAD, storage.findDraft("enqueue")?.state)
        val job = database.uploadDao().find(PATIENT_A_UUID, "enqueue")
        assertEquals("upload-audio:enqueue", job?.audioGrantKey)
        assertEquals("upload-video:enqueue", job?.videoGrantKey)
        assertEquals("upload-submit:enqueue", job?.submitKey)
        Unit
    }

    @Test
    fun 重录使旧双密文先失效再删除且保留会话和creationKey() = runBlocking {
        sessions.authenticate(PATIENT_A_UUID)
        val storage = provider.current()
        insertDraft(storage, "rerecord")
        storage.publishRecordingMedia("rerecord", plainFile("rerecord-video", 257), plainFile("rerecord-audio", 129), 2_000)
        val old = database.mediaDao().observeForDraft(PATIENT_A_UUID, "rerecord").first()
        val before = storage.findDraft("rerecord")!!

        val reset = storage.prepareRerecord("rerecord")

        assertEquals(before.sessionId, reset.sessionId)
        assertEquals(before.creationKey, reset.creationKey)
        assertEquals(DraftState.RECORDING, storage.findDraft("rerecord")?.state)
        assertTrue(database.mediaDao().observeForDraft(PATIENT_A_UUID, "rerecord").first().isEmpty())
        old.forEach { media ->
            assertThrows(com.vocaease.patient.core.security.EncryptedMediaException::class.java) {
                fileStore.open(PATIENT_A_UUID, media.encryptedRelativePath)
            }
        }
        Unit
    }

    @Test
    fun 重录第二个密文删除失败时全部reader先撤销且可幂等重试() = runBlocking {
        sessions.authenticate(PATIENT_A_UUID)
        var deletes = 0
        val injectedStore = ChunkedAesGcmFileStore(
            context = ApplicationProvider.getApplicationContext(),
            rootDirectory = File(contextFiles(), "delete-failure-${System.nanoTime()}"),
            failureInjector = { step ->
                if (step == StoreIoStep.DELETE_MEDIA && ++deletes == 2) error("injected delete")
            },
        )
        val injectedProvider = AccountScopedDraftStorageProvider(database, injectedStore, sessions)
        val storage = injectedProvider.current()
        insertDraft(storage, "delete-failure")
        storage.publishRecordingMedia(
            "delete-failure", plainFile("delete-video", 257), plainFile("delete-audio", 129), 2_000,
        )
        val old = database.mediaDao().findAll(PATIENT_A_UUID, "delete-failure")
        val oldSource = storage.encryptedMediaDataSource(old.first().encryptedRelativePath)
        oldSource.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse("vocaease://review/old")))

        assertThrows(Exception::class.java) { runBlocking { storage.prepareRerecord("delete-failure") } }

        assertTrue(database.mediaDao().findAll(PATIENT_A_UUID, "delete-failure").all {
            it.validationState == MediaValidationState.INVALID
        })
        assertThrows(Exception::class.java) { oldSource.read(ByteArray(1), 0, 1) }
        oldSource.close()
        assertEquals(DraftState.REVIEW_READY, storage.findDraft("delete-failure")?.state)

        val reset = storage.prepareRerecord("delete-failure")
        assertEquals(DraftState.RECORDING, reset.state)
        assertTrue(database.mediaDao().findAll(PATIENT_A_UUID, "delete-failure").isEmpty())
        Unit
    }

    @Test
    fun 重录Room提交失败时密文虽已删除但行保持INVALID并可安全重试() = runBlocking {
        sessions.authenticate(PATIENT_A_UUID)
        val storage = provider.current()
        insertDraft(storage, "db-failure")
        storage.publishRecordingMedia(
            "db-failure", plainFile("db-video", 257), plainFile("db-audio", 129), 2_000,
        )
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_rerecord BEFORE UPDATE OF state ON drafts " +
                "WHEN NEW.draft_id = 'db-failure' AND NEW.state = 'RECORDING' " +
                "BEGIN SELECT RAISE(ABORT, 'injected'); END",
        )

        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            runBlocking { storage.prepareRerecord("db-failure") }
        }
        assertEquals(DraftState.REVIEW_READY, storage.findDraft("db-failure")?.state)
        assertTrue(database.mediaDao().findAll(PATIENT_A_UUID, "db-failure").all {
            it.validationState == MediaValidationState.INVALID
        })
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_rerecord")

        assertEquals(DraftState.RECORDING, storage.prepareRerecord("db-failure").state)
        Unit
    }

    @Test
    fun 七天清理只删除当前账户刚好到期的未入队本地态() = runBlocking {
        sessions.authenticate(PATIENT_A_UUID)
        val accountA = provider.current()
        suspend fun add(id: String, state: DraftState, expiresAt: Long) = accountA.insertDraft(
            id, "song", "session-$id", "create-$id", state, 0, 0, expiresAt, null,
        )
        add("recording", DraftState.RECORDING, 100)
        add("review", DraftState.REVIEW_READY, 100)
        add("interrupted", DraftState.INTERRUPTED, 100)
        add("future", DraftState.REVIEW_READY, 101)
        add("failed", DraftState.FAILED, 100)
        add("ready-upload", DraftState.READY_TO_UPLOAD, 100)
        add("uploading", DraftState.UPLOADING, 100)
        add("submitted", DraftState.SUBMITTED, 100)

        sessions.authenticate(PATIENT_B_UUID)
        val accountB = provider.current()
        accountB.insertDraft("other", "song", "session-other", "create-other", DraftState.REVIEW_READY, 0, 0, 100, null)
        sessions.authenticate(PATIENT_A_UUID)

        assertEquals(3, provider.current().cleanupExpiredLocalDrafts(100))
        assertEquals(null, provider.current().findDraft("recording"))
        assertEquals(null, provider.current().findDraft("review"))
        assertEquals(null, provider.current().findDraft("interrupted"))
        listOf("future", "failed", "ready-upload", "uploading", "submitted").forEach {
            assertTrue(provider.current().findDraft(it) != null)
        }
        sessions.authenticate(PATIENT_B_UUID)
        assertTrue(provider.current().findDraft("other") != null)
        Unit
    }

    @Test
    fun 无可用媒体的中断草稿仍可加载身份以供重录或删除() = runBlocking {
        sessions.authenticate(PATIENT_A_UUID)
        val storage = provider.current()
        insertDraft(storage, "interrupted-empty")
        storage.markRecordingInterrupted("interrupted-empty", 0, "录制文件损坏")

        val review = storage.loadReviewDraft("interrupted-empty")

        assertEquals(DraftState.INTERRUPTED, review.state)
        assertTrue(review.media.isEmpty())
        Unit
    }

    private suspend fun insertDraft(storage: AccountScopedDraftStorage, id: String) = storage.insertDraft(
        draftId = id, songId = "song", sessionId = "session-$id", creationKey = "create-$id",
        state = DraftState.RECORDING, durationMs = 0, createdAt = 0, expiresAt = DraftEntity.MAX_RETENTION_MILLIS,
        interruptionReason = null,
    )

    private fun plainFile(prefix: String, size: Int) = File(root, "$prefix-${System.nanoTime()}.bin").apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(size) { index -> (index * 31).toByte() })
    }

    private fun contextFiles(): File = ApplicationProvider.getApplicationContext<Context>().filesDir

    private fun File.sha256() = readBytes().sha256()
    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it) }

    private fun pendingCheckpoint() = UploadCheckpoint(
        overallState = UploadOverallState.PAUSED,
        audioGrantState = UploadStepState.PENDING,
        videoGrantState = UploadStepState.PENDING,
        audioUploadState = UploadStepState.PENDING,
        videoUploadState = UploadStepState.PENDING,
        audioReceiptState = UploadStepState.PENDING,
        videoReceiptState = UploadStepState.PENDING,
        audioConfirmState = UploadStepState.PENDING,
        videoConfirmState = UploadStepState.PENDING,
        submitState = UploadStepState.PENDING,
        audioAssetKey = null,
        videoAssetKey = null,
        audioObjectKey = null,
        videoObjectKey = null,
        audioReceipt = null,
        videoReceipt = null,
        audioConfirmedAt = null,
        videoConfirmedAt = null,
        attemptCount = 0,
        nextRetryAt = null,
        lastSafeError = null,
    )

    private companion object {
        const val PATIENT_A_UUID = "11111111-1111-4111-8111-111111111111"
        const val PATIENT_B_UUID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}

private class BlockingAuthenticatedAccountSession : AuthenticatedAccountSession {
    private val mutex = Mutex()
    @Volatile private var lease: AuthenticatedAccountLease? = null
    private val nextGate = AtomicReference<OperationGate?>(null)

    suspend fun authenticate(patientId: String) = mutex.withLock {
        lease = AuthenticatedAccountLease(patientId)
    }

    suspend fun clear() = mutex.withLock {
        lease = null
    }

    fun armNextOperation(): OperationGate = OperationGate().also {
        check(nextGate.compareAndSet(null, it))
    }

    override fun current(): AuthenticatedAccountLease? = lease

    override suspend fun <T> withCurrentLease(
        expected: AuthenticatedAccountLease,
        operation: suspend () -> T,
    ): T = mutex.withLock {
        if (lease !== expected) throw StaleAccountScopeException()
        nextGate.getAndSet(null)?.let { gate ->
            gate.entered.complete(Unit)
            gate.release.await()
        }
        operation()
    }
}

private class OperationGate(
    val entered: CompletableDeferred<Unit> = CompletableDeferred(),
    val release: CompletableDeferred<Unit> = CompletableDeferred(),
)
