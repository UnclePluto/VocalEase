package com.vocaease.patient.feature.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.database.AccountExitStage
import com.vocaease.patient.core.database.VocaEaseDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountExitRecoveryTest {
    private lateinit var database: VocaEaseDatabase

    @Before
    fun setUp() {
        database = VocaEaseDatabase.inMemory(
            ApplicationProvider.getApplicationContext<Context>(),
            allowMainThreadQueries = true,
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun 删除退出在每个副作用后崩溃都能从Room意图幂等收敛() = runBlocking {
        listOf(
            AccountExitStage.INTENT_WRITTEN,
            AccountExitStage.PAUSED_LOCKED,
            AccountExitStage.WORK_CANCELLED,
            AccountExitStage.RUNTIME_REVOKED,
            AccountExitStage.DATA_DELETED,
        ).forEachIndexed { index, crashStage ->
            val owner = owner("exit-$index")
            val store = RoomAccountExitIntentStore(database, PATIENT_ID)
            val intent = store.persist(owner, LogoutChoice.DELETE)
            val firstEffects = RecordingExitEffects()
            val crashing = AccountExitProcessor(store, firstEffects) { completedStage ->
                if (completedStage == crashStage) throw SimulatedCrash()
            }

            assertThrows(SimulatedCrash::class.java) { runBlocking { crashing.converge(intent) } }

            val recovered = AccountExitProcessor(RoomAccountExitIntentStore(database, PATIENT_ID), firstEffects)
                .converge(intent)
            assertEquals(AccountExitStage.READY_TO_CLEAR, recovered.stage)
            assertEquals(1, store.deleteReady(recovered))
        }
    }

    @Test
    fun 保留退出不会执行数据删除且恢复后保持同一操作所有权() = runBlocking {
        val owner = owner("retain-exit")
        val store = RoomAccountExitIntentStore(database, PATIENT_ID)
        val intent = store.persist(owner, LogoutChoice.RETAIN)
        val effects = RecordingExitEffects()

        val recovered = AccountExitProcessor(store, effects).converge(intent)

        assertEquals(AccountExitStage.READY_TO_CLEAR, recovered.stage)
        assertEquals(0, effects.deleteCalls)
        assertEquals(owner.operationId, recovered.operationId)
        assertEquals(owner.incarnationProof, recovered.incarnationProof)
    }

    @Test
    fun 远端前绑定远端成功与认证清理窗口均有Room终态且可幂等收尾() = runBlocking {
        val owner = owner("terminal-exit")
        val firstStore = RoomAccountExitIntentStore(database, PATIENT_ID)
        val storageReady = AccountExitProcessor(firstStore, RecordingExitEffects())
            .converge(firstStore.persist(owner, LogoutChoice.RETAIN))

        val authBound = firstStore.advance(storageReady, AccountExitStage.AUTH_BOUND)
        assertEquals(AccountExitStage.AUTH_BOUND, RoomAccountExitIntentStore(database, PATIENT_ID).find()?.stage)

        val afterRemoteCrash = RoomAccountExitIntentStore(database, PATIENT_ID)
        val remoteRevoked = afterRemoteCrash.advance(authBound, AccountExitStage.REMOTE_REVOKED)
        assertEquals(AccountExitStage.REMOTE_REVOKED, afterRemoteCrash.find()?.stage)

        val afterClearCrash = RoomAccountExitIntentStore(database, PATIENT_ID)
        val authCleared = afterClearCrash.advance(remoteRevoked, AccountExitStage.AUTH_CLEARED)
        assertEquals(AccountExitStage.AUTH_CLEARED, afterClearCrash.find()?.stage)
        assertEquals(1, afterClearCrash.deleteCompletedLogout(authCleared))
        assertEquals(null, afterClearCrash.find())
    }

    @Test
    fun 同账户新incarnation可接管存储阶段但保持原操作身份() = runBlocking {
        val firstOwner = owner("old-operation")
        val store = RoomAccountExitIntentStore(database, PATIENT_ID)
        val written = store.persist(firstOwner, LogoutChoice.RETAIN)
        val nextOwner = LogoutOperationOwner(
            accountScopeHash = firstOwner.accountScopeHash,
            incarnationProof = "c".repeat(64),
            sessionEpoch = firstOwner.sessionEpoch + 9,
            operationId = firstOwner.operationId,
        )

        val adopted = store.takeover(written, nextOwner)

        assertEquals(nextOwner.incarnationProof, adopted.incarnationProof)
        assertEquals(nextOwner.sessionEpoch, adopted.sessionEpoch)
        assertEquals(firstOwner.operationId, adopted.operationId)
        assertEquals(AccountExitStage.INTENT_WRITTEN, adopted.stage)
    }

    private fun owner(operationId: String) = LogoutOperationOwner(
        accountScopeHash = "a".repeat(64),
        incarnationProof = "b".repeat(64),
        sessionEpoch = 7,
        operationId = operationId,
    )
}

private const val PATIENT_ID = "11111111-1111-4111-8111-111111111111"

private class RecordingExitEffects : AccountExitEffects {
    var deleteCalls = 0
    override suspend fun pauseAndLock(intent: LogoutIntent) = Unit
    override suspend fun cancelAndAwait(intent: LogoutIntent) = Unit
    override suspend fun revokeRuntimeAccess(intent: LogoutIntent) = Unit
    override suspend fun deleteAccountData(intent: LogoutIntent) {
        deleteCalls += 1
    }
}

private class SimulatedCrash : RuntimeException()
