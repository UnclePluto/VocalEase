package com.vocaease.patient.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val repository: HistoryRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    val state: StateFlow<HistoryState> = repository.state
    private var page = 1
    private var loadMoreJob: Job? = null

    fun start() {
        viewModelScope.launch(dispatcher) {
            repository.start()
            if (repository.refreshWithOutcome()) page = 1
        }
    }

    fun retry() {
        viewModelScope.launch(dispatcher) {
            if (repository.refreshWithOutcome()) page = 1
        }
    }

    fun loadMore() {
        if (!state.value.hasMore || state.value.loading || loadMoreJob?.isActive == true) return
        val requestedPage = page + 1
        loadMoreJob = viewModelScope.launch(dispatcher) {
            if (repository.refreshWithOutcome(requestedPage)) page = requestedPage
        }
    }

    override fun onCleared() {
        repository.invalidateLease()
    }

    companion object {
        fun factory(repository: HistoryRepository, dispatcher: CoroutineDispatcher = Dispatchers.IO): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(HistoryViewModel::class.java))
                    return HistoryViewModel(repository, dispatcher) as T
                }
            }
    }
}
