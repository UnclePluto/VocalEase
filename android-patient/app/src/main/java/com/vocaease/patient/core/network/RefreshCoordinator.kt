package com.vocaease.patient.core.network

import android.system.ErrnoException
import com.vocaease.patient.core.network.dto.AuthSession
import com.vocaease.patient.core.network.dto.ClientKind
import com.vocaease.patient.core.network.dto.RefreshRequestDto
import com.vocaease.patient.core.security.SessionMutation
import com.vocaease.patient.core.security.TokenVault
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

sealed interface SessionLifecycleEvent {
    data object SessionExpired : SessionLifecycleEvent
}

sealed interface RefreshResult {
    data class Success(
        val accessToken: String,
        val epoch: Long,
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
    private data class EpochOutcome(val sourceEpoch: Long, val result: RefreshResult)

    private val mutex = Mutex()
    private val latestOutcome = AtomicReference<EpochOutcome?>(null)
    private val mutableEvents = MutableSharedFlow<SessionLifecycleEvent>(extraBufferCapacity = 1)
    private val sessionExpiredListeners = CopyOnWriteArrayList<() -> Unit>()

    val events: SharedFlow<SessionLifecycleEvent> = mutableEvents.asSharedFlow()

    fun addSessionExpiredListener(listener: () -> Unit) {
        sessionExpiredListeners += listener
    }

    suspend fun refreshAfterUnauthorized(failedEpoch: Long): RefreshResult = mutex.withLock {
        latestOutcome.get()?.takeIf { it.sourceEpoch == failedEpoch }?.let { return@withLock it.result }

        val current = tokenVault.sessionSnapshot()
        if (current.epoch != failedEpoch) {
            val result = current.accessToken?.let {
                RefreshResult.Success(it, current.epoch, session = null)
            } ?: RefreshResult.Failed(cause = null)
            latestOutcome.set(EpochOutcome(failedEpoch, result))
            return@withLock result
        }

        val refreshLease = tokenVault.readRefreshToken(failedEpoch)
            ?: return@withLock expireEpoch(failedEpoch, cause = null)
        val session = try {
            remote.refresh(
                RefreshRequestDto(
                    clientKind = ClientKind.ANDROID,
                    refresh = refreshLease.value,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error.isExpectedRefreshFailure()) return@withLock expireEpoch(failedEpoch, error)
            throw error
        }

        val mutation = try {
            tokenVault.replaceTokens(
                expectedEpoch = failedEpoch,
                accessToken = session.access,
                refreshToken = session.refresh ?: refreshLease.value,
            )
        } catch (error: CancellationException) {
            clearRefreshAttempt(failedEpoch)
            throw error
        } catch (error: Throwable) {
            if (error.isExpectedRefreshFailure()) return@withLock expireEpoch(failedEpoch, error)
            throw error
        }

        val result = if (mutation.applied) {
            RefreshResult.Success(
                accessToken = requireNotNull(mutation.snapshot.accessToken),
                epoch = mutation.snapshot.epoch,
                session = session,
            )
        } else {
            mutation.snapshot.accessToken?.let {
                RefreshResult.Success(it, mutation.snapshot.epoch, session = null)
            } ?: RefreshResult.Failed(cause = null)
        }
        latestOutcome.set(EpochOutcome(failedEpoch, result))
        result
    }

    /** 每个原请求最多因 401 刷新并重试一次；第二个 401 原样传播。 */
    suspend fun <T> executeAuthenticated(request: suspend () -> T): T {
        val original = tokenVault.sessionSnapshot()
        if (original.accessToken == null) throw SessionExpiredException()
        return try {
            request()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!error.isUnauthorized()) throw error
            when (val refreshed = refreshAfterUnauthorized(original.epoch)) {
                is RefreshResult.Success -> request()
                is RefreshResult.Failed -> throw SessionExpiredException(refreshed.cause)
            }
        }
    }

    private suspend fun expireEpoch(failedEpoch: Long, cause: Throwable?): RefreshResult.Failed {
        val mutation = try {
            withContext(NonCancellable) { tokenVault.clear(failedEpoch) }
        } catch (error: Throwable) {
            if (!error.isExpectedRefreshFailure()) throw error
            val current = tokenVault.sessionSnapshot()
            SessionMutation(applied = current.epoch != failedEpoch, snapshot = current)
        }
        val result = RefreshResult.Failed(cause)
        latestOutcome.set(EpochOutcome(failedEpoch, result))
        if (mutation.applied) {
            mutableEvents.emit(SessionLifecycleEvent.SessionExpired)
            sessionExpiredListeners.forEach { listener -> listener() }
        }
        return result
    }

    private suspend fun clearRefreshAttempt(failedEpoch: Long) {
        withContext(NonCancellable) {
            val current = tokenVault.sessionSnapshot()
            val cleanupEpoch = when {
                current.epoch == failedEpoch -> failedEpoch
                current.epoch == failedEpoch + 1 && current.accessToken != null -> current.epoch
                else -> null
            }
            cleanupEpoch?.let { tokenVault.clear(it) }
        }
    }

    private fun Throwable.isUnauthorized(): Boolean = this is HttpException && code() == 401

    private fun Throwable.isExpectedRefreshFailure(): Boolean =
        this is HttpException ||
            this is IOException ||
            this is SerializationException ||
            this is NetworkContractException ||
            this is GeneralSecurityException ||
            this is ProviderException ||
            this is ErrnoException
}
