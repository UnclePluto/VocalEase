package com.vocaease.patient.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import java.io.File
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
        runCatching { fileStore.destroyAccountEncryption("patient-a") }
        runCatching { fileStore.destroyAccountEncryption("patient-b") }
        root.deleteRecursively()
    }

    @Test
    fun facadeNeverAcceptsScopeAndOldLeaseFailsImmediatelyAfterLogoutOrSwitch() = runBlocking {
        sessions.authenticate("patient-a")
        val accountA = provider.current()
        insertDraft(accountA, "shared")

        sessions.clear()
        assertThrows(StaleAccountScopeException::class.java) {
            runBlocking { accountA.findDraft("shared") }
        }

        sessions.authenticate("patient-b")
        val accountB = provider.current()
        assertEquals(null, accountB.findDraft("shared"))
        assertThrows(StaleAccountScopeException::class.java) {
            runBlocking { accountA.deleteDraft("shared") }
        }
        Unit
    }

    @Test
    fun mediaAndJobInsertionValidateParentInsideBoundAccountTransaction() = runBlocking {
        sessions.authenticate("patient-a")
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
        sessions.authenticate("patient-a")
        val storage = provider.current()
        storage.encryptMedia(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        insertDraft(storage, "d")

        assertEquals(false, storage.destroyEncryptionMaterialIfNoDrafts())
        assertEquals(1, storage.deleteDraft("d"))
        assertEquals(true, storage.destroyEncryptionMaterialIfNoDrafts())
        Unit
    }

    private suspend fun insertDraft(storage: AccountScopedDraftStorage, id: String) = storage.insertDraft(
        draftId = id, songId = "song", sessionId = "session-$id", creationKey = "create-$id",
        state = DraftState.RECORDING, durationMs = 0, createdAt = 0, expiresAt = DraftEntity.MAX_RETENTION_MILLIS,
        interruptionReason = null,
    )
}
