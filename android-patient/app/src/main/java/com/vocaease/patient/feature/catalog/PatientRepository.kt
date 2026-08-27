package com.vocaease.patient.feature.catalog

import com.vocaease.patient.core.network.PatientApi
import com.vocaease.patient.core.network.dto.PatientProfile
import com.vocaease.patient.core.network.dto.toDomain
import java.io.IOException
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

class PatientRepository(
    private val remote: PatientRemoteDataSource,
) {
    private val mutableProfile = MutableStateFlow<PatientProfile?>(null)
    val profile: StateFlow<PatientProfile?> = mutableProfile.asStateFlow()

    suspend fun refreshMe(): PatientRefreshResult = try {
        remote.fetchMe().let { refreshed ->
            mutableProfile.value = refreshed
            PatientRefreshResult.Success(refreshed)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: IOException) {
        PatientRefreshResult.Failure
    } catch (_: HttpException) {
        PatientRefreshResult.Failure
    } catch (_: SerializationException) {
        PatientRefreshResult.Failure
    }
}
