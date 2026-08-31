package com.vocaease.patient.feature.history

import com.vocaease.patient.core.database.AccountScopedDraftStorage
import com.vocaease.patient.core.network.NetworkContractException
import com.vocaease.patient.core.network.PatientApi
import com.vocaease.patient.core.network.dto.AnalysisTaskType
import com.vocaease.patient.core.network.dto.SessionStatus
import com.vocaease.patient.core.network.dto.SingingSession
import com.vocaease.patient.core.network.dto.toDomain
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import retrofit2.HttpException

class AccountScopedAnalysisAccount(
    private val storage: AccountScopedDraftStorage,
) : AnalysisAccountScope {
    override val accountScopeHash: String get() = storage.accountScopeHash
    override val incarnationProof: String get() = storage.cleanupScopeToken
    override val leaseActive: Boolean get() = storage.isLeaseActive()
    override val checkpointStore: AnalysisCheckpointStore = object : AnalysisCheckpointStore {
        override suspend fun load(sessionId: String) = storage.loadAnalysisCheckpoint(sessionId)
        override suspend fun persist(expectedVersion: Long?, checkpoint: AnalysisCheckpoint) =
            storage.persistAnalysisCheckpoint(expectedVersion, checkpoint)
    }
}

class AccountScopedHistoryLocalSource(
    private val storage: AccountScopedDraftStorage,
) : HistoryLocalSource {
    override suspend fun loadPending(): List<HistoryLocalRecord> = storage.loadPendingHistoryRecords()
}

class VocaEaseHistoryRemoteSource(
    private val api: PatientApi,
    private val accountScopeHash: String,
) : HistoryRemoteSource {
    override suspend fun load(page: Int, pageSize: Int, status: HistoryStatus?): HistoryPage {
        val serverStatus = status?.toServerStatus()
        val result = api.sessions(page, pageSize, serverStatus).data.toDomain()
        val records = result.results.map { session ->
            requireOwner(session.patient.id.toString(), accountScopeHash)
            HistoryRemoteRecord(
                sessionId = session.id.toString(),
                songTitle = session.song.title,
                artist = session.song.artist,
                durationSeconds = session.durationSeconds,
                status = session.status.toHistoryStatus(),
                score = session.score?.toDouble(),
                analysisGeneration = session.analysisGeneration,
                updatedAtEpochMillis = session.updatedAt.toEpochMilli(),
            )
        }
        return HistoryPage(result.count, result.page, result.pageSize, records)
    }
}

class VocaEaseAnalysisDetailRemote(
    private val api: PatientApi,
    private val accountScopeHash: String,
) : AnalysisDetailRemote {
    override suspend fun fetch(sessionId: String): AnalysisDetail {
        val detail = api.session(sessionId).data.toDomain().verified(accountScopeHash, sessionId)
        return AnalysisDetail(detail.id.toString(), detail.status.toAnalysisStatus(), detail.analysisGeneration)
    }
}

fun interface ResultSessionRemote {
    suspend fun fetch(sessionId: String): SingingSession
}

class VocaEaseResultSessionRemote(
    private val api: PatientApi,
    private val accountScopeHash: String,
) : ResultSessionRemote {
    override suspend fun fetch(sessionId: String): SingingSession =
        api.session(sessionId).data.toDomain().verified(accountScopeHash, sessionId)
}

class VocaEasePrivateVideoRemote(
    private val api: PatientApi,
    private val accountScopeHash: String,
) : PrivateVideoRemote {
    override suspend fun session(sessionId: String): PrivateVideoSession {
        val detail = api.session(sessionId).data.toDomain().verified(accountScopeHash, sessionId)
        return PrivateVideoSession(
            sessionId = detail.id.toString(),
            media = detail.media.map {
                PrivateVideoAsset(
                    assetId = it.assetId.toString(),
                    mediaType = it.mediaType.serializedValue,
                    status = it.status,
                    mimeType = it.mime,
                    sizeBytes = it.size,
                )
            },
        )
    }

    override suspend fun privateUrl(assetId: String): PrivateVideoGrant {
        val grant = api.patientMediaPrivateUrl(assetId).data.toDomain()
        return PrivateVideoGrant(grant.url, grant.expiresAt.toEpochMilli())
    }
}

class VocaEaseAnalysisRetryRemote(
    private val api: PatientApi,
    private val accountScopeHash: String,
) : AnalysisRetryRemote {
    override suspend fun retry(sessionId: String, idempotencyKey: String): AnalysisRetryResponse = try {
        val mutation = api.retrySession(sessionId, idempotencyKey).data.toDomain()
        if (mutation.sessionId.toString() != sessionId) throw NetworkContractException("重试响应会话不匹配")
        val detail = api.session(sessionId).data.toDomain().verified(accountScopeHash, sessionId)
        AnalysisRetryResponse.Accepted(
            AnalysisRetryMutation(
                sessionId = mutation.sessionId.toString(),
                status = mutation.status.toAnalysisStatus(),
                generation = detail.analysisGeneration,
                taskIds = mutation.analysisTaskIds.map { it.toString() },
            ),
        )
    } catch (error: HttpException) {
        if (error.code() == 409) AnalysisRetryResponse.Conflict else throw error
    }

    override suspend fun detail(sessionId: String): AnalysisRetryDetail {
        val detail = api.session(sessionId).data.toDomain().verified(accountScopeHash, sessionId)
        val attempt = detail.analysisResults
            .filter { it.taskType == AnalysisTaskType.SINGING_AUDIO_METRICS && it.generation == detail.analysisGeneration }
            .maxOfOrNull { it.attempt } ?: 0
        return AnalysisRetryDetail(detail.id.toString(), detail.status.toAnalysisStatus(), detail.analysisGeneration, attempt)
    }
}

private fun SingingSession.verified(accountScopeHash: String, requestedSessionId: String): SingingSession {
    if (id.toString() != requestedSessionId) throw NetworkContractException("会话响应归属不匹配")
    requireOwner(patient.id.toString(), accountScopeHash)
    return this
}

private fun requireOwner(patientId: String, accountScopeHash: String) {
    if (ChunkedAesGcmFileStore.sha256(patientId) != accountScopeHash) {
        throw NetworkContractException("会话响应账户归属不匹配")
    }
}

private fun HistoryStatus.toServerStatus(): SessionStatus? = when (this) {
    HistoryStatus.UPLOADED -> SessionStatus.UPLOADED
    HistoryStatus.PROCESSING, HistoryStatus.ANALYZING -> SessionStatus.PROCESSING
    HistoryStatus.COMPLETED -> SessionStatus.COMPLETED
    HistoryStatus.FAILED -> SessionStatus.FAILED
    HistoryStatus.CANCELLED -> SessionStatus.CANCELLED
    else -> null
}

private fun SessionStatus.toHistoryStatus(): HistoryStatus = when (this) {
    SessionStatus.UPLOADED -> HistoryStatus.UPLOADED
    SessionStatus.PROCESSING -> HistoryStatus.PROCESSING
    SessionStatus.COMPLETED -> HistoryStatus.COMPLETED
    SessionStatus.FAILED -> HistoryStatus.FAILED
    SessionStatus.CANCELLED -> HistoryStatus.CANCELLED
    SessionStatus.CREATED, SessionStatus.AWAITING_UPLOAD -> HistoryStatus.QUEUED
}

internal fun SessionStatus.toAnalysisStatus(): AnalysisStatus = when (this) {
    SessionStatus.UPLOADED -> AnalysisStatus.UPLOADED
    SessionStatus.PROCESSING -> AnalysisStatus.PROCESSING
    SessionStatus.COMPLETED -> AnalysisStatus.COMPLETED
    SessionStatus.FAILED -> AnalysisStatus.FAILED
    SessionStatus.CANCELLED -> AnalysisStatus.CANCELLED
    SessionStatus.CREATED, SessionStatus.AWAITING_UPLOAD -> AnalysisStatus.UNKNOWN
}
