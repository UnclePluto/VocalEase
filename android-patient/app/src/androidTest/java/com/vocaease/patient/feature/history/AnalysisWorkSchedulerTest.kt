package com.vocaease.patient.feature.history

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisWorkSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val workManager = WorkManager.getInstance(context)

    @Test
    fun start使用KEEP且输入仅账户证明与session并要求网络() {
        val contract = contract("scheduler-keep")
        val scheduler = AndroidAnalysisWorkScheduler(context)
        scheduler.start(contract, 300_000)
        scheduler.start(contract, 300_000)

        val work = workManager.getWorkInfosForUniqueWork(contract.uniqueWorkName).get(10, TimeUnit.SECONDS)

        assertEquals(1, work.size)
        val request = contract.request(300_000)
        assertEquals(
            setOf(
                AnalysisAndroidWorkContract.ACCOUNT_SCOPE_HASH_KEY,
                AnalysisAndroidWorkContract.SESSION_ID_KEY,
                AnalysisAndroidWorkContract.INCARNATION_PROOF_KEY,
            ),
            request.workSpec.input.keyValueMap.keys,
        )
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertFalse(contract.requiresForegroundNotification)
        scheduler.cancelAccount(contract.accountScopeHash)
    }

    @Test
    fun append形成同名oneTime链且账户取消不影响其他hash() {
        val first = contract("scheduler-chain")
        val other = AnalysisAndroidWorkContract("b".repeat(64), "scheduler-other", "c".repeat(64))
        val scheduler = AndroidAnalysisWorkScheduler(context)
        scheduler.start(first, 300_000)
        scheduler.append(first, 300_000)
        scheduler.start(other, 300_000)

        val chain = workManager.getWorkInfosForUniqueWork(first.uniqueWorkName).get(10, TimeUnit.SECONDS)
        assertEquals(2, chain.size)
        scheduler.cancelAccount(first.accountScopeHash)
        workManager.getWorkInfosByTag(AnalysisAndroidWorkContract.ACCOUNT_TAG_PREFIX + first.accountScopeHash).get(10, TimeUnit.SECONDS)
            .forEach { assertTrue(it.state.isFinished) }
        assertFalse(workManager.getWorkInfosForUniqueWork(other.uniqueWorkName).get(10, TimeUnit.SECONDS).single().state.isFinished)
        scheduler.cancelAccount(other.accountScopeHash)
    }

    private fun contract(sessionId: String) = AnalysisAndroidWorkContract(
        "a".repeat(64), sessionId, "d".repeat(64),
    )
}
