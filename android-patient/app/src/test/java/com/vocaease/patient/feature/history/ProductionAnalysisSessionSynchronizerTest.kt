package com.vocaease.patient.feature.history

import com.vocaease.patient.core.network.dto.MediaType
import com.vocaease.patient.core.network.dto.PatientSnapshot
import com.vocaease.patient.core.network.dto.SessionMedia
import com.vocaease.patient.core.network.dto.SessionStatus
import com.vocaease.patient.core.network.dto.SingingSession
import com.vocaease.patient.core.network.dto.SongSnapshot
import com.vocaease.patient.core.network.dto.TreatmentPlanSnapshot
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductionAnalysisSessionSynchronizerTest {
    @Test
    fun `页面学到generation后前后台同floor共享一次生产请求`() = runBlocking {
        val calls = AtomicInteger()
        val secondRequestEntered = CompletableDeferred<Unit>()
        val secondResponseGate = CompletableDeferred<Unit>()
        val synchronizer = ProductionAnalysisSessionSynchronizer(
            sessionSource = { _, _ ->
                when (calls.incrementAndGet()) {
                    1 -> session()
                    2 -> {
                        secondRequestEntered.complete(Unit)
                        secondResponseGate.await()
                        session()
                    }
                    else -> error("同floor不应发起第三次生产请求")
                }
            },
        )
        val resultRemote = synchronizer.resultRemote(ACCOUNT_HASH, INCARNATION_PROOF)
        val analysisRemote = synchronizer.analysisRemote(ACCOUNT_HASH, INCARNATION_PROOF)
        val firstPollGate = CompletableDeferred<Unit>()
        val secondPollGate = CompletableDeferred<Unit>()
        var delayCount = 0
        val viewModel = ResultViewModel(
            SESSION_ID.toString(),
            resultRemote,
            retryAction = { AnalysisRetryOutcome.Accepted(3) },
            dispatcher = Dispatchers.Unconfined,
            delayMillis = {
                delayCount += 1
                if (delayCount == 1) firstPollGate.await() else secondPollGate.await()
            },
        )

        viewModel.start()
        assertEquals(2, viewModel.state.value.content?.analysisGeneration)
        val background = async { analysisRemote.fetch(SESSION_ID.toString(), 2) }
        secondRequestEntered.await()
        firstPollGate.complete(Unit)
        yield()

        assertEquals(2, calls.get())
        secondResponseGate.complete(Unit)
        assertEquals(2, background.await().generation)
        yield()
        assertEquals(2, viewModel.state.value.content?.analysisGeneration)
        viewModel.stop()
        secondPollGate.complete(Unit)
        Unit
    }
}

private fun session() = SingingSession(
    SESSION_ID,
    PatientSnapshot(PATIENT_ID, "MR", "患者"),
    SongSnapshot(SONG_ID, "小幸运", "田馥甄", 265),
    TreatmentPlanSnapshot(PLAN_ID, LocalDate.parse("2026-08-01"), 8, 24),
    SessionStatus.PROCESSING,
    null,
    null,
    265,
    false,
    "android",
    2,
    Instant.EPOCH,
    null,
    Instant.EPOCH,
    Instant.EPOCH,
    listOf(SessionMedia(VIDEO_ID, MediaType.SINGING_VIDEO, "ready", "video/mp4", 1_024, Instant.EPOCH)),
    emptyList(),
    emptyList(),
)

private const val ACCOUNT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val INCARNATION_PROOF = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
private val SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000021")
private val PATIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000022")
private val SONG_ID = UUID.fromString("00000000-0000-0000-0000-000000000023")
private val PLAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000024")
private val VIDEO_ID = UUID.fromString("00000000-0000-0000-0000-000000000025")
