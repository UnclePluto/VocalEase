package com.vocaease.patient.feature.catalog

import com.vocaease.patient.core.network.PatientApi
import com.vocaease.patient.core.network.dto.PatientProfile
import com.vocaease.patient.core.network.dto.toDomain
import com.vocaease.patient.core.database.AuthenticatedAccountLease
import com.vocaease.patient.core.database.AuthenticatedAccountSession
import com.vocaease.patient.core.database.StaleAccountScopeException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

fun interface PatientRemoteDataSource {
    suspend fun fetchMe(): PatientProfile
}

class VocaEasePatientRemoteDataSource(
    private val api: PatientApi,
) : PatientRemoteDataSource {
    override suspend fun fetchMe(): PatientProfile = api.patientMe().data.toDomain()
}

sealed interface PatientRefreshResult {
    data class Success(val profile: PatientProfile) : PatientRefreshResult
    data object Failure : PatientRefreshResult
}

sealed interface PatientLoadState {
    data object Initial : PatientLoadState
    data class Loading(val previous: PatientProfile?) : PatientLoadState
    data class Content(val profile: PatientProfile) : PatientLoadState
    data class Error(val previous: PatientProfile?, val message: String) : PatientLoadState
}

class PatientRepository(
    private val remote: PatientRemoteDataSource,
    private val accountSession: AuthenticatedAccountSession,
) {
    private val lock = Any()
    private val mutableProfile = MutableStateFlow<PatientProfile?>(null)
    val profile: StateFlow<PatientProfile?> = mutableProfile.asStateFlow()
    private val mutableState = MutableStateFlow<PatientLoadState>(PatientLoadState.Initial)
    val state: StateFlow<PatientLoadState> = mutableState.asStateFlow()
    private var lease: AuthenticatedAccountLease? = accountSession.current()
    private var generation = 0L

    init {
        accountSession.addLeaseChangedListener(::accountChanged)
    }

    suspend fun refreshMe(): PatientRefreshResult {
        val request = synchronized(lock) {
            synchronizeLeaseLocked()
            val currentLease = lease ?: return PatientRefreshResult.Failure
            val previous = mutableProfile.value
            val expectedPatientId = currentLease.patientId.toCanonicalUuidOrNull()
            if (expectedPatientId == null) {
                mutableState.value = PatientLoadState.Error(previous, IDENTITY_ERROR)
                return PatientRefreshResult.Failure
            }
            generation += 1
            mutableState.value = PatientLoadState.Loading(previous)
            PatientRequest(currentLease, expectedPatientId, generation, previous)
        }
        return try {
            val refreshed = remote.fetchMe()
            var publication: PatientRefreshResult = PatientRefreshResult.Failure
            accountSession.withCurrentLease(request.lease) {
                synchronized(lock) {
                    if (lease === request.lease && generation == request.generation) {
                        if (refreshed.id == request.expectedPatientId) {
                            mutableProfile.value = refreshed
                            mutableState.value = PatientLoadState.Content(refreshed)
                            publication = PatientRefreshResult.Success(refreshed)
                        } else {
                            mutableState.value = PatientLoadState.Error(request.previous, IDENTITY_ERROR)
                        }
                    }
                }
            }
            publication
        } catch (error: CancellationException) {
            restoreAfterCancellation(request)
            throw error
        } catch (_: StaleAccountScopeException) {
            PatientRefreshResult.Failure
        } catch (_: IOException) {
            publishFailure(request)
        } catch (_: HttpException) {
            publishFailure(request)
        } catch (_: SerializationException) {
            publishFailure(request)
        }
    }

    private fun publishFailure(request: PatientRequest): PatientRefreshResult {
        synchronized(lock) {
            if (lease === request.lease && generation == request.generation) {
                mutableState.value = PatientLoadState.Error(
                    previous = request.previous,
                    message = "患者信息加载失败，请重试",
                )
            }
        }
        return PatientRefreshResult.Failure
    }

    private fun restoreAfterCancellation(request: PatientRequest) = synchronized(lock) {
        if (lease === request.lease && generation == request.generation) {
            mutableState.value = request.previous?.let(PatientLoadState::Content) ?: PatientLoadState.Initial
        }
    }

    private fun accountChanged(updated: AuthenticatedAccountLease?) = synchronized(lock) {
        if (lease !== updated) {
            lease = updated
            generation += 1
            mutableProfile.value = null
            mutableState.value = PatientLoadState.Initial
        }
    }

    private fun synchronizeLeaseLocked() {
        val current = accountSession.current()
        if (lease !== current) accountChanged(current)
    }

    private data class PatientRequest(
        val lease: AuthenticatedAccountLease,
        val expectedPatientId: UUID,
        val generation: Long,
        val previous: PatientProfile?,
    )

    private companion object {
        const val IDENTITY_ERROR = "患者身份校验失败，请重新登录"
    }
}

private fun String.toCanonicalUuidOrNull(): UUID? = try {
    UUID.fromString(this).takeIf { it.toString() == this }
} catch (_: IllegalArgumentException) {
    null
}
