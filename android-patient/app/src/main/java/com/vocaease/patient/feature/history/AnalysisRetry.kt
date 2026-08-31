package com.vocaease.patient.feature.history

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException

data class AnalysisRetryDetail(
    val sessionId: String,
    val status: AnalysisStatus,
    val generation: Int,
    val attempt: Int,
)

data class AnalysisRetryMutation(
    val sessionId: String,
    val status: AnalysisStatus,
    val generation: Int,
    val taskIds: List<String>,
)

sealed interface AnalysisRetryResponse {
    data class Accepted(val mutation: AnalysisRetryMutation) : AnalysisRetryResponse
    data object Conflict : AnalysisRetryResponse
}

interface AnalysisRetryRemote {
    suspend fun retry(sessionId: String, idempotencyKey: String): AnalysisRetryResponse
    suspend fun detail(sessionId: String): AnalysisRetryDetail
}

sealed interface AnalysisRetryOutcome {
    data class Accepted(val generation: Int) : AnalysisRetryOutcome
    data object Rejected : AnalysisRetryOutcome
    data object MaxAttempts : AnalysisRetryOutcome {
        const val message: String = "暂时无法重新分析，请联系医生"
    }
}

class AnalysisRetryCoordinator(
    private val remote: AnalysisRetryRemote,
    private val maxAttempts: Int,
) {
    private val lock = Any()
    private val inFlight = mutableMapOf<String, CompletableDeferred<AnalysisRetryOutcome>>()
    private val acceptedGenerations = mutableMapOf<String, Int>()

    init {
        require(maxAttempts > 0)
    }

    suspend fun retry(failed: AnalysisRetryDetail): AnalysisRetryOutcome {
        if (failed.sessionId.isBlank() || failed.status != AnalysisStatus.FAILED || failed.generation < 0 || failed.attempt < 0) {
            return AnalysisRetryOutcome.Rejected
        }
        if (failed.attempt >= maxAttempts) return AnalysisRetryOutcome.MaxAttempts
        synchronized(lock) {
            if ((acceptedGenerations[failed.sessionId] ?: -1) >= failed.generation + 1) {
                return AnalysisRetryOutcome.Rejected
            }
        }
        val targetGeneration = failed.generation + 1
        val operationKey = "${failed.sessionId}:$targetGeneration"
        val (deferred, leader) = synchronized(lock) {
            inFlight[operationKey]?.let { it to false } ?: CompletableDeferred<AnalysisRetryOutcome>().also {
                inFlight[operationKey] = it
            } to true
        }
        if (leader) {
            try {
                deferred.complete(execute(failed, targetGeneration))
            } catch (cancellation: CancellationException) {
                deferred.cancel(cancellation)
                throw cancellation
            } catch (_: Exception) {
                deferred.complete(AnalysisRetryOutcome.Rejected)
            } finally {
                synchronized(lock) { if (inFlight[operationKey] === deferred) inFlight.remove(operationKey) }
            }
        }
        return deferred.await()
    }

    private suspend fun execute(failed: AnalysisRetryDetail, targetGeneration: Int): AnalysisRetryOutcome {
        val key = "retry:${failed.sessionId}:$targetGeneration"
        return when (val response = remote.retry(failed.sessionId, key)) {
            is AnalysisRetryResponse.Accepted -> acceptMutation(response.mutation, failed.sessionId, targetGeneration)
            AnalysisRetryResponse.Conflict -> reconcileConflict(failed, targetGeneration, key)
        }
    }

    private suspend fun reconcileConflict(
        failed: AnalysisRetryDetail,
        targetGeneration: Int,
        key: String,
    ): AnalysisRetryOutcome {
        val detail = remote.detail(failed.sessionId)
        if (detail.sessionId != failed.sessionId || detail.generation < failed.generation) return AnalysisRetryOutcome.Rejected
        if (detail.status in ACCEPTED_STATUSES && detail.generation >= targetGeneration) {
            rememberAccepted(detail.sessionId, detail.generation)
            return AnalysisRetryOutcome.Accepted(detail.generation)
        }
        if (detail.status != AnalysisStatus.FAILED || detail.generation != failed.generation) {
            return AnalysisRetryOutcome.Rejected
        }
        return when (val second = remote.retry(failed.sessionId, key)) {
            is AnalysisRetryResponse.Accepted -> acceptMutation(second.mutation, failed.sessionId, targetGeneration)
            AnalysisRetryResponse.Conflict -> AnalysisRetryOutcome.Rejected
        }
    }

    private fun acceptMutation(
        mutation: AnalysisRetryMutation,
        sessionId: String,
        targetGeneration: Int,
    ): AnalysisRetryOutcome {
        if (mutation.sessionId != sessionId || mutation.generation != targetGeneration ||
            mutation.status !in ACCEPTED_STATUSES || mutation.taskIds.isEmpty() ||
            mutation.taskIds.any { !it.matches(OPAQUE_TASK_ID) } || mutation.taskIds.distinct().size != mutation.taskIds.size
        ) return AnalysisRetryOutcome.Rejected
        rememberAccepted(sessionId, mutation.generation)
        return AnalysisRetryOutcome.Accepted(mutation.generation)
    }

    private fun rememberAccepted(sessionId: String, generation: Int) = synchronized(lock) {
        acceptedGenerations[sessionId] = maxOf(acceptedGenerations[sessionId] ?: -1, generation)
    }

    private companion object {
        val ACCEPTED_STATUSES = setOf(AnalysisStatus.RETRYING, AnalysisStatus.PROCESSING, AnalysisStatus.COMPLETED)
        val OPAQUE_TASK_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
