package com.vocaease.patient.feature.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vocaease.patient.core.network.dto.PatientProfile
import com.vocaease.patient.core.network.dto.Song
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TreatmentProgressUi(
    val completedCount: Int,
    val targetCount: Int,
    val percent: Float,
    val currentWeek: Int,
)

data class CatalogSongUi(
    val id: UUID,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
)

data class CatalogUiState(
    val patientName: String = "",
    val hasActiveTreatmentPlan: Boolean = false,
    val treatmentProgress: TreatmentProgressUi? = null,
    val lifetimeCompletedSongs: Int = 0,
    val lifetimeDurationSeconds: Int = 0,
    val keyword: String = "",
    val songs: List<CatalogSongUi> = emptyList(),
    val totalSongCount: Int = 0,
    val canLoadMore: Boolean = false,
    val canStartTraining: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
)

class CatalogViewModel(
    private val patientRepository: PatientRepository,
    private val songRepository: SongRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(reduce())
    val state: StateFlow<CatalogUiState> = mutableState.asStateFlow()

    fun start() {
        if (!mutableState.value.isLoading && patientRepository.profile.value == null) {
            viewModelScope.launch(dispatcher) { refresh() }
        }
    }

    suspend fun refresh() {
        mutableState.value = reduce().copy(isLoading = true)
        patientRepository.refreshMe()
        songRepository.refresh()
        mutableState.value = reduce()
    }

    suspend fun search(keyword: String) {
        mutableState.value = reduce().copy(keyword = keyword.trim(), isLoading = true)
        songRepository.refresh(keyword)
        mutableState.value = reduce()
    }

    suspend fun retrySongs() {
        mutableState.value = reduce().copy(isLoading = true)
        songRepository.retry()
        mutableState.value = reduce()
    }

    suspend fun loadMore() {
        if (!mutableState.value.canLoadMore || mutableState.value.isLoadingMore) return
        mutableState.value = reduce().copy(isLoadingMore = true)
        songRepository.loadMore()
        mutableState.value = reduce()
    }

    private fun reduce(): CatalogUiState {
        val profile = patientRepository.profile.value
        val catalog = songRepository.snapshot.value
        val hasPlan = profile?.activeTreatmentPlan != null && profile.treatmentProgress != null
        return CatalogUiState(
            patientName = profile?.name.orEmpty(),
            hasActiveTreatmentPlan = hasPlan,
            treatmentProgress = profile?.takeIf { hasPlan }?.toTreatmentProgressUi(),
            lifetimeCompletedSongs = profile?.singingSummary?.completedSessionCount ?: 0,
            lifetimeDurationSeconds = profile?.singingSummary?.totalDurationSeconds ?: 0,
            keyword = catalog.keyword,
            songs = catalog.songs.map(Song::toUi),
            totalSongCount = catalog.totalCount,
            canLoadMore = catalog.nextPage != null,
            canStartTraining = hasPlan,
            errorMessage = catalog.errorMessage,
        )
    }

    companion object {
        fun factory(
            patientRepository: PatientRepository,
            songRepository: SongRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CatalogViewModel::class.java))
                return CatalogViewModel(patientRepository, songRepository) as T
            }
        }
    }
}

private fun PatientProfile.toTreatmentProgressUi(): TreatmentProgressUi {
    val progress = requireNotNull(treatmentProgress)
    val parsed = progress.progressPercent?.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val bounded = parsed.coerceIn(BigDecimal.ZERO, BigDecimal(100))
    return TreatmentProgressUi(
        completedCount = progress.completedSessionCount,
        targetCount = progress.targetSessionCount,
        percent = bounded.setScale(2, RoundingMode.HALF_UP).toFloat(),
        currentWeek = progress.currentWeek,
    )
}

private fun Song.toUi() = CatalogSongUi(id, title, artist, durationSeconds)
