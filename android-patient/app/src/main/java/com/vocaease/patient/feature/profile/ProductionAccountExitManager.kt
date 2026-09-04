package com.vocaease.patient.feature.profile

import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.vocaease.patient.core.cleanup.DraftCleanupWorkContract
import com.vocaease.patient.core.database.AccountExitIntentEntity
import com.vocaease.patient.core.database.AccountExitStage
import com.vocaease.patient.core.database.AccountOperationKind
import com.vocaease.patient.core.database.AccountScopedDraftStorageProvider
import com.vocaease.patient.core.database.AuthenticatedAccountLease
import com.vocaease.patient.core.database.StaleAccountScopeException
import com.vocaease.patient.core.database.VocaEaseDatabase
import com.vocaease.patient.core.media.RecordingPlaybackHandoff
import com.vocaease.patient.core.media.PrivateRecordingTempFiles
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.feature.auth.AuthRepository
import com.vocaease.patient.feature.auth.PreparedLogoutCheckpoint
import com.vocaease.patient.feature.auth.PasswordChangeResult
import com.vocaease.patient.feature.history.AnalysisSyncCoordinator
import com.vocaease.patient.feature.history.AnalysisAndroidWorkContract
import com.vocaease.patient.feature.upload.UploadCoordinator
import com.vocaease.patient.feature.upload.UploadWorkContract
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ProductionAccountExitManager(
    private val context: Context,
    private val authRepository: AuthRepository,
    private val storageProvider: AccountScopedDraftStorageProvider,
    private val database: VocaEaseDatabase,
    fileStore: ChunkedAesGcmFileStore,
    uploadCoordinator: UploadCoordinator,
    analysisCoordinator: AnalysisSyncCoordinator,
    recordingPlaybackHandoff: RecordingPlaybackHandoff,
    private val workCancellation: AccountWorkCancellation = AndroidAccountWorkCancellation(context),
    private val beforeRecoveryClaim: suspend () -> Unit = {},
) : LogoutAccountBoundary, PasswordChangeAccountBoundary {
    private val mutex = Mutex()
    private val effects = ProductionAccountExitEffects(
        context,
        database,
        fileStore,
        uploadCoordinator,
        analysisCoordinator,
        recordingPlaybackHandoff,
        workCancellation,
        authRepository,
        ::resolve,
    )

    override suspend fun <T> withLogoutClaim(owner: LogoutOperationOwner, operation: suspend () -> T): T {
        resolve(owner)
        return authRepository.withDestructiveCredentialClaim {
            resolve(owner)
            operation()
        }
    }

    override suspend fun acquireOwner(): LogoutOperationOwner? = mutex.withLock {
        val account = currentAccount() ?: return@withLock null
        authRepository.withAuthenticatedLease(account.lease) {
            val store = RoomAccountExitIntentStore(database, account.accountScope)
            var existing = store.find()
            if (existing?.operationKind == AccountOperationKind.LEGACY_LOCAL_UNLOCK) {
                check(store.deleteLegacyLocalUnlock(existing) == 1) { "旧账户操作本地解锁失败" }
                existing = null
            }
            val operationId = existing?.operationId ?: "logout-${UUID.randomUUID()}"
            val owner = account.owner(operationId)
            if (existing != null) store.takeover(existing, owner)
            owner
        }
    }

    override suspend fun pendingDraftCount(owner: LogoutOperationOwner): Int = withOwner(owner) { account ->
        database.draftDao().count(account.accountScope)
    }

    override suspend fun persistIntent(owner: LogoutOperationOwner, choice: LogoutChoice): LogoutIntent =
        withOwner(owner) { account ->
            RoomAccountExitIntentStore(database, account.accountScope).persist(
                owner,
                choice,
                AccountOperationKind.LOGOUT,
            )
            LogoutIntent(owner, choice)
        }

    override suspend fun pauseAndLock(intent: LogoutIntent) {
        converge(intent)
    }

    override suspend fun cancelAndAwait(intent: LogoutIntent) {
        converge(intent)
    }

    override suspend fun revokeRuntimeAccess(intent: LogoutIntent) {
        converge(intent)
    }

    override suspend fun deleteAccountData(intent: LogoutIntent) {
        converge(intent)
    }

    override suspend fun completeIntent(intent: LogoutIntent) {
        check(converge(intent).stage == AccountExitStage.READY_TO_CLEAR)
    }

    override suspend fun finishLogout(owner: LogoutOperationOwner): Boolean {
        val account = runCatching { resolve(owner) }.getOrNull() ?: return false
        val current = RoomAccountExitIntentStore(database, account.accountScope).find() ?: return false
        if (!current.matches(owner) || current.operationKind != AccountOperationKind.LOGOUT) return false
        return finishPersistedLogout(current)
    }

    override suspend fun preparePasswordChange(): PreparedPasswordChange? {
        val owner = acquireOwner() ?: return null
        val account = resolve(owner)
        RoomAccountExitIntentStore(database, account.accountScope).persist(
            owner,
            LogoutChoice.RETAIN,
            AccountOperationKind.PASSWORD_CHANGE,
        )
        val intent = LogoutIntent(owner, LogoutChoice.RETAIN)
        converge(intent)
        return PreparedPasswordChange(intent, account.accountScope)
    }

    override suspend fun changePassword(
        prepared: PreparedPasswordChange,
        oldPassword: String,
        newPassword: String,
    ): PasswordChangeAttempt {
        resolve(prepared.intent.owner)
        return when (val result = authRepository.changePasswordResult(oldPassword, newPassword)) {
            PasswordChangeResult.Changed -> PasswordChangeAttempt.Changed
            PasswordChangeResult.Superseded -> PasswordChangeAttempt.Superseded
            is PasswordChangeResult.Failed -> PasswordChangeAttempt.Failed(result.userMessage)
        }
    }

    override suspend fun completePasswordChange(prepared: PreparedPasswordChange): Boolean {
        val store = RoomAccountExitIntentStore(database, prepared.accountScope)
        val ready = store.find() ?: return false
        if (ready.stage != AccountExitStage.READY_TO_CLEAR || !ready.matches(prepared.intent.owner)) return false
        if (ready.operationKind != AccountOperationKind.PASSWORD_CHANGE) return false
        return store.rollbackPasswordChange(ready) == 1
    }

    override suspend fun rollbackPasswordChange(prepared: PreparedPasswordChange) {
        val store = RoomAccountExitIntentStore(database, prepared.accountScope)
        val current = store.find() ?: return
        if (current.operationKind != AccountOperationKind.PASSWORD_CHANGE ||
            current.operationId != prepared.intent.owner.operationId
        ) return
        val ready = if (current.stage == AccountExitStage.READY_TO_CLEAR) current
        else AccountExitProcessor(store, effects).converge(current)
        store.rollbackPasswordChange(ready)
    }

    /** 进程重启后在启动任何当前账户工作前调用。 */
    suspend fun recoverCurrentExit(): Boolean = mutex.withLock {
        beforeRecoveryClaim()
        authRepository.withDestructiveCredentialClaim claim@{
            // claim 内重读 epoch/lease，禁止 claim 前已提交的 refresh 留下旧 owner 快照。
            val account = currentAccount() ?: return@claim false
            val store = RoomAccountExitIntentStore(database, account.accountScope)
            val existing = store.find() ?: return@claim false
            require(existing.accountScopeHash == account.accountScopeHash)
            if (existing.operationKind == AccountOperationKind.LEGACY_LOCAL_UNLOCK) {
                check(store.deleteLegacyLocalUnlock(existing) == 1) { "旧账户操作本地解锁失败" }
                return@claim false
            }
            val owner = account.owner(existing.operationId)
            val inheritedFromOldIncarnation = existing.incarnationProof != owner.incarnationProof
            val adopted = authRepository.withAuthenticatedLease(account.lease) { store.takeover(existing, owner) }
            val ready = AccountExitProcessor(store, effects).converge(adopted)
            if (ready.operationKind == AccountOperationKind.PASSWORD_CHANGE) {
                store.rollbackPasswordChange(ready)
                return@claim false
            }
            if (inheritedFromOldIncarnation && ready.stage == AccountExitStage.READY_TO_CLEAR) {
                store.deletePreAuthenticationLogout(ready)
                return@claim false
            }
            check(finishPersistedLogout(ready)) { "账户退出恢复已被新会话取代" }
            true
        }
    }

    /** AuthRepository.restoreSession 在 refresh/login 可用前调用，收敛已绑定旧凭据的终态。 */
    suspend fun recoverAuthenticationFinalization() {
        database.accountExitIntentDao().findAuthenticationPending().forEach { intent ->
            if (intent.operationKind == AccountOperationKind.LOGOUT) {
                check(finishPersistedLogout(intent)) { "旧账户退出凭据尚未安全收敛" }
            }
        }
    }

    private suspend fun finishPersistedLogout(seed: AccountExitIntentEntity): Boolean {
        val store = RoomAccountExitIntentStore(database, seed.accountScope)
        var current = store.find() ?: return true
        if (current.operationId != seed.operationId || current.operationKind != AccountOperationKind.LOGOUT) return false
        if (current.stage == AccountExitStage.AUTH_CLEARED) {
            return store.deleteCompletedLogout(current) == 1
        }
        val resume = when (current.stage) {
            AccountExitStage.READY_TO_CLEAR -> PreparedLogoutCheckpoint.READY_TO_CLEAR
            AccountExitStage.AUTH_BOUND -> PreparedLogoutCheckpoint.AUTH_BOUND
            AccountExitStage.REMOTE_REVOKED -> PreparedLogoutCheckpoint.REMOTE_REVOKED
            else -> return false
        }
        return authRepository.finishPreparedLogout(
            expectedEpoch = current.sessionEpoch,
            operationId = current.operationId,
            resumeFrom = resume,
        ) { completed ->
            val target = when (completed) {
                PreparedLogoutCheckpoint.AUTH_BOUND -> AccountExitStage.AUTH_BOUND
                PreparedLogoutCheckpoint.REMOTE_REVOKED -> AccountExitStage.REMOTE_REVOKED
                PreparedLogoutCheckpoint.AUTH_CLEARED -> AccountExitStage.AUTH_CLEARED
                PreparedLogoutCheckpoint.READY_TO_CLEAR -> error("非法退出检查点")
            }
            if (current.stage != target) current = store.advance(current, target)
            if (target == AccountExitStage.AUTH_CLEARED) {
                check(store.deleteCompletedLogout(current) == 1) { "账户退出终态删除失败" }
            }
        }
    }

    private suspend fun converge(intent: LogoutIntent): AccountExitIntentEntity {
        val account = resolve(intent.owner)
        val store = RoomAccountExitIntentStore(database, account.accountScope)
        val seed = store.find() ?: throw StaleAccountScopeException()
        require(seed.matches(intent.owner) && seed.choice.name == intent.choice.name)
        return AccountExitProcessor(store, effects).converge(seed)
    }

    private suspend fun <T> withOwner(
        owner: LogoutOperationOwner,
        block: suspend (ExitAccount) -> T,
    ): T {
        val account = resolve(owner)
        return authRepository.withAuthenticatedLease(account.lease) { block(account) }
    }

    private fun currentAccount(): ExitAccount? {
        val lease = authRepository.currentAuthenticatedLease() ?: return null
        val storage = runCatching { storageProvider.current() }.getOrNull() ?: return null
        return ExitAccount(
            lease,
            storage.accountScope,
            storage.accountScopeHash,
            storage.cleanupScopeToken,
            authRepository.currentSessionEpoch(),
        )
    }

    private fun resolve(owner: LogoutOperationOwner): ExitAccount {
        val account = currentAccount() ?: throw StaleAccountScopeException()
        if (!account.owner(owner.operationId).sameAs(owner)) throw StaleAccountScopeException()
        return account
    }

    internal data class ExitAccount(
        val lease: AuthenticatedAccountLease,
        val accountScope: String,
        val accountScopeHash: String,
        val incarnationProof: String,
        val sessionEpoch: Long,
    ) {
        fun owner(operationId: String) = LogoutOperationOwner(
            accountScopeHash,
            incarnationProof,
            sessionEpoch,
            operationId,
        )
    }

    private fun LogoutOperationOwner.sameAs(other: LogoutOperationOwner): Boolean =
        accountScopeHash == other.accountScopeHash && incarnationProof == other.incarnationProof &&
            sessionEpoch == other.sessionEpoch && operationId == other.operationId

    private fun AccountExitIntentEntity.matches(owner: LogoutOperationOwner): Boolean =
        accountScopeHash == owner.accountScopeHash && incarnationProof == owner.incarnationProof &&
            sessionEpoch == owner.sessionEpoch && operationId == owner.operationId
}

internal interface AccountWorkCancellation {
    suspend fun cancelAndAwait(accountScopeHash: String)
}

internal class AndroidAccountWorkCancellation(context: Context) : AccountWorkCancellation {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override suspend fun cancelAndAwait(accountScopeHash: String) {
        require(accountScopeHash.matches(SHA256))
        val operations = listOf(
            workManager.cancelAllWorkByTag(UploadWorkContract.ACCOUNT_TAG_PREFIX + accountScopeHash),
            workManager.cancelAllWorkByTag(AnalysisAndroidWorkContract.ACCOUNT_TAG_PREFIX + accountScopeHash),
            workManager.cancelUniqueWork(DraftCleanupWorkContract.WORK_PREFIX + accountScopeHash),
        )
        operations.forEach { operation -> runInterruptible { operation.result.get() } }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

private class ProductionAccountExitEffects(
    private val context: Context,
    private val database: VocaEaseDatabase,
    private val fileStore: ChunkedAesGcmFileStore,
    private val uploadCoordinator: UploadCoordinator,
    private val analysisCoordinator: AnalysisSyncCoordinator,
    private val recordingPlaybackHandoff: RecordingPlaybackHandoff,
    private val workCancellation: AccountWorkCancellation,
    private val authRepository: AuthRepository,
    private val resolve: (LogoutOperationOwner) -> ProductionAccountExitManager.ExitAccount,
) : AccountExitEffects {
    private val dataEraser = AccountDataEraser(context, database, fileStore)
    override suspend fun pauseAndLock(intent: LogoutIntent) = Unit // Room intent 本身就是持久锁。

    override suspend fun cancelAndAwait(intent: LogoutIntent) {
        val account = resolve(intent.owner)
        authRepository.withAuthenticatedClaim(account.lease) {
            uploadCoordinator.cancelAndAwaitAccount(account.accountScopeHash)
            analysisCoordinator.cancelAccount(account.accountScopeHash)
            recordingPlaybackHandoff.discardAll()
            workCancellation.cancelAndAwait(account.accountScopeHash)
        }
    }

    override suspend fun revokeRuntimeAccess(intent: LogoutIntent) {
        val account = resolve(intent.owner)
        authRepository.withAuthenticatedClaim(account.lease) {
            fileStore.revokeEncryptedMediaReaders(account.accountScope)
        }
    }

    override suspend fun deleteAccountData(intent: LogoutIntent) {
        val account = resolve(intent.owner)
        authRepository.withAuthenticatedClaim(account.lease) {
            dataEraser.delete(account.accountScope, account.accountScopeHash)
        }
    }
}

internal class AccountDataEraser(
    private val context: Context,
    private val database: VocaEaseDatabase,
    private val fileStore: ChunkedAesGcmFileStore,
) {
    suspend fun delete(accountScope: String, accountScopeHash: String) {
        require(ChunkedAesGcmFileStore.sha256(accountScope) == accountScopeHash)
        val draftIds = database.draftDao().findAllIds(accountScope)
        PrivateRecordingTempFiles(context).deleteAccount(accountScopeHash)
        fileStore.destroyAccountEncryption(accountScope)
        deletePrivateTree(File(context.cacheDir, "upload-lease/$accountScopeHash"))
        deletePrivateTree(File(context.filesDir, "qiniu-upload-recorder/$accountScopeHash"))
        draftIds.forEach { draftId ->
            val opaqueJob = ChunkedAesGcmFileStore.sha256("$accountScopeHash\u0000$draftId")
            deletePrivateTree(File(context.cacheDir, "upload-lease/$opaqueJob"))
            deletePrivateTree(File(context.filesDir, "qiniu-upload-recorder/$opaqueJob"))
        }
        database.withTransaction {
            database.uploadLocalActionDao().deleteAll(accountScope)
            database.uploadDao().deleteAll(accountScope)
            database.mediaDao().deleteAllForAccount(accountScope)
            database.preparationDraftDao().deleteAll(accountScope)
            database.draftDao().deleteAll(accountScope)
            database.analysisCheckpointDao().deleteAll(accountScopeHash)
        }
    }

    private fun deletePrivateTree(target: File) {
        val root = target.toPath()
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        require(!Files.isSymbolicLink(root)) { "账户临时目录不可为符号链接" }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file) // 默认不跟随符号链接，只删除链接本身。
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
