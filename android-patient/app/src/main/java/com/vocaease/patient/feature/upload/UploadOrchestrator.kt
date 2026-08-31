package com.vocaease.patient.feature.upload

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UploadOrchestrator(
    private val store: UploadStore,
    private val remote: UploadRemote,
    private val uploader: QiniuUploader,
    private val leases: PlaintextUploadLeaseProvider,
    private val wait: suspend (Long) -> Unit,
    private val nowEpochMillis: () -> Long,
    private val onProgress: (Int) -> Unit = {},
) {
    suspend fun run(): UploadRunResult {
        val identity = store.load()
        return executionLocks.computeIfAbsent("${identity.accountScopeHash}:${identity.draftId}") { Mutex() }.withLock {
            runLinearized()
        }
    }

    private suspend fun runLinearized(): UploadRunResult {
        try {
            while (true) {
                val current = store.load()
                when (current.stage) {
                    UploadStage.PAUSED -> return UploadRunResult.Paused
                    UploadStage.WAITING_NETWORK -> {
                        waitForPersistedDeadline(current.nextRetryAtEpochMillis)
                        checkpoint(
                            current,
                            current.copy(
                                stage = current.resumeStage ?: UploadStage.REQUESTING_AUDIO_GRANT,
                                nextRetryAtEpochMillis = null,
                                resumeStage = null,
                                safeError = null,
                            ),
                        )
                    }
                    UploadStage.REQUESTING_AUDIO_GRANT -> grantAndUpload(current, UploadMediaKind.AUDIO)
                    UploadStage.UPLOADING_AUDIO -> grantAndUpload(current, UploadMediaKind.AUDIO)
                    UploadStage.WAITING_AUDIO_RECEIPT -> {
                        waitForPersistedDeadline(current.nextRetryAtEpochMillis)
                        checkpoint(current, current.copy(stage = UploadStage.CONFIRMING_AUDIO, nextRetryAtEpochMillis = null))
                    }
                    UploadStage.CONFIRMING_AUDIO -> confirm(current, UploadMediaKind.AUDIO)
                    UploadStage.REQUESTING_VIDEO_GRANT -> grantAndUpload(current, UploadMediaKind.VIDEO)
                    UploadStage.UPLOADING_VIDEO -> grantAndUpload(current, UploadMediaKind.VIDEO)
                    UploadStage.WAITING_VIDEO_RECEIPT -> {
                        waitForPersistedDeadline(current.nextRetryAtEpochMillis)
                        checkpoint(current, current.copy(stage = UploadStage.CONFIRMING_VIDEO, nextRetryAtEpochMillis = null))
                    }
                    UploadStage.CONFIRMING_VIDEO -> confirm(current, UploadMediaKind.VIDEO)
                    UploadStage.SUBMITTING -> submit(current)
                    UploadStage.ANALYZING -> {
                        store.finishLocalCleanup()
                        return UploadRunResult.Analyzing
                    }
                    UploadStage.FAILED -> return UploadRunResult.TerminalFailure(current.safeError ?: "上传失败，请稍后重试")
                }
            }
        } catch (cancelled: CancellationException) {
            uploader.cancel()
            throw cancelled
        } catch (_: UploadContractViolation) {
            val current = store.load()
            checkpoint(current, current.copy(stage = UploadStage.FAILED, safeError = "上传凭证与本地录制不一致"))
            return UploadRunResult.TerminalFailure("上传凭证与本地录制不一致")
        } catch (_: RetryableUploadException) {
            return persistRetry()
        } catch (_: java.io.IOException) {
            return persistRetry()
        } catch (_: UploadRemoteTerminalException) {
            val current = store.load()
            checkpoint(current, current.copy(stage = UploadStage.FAILED, safeError = "上传服务拒绝了当前任务"))
            return UploadRunResult.TerminalFailure("上传服务拒绝了当前任务")
        } catch (terminal: TerminalAlreadyPersisted) {
            return UploadRunResult.TerminalFailure(terminal.message ?: "演唱记录状态异常，无法继续提交")
        } finally {
            uploader.cancel()
        }
    }

    private suspend fun grantAndUpload(current: UploadRecord, kind: UploadMediaKind) {
        val requesting = when (kind) {
            UploadMediaKind.AUDIO -> UploadStage.REQUESTING_AUDIO_GRANT
            UploadMediaKind.VIDEO -> UploadStage.REQUESTING_VIDEO_GRANT
        }
        val uploading = when (kind) {
            UploadMediaKind.AUDIO -> UploadStage.UPLOADING_AUDIO
            UploadMediaKind.VIDEO -> UploadStage.UPLOADING_VIDEO
        }
        val waitingReceipt = when (kind) {
            UploadMediaKind.AUDIO -> UploadStage.WAITING_AUDIO_RECEIPT
            UploadMediaKind.VIDEO -> UploadStage.WAITING_VIDEO_RECEIPT
        }
        val media = current.media(kind)
        val key = current.grantKey(kind)
        if (current.stage != requesting && current.stage != uploading) throw UploadContractViolation("阶段错误")
        if (current.stage == uploading) checkpoint(current, current)
        // 进程恢复时不持久化 token；同一幂等键安全重取，再验证必须仍绑定同一 asset/object。
        val grant = remote.grant(UploadGrantRequest(current.sessionId, kind, media, key))
        validateUploadUrl(grant.uploadUrl)
        validateGrant(current, kind, grant)
        if (grant.expiresAtEpochMillis - nowEpochMillis() < GRANT_SAFETY_MARGIN_MILLIS) throw RetryableUploadException()
        val persistedBinding = current.binding(kind) ?: grant.binding
        val beforeUpload = current.withBinding(kind, persistedBinding).copy(stage = uploading, safeError = null)
        checkpoint(current, beforeUpload)
        validateMediaDetail(beforeUpload, kind, persistedBinding, remote.sessionDetail(current.sessionId), RemoteMediaState.UPLOADING)
        leases.open(kind, media).use { lease ->
            if (!lease.file.isFile || lease.file.length() != media.sizeBytes) throw UploadContractViolation("明文长度错误")
            val result = uploadWithDurableProgress(kind, grant, lease.file, media.mimeType)
            when (result) {
                is QiniuUploadResult.Completed -> if (result.objectKey != persistedBinding.objectKey) {
                    throw UploadContractViolation("对象不匹配")
                }
                QiniuUploadResult.Cancelled -> throw CancellationException("上传已取消")
            }
        }
        checkpoint(
            beforeUpload,
            beforeUpload.withUploaded(kind).copy(
                stage = waitingReceipt,
                progressPercent = if (kind == UploadMediaKind.AUDIO) 50 else 100,
            ),
        )
    }

    private suspend fun uploadWithDurableProgress(
        kind: UploadMediaKind,
        grant: UploadGrant,
        file: java.io.File,
        mimeType: String,
    ): QiniuUploadResult = coroutineScope {
        val updates = Channel<Int>(Channel.CONFLATED)
        val writer = launch {
            for (progress in updates) {
                store.checkpointProgress(progress)
                onProgress(progress)
            }
        }
        try {
            uploader.upload(QiniuUploadRequest(kind, grant, file, mimeType)) progress@{ percent ->
                if (percent !in 0..100) {
                    updates.close(UploadContractViolation("上传进度错误"))
                    uploader.cancel()
                    return@progress
                }
                updates.trySend(if (kind == UploadMediaKind.AUDIO) percent / 2 else 50 + percent / 2)
            }
        } finally {
            updates.close()
            writer.join()
        }
    }

    private suspend fun confirm(current: UploadRecord, kind: UploadMediaKind) {
        val binding = current.binding(kind) ?: throw UploadContractViolation("缺少媒体绑定")
        if (!current.uploaded(kind)) throw UploadContractViolation("媒体尚未上传")
        when (val result = remote.confirm(UploadConfirmRequest(current.sessionId, kind, binding))) {
            is UploadConfirmResult.Confirmed -> {
                if (result.binding != binding) throw UploadContractViolation("确认响应不匹配")
                validateMediaDetail(current, kind, binding, remote.sessionDetail(current.sessionId), RemoteMediaState.READY)
                val next = if (kind == UploadMediaKind.AUDIO) UploadStage.REQUESTING_VIDEO_GRANT else UploadStage.SUBMITTING
                checkpoint(current, current.withConfirmed(kind).copy(stage = next, receiptWaitAttempt = 0, safeError = null))
            }
            UploadConfirmResult.CallbackPending -> {
                val attempt = current.receiptWaitAttempt
                if (attempt >= CALLBACK_DELAYS_MILLIS.size) throw RetryableUploadException()
                val waitingStage = if (kind == UploadMediaKind.AUDIO) UploadStage.WAITING_AUDIO_RECEIPT else UploadStage.WAITING_VIDEO_RECEIPT
                checkpoint(
                    current,
                    current.copy(
                        stage = waitingStage,
                        receiptWaitAttempt = attempt + 1,
                        attemptCount = current.attemptCount + 1,
                        nextRetryAtEpochMillis = nowEpochMillis() + CALLBACK_DELAYS_MILLIS[attempt],
                    ),
                )
            }
        }
    }

    private suspend fun waitForPersistedDeadline(deadlineEpochMillis: Long?) {
        val remaining = deadlineEpochMillis?.minus(nowEpochMillis()) ?: return
        if (remaining > 0) wait(remaining)
    }

    private suspend fun persistRetry(): UploadRunResult {
        val current = store.load()
        return when (current.stage) {
            UploadStage.PAUSED -> UploadRunResult.Paused
            UploadStage.ANALYZING -> UploadRunResult.Analyzing
            UploadStage.FAILED -> UploadRunResult.TerminalFailure(current.safeError ?: "上传失败，请稍后重试")
            else -> {
                val resume = if (current.stage == UploadStage.WAITING_NETWORK) {
                    current.resumeStage ?: UploadStage.REQUESTING_AUDIO_GRANT
                } else {
                    current.stage
                }
                checkpoint(
                    current,
                    current.copy(
                        stage = UploadStage.WAITING_NETWORK,
                        resumeStage = resume,
                        attemptCount = current.attemptCount + 1,
                        nextRetryAtEpochMillis = nowEpochMillis() + NETWORK_RETRY_MILLIS,
                        safeError = "网络暂不可用，等待重试",
                    ),
                )
                UploadRunResult.Retry
            }
        }
    }

    private suspend fun submit(current: UploadRecord) {
        if (!current.audioConfirmed || !current.videoConfirmed) throw UploadContractViolation("媒体尚未确认")
        when (val result = remote.submit(current.sessionId, current.submitKey)) {
            is UploadSubmitResult.Accepted -> {
                if (result.sessionId != current.sessionId) throw UploadContractViolation("提交响应不匹配")
                checkpoint(current, current.copy(stage = UploadStage.ANALYZING, safeError = null))
            }
            UploadSubmitResult.Conflict -> {
                val detail = remote.sessionDetail(current.sessionId)
                validateSessionDetail(current, detail)
                when (detail.state) {
                    RemoteSessionState.PROCESSING, RemoteSessionState.COMPLETED ->
                        checkpoint(current, current.copy(stage = UploadStage.ANALYZING, safeError = null))
                    RemoteSessionState.UPLOADED -> Unit // 同一 submit key 在下一循环重试。
                    else -> {
                        checkpoint(current, current.copy(stage = UploadStage.FAILED, safeError = "演唱记录状态异常，无法继续提交"))
                        throw TerminalAlreadyPersisted("演唱记录状态异常，无法继续提交")
                    }
                }
            }
        }
    }

    private suspend fun checkpoint(expected: UploadRecord, next: UploadRecord) {
        val current = store.load()
        if (current.stage != expected.stage) {
            if (current.stage == UploadStage.PAUSED) throw CancellationException("上传已暂停")
            throw UploadContractViolation("任务阶段已变化")
        }
        UploadTransitions.requireAllowed(current.stage, next.stage)
        if (current.draftId != next.draftId || current.sessionId != next.sessionId || current.accountScopeHash != next.accountScopeHash) {
            throw UploadContractViolation("任务身份变化")
        }
        store.checkpoint(next)
    }

    private fun validateGrant(record: UploadRecord, kind: UploadMediaKind, grant: UploadGrant) {
        val media = record.media(kind)
        val expected = record.binding(kind)
        val binding = grant.binding
        if (binding.sessionId != record.sessionId || binding.mimeType != media.mimeType || binding.sizeBytes != media.sizeBytes ||
            binding.assetId.isBlank() || binding.objectKey.isBlank() || (expected != null && expected != binding)
        ) throw UploadContractViolation("上传凭证不匹配")
    }

    private fun validateSessionDetail(record: UploadRecord, detail: UploadSessionDetail) {
        if (detail.sessionId != record.sessionId) throw UploadContractViolation("会话不匹配")
        val expected = listOfNotNull(
            record.audioBinding?.let { UploadSessionMedia(it.assetId, UploadMediaKind.AUDIO, it.mimeType, it.sizeBytes, RemoteMediaState.READY) },
            record.videoBinding?.let { UploadSessionMedia(it.assetId, UploadMediaKind.VIDEO, it.mimeType, it.sizeBytes, RemoteMediaState.READY) },
        )
        if (expected.size != 2 || detail.media.size != 2 || detail.media.toSet() != expected.toSet()) {
            throw UploadContractViolation("会话媒体不匹配")
        }
    }

    private fun validateMediaDetail(
        record: UploadRecord,
        kind: UploadMediaKind,
        binding: UploadBinding,
        detail: UploadSessionDetail,
        expectedStatus: RemoteMediaState,
    ) {
        if (detail.sessionId != record.sessionId) throw UploadContractViolation("会话不匹配")
        val exact = detail.media.filter { it.kind == kind }
        if (exact.size != 1 || exact.single() != UploadSessionMedia(
                binding.assetId,
                kind,
                binding.mimeType,
                binding.sizeBytes,
                expectedStatus,
            )
        ) throw UploadContractViolation("会话媒体不匹配")
    }

    private fun validateUploadUrl(value: String) {
        runCatching { QiniuV2Configuration.validatedHost(value) }
            .getOrElse { throw UploadContractViolation("上传地址无效") }
    }

    private fun UploadRecord.media(kind: UploadMediaKind) = if (kind == UploadMediaKind.AUDIO) audio else video
    private fun UploadRecord.grantKey(kind: UploadMediaKind) = if (kind == UploadMediaKind.AUDIO) audioGrantKey else videoGrantKey
    private fun UploadRecord.binding(kind: UploadMediaKind) = if (kind == UploadMediaKind.AUDIO) audioBinding else videoBinding
    private fun UploadRecord.uploaded(kind: UploadMediaKind) = if (kind == UploadMediaKind.AUDIO) audioUploaded else videoUploaded
    private fun UploadRecord.withBinding(kind: UploadMediaKind, value: UploadBinding) = if (kind == UploadMediaKind.AUDIO) copy(audioBinding = value) else copy(videoBinding = value)
    private fun UploadRecord.withUploaded(kind: UploadMediaKind) = if (kind == UploadMediaKind.AUDIO) copy(audioUploaded = true) else copy(videoUploaded = true)
    private fun UploadRecord.withConfirmed(kind: UploadMediaKind) = if (kind == UploadMediaKind.AUDIO) copy(audioConfirmed = true) else copy(videoConfirmed = true)

    private class RetryableUploadException : Exception()
    private class TerminalAlreadyPersisted(message: String) : Exception(message)

    private companion object {
        const val GRANT_SAFETY_MARGIN_MILLIS = 60_000L
        const val NETWORK_RETRY_MILLIS = 30_000L
        val CALLBACK_DELAYS_MILLIS = longArrayOf(2_000, 5_000, 10_000, 30_000)
        val executionLocks = ConcurrentHashMap<String, Mutex>()
    }
}
