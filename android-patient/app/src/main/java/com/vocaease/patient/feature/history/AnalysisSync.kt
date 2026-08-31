package com.vocaease.patient.feature.history

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

enum class AnalysisStatus {
    UPLOADED,
    PROCESSING,
    RETRYING,
    COMPLETED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

private fun AnalysisStatus.isTerminal(): Boolean = this in setOf(
    AnalysisStatus.COMPLETED,
    AnalysisStatus.FAILED,
    AnalysisStatus.CANCELLED,
)

data class AnalysisDetail(
    val sessionId: String,
    val status: AnalysisStatus,
    val generation: Int,
) {
    fun isTerminal(): Boolean = status.isTerminal()
}

data class AnalysisWorkContract(
    val accountScopeHash: String,
    val sessionId: String,
    val incarnationProof: String,
) {
    init {
        require(accountScopeHash.matches(SHA256))
        require(sessionId.matches(OPAQUE_ID))
        require(incarnationProof.matches(SHA256))
    }

    val uniqueWorkName: String = "analysis:$accountScopeHash:$sessionId"

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
        val OPAQUE_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

data class AnalysisCheckpoint(
    val accountScopeHash: String,
    val sessionId: String,
    val incarnationProof: String,
    val status: AnalysisStatus,
    val analysisGeneration: Int,
    val pollStep: Int,
    val nextDeadlineEpochMillis: Long,
    val version: Long,
) {
    init {
        require(pollStep in 0..3)
        require(analysisGeneration >= 0 && nextDeadlineEpochMillis >= 0 && version >= 0)
    }
}

interface AnalysisCheckpointStore {
    suspend fun load(sessionId: String): AnalysisCheckpoint?
    suspend fun persist(expectedVersion: Long?, checkpoint: AnalysisCheckpoint): Boolean
}

fun interface AnalysisDetailRemote {
    suspend fun fetch(sessionId: String): AnalysisDetail
}

sealed interface AnalysisSyncDecision {
    data class NotDue(val remainingDelayMillis: Long) : AnalysisSyncDecision
    data class Continue(val delayMillis: Long) : AnalysisSyncDecision
    data object Terminal : AnalysisSyncDecision
    data object Retry : AnalysisSyncDecision
    data object Rejected : AnalysisSyncDecision
}

class AnalysisSyncEngine(
    private val store: AnalysisCheckpointStore,
    private val remote: AnalysisDetailRemote,
) {
    suspend fun initialize(contract: AnalysisWorkContract, nowEpochMillis: Long): AnalysisCheckpoint {
        require(nowEpochMillis >= 0)
        store.load(contract.sessionId)?.let { existing ->
            if (existing.matches(contract)) return existing
            throw IllegalStateException("分析任务账户绑定不匹配")
        }
        val checkpoint = AnalysisCheckpoint(
            accountScopeHash = contract.accountScopeHash,
            sessionId = contract.sessionId,
            incarnationProof = contract.incarnationProof,
            status = AnalysisStatus.PROCESSING,
            analysisGeneration = 0,
            pollStep = 0,
            nextDeadlineEpochMillis = nowEpochMillis + POLL_DELAYS[0],
            version = 0,
        )
        check(store.persist(null, checkpoint))
        return checkpoint
    }

    suspend fun runOnce(contract: AnalysisWorkContract, nowEpochMillis: Long): AnalysisSyncDecision {
        require(nowEpochMillis >= 0)
        val checkpoint = store.load(contract.sessionId) ?: return AnalysisSyncDecision.Rejected
        if (!checkpoint.matches(contract)) return AnalysisSyncDecision.Rejected
        if (checkpoint.status.isTerminal()) return AnalysisSyncDecision.Terminal
        if (nowEpochMillis < checkpoint.nextDeadlineEpochMillis) {
            return AnalysisSyncDecision.NotDue(checkpoint.nextDeadlineEpochMillis - nowEpochMillis)
        }
        val detail = try {
            remote.fetch(contract.sessionId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            return AnalysisSyncDecision.Retry
        } catch (_: Exception) {
            return AnalysisSyncDecision.Rejected
        }
        if (detail.sessionId != contract.sessionId || detail.generation < checkpoint.analysisGeneration ||
            detail.status == AnalysisStatus.UNKNOWN
        ) return AnalysisSyncDecision.Rejected
        val terminal = detail.isTerminal()
        val nextStep = if (terminal) checkpoint.pollStep else minOf(checkpoint.pollStep + 1, POLL_DELAYS.lastIndex)
        val delay = if (terminal) 0 else POLL_DELAYS[nextStep]
        val next = checkpoint.copy(
            status = detail.status,
            analysisGeneration = detail.generation,
            pollStep = nextStep,
            nextDeadlineEpochMillis = nowEpochMillis + delay,
            version = checkpoint.version + 1,
        )
        if (!store.persist(checkpoint.version, next)) return AnalysisSyncDecision.Rejected
        return if (terminal) AnalysisSyncDecision.Terminal else AnalysisSyncDecision.Continue(delay)
    }

    private fun AnalysisCheckpoint.matches(contract: AnalysisWorkContract): Boolean =
        accountScopeHash == contract.accountScopeHash && sessionId == contract.sessionId &&
            incarnationProof == contract.incarnationProof

    companion object {
        val POLL_DELAYS: List<Long> = listOf(10_000L, 30_000L, 60_000L, 300_000L)
    }
}

class AnalysisDetailSynchronizer(
    private val remote: AnalysisDetailRemote,
) {
    private val lock = Any()
    private val inFlight = mutableMapOf<String, CompletableDeferred<AnalysisDetail?>>()
    private val latest = mutableMapOf<String, AnalysisDetail>()

    suspend fun fetch(sessionId: String, minimumGeneration: Int): AnalysisDetail {
        require(sessionId.isNotBlank() && minimumGeneration >= 0)
        val (deferred, leader) = synchronized(lock) {
            inFlight[sessionId]?.let { it to false } ?: CompletableDeferred<AnalysisDetail?>().also {
                inFlight[sessionId] = it
            } to true
        }
        if (leader) {
            try {
                val fetched = remote.fetch(sessionId)
                deferred.complete(accept(fetched, minimumGeneration))
            } catch (error: Throwable) {
                deferred.completeExceptionally(error)
            } finally {
                synchronized(lock) { if (inFlight[sessionId] === deferred) inFlight.remove(sessionId) }
            }
        }
        return deferred.await() ?: throw IllegalStateException("分析响应已过期或归属不匹配")
    }

    fun accept(detail: AnalysisDetail, minimumGeneration: Int = 0): AnalysisDetail? = synchronized(lock) {
        if (detail.sessionId.isBlank() || detail.status == AnalysisStatus.UNKNOWN || detail.generation < minimumGeneration) {
            return@synchronized null
        }
        val current = latest[detail.sessionId]
        if (current != null && (
                detail.generation < current.generation ||
                    (detail.generation == current.generation && current.isTerminal() && !detail.isTerminal())
                )
        ) return@synchronized null
        latest[detail.sessionId] = detail
        detail
    }
}

class ResultForegroundPoller(
    private val sessionId: String,
    private val synchronizer: AnalysisDetailSynchronizer,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun runUntilTerminal(minimumGeneration: Int = 0): AnalysisDetail {
        while (true) {
            val detail = synchronizer.fetch(sessionId, minimumGeneration)
            if (detail.isTerminal()) return detail
            delayMillis(FOREGROUND_POLL_MILLIS)
        }
    }

    companion object {
        const val FOREGROUND_POLL_MILLIS = 3_000L
    }
}
