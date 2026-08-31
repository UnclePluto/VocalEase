package com.vocaease.patient.feature.history

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class HistoryStatus {
    WAITING_NETWORK,
    QUEUED,
    UPLOADING,
    WAITING_CALLBACK,
    CONFIRMING,
    SUBMITTING,
    ANALYZING,
    UPLOADED,
    PROCESSING,
    COMPLETED,
    UPLOAD_FAILED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

data class HistoryLocalRecord(
    val sessionId: String,
    val draftId: String,
    val songTitle: String,
    val artist: String,
    val durationSeconds: Int?,
    val status: HistoryStatus,
    val updatedAtEpochMillis: Long,
)

data class HistoryRemoteRecord(
    val sessionId: String,
    val songTitle: String,
    val artist: String,
    val durationSeconds: Int?,
    val status: HistoryStatus,
    val score: Double?,
    val analysisGeneration: Int,
    val updatedAtEpochMillis: Long,
)

data class HistoryPage(
    val count: Int,
    val page: Int,
    val pageSize: Int,
    val results: List<HistoryRemoteRecord>,
) {
    init {
        require(count >= 0 && page > 0 && pageSize > 0)
    }
}

data class HistoryItem(
    val sessionId: String,
    val draftId: String?,
    val songTitle: String,
    val artist: String,
    val durationSeconds: Int?,
    val status: HistoryStatus,
    val score: String?,
    val analysisGeneration: Int,
    val updatedAtEpochMillis: Long,
)

data class HistoryState(
    val items: List<HistoryItem> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val canRetry: Boolean = false,
    val hasMore: Boolean = false,
)

fun interface HistoryLocalSource {
    suspend fun loadPending(): List<HistoryLocalRecord>
}

fun interface HistoryRemoteSource {
    suspend fun load(page: Int, pageSize: Int, status: HistoryStatus?): HistoryPage
}

class HistoryRepository(
    private val local: HistoryLocalSource,
    private val remote: HistoryRemoteSource,
    private val leaseActive: () -> Boolean,
) {
    private val generation = AtomicLong()
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()
    private var localRecords: List<HistoryLocalRecord> = emptyList()
    private val remoteBySession = LinkedHashMap<String, HistoryRemoteRecord>()
    private val paginationMutex = Mutex()

    suspend fun start() {
        if (!leaseActive()) return invalidateLease()
        val loaded = try {
            local.loadPending()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            emptyList()
        }
        if (!leaseActive()) return invalidateLease()
        localRecords = loaded.distinctBy { it.sessionId }
        publish(error = null, canRetry = false)
    }

    suspend fun refresh(
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        status: HistoryStatus? = null,
    ) {
        require(page > 0 && pageSize in 1..MAX_PAGE_SIZE)
        if (page > 1) {
            paginationMutex.withLock { refreshInternal(page, pageSize, status) }
        } else {
            refreshInternal(page, pageSize, status)
        }
    }

    private suspend fun refreshInternal(page: Int, pageSize: Int, status: HistoryStatus?) {
        val requestGeneration = generation.incrementAndGet()
        if (!leaseActive()) return invalidateLease()
        _state.value = _state.value.copy(loading = true, errorMessage = null, canRetry = false)
        try {
            val result = remote.load(page, pageSize, status)
            if (!leaseActive() || requestGeneration != generation.get()) return
            if (result.page != page || result.pageSize != pageSize) throw IllegalStateException("分页响应不匹配")
            if (page == 1) remoteBySession.clear()
            result.results.forEach { candidate ->
                require(candidate.sessionId.isNotBlank())
                val existing = remoteBySession[candidate.sessionId]
                remoteBySession[candidate.sessionId] = chooseRemote(existing, candidate)
            }
            localRecords = try {
                local.loadPending()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                localRecords
            }.distinctBy { it.sessionId }
            publish(
                error = null,
                canRetry = false,
                hasMore = remoteBySession.size < result.count && result.results.isNotEmpty(),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            if (!leaseActive() || requestGeneration != generation.get()) return
            publish(error = "暂时无法刷新演唱记录，请重试", canRetry = true)
        }
    }

    fun invalidateLease() {
        generation.incrementAndGet()
        localRecords = emptyList()
        remoteBySession.clear()
        _state.value = HistoryState()
    }

    private fun publish(error: String?, canRetry: Boolean, hasMore: Boolean = _state.value.hasMore) {
        if (!leaseActive()) return invalidateLease()
        _state.value = HistoryState(
            items = mergeHistory(remoteBySession.values, localRecords),
            loading = false,
            errorMessage = error,
            canRetry = canRetry,
            hasMore = hasMore,
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
    }
}

internal fun mergeHistory(
    remote: Collection<HistoryRemoteRecord>,
    local: Collection<HistoryLocalRecord>,
): List<HistoryItem> {
    val remoteBySession = remote.associateBy { it.sessionId }
    val localBySession = local.associateBy { it.sessionId }
    return (remoteBySession.keys + localBySession.keys).mapNotNull { sessionId ->
        val server = remoteBySession[sessionId]
        val device = localBySession[sessionId]
        when {
            server != null && server.status.isTerminal() -> server.toItem()
            device != null -> device.toItem(server)
            server != null -> server.toItem()
            else -> null
        }
    }.sortedWith(compareByDescending<HistoryItem> { it.updatedAtEpochMillis }.thenBy { it.sessionId })
}

private fun chooseRemote(existing: HistoryRemoteRecord?, candidate: HistoryRemoteRecord): HistoryRemoteRecord = when {
    existing == null -> candidate
    existing.status.isTerminal() && !candidate.status.isTerminal() -> existing
    candidate.status.isTerminal() && !existing.status.isTerminal() -> candidate
    candidate.analysisGeneration > existing.analysisGeneration -> candidate
    candidate.analysisGeneration < existing.analysisGeneration -> existing
    candidate.updatedAtEpochMillis >= existing.updatedAtEpochMillis -> candidate
    else -> existing
}

private fun HistoryStatus?.isTerminal(): Boolean = this in setOf(
    HistoryStatus.COMPLETED,
    HistoryStatus.FAILED,
    HistoryStatus.CANCELLED,
)

private fun HistoryRemoteRecord.toItem() = HistoryItem(
    sessionId = sessionId,
    draftId = null,
    songTitle = songTitle,
    artist = artist,
    durationSeconds = durationSeconds,
    status = status,
    score = if (status == HistoryStatus.COMPLETED) ResultMapper.overallScore(score) else null,
    analysisGeneration = analysisGeneration.coerceAtLeast(0),
    updatedAtEpochMillis = updatedAtEpochMillis.coerceAtLeast(0),
)

private fun HistoryLocalRecord.toItem(server: HistoryRemoteRecord?) = HistoryItem(
    sessionId = sessionId,
    draftId = draftId,
    songTitle = server?.songTitle ?: songTitle,
    artist = server?.artist ?: artist,
    durationSeconds = durationSeconds ?: server?.durationSeconds,
    status = status,
    score = null,
    analysisGeneration = server?.analysisGeneration?.coerceAtLeast(0) ?: 0,
    updatedAtEpochMillis = maxOf(updatedAtEpochMillis.coerceAtLeast(0), server?.updatedAtEpochMillis ?: 0),
)
