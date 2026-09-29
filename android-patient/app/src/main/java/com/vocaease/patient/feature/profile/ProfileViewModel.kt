package com.vocaease.patient.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vocaease.patient.feature.catalog.PatientRepository
import com.vocaease.patient.feature.catalog.PatientLoadState
import com.vocaease.patient.core.database.AccountScopedDraftStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun interface PendingUploadCounter {
    suspend fun count(): Int
}

class AccountScopedPendingUploadCounter(
    private val storageProvider: AccountScopedDraftStorageProvider,
) : PendingUploadCounter {
    override suspend fun count(): Int = storageProvider.current().pendingUploadCount()
}

data class ProfileUiState(
    val patientName: String = "",
    val lifetimeCompletedSongs: Int = 0,
    val lifetimeDurationSeconds: Int = 0,
    val pendingUploadCount: Int = 0,
    val hasActiveTreatmentPlan: Boolean = false,
    val isLoading: Boolean = false,
    val patientStatus: ProfilePatientStatus = ProfilePatientStatus.INITIAL,
    val errorMessage: String? = null,
)

enum class ProfilePatientStatus { INITIAL, LOADING, CONTENT, ERROR }

class ProfileViewModel(
    private val patientRepository: PatientRepository,
    private val pendingUploadCounter: PendingUploadCounter,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(reduce())
    val state: StateFlow<ProfileUiState> = mutableState.asStateFlow()
    private var pendingCount = 0

    init {
        viewModelScope.launch(dispatcher) {
            patientRepository.state.collect {
                if (it is PatientLoadState.Initial) pendingCount = 0
                mutableState.value = reduce(pendingCount)
            }
        }
    }

    fun start() {
        if (!mutableState.value.isLoading) {
            viewModelScope.launch(dispatcher) { refresh() }
        }
    }

    suspend fun refresh() {
        mutableState.value = reduce().copy(isLoading = true)
        patientRepository.refreshMe()
        pendingCount = try {
            pendingUploadCounter.count().coerceAtLeast(0)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IllegalStateException) {
            0
        }
        mutableState.value = reduce(pendingCount)
    }

    private fun reduce(pendingCount: Int = 0): ProfileUiState {
        val patientState = patientRepository.state.value
        val profile = when (patientState) {
            PatientLoadState.Initial -> null
            is PatientLoadState.Loading -> patientState.previous
            is PatientLoadState.Content -> patientState.profile
            is PatientLoadState.Error -> patientState.previous
        }
        val status = when (patientState) {
            PatientLoadState.Initial -> ProfilePatientStatus.INITIAL
            is PatientLoadState.Loading -> ProfilePatientStatus.LOADING
            is PatientLoadState.Content -> ProfilePatientStatus.CONTENT
            is PatientLoadState.Error -> ProfilePatientStatus.ERROR
        }
        return ProfileUiState(
            patientName = profile?.name.orEmpty(),
            lifetimeCompletedSongs = profile?.singingSummary?.completedSessionCount ?: 0,
            lifetimeDurationSeconds = profile?.singingSummary?.totalDurationSeconds ?: 0,
            pendingUploadCount = pendingCount,
            hasActiveTreatmentPlan = profile?.activeTreatmentPlan != null,
            isLoading = patientState is PatientLoadState.Loading,
            patientStatus = status,
            errorMessage = (patientState as? PatientLoadState.Error)?.message,
        )
    }

    companion object {
        fun factory(
            patientRepository: PatientRepository,
            pendingUploadCounter: PendingUploadCounter,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ProfileViewModel::class.java))
                return ProfileViewModel(patientRepository, pendingUploadCounter) as T
            }
        }
    }
}
