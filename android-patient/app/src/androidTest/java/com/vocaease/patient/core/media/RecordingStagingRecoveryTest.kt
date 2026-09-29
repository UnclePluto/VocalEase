package com.vocaease.patient.core.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocaease.patient.core.database.AccountScopedDraftStorage
import com.vocaease.patient.core.database.AccountScopedDraftStorageProvider
import com.vocaease.patient.core.database.DraftEntity
import com.vocaease.patient.core.database.DraftState
import com.vocaease.patient.core.database.MutableAuthenticatedAccountSession
import com.vocaease.patient.core.database.VocaEaseDatabase
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.core.security.StoreIoStep
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingStagingRecoveryTest {
    private lateinit var context: Context
    private lateinit var database: VocaEaseDatabase
    private lateinit var sessions: MutableAuthenticatedAccountSession
    private lateinit var encryptedRoot: File
    private lateinit var fileStore: ChunkedAesGcmFileStore
    private lateinit var provider: AccountScopedDraftStorageProvider
    private lateinit var staging: PrivateRecordingTempFiles

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "recordings/plaintext").deleteRecursively()
        database = VocaEaseDatabase.inMemory(context, allowMainThreadQueries = true)
        sessions = MutableAuthenticatedAccountSession()
        encryptedRoot = File(context.filesDir, "recovery-encrypted-${System.nanoTime()}")
        fileStore = ChunkedAesGcmFileStore(context, encryptedRoot)
        provider = AccountScopedDraftStorageProvider(database, fileStore, sessions)
        staging = PrivateRecordingTempFiles(context, nowMillis = { NOW })
    }

    @After
    fun tearDown() {
        database.close()
        runCatching { fileStore.destroyAccountEncryption(PATIENT_A) }
        runCatching { fileStore.destroyAccountEncryption(PATIENT_B) }
        File(context.filesDir, "recordings/plaintext").deleteRecursively()
        encryptedRoot.deleteRecursively()
    }

    @Test
    fun 真实AVC_AAC在24小时内恢复为双密文中断草稿并删除全部明文() = runBlocking {
        sessions.authenticate(PATIENT_A)
        val storage = provider.current()
        insertDraft(storage, "recover")
        val identity = identity(storage, "recover")
        val video = staging.createVideo(identity)
        copyAsset("sample_avc_aac.mp4", video)

        val result = RecordingStagingRecovery(staging, nowMillis = { NOW }).recover(storage)

        assertEquals(1, result.recovered)
        assertEquals(DraftState.INTERRUPTED, storage.findDraft("recover")?.state)
        assertEquals(2, storage.loadReviewDraft("recover").media.size)
        assertTrue(staging.listRecoverable(storage.accountScopeHash).isEmpty())
        assertFalse(video.exists())
    }

    @Test
    fun 损坏与超过24小时安全中断且跨账户sidecar保留() = runBlocking {
        sessions.authenticate(PATIENT_A)
        val accountA = provider.current()
        insertDraft(accountA, "corrupt")
        val corrupt = staging.createVideo(identity(accountA, "corrupt")).apply { writeText("not-mp4") }
        insertDraft(accountA, "old")
        val oldFiles = PrivateRecordingTempFiles(context, nowMillis = { 0L })
        val old = oldFiles.createVideo(identity(accountA, "old")).also { copyAsset("sample_avc_aac.mp4", it) }

        sessions.authenticate(PATIENT_B)
        val accountB = provider.current()
        insertDraft(accountB, "other")
        val other = staging.createVideo(identity(accountB, "other")).also { copyAsset("sample_avc_aac.mp4", it) }
        sessions.authenticate(PATIENT_A)

        val result = RecordingStagingRecovery(staging, nowMillis = { NOW }).recover(provider.current())

        assertEquals(2, result.discarded)
        assertFalse(corrupt.exists())
        assertFalse(old.exists())
        assertTrue(other.exists())
        assertEquals(DraftState.INTERRUPTED, provider.current().findDraft("corrupt")?.state)
        sessions.authenticate(PATIENT_B)
        assertEquals(DraftState.RECORDING, provider.current().findDraft("other")?.state)
        assertEquals(1, staging.listRecoverable(provider.current().accountScopeHash).size)
    }

    @Test
    fun 加密失败或发布前换号保留sidecar且不留下新密文和错误状态() = runBlocking {
        sessions.authenticate(PATIENT_A)
        // 用明确IO故障验证发布补偿；fixture与sidecar必须保留给后续重试。
        val injectedStore = ChunkedAesGcmFileStore(
            context = context,
            rootDirectory = encryptedRoot,
            failureInjector = { if (it == StoreIoStep.FILE_FSYNC) error("injected") },
        )
        val failureProvider = AccountScopedDraftStorageProvider(database, injectedStore, sessions)
        val failureStorage = failureProvider.current()
        insertDraft(failureStorage, "retry")
        val retryVideo = staging.createVideo(identity(failureStorage, "retry")).also {
            copyAsset("sample_avc_aac.mp4", it)
        }

        val failed = RecordingStagingRecovery(staging, nowMillis = { NOW }).recover(failureStorage)

        assertEquals(1, failed.retryableFailures)
        assertTrue(retryVideo.exists())
        assertEquals(DraftState.RECORDING, failureStorage.findDraft("retry")?.state)
        assertEquals(0, encryptedRoot.walkTopDown().count { it.extension == "vef" })

        sessions.authenticate(PATIENT_A)
        val current = provider.current()
        insertDraft(current, "switch")
        val switchedVideo = staging.createVideo(identity(current, "switch")).also {
            copyAsset("sample_avc_aac.mp4", it)
        }
        val switched = RecordingStagingRecovery(
            staging,
            nowMillis = { NOW },
            beforePublish = { sessions.authenticate(PATIENT_B) },
        ).recover(current)
        assertEquals(1, switched.retryableFailures)
        assertTrue(switchedVideo.exists())
        sessions.authenticate(PATIENT_A)
        assertEquals(DraftState.RECORDING, provider.current().findDraft("switch")?.state)
    }

    @Test
    fun 无效媒体标记中断的数据库失败必须保留sidecar并报告可重试() = runBlocking {
        sessions.authenticate(PATIENT_A)
        val storage = provider.current()
        insertDraft(storage, "db-first")
        val video = staging.createVideo(identity(storage, "db-first")).apply { writeText("not-mp4") }
        val metadata = staging.listRecoverable(storage.accountScopeHash).single().metadata
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_recovery_state BEFORE UPDATE OF state ON drafts " +
                "WHEN NEW.draft_id='db-first' AND NEW.state='INTERRUPTED' " +
                "BEGIN SELECT RAISE(ABORT, 'injected recovery failure'); END",
        )

        val failed = RecordingStagingRecovery(staging, nowMillis = { NOW }).recover(storage)

        assertEquals(1, failed.retryableFailures)
        assertEquals(0, failed.discarded)
        assertEquals(DraftState.RECORDING, storage.findDraft("db-first")?.state)
        assertTrue(video.exists())
        assertTrue(metadata.exists())

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_recovery_state")
        val retried = RecordingStagingRecovery(staging, nowMillis = { NOW }).recover(storage)
        assertEquals(1, retried.discarded)
        assertEquals(DraftState.INTERRUPTED, storage.findDraft("db-first")?.state)
        assertFalse(video.exists())
        assertFalse(metadata.exists())
    }

    @Test
    fun 绑定失效与过期标记中断的数据库失败也必须先保留恢复文件() = runBlocking {
        sessions.authenticate(PATIENT_A)
        val storage = provider.current()
        insertDraft(storage, "binding-db-first")
        insertDraft(storage, "expired-db-first")
        val bindingFiles = PrivateRecordingTempFiles(context, nowMillis = { NOW })
        val bindingVideo = bindingFiles.createVideo(
            identity(storage, "binding-db-first").copy(sessionId = "wrong-session"),
        ).also { copyAsset("sample_avc_aac.mp4", it) }
        val expiredFiles = PrivateRecordingTempFiles(context, nowMillis = { 0L })
        val expiredVideo = expiredFiles.createVideo(identity(storage, "expired-db-first"))
            .also { copyAsset("sample_avc_aac.mp4", it) }
        val metadata = File(plaintextRoot(), bindingVideo.nameWithoutExtension + ".recovery") to
            File(plaintextRoot(), expiredVideo.nameWithoutExtension + ".recovery")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_binding_expired_recovery BEFORE UPDATE OF state ON drafts " +
                "WHEN NEW.draft_id IN ('binding-db-first','expired-db-first') AND NEW.state='INTERRUPTED' " +
                "BEGIN SELECT RAISE(ABORT, 'injected recovery failure'); END",
        )

        val failed = RecordingStagingRecovery(staging, nowMillis = { NOW }).recover(storage)

        assertEquals(2, failed.retryableFailures)
        assertEquals(0, failed.discarded)
        listOf(bindingVideo, expiredVideo, metadata.first, metadata.second).forEach { assertTrue(it.exists()) }
        assertEquals(DraftState.RECORDING, storage.findDraft("binding-db-first")?.state)
        assertEquals(DraftState.RECORDING, storage.findDraft("expired-db-first")?.state)

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_binding_expired_recovery")
        val retried = RecordingStagingRecovery(staging, nowMillis = { NOW }).recover(storage)
        assertEquals(2, retried.discarded)
        listOf(bindingVideo, expiredVideo, metadata.first, metadata.second).forEach { assertFalse(it.exists()) }
        assertEquals(DraftState.INTERRUPTED, storage.findDraft("binding-db-first")?.state)
        assertEquals(DraftState.INTERRUPTED, storage.findDraft("expired-db-first")?.state)
    }

    @Test
    fun 恢复窗口精确区分24小时减一毫秒等于24小时超一毫秒与未来时间() = runBlocking {
        sessions.authenticate(PATIENT_A)
        val storage = provider.current()
        val maxAge = 24L * 60L * 60L * 1_000L
        listOf(
            "inside" to (NOW - maxAge + 1L),
            "exact" to (NOW - maxAge),
            "expired" to (NOW - maxAge - 1L),
            "future" to (NOW + 1L),
        ).forEach { (id, createdAt) ->
            insertDraft(storage, id)
            PrivateRecordingTempFiles(context, nowMillis = { createdAt })
                .createVideo(identity(storage, id))
                .also { copyAsset("sample_avc_aac.mp4", it) }
        }

        val result = RecordingStagingRecovery(staging, nowMillis = { NOW }).recover(storage)

        assertEquals(1, result.recovered)
        assertEquals(3, result.discarded)
        assertEquals(2, storage.loadReviewDraft("inside").media.size)
        listOf("exact", "expired", "future").forEach { id ->
            assertEquals(DraftState.INTERRUPTED, storage.findDraft(id)?.state)
            assertTrue(storage.loadReviewDraft(id).media.isEmpty())
        }
    }

    private suspend fun insertDraft(storage: AccountScopedDraftStorage, id: String) = storage.insertDraft(
        id, "song", "session-$id", "create-$id", DraftState.RECORDING, 0, 0,
        DraftEntity.MAX_RETENTION_MILLIS, null,
    )

    private fun identity(storage: AccountScopedDraftStorage, id: String) = RecordingStagingIdentity(
        storage.accountScopeHash, id, "session-$id", "create-$id",
    )

    private fun copyAsset(name: String, target: File) {
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            target.outputStream().use(input::copyTo)
        }
    }

    private fun plaintextRoot() = File(context.filesDir, "recordings/plaintext")

    private companion object {
        const val PATIENT_A = "123e4567-e89b-12d3-a456-426614174000"
        const val PATIENT_B = "123e4567-e89b-12d3-a456-426614174001"
        const val NOW = 24L * 60L * 60L * 1_000L + 10_000L
    }
}
