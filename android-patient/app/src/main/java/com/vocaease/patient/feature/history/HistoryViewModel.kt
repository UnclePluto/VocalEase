package com.vocaease.patient.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val repository: HistoryRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    val state: StateFlow<HistoryState> = repository.state
    private var page = 1

    fun start() {
        viewModelScope.launch(dispatcher) {
            repository.start()
            repository.refresh()
        }
    }

    fun retry() {
        page = 1
        viewModelScope.launch(dispatcher) { repository.refresh() }
    }

    fun loadMore() {
        if (!state.value.hasMore || state.value.loading) return
        page += 1
        viewModelScope.launch(dispatcher) { repository.refresh(page) }
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
