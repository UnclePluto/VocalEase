package com.vocaease.patient.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vocaease.patient.feature.catalog.PatientRepository
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

data class ProfileUiState(
    val patientName: String = "",
    val lifetimeCompletedSongs: Int = 0,
    val lifetimeDurationSeconds: Int = 0,
    val pendingUploadCount: Int = 0,
    val hasActiveTreatmentPlan: Boolean = false,
    val isLoading: Boolean = false,
)

class ProfileViewModel(
    private val patientRepository: PatientRepository,
    private val pendingUploadCounter: PendingUploadCounter,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(reduce())
    val state: StateFlow<ProfileUiState> = mutableState.asStateFlow()

    fun start() {
        if (!mutableState.value.isLoading) {
            viewModelScope.launch(dispatcher) { refresh() }
        }
    }

    suspend fun refresh() {
        mutableState.value = reduce().copy(isLoading = true)
        patientRepository.refreshMe()
        val pendingCount = try {
            pendingUploadCounter.count().coerceAtLeast(0)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IllegalStateException) {
            0
        }
        mutableState.value = reduce(pendingCount)
    }

    private fun reduce(pendingCount: Int = 0): ProfileUiState {
        val profile = patientRepository.profile.value
        return ProfileUiState(
            patientName = profile?.name.orEmpty(),
            lifetimeCompletedSongs = profile?.singingSummary?.completedSessionCount ?: 0,
            lifetimeDurationSeconds = profile?.singingSummary?.totalDurationSeconds ?: 0,
            pendingUploadCount = pendingCount,
            hasActiveTreatmentPlan = profile?.activeTreatmentPlan != null,
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
