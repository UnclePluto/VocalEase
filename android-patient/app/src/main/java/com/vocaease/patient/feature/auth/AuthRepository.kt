package com.vocaease.patient.feature.auth

import android.system.ErrnoException
import com.vocaease.patient.core.network.ApiEndpoint
import com.vocaease.patient.core.network.ApiErrorMapper
import com.vocaease.patient.core.network.ApiFailure
import com.vocaease.patient.core.network.AuthApi
import com.vocaease.patient.core.network.NetworkContractException
import com.vocaease.patient.core.network.PatientApi
import com.vocaease.patient.core.network.RefreshCoordinator
import com.vocaease.patient.core.network.RefreshRemoteDataSource
import com.vocaease.patient.core.network.RefreshResult
import com.vocaease.patient.core.network.SessionExpiredException
import com.vocaease.patient.core.network.SessionLifecycleArbiter
import com.vocaease.patient.core.network.dto.AccountRole
import com.vocaease.patient.core.network.dto.AuthSession
import com.vocaease.patient.core.network.dto.ChangePasswordRequestDto
import com.vocaease.patient.core.network.dto.ClientKind
import com.vocaease.patient.core.network.dto.LoginRequestDto
import com.vocaease.patient.core.network.dto.LogoutRequestDto
import com.vocaease.patient.core.network.dto.RefreshRequestDto
import com.vocaease.patient.core.network.dto.toDomain
import com.vocaease.patient.core.security.RefreshTokenRead
import com.vocaease.patient.core.security.SessionMutation
import com.vocaease.patient.core.security.TokenVault
import com.vocaease.patient.core.security.VaultInvalidatedException
import com.vocaease.patient.core.database.AuthenticatedAccountLease
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

sealed interface AuthState {
    data object Restoring : AuthState
    data object LoggedOut : AuthState
    data object Authenticated : AuthState
    data object MustChangePassword : AuthState
}

sealed interface AuthEvent {
    data object SessionExpired : AuthEvent
    data object PasswordChanged : AuthEvent
}

sealed interface AuthOperationState {
    data object Idle : AuthOperationState
    data object Loading : AuthOperationState
    data class Error(val message: String) : AuthOperationState
}

interface AuthRemoteDataSource : RefreshRemoteDataSource {
    suspend fun login(request: LoginRequestDto): AuthSession
    override suspend fun refresh(request: RefreshRequestDto): AuthSession
    suspend fun changePassword(request: ChangePasswordRequestDto)
    suspend fun logout(request: LogoutRequestDto)
}

fun interface PatientIdentityRemoteDataSource {
    /** 返回 `/patient/me` 的原始 patient id，仓库负责做严格 UUID 校验。 */
    suspend fun patientUuid(): String
}

internal class VocaEasePatientIdentityRemoteDataSource(
    private val patientApi: PatientApi,
) : PatientIdentityRemoteDataSource {
    override suspend fun patientUuid(): String = patientApi.patientMe().data.id
}

class VocaEaseAuthRemoteDataSource(
    private val api: AuthApi,
) : AuthRemoteDataSource {
    override suspend fun login(request: LoginRequestDto): AuthSession = api.login(request).data.toDomain()
    override suspend fun refresh(request: RefreshRequestDto): AuthSession = api.refresh(request).data.toDomain()
    override suspend fun changePassword(request: ChangePasswordRequestDto) {
        api.changePassword(request)
    }
    override suspend fun logout(request: LogoutRequestDto) {
        api.logout(request)
    }
}

class AuthRepository(
    tokenVault: TokenVault,
    private val remote: AuthRemoteDataSource,
    private val refreshCoordinator: RefreshCoordinator,
    private val patientIdentity: PatientIdentityRemoteDataSource,
) {
    private val sessionArbiter = refreshCoordinator.sessionArbiter
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Restoring)
    private val mutableOperation = MutableStateFlow<AuthOperationState>(AuthOperationState.Idle)
    private val eventChannel = Channel<AuthEvent>(capacity = Channel.BUFFERED)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    val operation: StateFlow<AuthOperationState> = mutableOperation.asStateFlow()
    val events = eventChannel.receiveAsFlow()

    init {
        refreshCoordinator.requireTokenVault(tokenVault)
        refreshCoordinator.addSessionExpiredListener {
            mutableState.value = AuthState.LoggedOut
            mutableOperation.value = AuthOperationState.Idle
            eventChannel.trySend(AuthEvent.SessionExpired)
        }
    }

    suspend fun restoreSession() {
        if (mutableState.value != AuthState.Restoring) return
        mutableOperation.value = AuthOperationState.Idle
        val current = sessionArbiter.sessionSnapshot()
        if (current.accessToken != null) {
            restoreAuthenticatedPatient()
            return
        }
        when (val read = sessionArbiter.mutate { readRefreshToken(current.epoch) }) {
            is RefreshTokenRead.Missing -> {
                sessionArbiter.mutate {
                    val observed = sessionSnapshot()
                    if (observed.epoch == read.observedEpoch && observed.accessToken == null) {
                        revokeAuthenticatedAccount()
                        mutableState.value = AuthState.LoggedOut
                    }
                }
                return
            }
            is RefreshTokenRead.Invalidated -> {
                refreshCoordinator.recordVaultInvalidation(read.invalidation, read.cause)
                return
            }
            is RefreshTokenRead.Available -> Unit
        }
        when (val result = refreshCoordinator.refreshAfterUnauthorized(current.epoch)) {
            is RefreshResult.Success -> {
                val mustChangePassword = result.session?.mustChangePassword == true
                if (mustChangePassword) {
                    sessionArbiter.mutate {
                        val observed = sessionSnapshot()
                        if (observed.accessToken != null) {
                            revokeAuthenticatedAccount()
                            mutableState.value = AuthState.MustChangePassword
                        }
                    }
                } else {
                    restoreAuthenticatedPatient()
                }
            }
            is RefreshResult.Failed -> Unit
        }
    }

    suspend fun login(loginId: String, password: String) {
        if (loginId.isBlank() || password.isBlank()) {
            mutableOperation.value = AuthOperationState.Error("请输入病历号和密码")
            return
        }
        mutableOperation.value = AuthOperationState.Loading
        val accountIncarnationId = UUID.randomUUID().toString()
        val attemptEpoch = withContext(NonCancellable) {
            sessionArbiter.mutate {
                val mutation = prepareLoginAttemptLocked()
                beginAccountAuthentication(accountIncarnationId)
                mutableState.value = AuthState.LoggedOut
                mutation.snapshot.epoch
            }
        }
        val replacementId = UUID.randomUUID().toString()
        var failureEndpoint = ApiEndpoint.AUTH_LOGIN
        try {
            val session = remote.login(
                LoginRequestDto(
                    loginId = loginId.trim(),
                    password = password,
                    clientKind = ClientKind.ANDROID,
                    rememberMe = false,
                ),
            )
            if (session.role != AccountRole.PATIENT) throw AuthContractException("仅支持患者账号登录")
            val refresh = session.refresh ?: throw AuthContractException("安卓登录响应缺少 refresh token")
            val resolvePatient = sessionArbiter.mutate {
                val mutation = replaceTokens(
                    expectedEpoch = attemptEpoch,
                    accessToken = session.access,
                    refreshToken = refresh,
                    replacementId = replacementId,
                )
                if (mutation.applied) {
                    if (session.mustChangePassword) {
                        revokeAuthenticatedAccount()
                        mutableState.value = AuthState.MustChangePassword
                        false
                    } else {
                        true
                    }
                } else {
                    false
                }
            }
            if (resolvePatient) {
                failureEndpoint = ApiEndpoint.PATIENT_ME
                publishAuthenticatedPatient(accountIncarnationId)
            }
        } catch (error: CancellationException) {
            applyLoginAttemptCleanup(attemptEpoch, replacementId, accountIncarnationId)
            throw error
        } catch (error: Throwable) {
            applyLoginAttemptCleanup(attemptEpoch, replacementId, accountIncarnationId)
            mutableOperation.value = AuthOperationState.Error(error.userMessage(failureEndpoint))
        } finally {
            finishOperation()
        }
    }

    suspend fun changePassword(oldPassword: String, newPassword: String) {
        if (oldPassword.isBlank() || newPassword.isBlank()) {
            mutableOperation.value = AuthOperationState.Error("请输入原密码和新密码")
            return
        }
        mutableOperation.value = AuthOperationState.Loading
        var passwordChanged = false
        var refreshRead: RefreshTokenRead? = null
        try {
            try {
                remote.changePassword(ChangePasswordRequestDto(oldPassword, newPassword))
            } catch (error: CancellationException) {
                applyLoggedOutCleanup(expectedEpoch = null)
                throw error
            } catch (error: Throwable) {
                mutableOperation.value = AuthOperationState.Error(error.userMessage(ApiEndpoint.AUTH_CHANGE_PASSWORD))
                return
            }
            passwordChanged = true

            refreshRead = sessionArbiter.mutate {
                val beforeLogout = sessionSnapshot()
                readRefreshToken(beforeLogout.epoch)
            }
            val refresh = (refreshRead as? RefreshTokenRead.Available)?.lease?.value
            try {
                remote.logout(LogoutRequestDto(ClientKind.ANDROID, refresh))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 改密已成功；远端登出失败不能阻止 finally 清理本地凭据。
            }
        } finally {
            if (passwordChanged) {
                withContext(NonCancellable) {
                    sessionArbiter.mutate {
                        if (refreshRead !is RefreshTokenRead.Invalidated) secureClearLocked()
                        else revokeAuthenticatedAccount()
                        mutableState.value = AuthState.LoggedOut
                        eventChannel.trySend(AuthEvent.PasswordChanged)
                    }
                }
            }
            finishOperation()
        }
    }

    suspend fun logout() {
        mutableOperation.value = AuthOperationState.Loading
        val refresh = withContext(NonCancellable) {
            sessionArbiter.mutate {
                val beforeLogout = sessionSnapshot()
                val refreshRead = readRefreshToken(beforeLogout.epoch)
                if (refreshRead !is RefreshTokenRead.Invalidated) secureClearLocked()
                else revokeAuthenticatedAccount()
                mutableState.value = AuthState.LoggedOut
                mutableOperation.value = AuthOperationState.Idle
                (refreshRead as? RefreshTokenRead.Available)?.lease?.value
            }
        }
        try {
            remote.logout(LogoutRequestDto(ClientKind.ANDROID, refresh))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 远端失败不能恢复本地凭据。
        } finally {
            finishOperation()
        }
    }

    fun dismissError() {
        mutableOperation.value = AuthOperationState.Idle
    }

    fun finishOperation() {
        if (mutableOperation.value == AuthOperationState.Loading) {
            mutableOperation.value = AuthOperationState.Idle
        }
    }

    private suspend fun applyLoggedOutCleanup(expectedEpoch: Long?) {
        withContext(NonCancellable) {
            sessionArbiter.mutate {
                val mutation = secureClearLocked(expectedEpoch)
                if (mutation.snapshot.accessToken == null) {
                    mutableState.value = AuthState.LoggedOut
                }
            }
        }
    }

    private suspend fun applyLoginAttemptCleanup(
        attemptEpoch: Long,
        replacementId: String,
        accountIncarnationId: String,
    ) {
        withContext(NonCancellable) {
            sessionArbiter.mutate {
                val current = sessionSnapshot()
                val cleanupEpoch = when {
                    !isPendingAccountAuthentication(accountIncarnationId) -> null
                    current.epoch == attemptEpoch -> attemptEpoch
                    current.replacementId == replacementId -> current.epoch
                    current.accessToken != null -> current.epoch // `/patient/me` 内的正常 refresh 仍属于本次认证。
                    else -> null
                }
                val mutation = cleanupEpoch?.let { secureClearLocked(it) }
                if (isPendingAccountAuthentication(accountIncarnationId)) {
                    revokeAuthenticatedAccount()
                }
                if ((mutation?.snapshot ?: sessionSnapshot()).accessToken == null) {
                    mutableState.value = AuthState.LoggedOut
                }
            }
        }
    }

    private suspend fun SessionLifecycleArbiter.MutationScope.secureClearLocked(
        expectedEpoch: Long? = null,
    ): SessionMutation = try {
        clear(expectedEpoch)
    } catch (error: VaultInvalidatedException) {
        revokeAuthenticatedAccount()
        SessionMutation(applied = true, snapshot = sessionSnapshot())
    }

    private suspend fun SessionLifecycleArbiter.MutationScope.prepareLoginAttemptLocked(): SessionMutation {
        val current = sessionSnapshot()
        if (current.accessToken != null) return secureClearLocked(current.epoch)
        return when (readRefreshToken(current.epoch)) {
            is RefreshTokenRead.Available -> secureClearLocked(current.epoch)
            is RefreshTokenRead.Invalidated -> SessionMutation(applied = true, snapshot = sessionSnapshot())
            is RefreshTokenRead.Missing -> SessionMutation(applied = false, snapshot = sessionSnapshot())
        }
    }

    private suspend fun restoreAuthenticatedPatient() {
        val accountIncarnationId = UUID.randomUUID().toString()
        val began = sessionArbiter.mutate {
            if (mutableState.value != AuthState.Restoring || sessionSnapshot().accessToken == null) {
                false
            } else {
                beginAccountAuthentication(accountIncarnationId)
                true
            }
        }
        if (!began) return
        try {
            publishAuthenticatedPatient(accountIncarnationId)
        } catch (error: CancellationException) {
            cleanupPendingAccountAuthentication(accountIncarnationId)
            throw error
        } catch (error: Throwable) {
            cleanupPendingAccountAuthentication(accountIncarnationId)
            mutableOperation.value = AuthOperationState.Error(error.userMessage(ApiEndpoint.PATIENT_ME))
        }
    }

    private suspend fun publishAuthenticatedPatient(accountIncarnationId: String) {
        val rawPatientId = patientIdentity.patientUuid()
        val patientId = try {
            UUID.fromString(rawPatientId).toString()
        } catch (error: IllegalArgumentException) {
            throw NetworkContractException("patient.id 不是有效 UUID", error)
        }
        sessionArbiter.mutate {
            if (publishAuthenticatedAccount(patientId, accountIncarnationId) != null) {
                mutableState.value = AuthState.Authenticated
            }
        }
    }

    private suspend fun cleanupPendingAccountAuthentication(accountIncarnationId: String) {
        withContext(NonCancellable) {
            sessionArbiter.mutate {
                if (!isPendingAccountAuthentication(accountIncarnationId)) return@mutate
                val current = sessionSnapshot()
                if (current.accessToken != null) secureClearLocked(current.epoch) else revokeAuthenticatedAccount()
                mutableState.value = AuthState.LoggedOut
            }
        }
    }

    internal fun currentAuthenticatedLease(): AuthenticatedAccountLease? =
        sessionArbiter.currentAuthenticatedAccountLease()

    internal fun isCurrentAuthenticatedLease(lease: AuthenticatedAccountLease): Boolean =
        sessionArbiter.currentAuthenticatedAccountLease() === lease

    internal suspend fun <T> withAuthenticatedLease(
        lease: AuthenticatedAccountLease,
        operation: suspend () -> T,
    ): T = sessionArbiter.withAuthenticatedAccountLease(lease, operation)

    private fun Throwable.userMessage(endpoint: ApiEndpoint): String = when (this) {
        is HttpException, is IOException, is SerializationException -> when (val failure = ApiErrorMapper.map(this, endpoint)) {
            is ApiFailure.Unauthorized -> if (endpoint == ApiEndpoint.AUTH_LOGIN) "病历号或密码错误" else failure.userMessage
            else -> failure.userMessage
        }
        is AuthContractException, is NetworkContractException -> "服务返回的登录信息不完整，请稍后重试"
        is SessionExpiredException -> "登录状态已失效，请重新登录"
        is VaultInvalidatedException -> "安全会话保存失败，请重新登录"
        is GeneralSecurityException, is ProviderException, is ErrnoException -> "安全会话保存失败，请重新登录"
        else -> throw this
    }
}

private class AuthContractException(message: String) : IllegalStateException(message)
