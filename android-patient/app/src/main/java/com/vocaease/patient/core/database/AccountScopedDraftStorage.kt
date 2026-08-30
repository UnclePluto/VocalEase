package com.vocaease.patient.core.database

import androidx.room.withTransaction
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.core.media.EncryptedMediaDataSource
import java.io.InputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StaleAccountScopeException internal constructor() : IllegalStateException("当前账户存储已失效")
class SessionBindingMismatchException internal constructor() : IllegalArgumentException("服务端会话与本地草稿不一致")
class ReviewMediaInvalidException internal constructor() : IllegalStateException("录制文件检查未通过")

class AuthenticatedAccountLease internal constructor(
    internal val patientId: String,
    internal val incarnationId: String = UUID.randomUUID().toString(),
)

fun interface AccountLeaseListenerRegistration {
    fun unregister()
}

interface AuthenticatedAccountSession {
    fun current(): AuthenticatedAccountLease?
    fun addLeaseChangedListener(listener: (AuthenticatedAccountLease?) -> Unit): AccountLeaseListenerRegistration =
        AccountLeaseListenerRegistration {}
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
    override fun addLeaseChangedListener(
        listener: (AuthenticatedAccountLease?) -> Unit,
    ): AccountLeaseListenerRegistration {
        listeners += listener
        listener(lease)
        return AccountLeaseListenerRegistration { listeners.remove(listener) }
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
    val cleanupScopeToken: String = ChunkedAesGcmFileStore.sha256(lease.patientId + "\u0000" + lease.incarnationId)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    internal fun encryptedMediaDataSource(encryptedRelativePath: String) =
        EncryptedMediaDataSource(
            fileStore,
            lease.patientId,
            fixedRelativePath = encryptedRelativePath,
            leaseActive = { session.current() === lease },
        )

    fun isLeaseActive(): Boolean = session.current() === lease

    fun onLeaseInvalidated(listener: () -> Unit): AccountLeaseListenerRegistration =
        session.addLeaseChangedListener { if (session.current() !== lease) listener() }

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

    suspend fun loadReviewDraft(draftId: String): AccountScopedReviewDraftSnapshot = checked {
        val draft = database.draftDao().find(lease.patientId, draftId) ?: throw ReviewMediaInvalidException()
        if (draft.state !in setOf(DraftState.REVIEW_READY, DraftState.INTERRUPTED)) {
            throw ReviewMediaInvalidException()
        }
        val media = database.mediaDao().findAll(lease.patientId, draftId)
        if (media.size != 2 || media.map { it.type }.toSet() != setOf(MediaType.VIDEO, MediaType.AUDIO)) {
            if (draft.state == DraftState.INTERRUPTED) {
                return@checked AccountScopedReviewDraftSnapshot(
                    draft.draftId, draft.songId, draft.sessionId, draft.creationKey,
                    draft.state, draft.durationMs, emptyList(),
                )
            }
            throw ReviewMediaInvalidException()
        }
        val snapshots = media.map { entity ->
            val expectedMime = if (entity.type == MediaType.VIDEO) "video/mp4" else "audio/mp4"
            if (entity.mimeType != expectedMime || entity.validationState != MediaValidationState.VALID || entity.sizeBytes <= 0) {
                if (draft.state == DraftState.INTERRUPTED) {
                    return@checked AccountScopedReviewDraftSnapshot(
                        draft.draftId, draft.songId, draft.sessionId, draft.creationKey,
                        draft.state, draft.durationMs, emptyList(),
                    )
                }
                throw ReviewMediaInvalidException()
            }
            try {
                fileStore.verifyEncryptedMedia(lease.patientId, entity.encryptedRelativePath, entity.sizeBytes)
            } catch (_: Exception) {
                database.mediaDao().updateValidation(
                    lease.patientId, draftId, entity.type, MediaValidationState.INVALID,
                )
                if (draft.state == DraftState.INTERRUPTED) {
                    return@checked AccountScopedReviewDraftSnapshot(
                        draft.draftId, draft.songId, draft.sessionId, draft.creationKey,
                        draft.state, draft.durationMs, emptyList(),
                    )
                }
                throw ReviewMediaInvalidException()
            }
            AccountScopedReviewMediaSnapshot(
                type = entity.type,
                encryptedRelativePath = entity.encryptedRelativePath,
                mimeType = entity.mimeType,
                sizeBytes = entity.sizeBytes,
                readableLength = entity.sizeBytes,
                validationState = entity.validationState,
            )
        }
        AccountScopedReviewDraftSnapshot(
            draftId = draft.draftId,
            songId = draft.songId,
            sessionId = draft.sessionId,
            creationKey = draft.creationKey,
            state = draft.state,
            durationMs = draft.durationMs,
            media = snapshots,
        )
    }

    suspend fun enqueueReviewDraft(draftId: String): Boolean = checked {
        val initial = database.draftDao().find(lease.patientId, draftId) ?: throw ReviewMediaInvalidException()
        if (initial.state == DraftState.READY_TO_UPLOAD) {
            check(database.uploadDao().find(lease.patientId, draftId) != null) { "上传入口缺失" }
            return@checked false
        }
        if (initial.state != DraftState.REVIEW_READY) throw ReviewMediaInvalidException()
        val verifiedMedia = database.mediaDao().findAll(lease.patientId, draftId)
        if (verifiedMedia.size != 2 || verifiedMedia.map { it.type }.toSet() != setOf(MediaType.VIDEO, MediaType.AUDIO) ||
            verifiedMedia.any { it.validationState != MediaValidationState.VALID || it.sizeBytes <= 0 }
        ) throw ReviewMediaInvalidException()
        try {
            verifiedMedia.forEach {
                fileStore.verifyEncryptedMedia(lease.patientId, it.encryptedRelativePath, it.sizeBytes)
            }
        } catch (_: Exception) {
            verifiedMedia.forEach {
                database.mediaDao().updateValidation(lease.patientId, draftId, it.type, MediaValidationState.INVALID)
            }
            throw ReviewMediaInvalidException()
        }
        database.withTransaction {
            val draft = database.draftDao().find(lease.patientId, draftId) ?: throw ReviewMediaInvalidException()
            if (draft.state == DraftState.READY_TO_UPLOAD) {
                check(database.uploadDao().find(lease.patientId, draftId) != null) { "上传入口缺失" }
                return@withTransaction false
            }
            if (draft.state != DraftState.REVIEW_READY) throw ReviewMediaInvalidException()
            val media = database.mediaDao().findAll(lease.patientId, draftId)
            if (media.size != 2 || media.map { it.type }.toSet() != setOf(MediaType.VIDEO, MediaType.AUDIO) ||
                media.any { it.validationState != MediaValidationState.VALID } ||
                media.map { it.encryptedRelativePath to it.sizeBytes }.toSet() !=
                verifiedMedia.map { it.encryptedRelativePath to it.sizeBytes }.toSet()
            ) throw ReviewMediaInvalidException()
            check(
                database.draftDao().transitionState(
                    lease.patientId, draftId, DraftState.REVIEW_READY, DraftState.READY_TO_UPLOAD,
                    draft.durationMs, null,
                ) == 1,
            ) { "草稿入队状态未持久化" }
            database.uploadDao().insert(
                UploadJobEntity.newPending(
                    lease.patientId,
                    draftId,
                    stableUploadKey("audio", draftId),
                    stableUploadKey("video", draftId),
                    stableUploadKey("submit", draftId),
                ),
            )
            true
        }
    }

    suspend fun prepareRerecord(draftId: String): DraftSnapshot = checked {
        val draft = database.draftDao().find(lease.patientId, draftId) ?: throw ReviewMediaInvalidException()
        if (draft.state !in setOf(DraftState.REVIEW_READY, DraftState.INTERRUPTED)) {
            throw ReviewMediaInvalidException()
        }
        val media = database.withTransaction {
            val current = database.draftDao().find(lease.patientId, draftId) ?: throw ReviewMediaInvalidException()
            if (current.sessionId != draft.sessionId || current.creationKey != draft.creationKey ||
                current.state !in setOf(DraftState.REVIEW_READY, DraftState.INTERRUPTED)
            ) throw ReviewMediaInvalidException()
            database.mediaDao().updateAllValidation(lease.patientId, draftId, MediaValidationState.INVALID)
            database.mediaDao().findAll(lease.patientId, draftId)
        }
        fileStore.revokeEncryptedMediaReaders(lease.patientId, media.map { it.encryptedRelativePath }.toSet())
        media.forEach { fileStore.deleteEncryptedMedia(lease.patientId, it.encryptedRelativePath) }
        database.withTransaction {
            val current = database.draftDao().find(lease.patientId, draftId) ?: throw ReviewMediaInvalidException()
            if (current.sessionId != draft.sessionId || current.creationKey != draft.creationKey ||
                current.state !in setOf(DraftState.REVIEW_READY, DraftState.INTERRUPTED)
            ) throw ReviewMediaInvalidException()
            database.mediaDao().deleteAll(lease.patientId, draftId)
            check(database.draftDao().updateState(lease.patientId, draftId, DraftState.RECORDING, 0, null) == 1) {
                "重录状态未持久化"
            }
            val preparation = database.preparationDraftDao().find(lease.patientId, draftId)
            if (preparation?.status == PreparationDraftStatus.HANDED_OFF) {
                check(database.preparationDraftDao().prepareRerecord(lease.patientId, draftId, draft.sessionId) == 1) {
                    "重录准备状态未持久化"
                }
            }
        }
        database.draftDao().find(lease.patientId, draftId)?.let {
            DraftSnapshot(it.draftId, it.songId, it.sessionId, it.creationKey, it.state, it.durationMs, it.createdAt, it.expiresAt, it.interruptionReason)
        } ?: throw ReviewMediaInvalidException()
    }

    suspend fun deleteReviewDraft(draftId: String) = checked {
        val draft = database.draftDao().find(lease.patientId, draftId) ?: return@checked
        if (draft.state !in setOf(DraftState.RECORDING, DraftState.REVIEW_READY, DraftState.INTERRUPTED)) {
            throw ReviewMediaInvalidException()
        }
        val media = database.withTransaction {
            database.mediaDao().updateAllValidation(lease.patientId, draftId, MediaValidationState.INVALID)
            database.mediaDao().findAll(lease.patientId, draftId)
        }
        fileStore.revokeEncryptedMediaReaders(lease.patientId, media.map { it.encryptedRelativePath }.toSet())
        media.forEach { fileStore.deleteEncryptedMedia(lease.patientId, it.encryptedRelativePath) }
        database.withTransaction {
            val current = database.draftDao().find(lease.patientId, draftId) ?: return@withTransaction
            if (current.state !in setOf(DraftState.RECORDING, DraftState.REVIEW_READY, DraftState.INTERRUPTED)) {
                throw ReviewMediaInvalidException()
            }
            database.draftDao().delete(lease.patientId, draftId)
            database.preparationDraftDao().delete(lease.patientId, draftId)
        }
    }

    suspend fun cleanupExpiredLocalDrafts(nowEpochMilliseconds: Long): Int {
        require(nowEpochMilliseconds >= 0)
        val candidates = checked {
            database.draftDao().findExpiredLocal(lease.patientId, nowEpochMilliseconds).map { it.draftId }
        }
        var deleted = 0
        for (draftId in candidates) {
            // 每个草稿独立采用“先撤销可读性、再删密文、最后删行”的可重试屏障。
            deleteReviewDraft(draftId)
            if (checked { database.draftDao().find(lease.patientId, draftId) == null }) deleted += 1
        }
        return deleted
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
    ) = publishRecordingMedia(
        draftId, video, audio, durationMs, DraftState.REVIEW_READY, null, publicationActive,
    )

    suspend fun publishInterruptedRecordingMedia(
        draftId: String,
        video: File,
        audio: File,
        durationMs: Long,
        reason: String,
        publicationActive: () -> Boolean = { true },
    ) {
        require(reason.isNotBlank() && reason.length <= 256)
        publishRecordingMedia(draftId, video, audio, durationMs, DraftState.INTERRUPTED, reason, publicationActive)
    }

    private suspend fun publishRecordingMedia(
        draftId: String,
        video: File,
        audio: File,
        durationMs: Long,
        targetState: DraftState,
        interruptionReason: String?,
        publicationActive: () -> Boolean,
    ) = checked {
        require(targetState in setOf(DraftState.REVIEW_READY, DraftState.INTERRUPTED))
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
                        lease.patientId, draftId, targetState, durationMs, interruptionReason,
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
        val encryptedToDelete = database.withTransaction {
            val draft = database.draftDao().find(lease.patientId, draftId) ?: error("草稿不存在")
            if (draft.state !in setOf(DraftState.RECORDING, DraftState.REVIEW_READY, DraftState.INTERRUPTED)) {
                return@withTransaction emptyList()
            }
            database.mediaDao().updateAllValidation(lease.patientId, draftId, MediaValidationState.INVALID)
            database.mediaDao().findAll(lease.patientId, draftId).map { it.encryptedRelativePath }
        }
        fileStore.revokeEncryptedMediaReaders(lease.patientId, encryptedToDelete.toSet())
        encryptedToDelete.forEach { fileStore.deleteEncryptedMedia(lease.patientId, it) }
        database.withTransaction {
            val draft = database.draftDao().find(lease.patientId, draftId) ?: error("草稿不存在")
            if (draft.state in setOf(DraftState.RECORDING, DraftState.REVIEW_READY, DraftState.INTERRUPTED)) {
                database.mediaDao().deleteAll(lease.patientId, draftId)
                check(
                    database.draftDao().updateState(
                        lease.patientId, draftId, DraftState.INTERRUPTED,
                        durationMs.coerceAtLeast(0), reason,
                    ) == 1,
                )
            }
        }
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

    suspend fun loadUploadBundle(draftId: String): AccountScopedUploadBundle = checked {
        val draft = database.draftDao().find(lease.patientId, draftId) ?: throw ReviewMediaInvalidException()
        require(draft.state in setOf(DraftState.READY_TO_UPLOAD, DraftState.UPLOADING, DraftState.SUBMITTED, DraftState.FAILED))
        val job = database.uploadDao().find(lease.patientId, draftId) ?: error("上传任务不存在")
        val preparation = database.preparationDraftDao().find(lease.patientId, draftId) ?: throw ReviewMediaInvalidException()
        require(preparation.serverSessionId == draft.sessionId)
        val media = database.mediaDao().findAll(lease.patientId, draftId)
        require(media.size == 2 && media.map { it.type }.toSet() == setOf(MediaType.AUDIO, MediaType.VIDEO))
        require(media.all { it.validationState == MediaValidationState.VALID && it.sizeBytes > 0 })
        AccountScopedUploadBundle(
            accountScopeHash = accountScopeHash,
            draftId = draft.draftId,
            sessionId = draft.sessionId,
            songTitle = preparation.songTitle,
            job = job,
            media = media.map { AccountScopedUploadMedia(it.type, it.mimeType, it.sizeBytes, it.encryptedRelativePath) },
        )
    }

    fun observeUploadJobs(): kotlinx.coroutines.flow.Flow<List<UploadJobEntity>> {
        if (!isLeaseActive()) throw StaleAccountScopeException()
        return database.uploadDao().observeAll(lease.patientId)
    }

    suspend fun findUploadJob(draftId: String): UploadJobEntity? = checked {
        database.uploadDao().find(lease.patientId, draftId)
    }

    suspend fun copyUploadMediaTo(draftId: String, type: MediaType, destination: File): Long = checked {
        require(destination.isFile && !java.nio.file.Files.isSymbolicLink(destination.toPath()))
        val media = database.mediaDao().find(lease.patientId, draftId, type) ?: throw ReviewMediaInvalidException()
        require(media.validationState == MediaValidationState.VALID && media.sizeBytes > 0)
        fileStore.open(lease.patientId, media.encryptedRelativePath).use { reader ->
            require(reader.length == media.sizeBytes)
            FileOutputStream(destination, false).buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var position = 0L
                while (position < reader.length) {
                    val read = reader.read(position, buffer, 0, minOf(buffer.size.toLong(), reader.length - position).toInt())
                    check(read > 0)
                    output.write(buffer, 0, read)
                    position += read
                }
                buffer.fill(0)
                output.flush()
            }
            require(destination.length() == media.sizeBytes)
            media.sizeBytes
        }
    }

    suspend fun deleteSubmittedUploadMedia(draftId: String) = checked {
        val paths = database.withTransaction {
            val job = database.uploadDao().find(lease.patientId, draftId) ?: return@withTransaction emptyList()
            require(job.pipelineStage == UploadPipelineStage.ANALYZING)
            val draft = database.draftDao().find(lease.patientId, draftId) ?: return@withTransaction emptyList()
            database.draftDao().updateState(lease.patientId, draftId, DraftState.SUBMITTED, draft.durationMs, null)
            database.mediaDao().updateAllValidation(lease.patientId, draftId, MediaValidationState.INVALID)
            database.mediaDao().findAll(lease.patientId, draftId).map { it.encryptedRelativePath }
        }
        fileStore.revokeEncryptedMediaReaders(lease.patientId, paths.toSet())
        paths.forEach { fileStore.deleteEncryptedMedia(lease.patientId, it) }
        database.withTransaction { database.mediaDao().deleteAll(lease.patientId, draftId) }
    }

    suspend fun deleteQueuedUploadDraft(draftId: String) = checked {
        val paths = database.withTransaction {
            val job = database.uploadDao().find(lease.patientId, draftId) ?: return@withTransaction emptyList()
            require(job.pipelineStage !in setOf(UploadPipelineStage.SUBMITTING, UploadPipelineStage.ANALYZING)) {
                "任务已经提交，不能删除"
            }
            database.mediaDao().updateAllValidation(lease.patientId, draftId, MediaValidationState.INVALID)
            database.mediaDao().findAll(lease.patientId, draftId).map { it.encryptedRelativePath }
        }
        fileStore.revokeEncryptedMediaReaders(lease.patientId, paths.toSet())
        paths.forEach { fileStore.deleteEncryptedMedia(lease.patientId, it) }
        database.withTransaction {
            val job = database.uploadDao().find(lease.patientId, draftId) ?: return@withTransaction
            require(job.pipelineStage !in setOf(UploadPipelineStage.SUBMITTING, UploadPipelineStage.ANALYZING))
            database.draftDao().delete(lease.patientId, draftId)
            database.preparationDraftDao().delete(lease.patientId, draftId)
        }
    }

    suspend fun checkpointUpload(draftId: String, checkpoint: UploadCheckpoint): Int = checked {
        database.withTransaction {
            val current = database.uploadDao().find(lease.patientId, draftId) ?: error("上传任务不存在")
            require(allowedTransition(current.overallState, checkpoint.overallState)) { "非法上传状态转换" }
            require(allowedPipelineTransition(current.pipelineStage, checkpoint.pipelineStage)) { "非法上传管线状态转换" }
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
            require(checkpoint.progressPercent >= current.progressPercent) { "上传进度不能回退" }
            database.uploadDao().checkpoint(
                accountScope = lease.patientId,
                draftId = draftId,
                overallState = checkpoint.overallState,
                pipelineStage = checkpoint.pipelineStage,
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
                progressPercent = checkpoint.progressPercent,
                receiptWaitAttempt = checkpoint.receiptWaitAttempt,
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

    private fun allowedStepTransition(from: UploadStepState, to: UploadStepState): Boolean =
        to == from || to in STEP_TRANSITIONS.getValue(from)

    private fun allowedPipelineTransition(from: UploadPipelineStage, to: UploadPipelineStage): Boolean =
        to == from || to in PIPELINE_TRANSITIONS.getValue(from)

    private companion object {
        fun stableUploadKey(kind: String, draftId: String): String {
            val direct = if (kind == "submit") "submit:$draftId" else "grant:$draftId:$kind"
            require(direct.length <= 128) { "草稿标识过长，无法创建稳定上传键" }
            return direct
        }

        val OVERALL_TRANSITIONS = mapOf(
            UploadOverallState.PAUSED to setOf(
                UploadOverallState.WAITING_NETWORK, UploadOverallState.UPLOADING, UploadOverallState.WAITING_CALLBACK,
                UploadOverallState.CONFIRMING, UploadOverallState.SUBMITTING, UploadOverallState.CANCELLED,
            ),
            UploadOverallState.WAITING_NETWORK to setOf(UploadOverallState.UPLOADING, UploadOverallState.PAUSED, UploadOverallState.CANCELLED, UploadOverallState.FAILED),
            UploadOverallState.UPLOADING to setOf(UploadOverallState.WAITING_CALLBACK, UploadOverallState.WAITING_NETWORK, UploadOverallState.PAUSED, UploadOverallState.FAILED, UploadOverallState.CANCELLED),
            UploadOverallState.WAITING_CALLBACK to setOf(UploadOverallState.CONFIRMING, UploadOverallState.WAITING_NETWORK, UploadOverallState.FAILED, UploadOverallState.CANCELLED),
            UploadOverallState.CONFIRMING to setOf(UploadOverallState.UPLOADING, UploadOverallState.READY_TO_SUBMIT, UploadOverallState.SUBMITTING, UploadOverallState.WAITING_CALLBACK, UploadOverallState.WAITING_NETWORK, UploadOverallState.FAILED, UploadOverallState.CANCELLED),
            UploadOverallState.READY_TO_SUBMIT to setOf(UploadOverallState.SUBMITTING, UploadOverallState.PAUSED, UploadOverallState.CANCELLED),
            UploadOverallState.SUBMITTING to setOf(UploadOverallState.ANALYZING, UploadOverallState.WAITING_NETWORK, UploadOverallState.FAILED, UploadOverallState.CANCELLED),
            UploadOverallState.ANALYZING to setOf(UploadOverallState.COMPLETED, UploadOverallState.FAILED, UploadOverallState.CANCELLED),
            UploadOverallState.FAILED to setOf(UploadOverallState.PAUSED, UploadOverallState.WAITING_NETWORK, UploadOverallState.CANCELLED),
            UploadOverallState.CANCELLED to emptySet(),
            UploadOverallState.COMPLETED to emptySet(),
        )
        val PIPELINE_TRANSITIONS = mapOf(
            UploadPipelineStage.PAUSED to setOf(
                UploadPipelineStage.WAITING_NETWORK,
                UploadPipelineStage.REQUESTING_AUDIO_GRANT, UploadPipelineStage.UPLOADING_AUDIO,
                UploadPipelineStage.WAITING_AUDIO_RECEIPT, UploadPipelineStage.CONFIRMING_AUDIO,
                UploadPipelineStage.REQUESTING_VIDEO_GRANT, UploadPipelineStage.UPLOADING_VIDEO,
                UploadPipelineStage.WAITING_VIDEO_RECEIPT, UploadPipelineStage.CONFIRMING_VIDEO,
                UploadPipelineStage.SUBMITTING,
            ),
            UploadPipelineStage.WAITING_NETWORK to setOf(UploadPipelineStage.REQUESTING_AUDIO_GRANT, UploadPipelineStage.PAUSED, UploadPipelineStage.FAILED),
            UploadPipelineStage.REQUESTING_AUDIO_GRANT to setOf(UploadPipelineStage.UPLOADING_AUDIO, UploadPipelineStage.PAUSED, UploadPipelineStage.FAILED),
            UploadPipelineStage.UPLOADING_AUDIO to setOf(UploadPipelineStage.WAITING_AUDIO_RECEIPT, UploadPipelineStage.PAUSED, UploadPipelineStage.FAILED),
            UploadPipelineStage.WAITING_AUDIO_RECEIPT to setOf(UploadPipelineStage.CONFIRMING_AUDIO, UploadPipelineStage.PAUSED, UploadPipelineStage.FAILED),
            UploadPipelineStage.CONFIRMING_AUDIO to setOf(UploadPipelineStage.WAITING_AUDIO_RECEIPT, UploadPipelineStage.REQUESTING_VIDEO_GRANT, UploadPipelineStage.PAUSED, UploadPipelineStage.FAILED),
            UploadPipelineStage.REQUESTING_VIDEO_GRANT to setOf(UploadPipelineStage.UPLOADING_VIDEO, UploadPipelineStage.PAUSED, UploadPipelineStage.FAILED),
            UploadPipelineStage.UPLOADING_VIDEO to setOf(UploadPipelineStage.WAITING_VIDEO_RECEIPT, UploadPipelineStage.PAUSED, UploadPipelineStage.FAILED),
            UploadPipelineStage.WAITING_VIDEO_RECEIPT to setOf(UploadPipelineStage.CONFIRMING_VIDEO, UploadPipelineStage.PAUSED, UploadPipelineStage.FAILED),
            UploadPipelineStage.CONFIRMING_VIDEO to setOf(UploadPipelineStage.WAITING_VIDEO_RECEIPT, UploadPipelineStage.SUBMITTING, UploadPipelineStage.PAUSED, UploadPipelineStage.FAILED),
            UploadPipelineStage.SUBMITTING to setOf(UploadPipelineStage.ANALYZING, UploadPipelineStage.PAUSED, UploadPipelineStage.FAILED),
            UploadPipelineStage.ANALYZING to emptySet(),
            UploadPipelineStage.FAILED to setOf(UploadPipelineStage.WAITING_NETWORK, UploadPipelineStage.PAUSED),
        )
        val STEP_TRANSITIONS = mapOf(
            UploadStepState.PENDING to setOf(
                UploadStepState.REQUESTING_GRANT, UploadStepState.UPLOADING, UploadStepState.WAITING_RECEIPT,
                UploadStepState.CONFIRMING, UploadStepState.SUBMITTING, UploadStepState.RETRYABLE_FAILURE,
                UploadStepState.TERMINAL_FAILURE,
            ),
            UploadStepState.REQUESTING_GRANT to setOf(UploadStepState.GRANT_READY, UploadStepState.RETRYABLE_FAILURE, UploadStepState.TERMINAL_FAILURE),
            UploadStepState.GRANT_READY to setOf(UploadStepState.RETRYABLE_FAILURE, UploadStepState.TERMINAL_FAILURE),
            UploadStepState.UPLOADING to setOf(UploadStepState.UPLOADED, UploadStepState.RETRYABLE_FAILURE, UploadStepState.TERMINAL_FAILURE),
            UploadStepState.UPLOADED to setOf(UploadStepState.RETRYABLE_FAILURE, UploadStepState.TERMINAL_FAILURE),
            UploadStepState.WAITING_RECEIPT to setOf(UploadStepState.RECEIPT_RECEIVED, UploadStepState.RETRYABLE_FAILURE, UploadStepState.TERMINAL_FAILURE),
            UploadStepState.RECEIPT_RECEIVED to setOf(UploadStepState.RETRYABLE_FAILURE, UploadStepState.TERMINAL_FAILURE),
            UploadStepState.CONFIRMING to setOf(UploadStepState.CONFIRMED, UploadStepState.RETRYABLE_FAILURE, UploadStepState.TERMINAL_FAILURE),
            UploadStepState.CONFIRMED to setOf(UploadStepState.RETRYABLE_FAILURE, UploadStepState.TERMINAL_FAILURE),
            UploadStepState.SUBMITTING to setOf(UploadStepState.SUBMITTED, UploadStepState.RETRYABLE_FAILURE, UploadStepState.TERMINAL_FAILURE),
            UploadStepState.SUBMITTED to setOf(UploadStepState.ANALYZING, UploadStepState.RETRYABLE_FAILURE, UploadStepState.TERMINAL_FAILURE),
            UploadStepState.ANALYZING to setOf(UploadStepState.SUCCEEDED, UploadStepState.RETRYABLE_FAILURE, UploadStepState.TERMINAL_FAILURE),
            UploadStepState.RETRYABLE_FAILURE to setOf(
                UploadStepState.PENDING, UploadStepState.REQUESTING_GRANT, UploadStepState.GRANT_READY,
                UploadStepState.UPLOADING, UploadStepState.UPLOADED, UploadStepState.WAITING_RECEIPT,
                UploadStepState.RECEIPT_RECEIVED, UploadStepState.CONFIRMING, UploadStepState.CONFIRMED,
                UploadStepState.SUBMITTING, UploadStepState.SUBMITTED, UploadStepState.ANALYZING,
            ),
            UploadStepState.TERMINAL_FAILURE to emptySet(),
            UploadStepState.SUCCEEDED to emptySet(),
        )
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
    val pipelineStage: UploadPipelineStage = UploadPipelineStage.PAUSED,
    val progressPercent: Int = 0,
    val receiptWaitAttempt: Int = 0,
)

data class EncryptedMediaAsset(val relativePath: String, val encryptedSizeBytes: Long)

data class AccountScopedReviewMediaSnapshot(
    val type: MediaType,
    internal val encryptedRelativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val readableLength: Long,
    val validationState: MediaValidationState,
)

data class AccountScopedReviewDraftSnapshot(
    val draftId: String,
    val songId: String,
    val sessionId: String,
    val creationKey: String,
    val state: DraftState,
    val durationMs: Long,
    val media: List<AccountScopedReviewMediaSnapshot>,
)

data class AccountScopedUploadMedia(
    val type: MediaType,
    val mimeType: String,
    val sizeBytes: Long,
    internal val encryptedRelativePath: String,
)

data class AccountScopedUploadBundle(
    val accountScopeHash: String,
    val draftId: String,
    val sessionId: String,
    val songTitle: String,
    val job: UploadJobEntity,
    val media: List<AccountScopedUploadMedia>,
)

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
