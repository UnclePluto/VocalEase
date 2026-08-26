package com.vocaease.patient.feature.auth

import android.system.ErrnoException
import com.vocaease.patient.core.network.ApiEndpoint
import com.vocaease.patient.core.network.ApiErrorMapper
import com.vocaease.patient.core.network.ApiFailure
import com.vocaease.patient.core.network.AuthApi
import com.vocaease.patient.core.network.NetworkContractException
import com.vocaease.patient.core.network.RefreshCoordinator
import com.vocaease.patient.core.network.RefreshRemoteDataSource
import com.vocaease.patient.core.network.RefreshResult
import com.vocaease.patient.core.network.dto.AccountRole
import com.vocaease.patient.core.network.dto.AuthSession
import com.vocaease.patient.core.network.dto.ChangePasswordRequestDto
import com.vocaease.patient.core.network.dto.ClientKind
import com.vocaease.patient.core.network.dto.LoginRequestDto
import com.vocaease.patient.core.network.dto.LogoutRequestDto
import com.vocaease.patient.core.network.dto.RefreshRequestDto
import com.vocaease.patient.core.network.dto.toDomain
import com.vocaease.patient.core.security.SessionMutation
import com.vocaease.patient.core.security.TokenVault
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
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
    private val tokenVault: TokenVault,
    private val remote: AuthRemoteDataSource,
    private val refreshCoordinator: RefreshCoordinator,
) {
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Restoring)
    private val mutableOperation = MutableStateFlow<AuthOperationState>(AuthOperationState.Idle)
    private val eventChannel = Channel<AuthEvent>(capacity = Channel.BUFFERED)

    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    val operation: StateFlow<AuthOperationState> = mutableOperation.asStateFlow()
    val events = eventChannel.receiveAsFlow()

    init {
        refreshCoordinator.addSessionExpiredListener {
            mutableState.value = AuthState.LoggedOut
            mutableOperation.value = AuthOperationState.Idle
            eventChannel.trySend(AuthEvent.SessionExpired)
        }
    }

    suspend fun restoreSession() {
        if (mutableState.value != AuthState.Restoring) return
        mutableOperation.value = AuthOperationState.Idle
        val current = tokenVault.sessionSnapshot()
        if (current.accessToken != null) {
            mutableState.value = AuthState.Authenticated
            return
        }
        if (tokenVault.readRefreshToken(current.epoch) == null) {
            mutableState.value = AuthState.LoggedOut
            return
        }
        when (val result = refreshCoordinator.refreshAfterUnauthorized(current.epoch)) {
            is RefreshResult.Success -> result.session?.let(::routeFor) ?: run {
                if (mutableState.value == AuthState.Restoring) mutableState.value = AuthState.Authenticated
            }
            is RefreshResult.Failed -> mutableState.value = AuthState.LoggedOut
        }
    }

    suspend fun login(loginId: String, password: String) {
        if (loginId.isBlank() || password.isBlank()) {
            mutableOperation.value = AuthOperationState.Error("请输入病历号和密码")
            return
        }
        mutableOperation.value = AuthOperationState.Loading
        val attemptEpoch = secureClear().snapshot.epoch
        mutableState.value = AuthState.LoggedOut
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
            val mutation = tokenVault.replaceTokens(attemptEpoch, session.access, refresh)
            if (mutation.applied) routeFor(session)
        } catch (error: CancellationException) {
            applyLoginAttemptCleanup(attemptEpoch)
            throw error
        } catch (error: Throwable) {
            applyLoginAttemptCleanup(attemptEpoch)
            mutableOperation.value = AuthOperationState.Error(error.userMessage(ApiEndpoint.AUTH_LOGIN))
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
        try {
            remote.changePassword(ChangePasswordRequestDto(oldPassword, newPassword))
        } catch (error: CancellationException) {
            applyLoggedOutCleanup(expectedEpoch = null)
            throw error
        } catch (error: Throwable) {
            mutableOperation.value = AuthOperationState.Error(error.userMessage(ApiEndpoint.AUTH_CHANGE_PASSWORD))
            return
        } finally {
            finishOperation()
        }

        val beforeLogout = tokenVault.sessionSnapshot()
        val refresh = tokenVault.readRefreshToken(beforeLogout.epoch)?.value
        withContext(NonCancellable) {
            tokenVault.clear()
            mutableState.value = AuthState.LoggedOut
            eventChannel.trySend(AuthEvent.PasswordChanged)
        }
        try {
            remote.logout(LogoutRequestDto(ClientKind.ANDROID, refresh))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 改密已成功，本地会话已经在线性化点销毁。
        }
    }

    suspend fun logout() {
        mutableOperation.value = AuthOperationState.Loading
        val beforeLogout = tokenVault.sessionSnapshot()
        val refresh = tokenVault.readRefreshToken(beforeLogout.epoch)?.value
        withContext(NonCancellable) {
            tokenVault.clear()
            mutableState.value = AuthState.LoggedOut
            mutableOperation.value = AuthOperationState.Idle
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
        val mutation = secureClear(expectedEpoch)
        if (mutation.snapshot.accessToken == null) mutableState.value = AuthState.LoggedOut
    }

    private suspend fun applyLoginAttemptCleanup(attemptEpoch: Long) {
        val current = tokenVault.sessionSnapshot()
        val cleanupEpoch = when {
            current.epoch == attemptEpoch -> attemptEpoch
            current.epoch == attemptEpoch + 1 && current.accessToken != null -> current.epoch
            else -> null
        }
        val mutation = cleanupEpoch?.let { secureClear(it) }
        if ((mutation?.snapshot ?: current).accessToken == null) {
            mutableState.value = AuthState.LoggedOut
        }
    }

    private suspend fun secureClear(expectedEpoch: Long? = null): SessionMutation =
        withContext(NonCancellable) {
            try {
                tokenVault.clear(expectedEpoch)
            } catch (_: Exception) {
                val current = tokenVault.sessionSnapshot()
                SessionMutation(applied = current.epoch != expectedEpoch, snapshot = current)
            }
        }

    private fun routeFor(session: AuthSession) {
        mutableState.value = if (session.mustChangePassword) {
            AuthState.MustChangePassword
        } else {
            AuthState.Authenticated
        }
    }

    private fun Throwable.userMessage(endpoint: ApiEndpoint): String = when (this) {
        is HttpException, is IOException, is SerializationException -> when (val failure = ApiErrorMapper.map(this, endpoint)) {
            is ApiFailure.Unauthorized -> if (endpoint == ApiEndpoint.AUTH_LOGIN) "病历号或密码错误" else failure.userMessage
            else -> failure.userMessage
        }
        is AuthContractException, is NetworkContractException -> "服务返回的登录信息不完整，请稍后重试"
        is GeneralSecurityException, is ProviderException, is ErrnoException -> "安全会话保存失败，请重新登录"
        else -> throw this
    }
}

private class AuthContractException(message: String) : IllegalStateException(message)
