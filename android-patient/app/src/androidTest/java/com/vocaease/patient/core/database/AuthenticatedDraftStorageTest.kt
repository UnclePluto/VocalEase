package com.vocaease.patient.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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

    private suspend fun insertDraft(storage: AccountScopedDraftStorage, id: String) = storage.insertDraft(
        draftId = id, songId = "song", sessionId = "session-$id", creationKey = "create-$id",
        state = DraftState.RECORDING, durationMs = 0, createdAt = 0, expiresAt = DraftEntity.MAX_RETENTION_MILLIS,
        interruptionReason = null,
    )

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
