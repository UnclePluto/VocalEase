package com.vocaease.patient.core.database

import androidx.room.withTransaction
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import java.io.InputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StaleAccountScopeException internal constructor() : IllegalStateException("当前账户存储已失效")
class SessionBindingMismatchException internal constructor() : IllegalArgumentException("服务端会话与本地草稿不一致")

class AuthenticatedAccountLease internal constructor(
    internal val patientId: String,
    internal val incarnationId: String = UUID.randomUUID().toString(),
)

interface AuthenticatedAccountSession {
    fun current(): AuthenticatedAccountLease?
    fun addLeaseChangedListener(listener: (AuthenticatedAccountLease?) -> Unit) = Unit
    suspend fun <T> withCurrentLease(
        expected: AuthenticatedAccountLease,
        operation: suspend () -> T,
    ): T
}

internal class MutableAuthenticatedAccountSession : AuthenticatedAccountSession {
    private val mutex = Mutex()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(AuthenticatedAccountLease?) -> Unit>()
    @Volatile private var lease: AuthenticatedAccountLease? = null

    suspend fun authenticate(patientId: String) = mutex.withLock {
        require(patientId.isNotBlank())
        lease = AuthenticatedAccountLease(patientId)
        listeners.forEach { it(lease) }
    }

    suspend fun clear() = mutex.withLock {
        lease = null
        listeners.forEach { it(null) }
    }

    override fun current(): AuthenticatedAccountLease? = lease
    override fun addLeaseChangedListener(listener: (AuthenticatedAccountLease?) -> Unit) {
        listeners += listener
        listener(lease)
    }
    override suspend fun <T> withCurrentLease(
        expected: AuthenticatedAccountLease,
        operation: suspend () -> T,
    ): T = mutex.withLock {
        if (lease !== expected) throw StaleAccountScopeException()
        operation()
    }
}

/**
 * 只签发与当前认证患者绑定的存储 facade。账户标识不出现在任何公开方法参数中。
 */
class AccountScopedDraftStorageProvider internal constructor(
    private val database: VocaEaseDatabase,
    private val fileStore: ChunkedAesGcmFileStore,
    private val session: AuthenticatedAccountSession,
) {
    fun current(): AccountScopedDraftStorage {
        val lease = session.current() ?: throw StaleAccountScopeException()
        return AccountScopedDraftStorage(database, fileStore, session, lease)
    }
}

class AccountScopedDraftStorage internal constructor(
    private val database: VocaEaseDatabase,
    private val fileStore: ChunkedAesGcmFileStore,
    private val session: AuthenticatedAccountSession,
    private val lease: AuthenticatedAccountLease,
) {
    val accountScopeHash: String = ChunkedAesGcmFileStore.sha256(lease.patientId)

    suspend fun encryptMedia(plaintext: InputStream, originalLength: Long): EncryptedMediaAsset = checked {
        fileStore.encrypt(lease.patientId, plaintext, originalLength).let {
            EncryptedMediaAsset(it.relativePath, it.encryptedSizeBytes)
        }
    }
    suspend fun insertDraft(
        draftId: String,
        songId: String,
        sessionId: String,
        creationKey: String,
        state: DraftState,
        durationMs: Long,
        createdAt: Long,
        expiresAt: Long,
        interruptionReason: String?,
    ) = checked {
        database.draftDao().insert(
            DraftEntity(
                accountScope = lease.patientId,
                draftId = draftId,
                songId = songId,
                sessionId = sessionId,
                creationKey = creationKey,
                state = state,
                durationMs = durationMs,
                createdAt = createdAt,
                expiresAt = expiresAt,
                interruptionReason = interruptionReason,
            ),
        )
    }

    suspend fun findDraft(draftId: String): DraftSnapshot? = checked {
        database.draftDao().find(lease.patientId, draftId)?.let {
            DraftSnapshot(it.draftId, it.songId, it.sessionId, it.creationKey, it.state, it.durationMs, it.createdAt, it.expiresAt, it.interruptionReason)
        }
    }

    suspend fun insertPreparationDraft(draft: PreparationDraftSnapshot) = checked {
        require(draft.accountScopeHash == accountScopeHash) { "账户作用域不匹配" }
        database.preparationDraftDao().insert(
            PreparationDraftEntity(
                accountScope = lease.patientId,
                draftId = draft.draftId,
                songId = draft.songId,
                songTitle = draft.songTitle,
                songArtist = draft.songArtist,
                songDurationSeconds = draft.songDurationSeconds,
                serverSessionId = draft.serverSessionId,
                creationKey = draft.creationKey,
                status = draft.status,
                activeSongId = draft.activeSongId,
                createdAt = draft.createdAt,
                expiresAt = draft.expiresAt,
            ),
        )
    }

    suspend fun findPreparationDraft(draftId: String): PreparationDraftSnapshot? = checked {
        database.preparationDraftDao().find(lease.patientId, draftId)?.toSnapshot(accountScopeHash)
    }

    suspend fun findActivePreparationDraft(songId: String): PreparationDraftSnapshot? = checked {
        database.preparationDraftDao().findActive(lease.patientId, songId)?.toSnapshot(accountScopeHash)
    }

    suspend fun findOrCreatePreparationDraft(candidate: PreparationDraftSnapshot): PreparationDraftSnapshot = checked {
        require(candidate.accountScopeHash == accountScopeHash) { "账户作用域不匹配" }
        database.withTransaction {
            database.preparationDraftDao().findActive(lease.patientId, candidate.songId)?.toSnapshot(accountScopeHash)
                ?: run {
                    insertPreparationEntity(candidate)
                    requireNotNull(
                        database.preparationDraftDao().find(lease.patientId, candidate.draftId),
                    ).toSnapshot(accountScopeHash)
                }
        }
    }

    suspend fun bindPreparationSession(
        draftId: String,
        patientId: String,
        serverSessionId: String,
        songId: String,
        songTitle: String,
        songArtist: String,
        songDurationSeconds: Int,
    ) = checked {
        if (patientId != lease.patientId) throw SessionBindingMismatchException()
        database.withTransaction {
            val pending = database.preparationDraftDao().find(lease.patientId, draftId)
                ?: error("演唱准备草稿不存在")
            if (pending.songId != songId) throw SessionBindingMismatchException()
            if (pending.serverSessionId == null) {
                check(
                    database.preparationDraftDao().bind(
                        accountScope = lease.patientId,
                        draftId = draftId,
                        serverSessionId = serverSessionId,
                        songTitle = songTitle,
                        songArtist = songArtist,
                        songDurationSeconds = songDurationSeconds,
                    ) == 1,
                ) { "服务端会话绑定未持久化" }
            } else {
                if (pending.serverSessionId != serverSessionId) throw SessionBindingMismatchException()
            }
            val existing = database.draftDao().find(lease.patientId, draftId)
            if (existing == null) {
                database.draftDao().insert(
                    DraftEntity(
                        accountScope = lease.patientId,
                        draftId = draftId,
                        songId = songId,
                        sessionId = serverSessionId,
                        creationKey = pending.creationKey,
                        state = DraftState.RECORDING,
                        durationMs = 0,
                        createdAt = pending.createdAt,
                        expiresAt = pending.expiresAt,
                        interruptionReason = null,
                    ),
                )
            } else {
                if (
                    existing.sessionId != serverSessionId || existing.songId != songId ||
                    existing.creationKey != pending.creationKey
                ) {
                    throw SessionBindingMismatchException()
                }
            }
        }
    }

    suspend fun markPreparationHandoffPending(
        draftId: String,
        serverSessionId: String,
        publishNavigation: () -> Unit,
    ) = checked {
        database.withTransaction {
            val preparation = database.preparationDraftDao().find(lease.patientId, draftId)
                ?: error("演唱准备草稿不存在")
            if (
                preparation.serverSessionId != serverSessionId ||
                preparation.status !in setOf(PreparationDraftStatus.BOUND, PreparationDraftStatus.HANDOFF_PENDING)
            ) {
                throw SessionBindingMismatchException()
            }
            val recordingDraft = database.draftDao().find(lease.patientId, draftId)
                ?: throw SessionBindingMismatchException()
            if (recordingDraft.sessionId != serverSessionId || recordingDraft.songId != preparation.songId) {
                throw SessionBindingMismatchException()
            }
            check(database.preparationDraftDao().markHandoffPending(lease.patientId, draftId, serverSessionId) == 1)
        }
        publishNavigation()
    }

    suspend fun acknowledgePreparationHandoff(draftId: String): Boolean = checked {
        database.preparationDraftDao().acknowledgeHandoff(lease.patientId, draftId) == 1
    }

    suspend fun abandonPreparation(draftId: String): Boolean = checked {
        database.preparationDraftDao().abandon(lease.patientId, draftId) == 1
    }

    suspend fun deleteDraft(draftId: String): Int = checked {
        database.withTransaction {
            val deletedDraft = database.draftDao().delete(lease.patientId, draftId)
            val deletedPreparation = database.preparationDraftDao().delete(lease.patientId, draftId)
            maxOf(deletedDraft, deletedPreparation)
        }
    }

    suspend fun insertMedia(
        draftId: String,
        type: MediaType,
        encryptedRelativePath: String,
        mimeType: String,
        sizeBytes: Long,
        sha256: String,
        validationState: MediaValidationState,
    ) = checked {
        database.withTransaction {
            require(database.draftDao().find(lease.patientId, draftId) != null) { "草稿不存在" }
            require(fileStore.encryptedMediaExists(lease.patientId, encryptedRelativePath)) { "加密媒体不存在" }
            database.mediaDao().insert(
                MediaEntity(
                    accountScope = lease.patientId,
                    draftId = draftId,
                    type = type,
                    encryptedRelativePath = encryptedRelativePath,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    validationState = validationState,
                ),
            )
        }
    }

    suspend fun publishRecordingMedia(
        draftId: String,
        video: File,
        audio: File,
        durationMs: Long,
        publicationActive: () -> Boolean = { true },
    ) = checked {
        check(publicationActive()) { "录制发布已取消" }
        require(durationMs > 0) { "录制时长无效" }
        require(video.isFile && video.length() > 0) { "视频文件无效" }
        require(audio.isFile && audio.length() > 0) { "音频文件无效" }
        val draft = database.draftDao().find(lease.patientId, draftId) ?: error("草稿不存在")
        require(draft.state == DraftState.RECORDING) { "草稿状态不可发布" }
        val published = mutableListOf<EncryptedMediaAsset>()
        try {
            val videoLength = video.length()
            val audioLength = audio.length()
            val videoSha = video.sha256()
            val audioSha = audio.sha256()
            check(publicationActive()) { "录制发布已取消" }
            val videoAsset = video.inputStream().use { input ->
                fileStore.encrypt(lease.patientId, input, videoLength).let {
                    EncryptedMediaAsset(it.relativePath, it.encryptedSizeBytes)
                }
            }.also(published::add)
            fileStore.verifyEncryptedMedia(lease.patientId, videoAsset.relativePath, videoLength)
            check(publicationActive()) { "录制发布已取消" }
            val audioAsset = audio.inputStream().use { input ->
                fileStore.encrypt(lease.patientId, input, audioLength).let {
                    EncryptedMediaAsset(it.relativePath, it.encryptedSizeBytes)
                }
            }.also(published::add)
            fileStore.verifyEncryptedMedia(lease.patientId, audioAsset.relativePath, audioLength)
            check(publicationActive()) { "录制发布已取消" }
            database.withTransaction {
                check(publicationActive()) { "录制发布已取消" }
                val current = database.draftDao().find(lease.patientId, draftId) ?: error("草稿不存在")
                require(current.sessionId == draft.sessionId && current.state == DraftState.RECORDING) {
                    "草稿状态已变化"
                }
                database.mediaDao().insert(
                    MediaEntity(
                        lease.patientId, draftId, MediaType.VIDEO, videoAsset.relativePath,
                        "video/mp4", videoLength, videoSha, MediaValidationState.VALID,
                    ),
                )
                database.mediaDao().insert(
                    MediaEntity(
                        lease.patientId, draftId, MediaType.AUDIO, audioAsset.relativePath,
                        "audio/mp4", audioLength, audioSha, MediaValidationState.VALID,
                    ),
                )
                check(
                    database.draftDao().updateState(
                        lease.patientId, draftId, DraftState.REVIEW_READY, durationMs, null,
                    ) == 1,
                ) { "草稿状态未持久化" }
                check(publicationActive()) { "录制发布已取消" }
            }
        } catch (error: Exception) {
            published.forEach { asset ->
                runCatching { fileStore.deleteEncryptedMedia(lease.patientId, asset.relativePath) }
            }
            throw error
        }
    }

    suspend fun markRecordingInterrupted(draftId: String, durationMs: Long, reason: String) = checked {
        require(reason.isNotBlank() && reason.length <= 256)
        val encryptedToDelete = mutableListOf<String>()
        database.withTransaction {
            val draft = database.draftDao().find(lease.patientId, draftId) ?: error("草稿不存在")
            if (draft.state == DraftState.RECORDING || draft.state == DraftState.REVIEW_READY) {
                if (draft.state == DraftState.REVIEW_READY) {
                    listOf(MediaType.VIDEO, MediaType.AUDIO).forEach { type ->
                        database.mediaDao().find(lease.patientId, draftId, type)?.let { media ->
                            encryptedToDelete += media.encryptedRelativePath
                            check(database.mediaDao().delete(lease.patientId, draftId, type) == 1)
                        }
                    }
                }
                check(
                    database.draftDao().updateState(
                        lease.patientId,
                        draftId,
                        DraftState.FAILED,
                        durationMs.coerceAtLeast(0),
                        reason,
                    ) == 1,
                )
            }
        }
        encryptedToDelete.forEach { fileStore.deleteEncryptedMedia(lease.patientId, it) }
    }

    suspend fun insertUploadJob(draftId: String, audioGrantKey: String, videoGrantKey: String, submitKey: String) = checked {
        database.withTransaction {
            require(database.draftDao().find(lease.patientId, draftId) != null) { "草稿不存在" }
            database.uploadDao().insert(UploadJobEntity.newPending(lease.patientId, draftId, audioGrantKey, videoGrantKey, submitKey))
        }
    }

    suspend fun pendingUploadCount(): Int = checked {
        database.uploadDao().countPending(lease.patientId)
    }

    suspend fun checkpointUpload(draftId: String, checkpoint: UploadCheckpoint): Int = checked {
        database.withTransaction {
            val current = database.uploadDao().find(lease.patientId, draftId) ?: error("上传任务不存在")
            require(allowedTransition(current.overallState, checkpoint.overallState)) { "非法上传状态转换" }
            require(
                listOf(
                    current.audioGrantState to checkpoint.audioGrantState,
                    current.videoGrantState to checkpoint.videoGrantState,
                    current.audioUploadState to checkpoint.audioUploadState,
                    current.videoUploadState to checkpoint.videoUploadState,
                    current.audioReceiptState to checkpoint.audioReceiptState,
                    current.videoReceiptState to checkpoint.videoReceiptState,
                    current.audioConfirmState to checkpoint.audioConfirmState,
                    current.videoConfirmState to checkpoint.videoConfirmState,
                    current.submitState to checkpoint.submitState,
                ).all { (from, to) -> allowedStepTransition(from, to) },
            ) { "非法上传步骤状态转换" }
            require(checkpoint.attemptCount >= current.attemptCount) { "上传尝试次数不能回退" }
            database.uploadDao().checkpoint(
                accountScope = lease.patientId,
                draftId = draftId,
                overallState = checkpoint.overallState,
                audioGrantState = checkpoint.audioGrantState,
                videoGrantState = checkpoint.videoGrantState,
                audioUploadState = checkpoint.audioUploadState,
                videoUploadState = checkpoint.videoUploadState,
                audioReceiptState = checkpoint.audioReceiptState,
                videoReceiptState = checkpoint.videoReceiptState,
                audioConfirmState = checkpoint.audioConfirmState,
                videoConfirmState = checkpoint.videoConfirmState,
                submitState = checkpoint.submitState,
                audioGrantKey = current.audioGrantKey,
                videoGrantKey = current.videoGrantKey,
                submitKey = current.submitKey,
                audioAssetKey = checkpoint.audioAssetKey,
                videoAssetKey = checkpoint.videoAssetKey,
                audioObjectKey = checkpoint.audioObjectKey,
                videoObjectKey = checkpoint.videoObjectKey,
                audioReceipt = checkpoint.audioReceipt,
                videoReceipt = checkpoint.videoReceipt,
                audioConfirmedAt = checkpoint.audioConfirmedAt,
                videoConfirmedAt = checkpoint.videoConfirmedAt,
                attemptCount = checkpoint.attemptCount,
                nextRetryAt = checkpoint.nextRetryAt,
                lastSafeError = checkpoint.lastSafeError,
            ).also { check(it == 1) { "上传检查点未持久化" } }
        }
    }

    /** 普通登出不会调用此操作，因此保留草稿和 wrapped master。 */
    suspend fun destroyEncryptionMaterialIfNoDrafts(): Boolean = checked {
        database.withTransaction {
            if (database.draftDao().count(lease.patientId) != 0) return@withTransaction false
            fileStore.destroyAccountEncryption(lease.patientId)
            true
        }
    }

    /** 仅供明确处理永久密钥失效：先清账户草稿，再销毁旧密文和 KEK。 */
    suspend fun purgeAfterPermanentKeyInvalidation() = checked {
        database.withTransaction { database.draftDao().deleteAll(lease.patientId) }
        fileStore.destroyAccountEncryption(lease.patientId)
    }

    private suspend fun <T> checked(block: suspend () -> T): T {
        return session.withCurrentLease(lease, block)
    }

    private suspend fun insertPreparationEntity(draft: PreparationDraftSnapshot) {
        database.preparationDraftDao().insert(
            PreparationDraftEntity(
                accountScope = lease.patientId,
                draftId = draft.draftId,
                songId = draft.songId,
                songTitle = draft.songTitle,
                songArtist = draft.songArtist,
                songDurationSeconds = draft.songDurationSeconds,
                serverSessionId = draft.serverSessionId,
                creationKey = draft.creationKey,
                status = draft.status,
                activeSongId = draft.activeSongId,
                createdAt = draft.createdAt,
                expiresAt = draft.expiresAt,
            ),
        )
    }

    private fun allowedTransition(from: UploadOverallState, to: UploadOverallState): Boolean =
        to == from || to in OVERALL_TRANSITIONS.getValue(from)

    private fun allowedStepTransition(from: UploadStepState, to: UploadStepState): Boolean = when (from) {
        UploadStepState.TERMINAL_FAILURE, UploadStepState.SUCCEEDED -> to == from
        UploadStepState.RETRYABLE_FAILURE -> true
        else -> to == UploadStepState.RETRYABLE_FAILURE || to == UploadStepState.TERMINAL_FAILURE || STEP_RANK.getValue(to) >= STEP_RANK.getValue(from)
    }

    private companion object {
        val OVERALL_TRANSITIONS = mapOf(
            UploadOverallState.PAUSED to setOf(UploadOverallState.WAITING_NETWORK, UploadOverallState.UPLOADING, UploadOverallState.CANCELLED),
            UploadOverallState.WAITING_NETWORK to setOf(UploadOverallState.UPLOADING, UploadOverallState.PAUSED, UploadOverallState.CANCELLED, UploadOverallState.FAILED),
            UploadOverallState.UPLOADING to setOf(UploadOverallState.WAITING_CALLBACK, UploadOverallState.WAITING_NETWORK, UploadOverallState.PAUSED, UploadOverallState.FAILED, UploadOverallState.CANCELLED),
            UploadOverallState.WAITING_CALLBACK to setOf(UploadOverallState.CONFIRMING, UploadOverallState.WAITING_NETWORK, UploadOverallState.FAILED, UploadOverallState.CANCELLED),
            UploadOverallState.CONFIRMING to setOf(UploadOverallState.READY_TO_SUBMIT, UploadOverallState.WAITING_CALLBACK, UploadOverallState.WAITING_NETWORK, UploadOverallState.FAILED, UploadOverallState.CANCELLED),
            UploadOverallState.READY_TO_SUBMIT to setOf(UploadOverallState.SUBMITTING, UploadOverallState.PAUSED, UploadOverallState.CANCELLED),
            UploadOverallState.SUBMITTING to setOf(UploadOverallState.ANALYZING, UploadOverallState.WAITING_NETWORK, UploadOverallState.FAILED, UploadOverallState.CANCELLED),
            UploadOverallState.ANALYZING to setOf(UploadOverallState.COMPLETED, UploadOverallState.FAILED, UploadOverallState.CANCELLED),
            UploadOverallState.FAILED to setOf(UploadOverallState.PAUSED, UploadOverallState.WAITING_NETWORK, UploadOverallState.CANCELLED),
            UploadOverallState.CANCELLED to emptySet(),
            UploadOverallState.COMPLETED to emptySet(),
        )
        val STEP_RANK = UploadStepState.entries.withIndex().associate { (index, state) ->
            state to when (state) {
                UploadStepState.RETRYABLE_FAILURE -> 0
                UploadStepState.TERMINAL_FAILURE -> Int.MAX_VALUE
                else -> index
            }
        }
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

data class UploadCheckpoint(
    val overallState: UploadOverallState,
    val audioGrantState: UploadStepState,
    val videoGrantState: UploadStepState,
    val audioUploadState: UploadStepState,
    val videoUploadState: UploadStepState,
    val audioReceiptState: UploadStepState,
    val videoReceiptState: UploadStepState,
    val audioConfirmState: UploadStepState,
    val videoConfirmState: UploadStepState,
    val submitState: UploadStepState,
    val audioAssetKey: String?,
    val videoAssetKey: String?,
    val audioObjectKey: String?,
    val videoObjectKey: String?,
    val audioReceipt: String?,
    val videoReceipt: String?,
    val audioConfirmedAt: Long?,
    val videoConfirmedAt: Long?,
    val attemptCount: Int,
    val nextRetryAt: Long?,
    val lastSafeError: String?,
)

data class EncryptedMediaAsset(val relativePath: String, val encryptedSizeBytes: Long)

data class DraftSnapshot(
    val draftId: String,
    val songId: String,
    val sessionId: String,
    val creationKey: String,
    val state: DraftState,
    val durationMs: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val interruptionReason: String?,
)

data class PreparationDraftSnapshot(
    val accountScopeHash: String,
    val draftId: String,
    val songId: String,
    val songTitle: String,
    val songArtist: String,
    val songDurationSeconds: Int,
    val serverSessionId: String?,
    val creationKey: String,
    val status: PreparationDraftStatus,
    val activeSongId: String?,
    val createdAt: Long,
    val expiresAt: Long,
)

private fun PreparationDraftEntity.toSnapshot(accountScopeHash: String) = PreparationDraftSnapshot(
    accountScopeHash = accountScopeHash,
    draftId = draftId,
    songId = songId,
    songTitle = songTitle,
    songArtist = songArtist,
    songDurationSeconds = songDurationSeconds,
    serverSessionId = serverSessionId,
    creationKey = creationKey,
    status = status,
    activeSongId = activeSongId,
    createdAt = createdAt,
    expiresAt = expiresAt,
)
