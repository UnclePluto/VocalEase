package com.vocaease.patient.core.network

import android.system.ErrnoException
import com.vocaease.patient.core.network.dto.AuthSession
import com.vocaease.patient.core.network.dto.ClientKind
import com.vocaease.patient.core.network.dto.RefreshRequestDto
import com.vocaease.patient.core.security.TokenVault
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

sealed interface SessionLifecycleEvent {
    data object SessionExpired : SessionLifecycleEvent
}

sealed interface RefreshResult {
    data class Success(
        val accessToken: String,
        val generation: Long,
        val session: AuthSession?,
    ) : RefreshResult

    data class Failed(val cause: Throwable?) : RefreshResult
}

class SessionExpiredException(cause: Throwable? = null) : Exception("登录状态已失效", cause)

fun interface RefreshRemoteDataSource {
    suspend fun refresh(request: RefreshRequestDto): AuthSession
}

class RefreshCoordinator(
    private val tokenVault: TokenVault,
    private val remote: RefreshRemoteDataSource,
) {
    private val mutex = Mutex()
    private val latestSuccess = AtomicReference<RefreshResult.Success?>(null)
    private val mutableEvents = MutableSharedFlow<SessionLifecycleEvent>(extraBufferCapacity = 1)
    private val sessionExpiredListeners = CopyOnWriteArrayList<() -> Unit>()

    val events: SharedFlow<SessionLifecycleEvent> = mutableEvents.asSharedFlow()

    fun addSessionExpiredListener(listener: () -> Unit) {
        sessionExpiredListeners += listener
    }

    suspend fun refreshAfterUnauthorized(failedGeneration: Long): RefreshResult = mutex.withLock {
        val current = tokenVault.accessSnapshot()
        if (current.generation != failedGeneration && current.value != null) {
            return@withLock latestSuccess.get()
                ?.takeIf { it.generation == current.generation }
                ?: RefreshResult.Success(current.value, current.generation, session = null)
        }

        val storedRefresh = tokenVault.readRefreshToken()
            ?: return@withLock expireSession(cause = null)
        try {
            val session = remote.refresh(
                RefreshRequestDto(
                    clientKind = ClientKind.ANDROID,
                    refresh = storedRefresh,
                ),
            )
            val rotatedRefresh = session.refresh ?: storedRefresh
            tokenVault.replaceTokens(session.access, rotatedRefresh)
            val updated = tokenVault.accessSnapshot()
            RefreshResult.Success(
                accessToken = requireNotNull(updated.value),
                generation = updated.generation,
                session = session,
            ).also(latestSuccess::set)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error.isExpectedRefreshFailure()) expireSession(error) else throw error
        }
    }

    /** 每个原请求最多因 401 刷新并重试一次；第二个 401 原样返回。 */
    suspend fun <T> executeAuthenticated(
        request: suspend (accessToken: String) -> T,
    ): T {
        val original = tokenVault.accessSnapshot()
        val access = original.value ?: throw SessionExpiredException()
        return try {
            request(access)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!error.isUnauthorized()) throw error
            when (val refreshed = refreshAfterUnauthorized(original.generation)) {
                is RefreshResult.Success -> request(refreshed.accessToken)
                is RefreshResult.Failed -> throw SessionExpiredException(refreshed.cause)
            }
        }
    }

    private suspend fun expireSession(cause: Throwable?): RefreshResult.Failed {
        tokenVault.clear()
        latestSuccess.set(null)
        mutableEvents.emit(SessionLifecycleEvent.SessionExpired)
        sessionExpiredListeners.forEach { listener -> listener() }
        return RefreshResult.Failed(cause)
    }

    private fun Throwable.isUnauthorized(): Boolean = this is HttpException && code() == 401

    private fun Throwable.isExpectedRefreshFailure(): Boolean =
        this is HttpException ||
            this is IOException ||
            this is SerializationException ||
            this is NetworkContractException ||
            this is GeneralSecurityException ||
            this is ErrnoException
}
