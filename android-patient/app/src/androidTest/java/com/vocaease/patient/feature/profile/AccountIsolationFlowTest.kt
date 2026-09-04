package com.vocaease.patient.feature.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.database.AccountScopedDraftStorageProvider
import com.vocaease.patient.core.database.DraftState
import com.vocaease.patient.core.database.MutableAuthenticatedAccountSession
import com.vocaease.patient.core.database.StaleAccountScopeException
import com.vocaease.patient.core.database.VocaEaseDatabase
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIsolationFlowTest {
    private lateinit var database: VocaEaseDatabase
    private lateinit var session: MutableAuthenticatedAccountSession
    private lateinit var storageProvider: AccountScopedDraftStorageProvider

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = VocaEaseDatabase.inMemory(context, allowMainThreadQueries = true)
        session = MutableAuthenticatedAccountSession()
        storageProvider = AccountScopedDraftStorageProvider(database, ChunkedAesGcmFileStore(context), session)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun A保留后B零可见且A新incarnation可恢复旧lease立即失效() = runBlocking {
        session.authenticate(PATIENT_A)
        val oldA = storageProvider.current()
        oldA.insertDraft("draft-a", "song-a", "session-a", "create-a", DraftState.REVIEW_READY, 10, 0, 100, null)

        session.clear()
        session.authenticate(PATIENT_B)
        assertNull(storageProvider.current().findDraft("draft-a"))
        assertThrows(StaleAccountScopeException::class.java) { runBlocking { oldA.findDraft("draft-a") } }

        session.clear()
        session.authenticate(PATIENT_A)
        val newA = storageProvider.current()
        assertEquals("song-a", newA.findDraft("draft-a")?.songId)
        assertNotEquals(oldA.cleanupScopeToken, newA.cleanupScopeToken)
    }

    @Test
    fun 持久退出意图立即锁死同账户storage直到意图完成删除() = runBlocking {
        session.authenticate(PATIENT_A)
        val storage = storageProvider.current()
        val store = RoomAccountExitIntentStore(database, PATIENT_A)
        val persisted = store.persist(
            LogoutOperationOwner(storage.accountScopeHash, storage.cleanupScopeToken, 1, "isolation-lock"),
            LogoutChoice.RETAIN,
        )

        assertThrows(StaleAccountScopeException::class.java) {
            runBlocking { storage.insertDraft("blocked", "song", "session", "create", DraftState.RECORDING, 0, 0, 1, null) }
        }

        val ready = AccountExitProcessor(store, NoopExitEffects).converge(persisted)
        assertEquals(1, store.deleteReady(ready))
        storage.insertDraft("allowed", "song", "session", "create", DraftState.RECORDING, 0, 0, 1, null)
        assertEquals("song", storage.findDraft("allowed")?.songId)
    }
}

private object NoopExitEffects : AccountExitEffects {
    override suspend fun pauseAndLock(intent: LogoutIntent) = Unit
    override suspend fun cancelAndAwait(intent: LogoutIntent) = Unit
    override suspend fun revokeRuntimeAccess(intent: LogoutIntent) = Unit
    override suspend fun deleteAccountData(intent: LogoutIntent) = Unit
}

private const val PATIENT_A = "11111111-1111-4111-8111-111111111111"
private const val PATIENT_B = "22222222-2222-4222-8222-222222222222"
