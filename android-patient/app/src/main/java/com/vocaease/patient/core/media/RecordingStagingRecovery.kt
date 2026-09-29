package com.vocaease.patient.core.media

import com.vocaease.patient.core.database.AccountScopedDraftStorage
import com.vocaease.patient.core.database.DraftState
import com.vocaease.patient.core.database.StaleAccountScopeException

data class RecordingRecoveryResult(
    val recovered: Int,
    val discarded: Int,
    val retryableFailures: Int,
)

/** 将当前账户的崩溃暂存恢复成不可提交、但可本地回看的中断草稿。 */
class RecordingStagingRecovery internal constructor(
    private val files: PrivateRecordingTempFiles,
    private val extractor: Mp4AudioTrackExtractor = Mp4AudioTrackExtractor(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val beforePublish: suspend () -> Unit = {},
) {
    suspend fun recover(storage: AccountScopedDraftStorage): RecordingRecoveryResult {
        var recovered = 0
        var discarded = 0
        var retryable = 0
        val entries = files.listRecoverable(storage.accountScopeHash)
        for (entry in entries) {
            if (!storage.isLeaseActive()) break
            val draft = try {
                storage.findDraft(entry.identity.draftId)
            } catch (_: StaleAccountScopeException) {
                break
            }
            if (draft == null) {
                files.cleanup(entry)
                discarded += 1
                continue
            }
            val identityMatches = draft.sessionId == entry.identity.sessionId &&
                draft.creationKey == entry.identity.creationKey
            if (!identityMatches) {
                if (!storage.isLeaseActive()) break
                try {
                    if (draft.state == DraftState.RECORDING) {
                        storage.markRecordingInterrupted(draft.draftId, 0, BINDING_INVALID_REASON)
                    }
                    files.cleanup(entry)
                    discarded += 1
                } catch (_: StaleAccountScopeException) {
                    break
                } catch (_: Exception) {
                    retryable += 1
                }
                continue
            }
            if (draft.state != DraftState.RECORDING) {
                // 上次运行可能已提交Room事务但尚未删sidecar；重跑只收尾明文，不重发。
                files.cleanup(entry)
                discarded += 1
                continue
            }
            val now = nowMillis()
            val recoverableAge = entry.createdAtMillis in 0..now &&
                now - entry.createdAtMillis < MAX_RECOVERY_AGE_MILLIS
            if (!recoverableAge) {
                if (!storage.isLeaseActive()) break
                try {
                    storage.markRecordingInterrupted(draft.draftId, 0, EXPIRED_REASON)
                    files.cleanup(entry)
                    discarded += 1
                } catch (_: StaleAccountScopeException) {
                    break
                } catch (_: Exception) {
                    retryable += 1
                }
                continue
            }

            val audio = files.createAudio()
            try {
                val track = extractor.extract(entry.video, audio)
                beforePublish()
                if (!storage.isLeaseActive()) throw StaleAccountScopeException()
                storage.publishInterruptedRecordingMedia(
                    draftId = draft.draftId,
                    video = entry.video,
                    audio = audio,
                    durationMs = (track.sourceDurationUs / 1_000L).coerceAtLeast(1L),
                    reason = PROCESS_DEATH_REASON,
                    publicationActive = storage::isLeaseActive,
                )
                files.cleanup(entry)
                files.cleanup(audio)
                recovered += 1
            } catch (_: MediaValidationException) {
                if (!storage.isLeaseActive()) {
                    files.cleanup(audio)
                    break
                }
                try {
                    storage.markRecordingInterrupted(draft.draftId, 0, INVALID_MEDIA_REASON)
                    files.cleanup(entry)
                    files.cleanup(audio)
                    discarded += 1
                } catch (_: StaleAccountScopeException) {
                    files.cleanup(audio)
                    break
                } catch (_: Exception) {
                    files.cleanup(audio)
                    retryable += 1
                }
            } catch (_: Exception) {
                // 加密/Room/lease故障由发布仓储回滚新密文；保留原视频+sidecar供重试。
                files.cleanup(audio)
                retryable += 1
                if (!storage.isLeaseActive()) break
            }
        }
        return RecordingRecoveryResult(recovered, discarded, retryable)
    }

    private companion object {
        const val MAX_RECOVERY_AGE_MILLIS = 24L * 60L * 60L * 1_000L
        const val PROCESS_DEATH_REASON = "录制因应用中断，已恢复本地回看"
        const val INVALID_MEDIA_REASON = "录制文件损坏，请重新录制"
        const val EXPIRED_REASON = "录制恢复已过期，请重新录制"
        const val BINDING_INVALID_REASON = "录制绑定失效，请重新录制"
    }
}
