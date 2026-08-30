package com.vocaease.patient.feature.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vocaease.patient.core.database.AccountScopedDraftStorage
import com.vocaease.patient.core.database.UploadJobEntity
import com.vocaease.patient.core.database.UploadPipelineStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PendingUploadsViewModel(
    private val storage: AccountScopedDraftStorage,
    private val coordinator: UploadCoordinator,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PendingUploadsUiState(loading = true))
    val state: StateFlow<PendingUploadsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch(dispatcher) {
            try {
                storage.observeUploadJobs().map { jobs ->
                    jobs.map { job -> job.toItem(storage.findPreparationDraft(job.draftId)?.songTitle ?: "演唱记录") }
                }.collect { items -> mutableState.value = PendingUploadsUiState(items = items) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = PendingUploadsUiState(safeError = "待上传记录暂时无法读取")
            }
        }
    }

    fun pause(draftId: String) = mutate(draftId) { coordinator.pause(it) }
    fun resume(draftId: String) = mutate(draftId) { coordinator.retry(it) }
    fun retry(draftId: String) = mutate(draftId) { coordinator.retry(it) }
    fun delete(draftId: String) = mutate(draftId) { coordinator.delete(it) }

    private fun mutate(draftId: String, action: suspend (UploadWorkContract) -> Unit) {
        viewModelScope.launch(dispatcher) {
            try {
                action(UploadWorkContract(storage.accountScopeHash, draftId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(safeError = "操作未完成，请稍后重试")
            }
        }
    }

    companion object {
        fun factory(storage: AccountScopedDraftStorage, coordinator: UploadCoordinator): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(PendingUploadsViewModel::class.java))
                    return PendingUploadsViewModel(storage, coordinator) as T
                }
            }
    }
}

private fun UploadJobEntity.toItem(title: String): PendingUploadItem {
    val status = when (pipelineStage) {
        UploadPipelineStage.PAUSED -> "已暂停"
        UploadPipelineStage.WAITING_NETWORK -> "等待网络"
        UploadPipelineStage.REQUESTING_AUDIO_GRANT, UploadPipelineStage.REQUESTING_VIDEO_GRANT -> "正在准备上传"
        UploadPipelineStage.UPLOADING_AUDIO, UploadPipelineStage.UPLOADING_VIDEO -> "正在上传 $progressPercent%"
        UploadPipelineStage.WAITING_AUDIO_RECEIPT, UploadPipelineStage.WAITING_VIDEO_RECEIPT -> "等待服务器确认"
        UploadPipelineStage.CONFIRMING_AUDIO, UploadPipelineStage.CONFIRMING_VIDEO -> "确认中"
        UploadPipelineStage.SUBMITTING -> "正在提交"
        UploadPipelineStage.ANALYZING -> "分析中"
        UploadPipelineStage.FAILED -> lastSafeError ?: "上传失败，请稍后重试"
    }
    val beforeSubmit = pipelineStage !in setOf(UploadPipelineStage.SUBMITTING, UploadPipelineStage.ANALYZING)
    return PendingUploadItem(
        draftId = draftId,
        songTitle = title,
        statusText = status,
        progressPercent = progressPercent,
        canPause = pipelineStage !in setOf(UploadPipelineStage.PAUSED, UploadPipelineStage.FAILED, UploadPipelineStage.ANALYZING),
        canResume = pipelineStage == UploadPipelineStage.PAUSED,
        canDelete = beforeSubmit,
        canRetry = pipelineStage !in setOf(
            UploadPipelineStage.PAUSED, UploadPipelineStage.FAILED,
            UploadPipelineStage.SUBMITTING, UploadPipelineStage.ANALYZING,
        ),
    )
}
