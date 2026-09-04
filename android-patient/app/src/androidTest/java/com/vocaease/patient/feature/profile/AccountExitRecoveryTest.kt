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
        AccountExitStage.entries.filter { it != AccountExitStage.READY_TO_CLEAR }.forEachIndexed { index, crashStage ->
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
