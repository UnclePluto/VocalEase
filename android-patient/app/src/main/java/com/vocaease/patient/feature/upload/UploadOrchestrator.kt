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
                        withExpectedOperation(current) { waitForPersistedDeadline(current.nextRetryAtEpochMillis) }
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
                        withExpectedOperation(current) { waitForPersistedDeadline(current.nextRetryAtEpochMillis) }
                        checkpoint(current, current.copy(stage = UploadStage.CONFIRMING_AUDIO, nextRetryAtEpochMillis = null))
                    }
                    UploadStage.CONFIRMING_AUDIO -> confirm(current, UploadMediaKind.AUDIO)
                    UploadStage.REQUESTING_VIDEO_GRANT -> grantAndUpload(current, UploadMediaKind.VIDEO)
                    UploadStage.UPLOADING_VIDEO -> grantAndUpload(current, UploadMediaKind.VIDEO)
                    UploadStage.WAITING_VIDEO_RECEIPT -> {
                        withExpectedOperation(current) { waitForPersistedDeadline(current.nextRetryAtEpochMillis) }
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
        } catch (failure: ExpectedOperationFailure) {
            return when (failure.error) {
                is UploadContractViolation -> persistTerminal(
                    failure.expected,
                    "上传凭证与本地录制不一致",
                )
                is RetryableUploadException, is java.io.IOException -> persistRetry(failure.expected)
                is UploadRemoteTerminalException -> persistTerminal(
                    failure.expected,
                    "上传服务拒绝了当前任务",
                )
                else -> throw failure.error
            }
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
        if (current.stage != requesting && current.stage != uploading) {
            throw ExpectedOperationFailure(current, UploadContractViolation("阶段错误"))
        }
        val durableCurrent = if (current.stage == uploading) {
            val recovered = withExpectedOperation(current) {
                val binding = current.binding(kind) ?: throw UploadContractViolation("缺少媒体绑定")
                mediaStatus(current, kind, binding, remote.sessionDetail(current.sessionId)).also {
                    if (it == RemoteMediaState.UNKNOWN) throw UploadContractViolation("会话媒体状态无效")
                }
            }
            when (recovered) {
                RemoteMediaState.READY -> {
                    checkpoint(
                        current,
                        current.withUploaded(kind).copy(
                            stage = waitingReceipt,
                            progressPercent = if (kind == UploadMediaKind.AUDIO) 50 else 100,
                        ),
                    )
                    return
                }
                RemoteMediaState.UPLOADING -> checkpoint(current, current)
                RemoteMediaState.UNKNOWN -> error("已在操作代际边界内拒绝未知媒体状态")
            }
        } else current
        // 进程恢复时不持久化 token；同一幂等键安全重取，再验证必须仍绑定同一 asset/object。
        val grant = withExpectedOperation(durableCurrent) {
            remote.grant(UploadGrantRequest(durableCurrent.sessionId, kind, media, key)).also {
                validateUploadUrl(it.uploadUrl)
                validateGrant(durableCurrent, kind, it)
                if (it.expiresAtEpochMillis - nowEpochMillis() < GRANT_SAFETY_MARGIN_MILLIS) {
                    throw RetryableUploadException()
                }
            }
        }
        val persistedBinding = durableCurrent.binding(kind) ?: grant.binding
        val beforeUpload = checkpoint(
            durableCurrent,
            durableCurrent.withBinding(kind, persistedBinding).copy(stage = uploading, safeError = null),
        )
        withExpectedOperation(beforeUpload) {
            validateMediaDetail(
                beforeUpload,
                kind,
                persistedBinding,
                remote.sessionDetail(durableCurrent.sessionId),
                RemoteMediaState.UPLOADING,
            )
        }
        // detail 请求期间可能被暂停/删除取代；启动 SDK 前必须再用旧版本 CAS 确认仍属于本次操作。
        val uploadReady = checkpoint(beforeUpload, beforeUpload)
        val completion = withExpectedOperation(uploadReady) {
            leases.open(kind, media).use { lease ->
                if (!lease.file.isFile || lease.file.length() != media.sizeBytes) throw UploadContractViolation("明文长度错误")
                val completion = uploadWithDurableProgress(uploadReady, kind, grant, lease.file, media.mimeType)
                withExpectedOperation(completion.durableRecord) {
                    when (completion.result) {
                        is QiniuUploadResult.Completed -> if (completion.result.objectKey != persistedBinding.objectKey) {
                            throw UploadContractViolation("对象不匹配")
                        }
                        QiniuUploadResult.Cancelled -> throw CancellationException("上传已取消")
                    }
                }
                completion
            }
        }
        checkpoint(
            completion.durableRecord,
            completion.durableRecord.withUploaded(kind).copy(
                stage = waitingReceipt,
                progressPercent = if (kind == UploadMediaKind.AUDIO) 50 else 100,
            ),
        )
    }

    private suspend fun uploadWithDurableProgress(
        expected: UploadRecord,
        kind: UploadMediaKind,
        grant: UploadGrant,
        file: java.io.File,
        mimeType: String,
    ): DurableUploadCompletion = coroutineScope {
        val updates = Channel<Int>(Channel.CONFLATED)
        val durable = java.util.concurrent.atomic.AtomicReference(expected)
        val writer = launch {
            for (progress in updates) {
                durable.set(store.checkpointProgress(durable.get(), progress))
                onProgress(progress)
            }
        }
        val invalidProgress = java.util.concurrent.atomic.AtomicReference<UploadContractViolation?>(null)
        var uploadFailure: Throwable? = null
        val result = try {
            uploader.upload(QiniuUploadRequest(kind, grant, file, mimeType)) progress@{ percent ->
                if (percent !in 0..100) {
                    invalidProgress.compareAndSet(null, UploadContractViolation("上传进度错误"))
                    updates.close()
                    uploader.cancel()
                    return@progress
                }
                updates.trySend(if (kind == UploadMediaKind.AUDIO) percent / 2 else 50 + percent / 2)
            }
        } catch (error: Throwable) {
            uploadFailure = error
            null
        } finally {
            updates.close()
            writer.join()
        }
        val durableRecord = durable.get()
        invalidProgress.get()?.let { throw ExpectedOperationFailure(durableRecord, it) }
        uploadFailure?.let {
            if (it is CancellationException || it is ExpectedOperationFailure) throw it
            throw ExpectedOperationFailure(durableRecord, it)
        }
        DurableUploadCompletion(requireNotNull(result), durableRecord)
    }

    private suspend fun confirm(current: UploadRecord, kind: UploadMediaKind) {
        val bindingAndResult = withExpectedOperation(current) {
            val binding = current.binding(kind) ?: throw UploadContractViolation("缺少媒体绑定")
            if (!current.uploaded(kind)) throw UploadContractViolation("媒体尚未上传")
            binding to remote.confirm(UploadConfirmRequest(current.sessionId, kind, binding))
        }
        val binding = bindingAndResult.first
        when (val result = bindingAndResult.second) {
            is UploadConfirmResult.Confirmed -> {
                withExpectedOperation(current) {
                    if (result.binding != binding) throw UploadContractViolation("确认响应不匹配")
                }
                val confirmedResponse = checkpoint(current, current)
                withExpectedOperation(confirmedResponse) {
                    validateMediaDetail(
                        confirmedResponse,
                        kind,
                        binding,
                        remote.sessionDetail(current.sessionId),
                        RemoteMediaState.READY,
                    )
                }
                val next = if (kind == UploadMediaKind.AUDIO) UploadStage.REQUESTING_VIDEO_GRANT else UploadStage.SUBMITTING
                checkpoint(
                    confirmedResponse,
                    confirmedResponse.withConfirmed(kind).copy(stage = next, receiptWaitAttempt = 0, safeError = null),
                )
            }
            UploadConfirmResult.CallbackPending -> {
                val attempt = withExpectedOperation(current) {
                    current.receiptWaitAttempt.also {
                        if (it >= CALLBACK_DELAYS_MILLIS.size) throw RetryableUploadException()
                    }
                }
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

    private suspend fun persistRetry(expected: UploadRecord): UploadRunResult {
        return when (expected.stage) {
            UploadStage.PAUSED -> UploadRunResult.Paused
            UploadStage.ANALYZING -> UploadRunResult.Analyzing
            UploadStage.FAILED -> UploadRunResult.TerminalFailure(expected.safeError ?: "上传失败，请稍后重试")
            else -> {
                val resume = if (expected.stage == UploadStage.WAITING_NETWORK) {
                    expected.resumeStage ?: UploadStage.REQUESTING_AUDIO_GRANT
                } else {
                    expected.stage
                }
                checkpoint(
                    expected,
                    expected.copy(
                        stage = UploadStage.WAITING_NETWORK,
                        resumeStage = resume,
                        attemptCount = expected.attemptCount + 1,
                        nextRetryAtEpochMillis = nowEpochMillis() + NETWORK_RETRY_MILLIS,
                        safeError = "网络暂不可用，等待重试",
                    ),
                )
                UploadRunResult.Retry
            }
        }
    }

    private suspend fun submit(current: UploadRecord) {
        val result = withExpectedOperation(current) {
            if (!current.audioConfirmed || !current.videoConfirmed) throw UploadContractViolation("媒体尚未确认")
            remote.submit(current.sessionId, current.submitKey)
        }
        when (result) {
            is UploadSubmitResult.Accepted -> {
                withExpectedOperation(current) {
                    if (result.sessionId != current.sessionId) throw UploadContractViolation("提交响应不匹配")
                }
                checkpoint(current, current.copy(stage = UploadStage.ANALYZING, safeError = null))
            }
            UploadSubmitResult.Conflict -> {
                val conflictResponse = checkpoint(current, current)
                val detail = withExpectedOperation(conflictResponse) {
                    remote.sessionDetail(current.sessionId).also { validateSessionDetail(conflictResponse, it) }
                }
                when (detail.state) {
                    RemoteSessionState.PROCESSING, RemoteSessionState.COMPLETED ->
                        checkpoint(conflictResponse, conflictResponse.copy(stage = UploadStage.ANALYZING, safeError = null))
                    RemoteSessionState.UPLOADED -> Unit // 同一 submit key 在下一循环重试。
                    else -> {
                        checkpoint(
                            conflictResponse,
                            conflictResponse.copy(stage = UploadStage.FAILED, safeError = "演唱记录状态异常，无法继续提交"),
                        )
                        throw TerminalAlreadyPersisted("演唱记录状态异常，无法继续提交")
                    }
                }
            }
        }
    }

    private suspend fun checkpoint(expected: UploadRecord, next: UploadRecord): UploadRecord {
        return withExpectedOperation(expected) {
            UploadTransitions.requireAllowed(expected.stage, next.stage)
            if (expected.draftId != next.draftId || expected.sessionId != next.sessionId || expected.accountScopeHash != next.accountScopeHash) {
                throw UploadContractViolation("任务身份变化")
            }
            store.checkpoint(
                expected,
                next.copy(operationVersion = expected.operationVersion + 1),
            )
        }
    }

    private suspend fun persistTerminal(expected: UploadRecord, safeReason: String): UploadRunResult {
        checkpoint(expected, expected.copy(stage = UploadStage.FAILED, safeError = safeReason))
        return UploadRunResult.TerminalFailure(safeReason)
    }

    private suspend fun <T> withExpectedOperation(
        expected: UploadRecord,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ExpectedOperationFailure) {
        throw failure
    } catch (terminal: TerminalAlreadyPersisted) {
        throw terminal
    } catch (error: Throwable) {
        when (error) {
            is UploadContractViolation,
            is RetryableUploadException,
            is java.io.IOException,
            is UploadRemoteTerminalException,
            -> throw ExpectedOperationFailure(expected, error)
            else -> throw error
        }
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

    private fun mediaStatus(
        record: UploadRecord,
        kind: UploadMediaKind,
        binding: UploadBinding,
        detail: UploadSessionDetail,
    ): RemoteMediaState {
        if (detail.sessionId != record.sessionId) throw UploadContractViolation("会话不匹配")
        val exact = detail.media.filter { it.kind == kind }
        if (exact.size != 1) throw UploadContractViolation("会话媒体不匹配")
        val media = exact.single()
        if (media.assetId != binding.assetId || media.mimeType != binding.mimeType || media.sizeBytes != binding.sizeBytes) {
            throw UploadContractViolation("会话媒体不匹配")
        }
        return media.status
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
    private class ExpectedOperationFailure(
        val expected: UploadRecord,
        val error: Throwable,
    ) : Exception(error)
    private data class DurableUploadCompletion(
        val result: QiniuUploadResult,
        val durableRecord: UploadRecord,
    )

    private companion object {
        const val GRANT_SAFETY_MARGIN_MILLIS = 60_000L
        const val NETWORK_RETRY_MILLIS = 30_000L
        val CALLBACK_DELAYS_MILLIS = longArrayOf(2_000, 5_000, 10_000, 30_000)
        val executionLocks = ConcurrentHashMap<String, Mutex>()
    }
}
