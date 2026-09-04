package com.vocaease.patient.feature.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.database.AccountLeaseListenerRegistration
import com.vocaease.patient.core.database.AccountScopedDraftStorageProvider
import com.vocaease.patient.core.database.AuthenticatedAccountLease
import com.vocaease.patient.core.database.AuthenticatedAccountSession
import com.vocaease.patient.core.database.VocaEaseDatabase
import com.vocaease.patient.core.media.RecordingPlaybackHandoff
import com.vocaease.patient.core.network.RefreshCoordinator
import com.vocaease.patient.core.network.RefreshResult
import com.vocaease.patient.core.network.dto.AccountRole
import com.vocaease.patient.core.network.dto.AuthSession
import com.vocaease.patient.core.network.dto.ChangePasswordRequestDto
import com.vocaease.patient.core.network.dto.LoginRequestDto
import com.vocaease.patient.core.network.dto.LogoutRequestDto
import com.vocaease.patient.core.network.dto.RefreshRequestDto
import com.vocaease.patient.core.security.AndroidRevocationVault
import com.vocaease.patient.core.security.AndroidTokenVault
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.core.security.DurableLogoutTokenVault
import com.vocaease.patient.core.security.RefreshTokenRead
import com.vocaease.patient.core.security.RevocationHandle
import com.vocaease.patient.core.security.RevocationRemote
import com.vocaease.patient.core.security.RevocationRemoteResult
import com.vocaease.patient.core.security.RevocationScheduling
import com.vocaease.patient.core.security.RevocationTransferSink
import com.vocaease.patient.feature.auth.AuthRemoteDataSource
import com.vocaease.patient.feature.auth.AuthRepository
import com.vocaease.patient.feature.auth.AuthState
import com.vocaease.patient.feature.auth.PatientIdentityRemoteDataSource
import com.vocaease.patient.feature.auth.PreparedLogoutCheckpoint
import com.vocaease.patient.feature.history.AnalysisAndroidWorkContract
import com.vocaease.patient.feature.history.AnalysisSyncCoordinator
import com.vocaease.patient.feature.history.AnalysisWorkScheduling
import com.vocaease.patient.feature.upload.UploadCoordinator
import com.vocaease.patient.feature.upload.UploadWorkContract
import com.vocaease.patient.feature.upload.UploadWorkScheduling
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionAccountExitRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: VocaEaseDatabase

    @Before
    fun setUp() {
        cleanupVaultFiles()
        database = VocaEaseDatabase.inMemory(context, allowMainThreadQueries = true)
    }

    @After
    fun tearDown() {
        database.close()
        cleanupVaultFiles()
    }

    @Test
    fun A离线退出进程死亡后启动先移交撤销且B再到A不被旧操作清除() = runBlocking {
        val firstTokenVault = AndroidTokenVault(context)
        val firstRemote = AccountSwitchingRemote()
        val firstRepository = repository(firstTokenVault, firstRemote, FailingTransferSink)
        val firstManager = manager(firstRepository)
        firstRepository.registerPreparedLogoutRecovery(firstManager::recoverAuthenticationFinalization)
        firstRepository.login("patient-a", "password")
        assertEquals(AuthState.Authenticated, firstRepository.state.value)

        val owner = requireNotNull(firstManager.acquireOwner())
        val intent = firstManager.persistIntent(owner, LogoutChoice.RETAIN)
        firstManager.completeIntent(intent)
        assertThrows(IOException::class.java) {
            runBlocking { firstManager.finishLogout(owner) }
        }

        val afterCrashVault = AndroidTokenVault(context)
        assertNull(afterCrashVault.sessionSnapshot().accessToken)
        assertNotNull((afterCrashVault as DurableLogoutTokenVault).pendingRevocationTransfer())
        assertNull(firstRepository.currentAuthenticatedLease())

        val revocationVault = AndroidRevocationVault(context)
        val recoveredRemote = AccountSwitchingRemote()
        val recoveredRepository = repository(afterCrashVault, recoveredRemote, revocationVault)
        val recoveredManager = manager(recoveredRepository)
        recoveredRepository.registerPreparedLogoutRecovery(recoveredManager::recoverAuthenticationFinalization)
        recoveredRepository.restoreSession()

        assertEquals(AuthState.LoggedOut, recoveredRepository.state.value)
        assertNull(database.accountExitIntentDao().find(PATIENT_A))
        assertNull((afterCrashVault as DurableLogoutTokenVault).pendingRevocationTransfer())
        val oldHandle = revocationVault.handles().single()
        assertEquals("patient-a-refresh", revocationVault.lease(oldHandle)?.refreshToken)

        recoveredRepository.login("patient-b", "password")
        assertEquals(PATIENT_B, recoveredRepository.currentAuthenticatedLease()?.patientId)
        assertEquals("patient-b-refresh", activeRefresh(afterCrashVault))
        assertFalse(recoveredManager.recoverCurrentExit())

        recoveredRepository.login("patient-a", "password")
        assertEquals(PATIENT_A, recoveredRepository.currentAuthenticatedLease()?.patientId)
        assertEquals("patient-a-refresh", activeRefresh(afterCrashVault))
        assertFalse(recoveredManager.recoverCurrentExit())
        assertEquals("patient-a-access", afterCrashVault.sessionSnapshot().accessToken)
        assertEquals("patient-a-refresh", revocationVault.lease(oldHandle)?.refreshToken)
        assertTrue(revocationVault.handles().contains(oldHandle))
    }

    @Test
    fun vault绑定后Room检查点前死亡仍在登录可用前完成旧family退出() = runBlocking {
        val firstVault = AndroidTokenVault(context)
        val firstRemote = AccountSwitchingRemote()
        val firstRepository = repository(firstVault, firstRemote, AndroidRevocationVault(context))
        val firstManager = manager(firstRepository)
        firstRepository.login("patient-a", "password")
        val owner = requireNotNull(firstManager.acquireOwner())
        firstManager.completeIntent(firstManager.persistIntent(owner, LogoutChoice.RETAIN))

        assertThrows(IOException::class.java) {
            runBlocking {
                firstRepository.finishPreparedLogout(
                    owner.sessionEpoch,
                    owner.operationId,
                    PreparedLogoutCheckpoint.READY_TO_CLEAR,
                ) { checkpoint ->
                    if (checkpoint == PreparedLogoutCheckpoint.AUTH_BOUND) {
                        throw IOException("模拟Room AUTH_BOUND落盘前死亡")
                    }
                }
            }
        }

        val restartedVault = AndroidTokenVault(context)
        val restartedRepository = repository(restartedVault, AccountSwitchingRemote(), AndroidRevocationVault(context))
        val restartedManager = manager(restartedRepository)
        restartedRepository.registerPreparedLogoutRecovery(restartedManager::recoverAuthenticationFinalization)
        restartedRepository.restoreSession()

        assertEquals(AuthState.LoggedOut, restartedRepository.state.value)
        assertNull(database.accountExitIntentDao().find(PATIENT_A))
        assertNull((restartedVault as DurableLogoutTokenVault).pendingRevocationTransfer())
    }

    @Test
    fun 旧incarnation取消副作用执行期间新登录不能发布且结束后不误伤新账户() = runBlocking {
        val vault = AndroidTokenVault(context)
        val remote = AccountSwitchingRemote()
        val repository = repository(vault, remote, AndroidRevocationVault(context))
        repository.login("patient-a", "password")
        val cancellation = BlockingAccountWorkCancellation(repository, requireNotNull(repository.currentAuthenticatedLease()))
        val manager = manager(repository, cancellation)
        val owner = requireNotNull(manager.acquireOwner())
        val intent = manager.persistIntent(owner, LogoutChoice.RETAIN)

        val oldOperation = async { runCatching { manager.completeIntent(intent) } }
        cancellation.started.await()
        val newLogin = async { repository.login("patient-b", "password") }

        assertNull(withTimeoutOrNull(100) { remote.secondLoginStarted.await() })
        cancellation.release.complete(Unit)
        oldOperation.await()
        newLogin.await()

        assertEquals(PATIENT_B, repository.currentAuthenticatedLease()?.patientId)
        assertEquals("patient-b-access", vault.sessionSnapshot().accessToken)
    }

    @Test
    fun DELETE副作用门闩期间refresh被同一destructiveClaim拒绝且退出收敛() = runBlocking {
        val vault = AndroidTokenVault(context)
        val remote = AccountSwitchingRemote()
        val refreshCoordinator = RefreshCoordinator(vault, remote)
        val repository = AuthRepository(
            tokenVault = vault,
            remote = remote,
            refreshCoordinator = refreshCoordinator,
            patientIdentity = PatientIdentityRemoteDataSource(remote::currentPatientId),
            revocationTokenSink = AndroidRevocationVault(context),
            revocationRemote = RevocationRemote { _, _ -> RevocationRemoteResult.Retryable },
            revocationScheduler = RevocationScheduling { },
        )
        repository.login("patient-a", "password")
        val epoch = vault.sessionSnapshot().epoch
        val cancellation = BlockingAccountWorkCancellation(repository, requireNotNull(repository.currentAuthenticatedLease()))
        val manager = manager(repository, cancellation)

        val logout = async { LogoutCoordinator(manager).beginLogout() }
        cancellation.started.await()
        val refresh = async { refreshCoordinator.refreshAfterUnauthorized(epoch) }

        assertTrue(refresh.await() is RefreshResult.Superseded)
        assertFalse(remote.refreshStarted.isCompleted)
        cancellation.release.complete(Unit)
        assertEquals(LogoutOutcome.LoggedOut, logout.await())
        assertEquals(AuthState.LoggedOut, repository.state.value)
        assertNull(database.accountExitIntentDao().find(PATIENT_A))
    }

    @Test
    fun 启动恢复在claim前遇refresh后必须重读epoch再安全收敛() = runBlocking {
        val vault = AndroidTokenVault(context)
        val remote = AccountSwitchingRemote()
        val refreshCoordinator = RefreshCoordinator(vault, remote)
        val repository = AuthRepository(
            vault, remote, refreshCoordinator, PatientIdentityRemoteDataSource(remote::currentPatientId),
            AndroidRevocationVault(context), RevocationRemote { _, _ -> RevocationRemoteResult.Retryable },
            RevocationScheduling { },
        )
        repository.login("patient-a", "password")
        val seedManager = manager(repository)
        val owner = requireNotNull(seedManager.acquireOwner())
        seedManager.completeIntent(seedManager.persistIntent(owner, LogoutChoice.RETAIN))
        val gate = RecoveryClaimGate()
        val recoveringManager = manager(repository, beforeRecoveryClaim = gate::awaitRelease)

        val recovery = async { recoveringManager.recoverCurrentExit() }
        gate.started.await()
        assertTrue(refreshCoordinator.refreshAfterUnauthorized(owner.sessionEpoch) is RefreshResult.Success)
        gate.release.complete(Unit)

        assertTrue(recovery.await())
        assertEquals(AuthState.LoggedOut, repository.state.value)
        assertNull(database.accountExitIntentDao().find(PATIENT_A))
    }

    private fun repository(
        tokenVault: AndroidTokenVault,
        remote: AccountSwitchingRemote,
        transferSink: RevocationTransferSink,
    ): AuthRepository = AuthRepository(
        tokenVault = tokenVault,
        remote = remote,
        refreshCoordinator = RefreshCoordinator(tokenVault, remote),
        patientIdentity = PatientIdentityRemoteDataSource(remote::currentPatientId),
        revocationTokenSink = transferSink,
        revocationRemote = RevocationRemote { _, _ -> RevocationRemoteResult.Retryable },
        revocationScheduler = RevocationScheduling { },
    )

    private fun manager(
        repository: AuthRepository,
        workCancellation: AccountWorkCancellation = NoopAccountWorkCancellation,
        beforeRecoveryClaim: suspend () -> Unit = {},
    ): ProductionAccountExitManager {
        val accountSession = RepositoryAccountSession(repository)
        val fileStore = ChunkedAesGcmFileStore(context)
        val storageProvider = AccountScopedDraftStorageProvider(database, fileStore, accountSession)
        val uploadCoordinator = UploadCoordinator(
            context = context,
            storageProvider = storageProvider,
            remoteFactory = { error("测试不会启动上传") },
            scheduler = NoopUploadScheduler,
        )
        val analysisCoordinator = AnalysisSyncCoordinator(
            scopeProvider = { error("测试不会启动分析") },
            remoteFactory = { error("测试不会请求分析") },
            scheduler = NoopAnalysisScheduler,
            nowEpochMillis = { 0L },
        )
        return ProductionAccountExitManager(
            context = context,
            authRepository = repository,
            storageProvider = storageProvider,
            database = database,
            fileStore = fileStore,
            uploadCoordinator = uploadCoordinator,
            analysisCoordinator = analysisCoordinator,
            recordingPlaybackHandoff = RecordingPlaybackHandoff(Dispatchers.Unconfined),
            workCancellation = workCancellation,
            beforeRecoveryClaim = beforeRecoveryClaim,
        )
    }

    private suspend fun activeRefresh(vault: AndroidTokenVault): String? {
        val snapshot = vault.sessionSnapshot()
        return (vault.readRefreshToken(snapshot.epoch) as? RefreshTokenRead.Available)?.lease?.value
    }

    private fun cleanupVaultFiles() {
        listOf(AndroidTokenVault.FILE_NAME, AndroidRevocationVault.FILE_NAME).forEach { name ->
            val file = context.getFileStreamPath(name)
            file.delete()
            File(file.path + ".bak").delete()
        }
    }
}

private class RepositoryAccountSession(
    private val repository: AuthRepository,
) : AuthenticatedAccountSession {
    override fun current(): AuthenticatedAccountLease? = repository.currentAuthenticatedLease()

    override fun addLeaseChangedListener(
        listener: (AuthenticatedAccountLease?) -> Unit,
    ): AccountLeaseListenerRegistration = repository.addAuthenticatedLeaseChangedListener(listener)

    override suspend fun <T> withCurrentLease(
        expected: AuthenticatedAccountLease,
        operation: suspend () -> T,
    ): T = repository.withAuthenticatedLease(expected, operation)
}

private class AccountSwitchingRemote : AuthRemoteDataSource {
    private var patientId: String = PATIENT_A
    private var loginCalls = 0
    val secondLoginStarted = CompletableDeferred<Unit>()
    val refreshStarted = CompletableDeferred<Unit>()

    fun currentPatientId(): String = patientId

    override suspend fun login(request: LoginRequestDto): AuthSession {
        loginCalls += 1
        if (loginCalls == 2) secondLoginStarted.complete(Unit)
        patientId = if (request.loginId == "patient-a") PATIENT_A else PATIENT_B
        return AuthSession(
            access = "${request.loginId}-access",
            refresh = "${request.loginId}-refresh",
            refreshExpiresAt = Instant.parse("2027-01-01T00:00:00Z"),
            loginId = request.loginId,
            role = AccountRole.PATIENT,
            mustChangePassword = false,
        )
    }

    override suspend fun refresh(request: RefreshRequestDto): AuthSession {
        refreshStarted.complete(Unit)
        return AuthSession(
            access = "refreshed-access",
            refresh = "refreshed-refresh",
            refreshExpiresAt = Instant.parse("2027-01-01T00:00:00Z"),
            loginId = "patient-a",
            role = AccountRole.PATIENT,
            mustChangePassword = false,
        )
    }
    override suspend fun changePassword(request: ChangePasswordRequestDto) = Unit
    override suspend fun logout(request: LogoutRequestDto) = Unit
}

private object FailingTransferSink : RevocationTransferSink {
    override suspend fun store(accessToken: String, refreshToken: String): RevocationHandle =
        throw IOException("模拟撤销槽写入失败")

    override suspend fun store(handle: RevocationHandle, accessToken: String, refreshToken: String) {
        throw IOException("模拟撤销槽写入失败")
    }
}

private object NoopUploadScheduler : UploadWorkScheduling {
    override fun enqueue(contract: UploadWorkContract, replace: Boolean) = Unit
    override fun cancel(contract: UploadWorkContract) = Unit
    override fun cancelAccount(accountScopeHash: String) = Unit
}

private object NoopAnalysisScheduler : AnalysisWorkScheduling {
    override fun start(contract: AnalysisAndroidWorkContract, delayMillis: Long) = Unit
    override fun append(contract: AnalysisAndroidWorkContract, delayMillis: Long) = Unit
    override fun replace(contract: AnalysisAndroidWorkContract, delayMillis: Long) = Unit
    override fun cancelAccount(accountScopeHash: String) = Unit
}

private object NoopAccountWorkCancellation : AccountWorkCancellation {
    override suspend fun cancelAndAwait(accountScopeHash: String) = Unit
}

private class BlockingAccountWorkCancellation(
    private val repository: AuthRepository,
    private val lease: AuthenticatedAccountLease,
) : AccountWorkCancellation {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    override suspend fun cancelAndAwait(accountScopeHash: String) {
        // 模拟 upload 持 executionLock 后进入 storage.checked()；外层不得继续持 session mutationMutex。
        repository.withAuthenticatedLease(lease) { Unit }
        started.complete(Unit)
        release.await()
    }
}

private class RecoveryClaimGate {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    suspend fun awaitRelease() {
        started.complete(Unit)
        release.await()
    }
}

private const val PATIENT_A = "11111111-1111-4111-8111-111111111111"
private const val PATIENT_B = "22222222-2222-4222-8222-222222222222"
