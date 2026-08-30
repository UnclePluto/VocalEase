package com.vocaease.patient.core.cleanup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vocaease.patient.core.database.AccountScopedDraftStorageProvider
import com.vocaease.patient.core.database.MutableAuthenticatedAccountSession
import com.vocaease.patient.core.database.VocaEaseDatabase
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DailyDraftCleanupSchedulerTest {
    private lateinit var context: Context
    private lateinit var database: VocaEaseDatabase
    private lateinit var sessions: MutableAuthenticatedAccountSession
    private lateinit var provider: AccountScopedDraftStorageProvider
    private lateinit var root: File
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = VocaEaseDatabase.inMemory(context, allowMainThreadQueries = true)
        sessions = MutableAuthenticatedAccountSession()
        root = File(context.filesDir, "cleanup-schedule-${System.nanoTime()}")
        provider = AccountScopedDraftStorageProvider(database, ChunkedAesGcmFileStore(context, root), sessions)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        workManager.cancelAllWorkByTag(DraftCleanupWorkContract.WORK_TAG).result.get(5, TimeUnit.SECONDS)
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun 认证排唯一无网络oneTime同账户新incarnation替换且登出取消() = runBlocking {
        sessions.authenticate(PATIENT)
        val first = provider.current()
        val scheduler = DailyDraftCleanupScheduler(workManager)
        scheduler.replaceFor(first)
        val name = DraftCleanupWorkContract(first.accountScopeHash, first.cleanupScopeToken).uniqueWorkName
        val firstInfo = workManager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).single()

        assertEquals(WorkInfo.State.ENQUEUED, firstInfo.state)
        assertEquals(NetworkType.NOT_REQUIRED, firstInfo.constraints.requiredNetworkType)
        assertTrue(firstInfo.nextScheduleTimeMillis - System.currentTimeMillis() > 23L * 60L * 60L * 1_000L)

        sessions.clear()
        sessions.authenticate(PATIENT)
        val second = provider.current()
        assertNotEquals(first.cleanupScopeToken, second.cleanupScopeToken)
        scheduler.replaceFor(second)
        val secondInfo = workManager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)
            .single { it.state == WorkInfo.State.ENQUEUED }
        assertNotEquals(firstInfo.id, secondInfo.id)

        scheduler.replaceFor(null)
        val finalStates = workManager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)
        assertTrue(finalStates.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING })
    }

    @Test
    fun 旧worker末次检查后换incarnation也不能把旧链追加到新token之后() = runBlocking {
        sessions.authenticate(PATIENT)
        val oldStorage = provider.current()
        val oldContract = DraftCleanupWorkContract(oldStorage.accountScopeHash, oldStorage.cleanupScopeToken)
        val scheduler = DailyDraftCleanupScheduler(workManager)
        scheduler.replaceFor(oldStorage)
        val checked = CompletableDeferred<Unit>()
        val releaseOldWorker = CompletableDeferred<Unit>()
        val oldWorker = async(Dispatchers.IO) {
            assertTrue(oldStorage.isLeaseActive())
            checked.complete(Unit)
            releaseOldWorker.await()
            DailyDraftCleanupScheduler(workManager).appendNext(oldContract)
        }
        checked.await()

        sessions.clear()
        scheduler.replaceFor(null)
        sessions.authenticate(PATIENT)
        val newStorage = provider.current()
        scheduler.replaceFor(newStorage)
        val newInfo = workManager.getWorkInfosForUniqueWork(oldContract.uniqueWorkName).get(5, TimeUnit.SECONDS)
            .single { it.state == WorkInfo.State.ENQUEUED }

        releaseOldWorker.complete(Unit)
        oldWorker.await()

        val unfinished = workManager.getWorkInfosForUniqueWork(oldContract.uniqueWorkName).get(5, TimeUnit.SECONDS)
            .filter { !it.state.isFinished }
        assertEquals(listOf(newInfo.id), unfinished.map { it.id })
        assertNotEquals(oldContract.scopeToken, newStorage.cleanupScopeToken)
    }

    private companion object {
        const val PATIENT = "123e4567-e89b-12d3-a456-426614174099"
    }
}
