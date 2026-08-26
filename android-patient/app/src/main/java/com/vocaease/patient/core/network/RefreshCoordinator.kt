package com.vocaease.patient.core.network

import android.system.ErrnoException
import com.vocaease.patient.core.network.dto.AuthSession
import com.vocaease.patient.core.network.dto.ClientKind
import com.vocaease.patient.core.network.dto.RefreshRequestDto
import com.vocaease.patient.core.security.RefreshTokenRead
import com.vocaease.patient.core.security.SessionInvalidation
import com.vocaease.patient.core.security.SessionMutation
import com.vocaease.patient.core.security.TokenVault
import com.vocaease.patient.core.security.VaultInvalidatedException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.UUID
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
        val current = tokenVault.sessionSnapshot()
        if (current.epoch != failedEpoch) {
            current.accessToken?.let {
                return@withLock RefreshResult.Success(it, current.epoch, session = null)
            }
            latestOutcome.get()?.takeIf { it.sourceEpoch == failedEpoch }?.let {
                return@withLock it.result
            }
            return@withLock RefreshResult.Failed(cause = null)
        }
        latestOutcome.get()?.takeIf { it.sourceEpoch == failedEpoch }?.let { return@withLock it.result }

        val refreshLease = when (val read = tokenVault.readRefreshToken(failedEpoch)) {
            is RefreshTokenRead.Available -> read.lease
            is RefreshTokenRead.Missing -> {
                val observed = tokenVault.sessionSnapshot()
                if (observed.epoch != failedEpoch) {
                    return@withLock observed.accessToken?.let {
                        RefreshResult.Success(it, observed.epoch, session = null)
                    } ?: latestOutcome.get()
                        ?.takeIf { it.sourceEpoch == failedEpoch }
                        ?.result
                        ?: RefreshResult.Failed(cause = null)
                }
                return@withLock expireEpoch(failedEpoch, cause = null)
            }
            is RefreshTokenRead.Invalidated -> return@withLock completeVaultInvalidation(
                failedEpoch = failedEpoch,
                invalidation = read.invalidation,
                cause = read.cause,
            )
        }
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

        val replacementId = UUID.randomUUID().toString()
        val mutation = try {
            tokenVault.replaceTokens(
                expectedEpoch = failedEpoch,
                accessToken = session.access,
                refreshToken = session.refresh ?: refreshLease.value,
                replacementId = replacementId,
            )
        } catch (error: CancellationException) {
            clearRefreshAttempt(failedEpoch, replacementId)
            throw error
        } catch (error: VaultInvalidatedException) {
            return@withLock completeVaultInvalidation(
                failedEpoch = failedEpoch,
                invalidation = error.invalidation,
                cause = error.cause ?: error,
            )
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

    suspend fun recordVaultInvalidation(
        invalidation: SessionInvalidation,
        cause: Throwable,
    ): RefreshResult = mutex.withLock {
        val current = tokenVault.sessionSnapshot()
        if (current.accessToken != null && current.epoch != invalidation.toEpoch) {
            return@withLock RefreshResult.Success(current.accessToken, current.epoch, session = null)
        }
        latestOutcome.get()?.takeIf { it.sourceEpoch == invalidation.fromEpoch }?.let {
            return@withLock it.result
        }
        completeVaultInvalidation(invalidation.fromEpoch, invalidation, cause)
    }

    private suspend fun expireEpoch(failedEpoch: Long, cause: Throwable?): RefreshResult {
        val mutation = try {
            withContext(NonCancellable) { tokenVault.clear(failedEpoch) }
        } catch (error: VaultInvalidatedException) {
            return completeVaultInvalidation(
                failedEpoch = failedEpoch,
                invalidation = error.invalidation,
                cause = cause ?: error.cause ?: error,
            )
        } catch (error: Throwable) {
            if (!error.isExpectedRefreshFailure()) throw error
            SessionMutation(applied = false, snapshot = tokenVault.sessionSnapshot())
        }
        if (!mutation.applied && mutation.snapshot.accessToken != null) {
            return RefreshResult.Success(
                mutation.snapshot.accessToken,
                mutation.snapshot.epoch,
                session = null,
            )
        }
        val result = RefreshResult.Failed(cause)
        latestOutcome.set(EpochOutcome(failedEpoch, result))
        if (mutation.applied) {
            mutableEvents.emit(SessionLifecycleEvent.SessionExpired)
            sessionExpiredListeners.forEach { listener -> listener() }
        }
        return result
    }

    private suspend fun completeVaultInvalidation(
        failedEpoch: Long,
        invalidation: SessionInvalidation,
        cause: Throwable,
    ): RefreshResult {
        check(invalidation.fromEpoch == failedEpoch) { "vault 失效起点与请求 epoch 不一致" }
        val current = tokenVault.sessionSnapshot()
        if (current.epoch != invalidation.toEpoch || current.accessToken != null) {
            return current.accessToken?.let {
                RefreshResult.Success(it, current.epoch, session = null)
            } ?: latestOutcome.get()
                ?.takeIf { it.sourceEpoch == failedEpoch }
                ?.result
                ?: RefreshResult.Failed(cause)
        }
        latestOutcome.get()?.takeIf { it.sourceEpoch == failedEpoch }?.let { return it.result }
        val result = RefreshResult.Failed(cause)
        latestOutcome.set(EpochOutcome(failedEpoch, result))
        mutableEvents.emit(SessionLifecycleEvent.SessionExpired)
        sessionExpiredListeners.forEach { listener -> listener() }
        return result
    }

    private suspend fun clearRefreshAttempt(failedEpoch: Long, replacementId: String) {
        withContext(NonCancellable) {
            val current = tokenVault.sessionSnapshot()
            val cleanupEpoch = when {
                current.epoch == failedEpoch -> failedEpoch
                current.replacementId == replacementId -> current.epoch
                else -> null
            }
            cleanupEpoch?.let {
                try {
                    tokenVault.clear(it)
                } catch (_: VaultInvalidatedException) {
                    // 本次 replacement/clear 已在线性化点失效，保留原取消异常。
                }
            }
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
            this is ErrnoException ||
            this is VaultInvalidatedException
}
