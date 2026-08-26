package com.vocaease.patient.feature.auth

import com.vocaease.patient.core.network.ApiEndpoint
import com.vocaease.patient.core.network.ApiErrorMapper
import com.vocaease.patient.core.network.ApiFailure
import com.vocaease.patient.core.network.RefreshCoordinator
import com.vocaease.patient.core.network.RefreshRemoteDataSource
import com.vocaease.patient.core.network.RefreshResult
import com.vocaease.patient.core.network.VocaEaseApi
import com.vocaease.patient.core.network.dto.AccountRole
import com.vocaease.patient.core.network.dto.AuthSession
import com.vocaease.patient.core.network.dto.ChangePasswordRequestDto
import com.vocaease.patient.core.network.dto.ClientKind
import com.vocaease.patient.core.network.dto.LoginRequestDto
import com.vocaease.patient.core.network.dto.LogoutRequestDto
import com.vocaease.patient.core.network.dto.RefreshRequestDto
import com.vocaease.patient.core.network.dto.toDomain
import com.vocaease.patient.core.security.TokenVault
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val api: VocaEaseApi,
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
        mutableState.value = AuthState.Restoring
        mutableOperation.value = AuthOperationState.Idle
        val current = tokenVault.accessSnapshot()
        if (current.value != null) {
            mutableState.value = AuthState.Authenticated
            return
        }
        val refresh = tokenVault.readRefreshToken()
        if (refresh == null) {
            mutableState.value = AuthState.LoggedOut
            return
        }
        when (val result = refreshCoordinator.refreshAfterUnauthorized(current.generation)) {
            is RefreshResult.Success -> routeFor(requireNotNull(result.session))
            is RefreshResult.Failed -> {
                mutableState.value = AuthState.LoggedOut
            }
        }
    }

    suspend fun login(loginId: String, password: String) {
        if (loginId.isBlank() || password.isBlank()) {
            mutableOperation.value = AuthOperationState.Error("请输入病历号和密码")
            return
        }
        mutableOperation.value = AuthOperationState.Loading
        try {
            val session = remote.login(
                LoginRequestDto(
                    loginId = loginId.trim(),
                    password = password,
                    clientKind = ClientKind.ANDROID,
                    rememberMe = false,
                ),
            )
            if (session.role != AccountRole.PATIENT) {
                throw AuthContractException("仅支持患者账号登录")
            }
            val refresh = session.refresh
                ?: throw AuthContractException("安卓登录响应缺少 refresh token")
            tokenVault.replaceTokens(session.access, refresh)
            routeFor(session)
            mutableOperation.value = AuthOperationState.Idle
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = error.userMessage(ApiEndpoint.AUTH_LOGIN)
            tokenVault.clear()
            mutableState.value = AuthState.LoggedOut
            mutableOperation.value = AuthOperationState.Error(message)
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
            throw error
        } catch (error: Throwable) {
            mutableOperation.value = AuthOperationState.Error(error.userMessage(ApiEndpoint.AUTH_CHANGE_PASSWORD))
            return
        }

        val refresh = tokenVault.readRefreshToken()
        var cancellation: CancellationException? = null
        try {
            remote.logout(LogoutRequestDto(ClientKind.ANDROID, refresh))
        } catch (error: CancellationException) {
            cancellation = error
        } catch (_: Exception) {
            // 改密已成功；远端登出失败不能阻止本地凭据销毁。
        } finally {
            tokenVault.clear()
            mutableState.value = AuthState.LoggedOut
            mutableOperation.value = AuthOperationState.Idle
            eventChannel.send(AuthEvent.PasswordChanged)
        }
        cancellation?.let { throw it }
    }

    suspend fun logout() {
        val refresh = tokenVault.readRefreshToken()
        var cancellation: CancellationException? = null
        try {
            remote.logout(LogoutRequestDto(ClientKind.ANDROID, refresh))
        } catch (error: CancellationException) {
            cancellation = error
        } catch (_: Exception) {
            // 本地登出必须完成。
        } finally {
            tokenVault.clear()
            mutableState.value = AuthState.LoggedOut
            mutableOperation.value = AuthOperationState.Idle
        }
        cancellation?.let { throw it }
    }

    fun dismissError() {
        mutableOperation.value = AuthOperationState.Idle
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
        is AuthContractException -> "服务返回的登录信息不完整，请稍后重试"
        else -> throw this
    }
}

private class AuthContractException(message: String) : IllegalStateException(message)
