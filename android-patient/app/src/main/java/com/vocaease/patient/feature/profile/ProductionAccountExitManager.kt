package com.vocaease.patient.feature.profile

import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.vocaease.patient.core.cleanup.DraftCleanupWorkContract
import com.vocaease.patient.core.database.AccountExitIntentEntity
import com.vocaease.patient.core.database.AccountExitStage
import com.vocaease.patient.core.database.AccountScopedDraftStorageProvider
import com.vocaease.patient.core.database.AuthenticatedAccountLease
import com.vocaease.patient.core.database.StaleAccountScopeException
import com.vocaease.patient.core.database.VocaEaseDatabase
import com.vocaease.patient.core.media.RecordingPlaybackHandoff
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.core.security.RevocationRemoteResult
import com.vocaease.patient.feature.auth.AuthRepository
import com.vocaease.patient.feature.history.AnalysisSyncCoordinator
import com.vocaease.patient.feature.history.AnalysisAndroidWorkContract
import com.vocaease.patient.feature.upload.UploadCoordinator
import com.vocaease.patient.feature.upload.UploadWorkContract
import java.io.File
import java.nio.file.Files
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
        ::resolve,
    )

    override suspend fun acquireOwner(): LogoutOperationOwner? = mutex.withLock {
        val account = currentAccount() ?: return@withLock null
        authRepository.withAuthenticatedLease(account.lease) {
            val store = RoomAccountExitIntentStore(database, account.accountScope)
            val existing = store.find()
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
            RoomAccountExitIntentStore(database, account.accountScope).persist(owner, choice)
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

    override suspend fun logoutServer(owner: LogoutOperationOwner): LogoutRemoteResult =
        when (authRepository.attemptPreparedLogout(owner.sessionEpoch)) {
            RevocationRemoteResult.Success,
            RevocationRemoteResult.InvalidOrExpired,
            -> LogoutRemoteResult.Revoked
            RevocationRemoteResult.Retryable -> LogoutRemoteResult.Offline
            null -> LogoutRemoteResult.Revoked
        }

    override suspend fun commitLoggedOut(
        owner: LogoutOperationOwner,
        moveRefreshToRevocationOnly: Boolean,
    ): Boolean {
        val account = runCatching { resolve(owner) }.getOrNull() ?: return false
        val store = RoomAccountExitIntentStore(database, account.accountScope)
        val ready = store.find() ?: return false
        if (ready.stage != AccountExitStage.READY_TO_CLEAR || !ready.matches(owner)) return false
        val committed = authRepository.commitPreparedLogout(owner.sessionEpoch, moveRefreshToRevocationOnly)
        if (committed) store.deleteReady(ready)
        return committed
    }

    override suspend fun preparePasswordChange(): PreparedPasswordChange? {
        val owner = acquireOwner() ?: return null
        val intent = persistIntent(owner, LogoutChoice.RETAIN)
        converge(intent)
        return PreparedPasswordChange(intent, resolve(owner).accountScope)
    }

    override suspend fun changePassword(
        prepared: PreparedPasswordChange,
        oldPassword: String,
        newPassword: String,
    ): Boolean {
        resolve(prepared.intent.owner)
        return authRepository.changePassword(oldPassword, newPassword)
    }

    override suspend fun completePasswordChange(prepared: PreparedPasswordChange): Boolean {
        val store = RoomAccountExitIntentStore(database, prepared.accountScope)
        val ready = store.find() ?: return false
        if (ready.stage != AccountExitStage.READY_TO_CLEAR || !ready.matches(prepared.intent.owner)) return false
        return store.deleteReady(ready) == 1
    }

    /** 进程重启后在启动任何当前账户工作前调用。 */
    suspend fun recoverCurrentExit(): Boolean = mutex.withLock {
        val account = currentAccount() ?: return@withLock false
        val store = RoomAccountExitIntentStore(database, account.accountScope)
        val existing = store.find() ?: return@withLock false
        require(existing.accountScopeHash == account.accountScopeHash)
        val owner = account.owner(existing.operationId)
        val adopted = authRepository.withAuthenticatedLease(account.lease) { store.takeover(existing, owner) }
        val intent = LogoutIntent(owner, LogoutChoice.valueOf(adopted.choice.name))
        AccountExitProcessor(store, effects).converge(adopted)
        val remote = logoutServer(owner)
        check(commitLoggedOut(owner, remote == LogoutRemoteResult.Offline)) { "账户退出恢复已被新会话取代" }
        true
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
    private val resolve: (LogoutOperationOwner) -> ProductionAccountExitManager.ExitAccount,
) : AccountExitEffects {
    private val dataEraser = AccountDataEraser(context, database, fileStore)
    override suspend fun pauseAndLock(intent: LogoutIntent) = Unit // Room intent 本身就是持久锁。

    override suspend fun cancelAndAwait(intent: LogoutIntent) {
        val account = resolve(intent.owner)
        uploadCoordinator.cancelAndAwaitAccount(account.accountScopeHash)
        analysisCoordinator.cancelAccount(account.accountScopeHash)
        recordingPlaybackHandoff.discardAll()
        workCancellation.cancelAndAwait(account.accountScopeHash)
    }

    override suspend fun revokeRuntimeAccess(intent: LogoutIntent) {
        val account = resolve(intent.owner)
        fileStore.revokeEncryptedMediaReaders(account.accountScope)
    }

    override suspend fun deleteAccountData(intent: LogoutIntent) {
        val account = resolve(intent.owner)
        dataEraser.delete(account.accountScope, account.accountScopeHash)
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
        fileStore.destroyAccountEncryption(accountScope)
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
        if (!target.exists()) return
        require(!Files.isSymbolicLink(target.toPath())) { "账户临时目录不可为符号链接" }
        target.walkBottomUp().forEach { item ->
            require(!Files.isSymbolicLink(item.toPath())) { "账户临时文件不可为符号链接" }
            check(!item.exists() || item.delete()) { "账户临时文件删除失败" }
        }
    }
}
