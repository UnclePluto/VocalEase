package com.vocaease.patient.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.feature.profile.AccountScopedPendingUploadCounter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingUploadCounterIntegrationTest {
    private lateinit var database: VocaEaseDatabase
    private lateinit var session: MutableAuthenticatedAccountSession
    private lateinit var fileStore: ChunkedAesGcmFileStore
    private lateinit var root: File
    private lateinit var provider: AccountScopedDraftStorageProvider

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = VocaEaseDatabase.inMemory(context, allowMainThreadQueries = true)
        session = MutableAuthenticatedAccountSession()
        root = File(context.filesDir, "pending-counter-${System.nanoTime()}")
        fileStore = ChunkedAesGcmFileStore(context, root)
        provider = AccountScopedDraftStorageProvider(database, fileStore, session)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun 真实Room计数按账户隔离且排除完成取消并在登出后拒绝访问() = runBlocking {
        session.authenticate(PATIENT_A)
        insertJob("a-pending", UploadOverallState.PAUSED)
        insertJob("a-failed", UploadOverallState.FAILED)
        insertJob("a-completed", UploadOverallState.COMPLETED)
        insertJob("a-cancelled", UploadOverallState.CANCELLED)
        val counter = AccountScopedPendingUploadCounter(provider)
        assertEquals(2, counter.count())

        session.authenticate(PATIENT_B)
        insertJob("b-pending", UploadOverallState.WAITING_NETWORK)
        assertEquals(1, counter.count())

        session.clear()
        assertThrows(StaleAccountScopeException::class.java) {
            runBlocking { counter.count() }
        }
        Unit
    }

    private suspend fun insertJob(id: String, state: UploadOverallState) {
        val storage = provider.current()
        storage.insertDraft(
            draftId = id,
            songId = "song-$id",
            sessionId = "session-$id",
            creationKey = "create-$id",
            state = DraftState.RECORDING,
            durationMs = 0,
            createdAt = 0,
            expiresAt = DraftEntity.MAX_RETENTION_MILLIS,
            interruptionReason = null,
        )
        val scope = session.current()!!.patientId
        database.uploadDao().insert(
            UploadJobEntity.newPending(scope, id, "audio-$id", "video-$id", "submit-$id")
                .copy(overallState = state),
        )
    }

    private companion object {
        const val PATIENT_A = "11111111-1111-4111-8111-111111111111"
        const val PATIENT_B = "22222222-2222-4222-8222-222222222222"
    }
}
