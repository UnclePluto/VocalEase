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

    suspend fun fetch(sessionId: String, minimumGeneration: Int): AnalysisDetail = fetch(sessionId)
}

sealed interface AnalysisSyncDecision {
    data class NotDue(val remainingDelayMillis: Long) : AnalysisSyncDecision
    data class Continue(val delayMillis: Long) : AnalysisSyncDecision
    data object Terminal : AnalysisSyncDecision
    data class Retry(val minimumDelayMillis: Long? = null) : AnalysisSyncDecision
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
            remote.fetch(contract.sessionId, checkpoint.analysisGeneration)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IOException) {
            return AnalysisSyncDecision.Retry((error as? AnalysisRemoteRetryException)?.retryAfterMillis)
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

data class VersionedSessionIdentity(
    val sessionId: String,
    val generation: Int,
    val terminal: Boolean,
)

/** 生产前后台共享的账户/登录世代隔离 single-flight。 */
class AccountScopedSessionSynchronizer<T : Any>(
    private val source: suspend (accountScopeHash: String, sessionId: String) -> T,
    private val identity: (T) -> VersionedSessionIdentity,
) {
    private data class ScopeKey(val accountScopeHash: String, val incarnationProof: String, val sessionId: String)
    private data class FlightKey(val scope: ScopeKey, val minimumGeneration: Int)

    private val lock = Any()
    private val inFlight = mutableMapOf<FlightKey, CompletableDeferred<T>>()
    private val latest = mutableMapOf<ScopeKey, T>()

    suspend fun fetch(
        accountScopeHash: String,
        incarnationProof: String,
        sessionId: String,
        minimumGeneration: Int,
    ): T {
        require(accountScopeHash.matches(Regex("[0-9a-f]{64}")))
        require(incarnationProof.matches(Regex("[0-9a-f]{64}")))
        require(sessionId.isNotBlank() && sessionId.length <= 128 && minimumGeneration >= 0)
        val scopeKey = ScopeKey(accountScopeHash, incarnationProof, sessionId)
        val flightKey = FlightKey(scopeKey, minimumGeneration)
        val (deferred, leader) = synchronized(lock) {
            inFlight.entries
                .asSequence()
                .filter { (key) -> key.scope == scopeKey && key.minimumGeneration >= minimumGeneration }
                .minByOrNull { (key) -> key.minimumGeneration }
                ?.value
                ?.let { it to false }
                ?: CompletableDeferred<T>().also { inFlight[flightKey] = it } to true
        }
        if (leader) {
            try {
                val fetched = source(accountScopeHash, sessionId)
                val fetchedIdentity = identity(fetched)
                check(fetchedIdentity.sessionId == sessionId && fetchedIdentity.generation >= 0)
                if (fetchedIdentity.generation < minimumGeneration) {
                    throw AnalysisGenerationNotReadyException(minimumGeneration, fetchedIdentity.generation)
                }
                val accepted = synchronized(lock) {
                    val current = latest[scopeKey]
                    val currentIdentity = current?.let(identity)
                    if (currentIdentity != null && (
                            fetchedIdentity.generation < currentIdentity.generation ||
                                (fetchedIdentity.generation == currentIdentity.generation && currentIdentity.terminal && !fetchedIdentity.terminal)
                            )
                    ) current else fetched.also { latest[scopeKey] = it }
                }
                deferred.complete(accepted)
            } catch (error: Throwable) {
                deferred.completeExceptionally(error)
            } finally {
                synchronized(lock) { if (inFlight[flightKey] === deferred) inFlight.remove(flightKey) }
            }
        }
        return deferred.await().also { accepted ->
            if (identity(accepted).generation < minimumGeneration) {
                throw AnalysisGenerationNotReadyException(minimumGeneration, identity(accepted).generation)
            }
        }
    }
}

class AnalysisGenerationNotReadyException(
    val minimumGeneration: Int,
    val actualGeneration: Int,
) : IOException("分析响应generation尚未达到本地重试代际")

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
