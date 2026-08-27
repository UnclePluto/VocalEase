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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

sealed interface RefreshResult {
    data class Success(
        val accessToken: String,
        val epoch: Long,
        val session: AuthSession?,
    ) : RefreshResult

    data class PasswordChangeRequired(
        val accessToken: String,
        val epoch: Long,
    ) : RefreshResult

    /** 原 401 所属会话已被登出、登录或其他不可证明同源的会话变更取代。 */
    data class Superseded(val observedEpoch: Long) : RefreshResult

    data class Failed(val cause: Throwable?) : RefreshResult
}

class SessionExpiredException(cause: Throwable? = null) : Exception("登录状态已失效", cause)
class SessionChangedException : IOException("登录账号已变更，请重新操作")

fun interface RefreshRemoteDataSource {
    suspend fun refresh(request: RefreshRequestDto): AuthSession
}

class RefreshCoordinator(
    private val tokenVault: TokenVault,
    private val remote: RefreshRemoteDataSource,
    internal val sessionArbiter: SessionLifecycleArbiter = SessionLifecycleArbiter(tokenVault),
    private val beforeInvalidationPublish: suspend (SessionInvalidation) -> Unit = {},
) {
    private data class EpochOutcome(
        val sourceEpoch: Long,
        val terminalEpoch: Long,
        val result: RefreshResult,
    )

    private val mutex = Mutex()
    private val latestOutcome = AtomicReference<EpochOutcome?>(null)

    val events: Flow<SessionLifecycleEvent> = sessionArbiter.events

    fun addSessionExpiredListener(listener: (SessionInvalidation) -> Unit) {
        sessionArbiter.addSessionExpiredListener(listener)
    }

    internal fun requireTokenVault(candidate: TokenVault) {
        require(candidate === tokenVault) { "AuthRepository 与 RefreshCoordinator 必须共享同一 TokenVault" }
    }

    suspend fun refreshAfterUnauthorized(failedEpoch: Long): RefreshResult = mutex.withLock {
        val current = sessionArbiter.sessionSnapshot()
        if (current.epoch != failedEpoch) {
            latestOutcome.get()?.takeIf {
                it.sourceEpoch == failedEpoch && it.terminalEpoch == current.epoch
            }?.let {
                return@withLock it.result
            }
            return@withLock RefreshResult.Superseded(current.epoch)
        }
        latestOutcome.get()?.takeIf { it.sourceEpoch == failedEpoch }?.let { return@withLock it.result }

        val refreshLease = when (val read = sessionArbiter.mutate { readRefreshToken(failedEpoch) }) {
            is RefreshTokenRead.Available -> read.lease
            is RefreshTokenRead.Missing -> {
                val observed = sessionArbiter.sessionSnapshot()
                if (observed.epoch != failedEpoch) {
                    return@withLock latestOutcome.get()
                        ?.takeIf {
                            it.sourceEpoch == failedEpoch && it.terminalEpoch == observed.epoch
                        }
                        ?.result
                        ?: RefreshResult.Superseded(observed.epoch)
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
            sessionArbiter.mutate {
                replaceTokens(
                    expectedEpoch = failedEpoch,
                    accessToken = session.access,
                    refreshToken = session.refresh ?: refreshLease.value,
                    replacementId = replacementId,
                ).also { applied ->
                    if (applied.applied) synchronizeAppliedRefreshSession(session)
                }
            }
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
            if (session.mustChangePassword) {
                RefreshResult.PasswordChangeRequired(
                    accessToken = requireNotNull(mutation.snapshot.accessToken),
                    epoch = mutation.snapshot.epoch,
                )
            } else {
                RefreshResult.Success(
                    accessToken = requireNotNull(mutation.snapshot.accessToken),
                    epoch = mutation.snapshot.epoch,
                    session = session,
                )
            }
        } else {
            RefreshResult.Superseded(mutation.snapshot.epoch)
        }
        latestOutcome.set(EpochOutcome(failedEpoch, mutation.snapshot.epoch, result))
        result
    }

    /** 每个原请求最多因 401 刷新并重试一次；第二个 401 原样传播。 */
    internal suspend fun <T> executeAuthenticated(request: suspend (AuthRequestContext) -> T): T {
        val original = sessionArbiter.sessionSnapshot()
        if (original.accessToken == null) throw SessionExpiredException()
        return try {
            request(AuthRequestContext.Current)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!error.isUnauthorized()) throw error
            when (val refreshed = refreshAfterUnauthorized(original.epoch)) {
                is RefreshResult.Success -> request(
                    AuthRequestContext.Retry(
                        accessToken = refreshed.accessToken,
                        expectedEpoch = refreshed.epoch,
                    ),
                )
                is RefreshResult.PasswordChangeRequired -> throw SessionExpiredException()
                is RefreshResult.Superseded -> throw SessionChangedException()
                is RefreshResult.Failed -> throw SessionExpiredException(refreshed.cause)
            }
        }
    }

    suspend fun recordVaultInvalidation(
        invalidation: SessionInvalidation,
        cause: Throwable,
    ): RefreshResult = mutex.withLock {
        val current = sessionArbiter.sessionSnapshot()
        if (current.accessToken != null && current.epoch != invalidation.toEpoch) {
            return@withLock RefreshResult.Superseded(current.epoch)
        }
        latestOutcome.get()?.takeIf { it.sourceEpoch == invalidation.fromEpoch }?.let {
            return@withLock it.result
        }
        completeVaultInvalidation(invalidation.fromEpoch, invalidation, cause)
    }

    private suspend fun expireEpoch(failedEpoch: Long, cause: Throwable?): RefreshResult {
        var claim: InvalidationClaim? = null
        val mutation = withContext(NonCancellable) {
            sessionArbiter.mutate {
                try {
                    clear(failedEpoch).also { cleared ->
                        if (cleared.applied) {
                            claim = claimInvalidation(
                                SessionInvalidation(failedEpoch, cleared.snapshot.epoch),
                            )
                        }
                    }
                } catch (error: VaultInvalidatedException) {
                    claim = claimInvalidation(error.invalidation)
                    SessionMutation(applied = true, snapshot = sessionSnapshot())
                } catch (error: Throwable) {
                    if (!error.isExpectedRefreshFailure()) throw error
                    SessionMutation(applied = false, snapshot = sessionSnapshot())
                }
            }
        }
        if (!mutation.applied) {
            return RefreshResult.Superseded(mutation.snapshot.epoch)
        }
        val result = RefreshResult.Failed(cause)
        latestOutcome.set(EpochOutcome(failedEpoch, mutation.snapshot.epoch, result))
        check(!mutation.applied || claim != null) { "会话清理成功但未形成失效裁决" }
        return result
    }

    private suspend fun completeVaultInvalidation(
        failedEpoch: Long,
        invalidation: SessionInvalidation,
        cause: Throwable,
    ): RefreshResult {
        check(invalidation.fromEpoch == failedEpoch) { "vault 失效起点与请求 epoch 不一致" }
        latestOutcome.get()?.takeIf { it.sourceEpoch == failedEpoch }?.let { return it.result }
        beforeInvalidationPublish(invalidation)
        val claim = sessionArbiter.mutate { claimInvalidation(invalidation) }
        if (claim is InvalidationClaim.Superseded) {
            return RefreshResult.Superseded(claim.snapshot.epoch)
        }
        latestOutcome.get()?.takeIf { it.sourceEpoch == failedEpoch }?.let { return it.result }
        val result = RefreshResult.Failed(cause)
        latestOutcome.set(EpochOutcome(failedEpoch, invalidation.toEpoch, result))
        return result
    }

    private suspend fun clearRefreshAttempt(failedEpoch: Long, replacementId: String) {
        withContext(NonCancellable) {
            sessionArbiter.mutate {
                val current = sessionSnapshot()
                val cleanupEpoch = when {
                    current.epoch == failedEpoch -> failedEpoch
                    current.replacementId == replacementId -> current.epoch
                    else -> null
                }
                cleanupEpoch?.let {
                    try {
                        clear(it)
                    } catch (_: VaultInvalidatedException) {
                        // 本次 replacement/clear 已在线性化点失效，保留原取消异常。
                    }
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
