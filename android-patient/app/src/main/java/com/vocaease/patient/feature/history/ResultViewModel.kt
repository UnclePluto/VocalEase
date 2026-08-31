package com.vocaease.patient.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ResultViewModel(
    private val sessionId: String,
    private val remote: ResultSessionRemote,
    private val retryAction: suspend () -> AnalysisRetryOutcome,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    private val mutableState = MutableStateFlow(ResultScreenState())
    val state: StateFlow<ResultScreenState> = mutableState.asStateFlow()
    private var pollingJob: Job? = null
    private var operationGeneration = 0L
    val isPolling: Boolean get() = pollingJob?.isActive == true

    fun start() {
        if (pollingJob?.isActive == true) return
        val operation = ++operationGeneration
        pollingJob = viewModelScope.launch(dispatcher) { loadUntilTerminal(operation) }
    }

    fun stop() {
        operationGeneration += 1
        pollingJob?.cancel()
        pollingJob = null
    }

    suspend fun retryAnalysis() {
        val current = mutableState.value.content ?: return
        if (!current.canRetry || mutableState.value.retrying) return
        mutableState.value = mutableState.value.copy(retrying = true, errorMessage = null)
        val outcome = try {
            retryAction()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AnalysisRetryOutcome.Rejected
        }
        when (outcome) {
            AnalysisRetryOutcome.Accepted -> {
                mutableState.value = mutableState.value.copy(retrying = false, errorMessage = null)
                stop()
                start()
            }
            AnalysisRetryOutcome.MaxAttempts -> mutableState.value = mutableState.value.copy(
                retrying = false,
                errorMessage = AnalysisRetryOutcome.MaxAttempts.message,
            )
            AnalysisRetryOutcome.Rejected -> mutableState.value = mutableState.value.copy(
                retrying = false,
                errorMessage = "暂时无法重新分析，请稍后重试",
            )
        }
    }

    private suspend fun loadUntilTerminal(operation: Long) {
        while (operation == operationGeneration) {
            val current = mutableState.value.content
            if (current == null) mutableState.value = mutableState.value.copy(loading = true, errorMessage = null)
            val fetched = try {
                remote.fetch(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                if (operation == operationGeneration) mutableState.value = mutableState.value.copy(
                    loading = false,
                    errorMessage = "暂时无法加载演唱结果，请重试",
                )
                return
            } catch (_: Exception) {
                if (operation == operationGeneration) mutableState.value = ResultScreenState(
                    errorMessage = "演唱结果归属或数据校验失败",
                )
                return
            }
            if (operation != operationGeneration || fetched.id.toString() != sessionId) return
            val mapped = ResultMapper.map(fetched)
            val published = mutableState.value.content
            if (published == null || mapped.analysisGeneration >= published.analysisGeneration) {
                mutableState.value = ResultScreenState(content = mapped)
            }
            val effective = mutableState.value.content ?: return
            if (effective.contentState in TERMINAL_STATES) return
            delayMillis(ResultForegroundPoller.FOREGROUND_POLL_MILLIS)
        }
    }

    override fun onCleared() {
        stop()
    }

    companion object {
        private val TERMINAL_STATES = setOf(ResultContentState.COMPLETED, ResultContentState.FAILED, ResultContentState.CANCELLED)

        fun factory(
            sessionId: String,
            remote: ResultSessionRemote,
            retryAction: suspend () -> AnalysisRetryOutcome,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ResultViewModel::class.java))
                return ResultViewModel(sessionId, remote, retryAction, dispatcher) as T
            }
        }
    }
}
