package com.vocaease.patient.core.network

import com.vocaease.patient.core.database.AuthenticatedAccountLease
import com.vocaease.patient.core.database.StaleAccountScopeException
import com.vocaease.patient.core.network.dto.AuthSession
import com.vocaease.patient.core.security.RefreshTokenRead
import com.vocaease.patient.core.security.SessionInvalidation
import com.vocaease.patient.core.security.SessionMutation
import com.vocaease.patient.core.security.SessionSnapshot
import com.vocaease.patient.core.security.TokenVault
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SessionLifecycleEvent {
    data class SessionExpired(val invalidation: SessionInvalidation) : SessionLifecycleEvent
}

internal sealed interface InvalidationClaim {
    data object Published : InvalidationClaim
    data object AlreadyPublished : InvalidationClaim
    data class Superseded(val snapshot: SessionSnapshot) : InvalidationClaim
}

/**
 * 会话写入、认证状态迁移与失效事件的共享线性化边界。
 *
 * 事件使用进程生命周期内的无限有序 Channel：trySend 不会在会话锁内挂起，已裁决的有效失效
 * 不会因慢消费者丢失；同一失效只入队一次，消费后也不会向新订阅者重放。此 Flow 是交给未来
 * Task10 上传协调器的单消费者边界；UI 只消费 AuthRepository.events，不参与此队列竞争。
 */
class SessionLifecycleArbiter(
    private val tokenVault: TokenVault,
) {
    private val mutationMutex = Mutex()
    private val eventQueue = Channel<SessionLifecycleEvent>(capacity = Channel.UNLIMITED)
    private val sessionExpiredListeners = CopyOnWriteArrayList<(SessionInvalidation) -> Unit>()
    private val refreshSessionAppliedListeners = CopyOnWriteArrayList<(AuthSession) -> Unit>()
    private var lastPublishedInvalidation: SessionInvalidation? = null
    @Volatile private var authenticatedAccountLease: AuthenticatedAccountLease? = null
    private var pendingAccountIncarnationId: String? = null

    /** 单消费者、进程生命周期、按裁决顺序交付的会话失效队列。 */
    val events: Flow<SessionLifecycleEvent> = eventQueue.receiveAsFlow()

    fun sessionSnapshot(): SessionSnapshot = tokenVault.sessionSnapshot()

    internal fun currentAuthenticatedAccountLease(): AuthenticatedAccountLease? = authenticatedAccountLease

    fun addSessionExpiredListener(listener: (SessionInvalidation) -> Unit) {
        sessionExpiredListeners += listener
    }

    internal fun addRefreshSessionAppliedListener(listener: (AuthSession) -> Unit) {
        refreshSessionAppliedListeners += listener
    }

    internal suspend fun <T> mutate(block: suspend MutationScope.() -> T): T =
        mutationMutex.withLock { MutationScope().block() }

    /** 存储操作与登出/换号共用同一线性化边界；获锁等待保持可取消。 */
    internal suspend fun <T> withAuthenticatedAccountLease(
        expected: AuthenticatedAccountLease,
        operation: suspend () -> T,
    ): T = mutationMutex.withLock {
        if (authenticatedAccountLease !== expected) throw StaleAccountScopeException()
        operation()
    }

    internal inner class MutationScope internal constructor() {
        fun sessionSnapshot(): SessionSnapshot = tokenVault.sessionSnapshot()

        suspend fun readRefreshToken(expectedEpoch: Long): RefreshTokenRead =
            tokenVault.readRefreshToken(expectedEpoch)

        suspend fun replaceTokens(
            expectedEpoch: Long,
            accessToken: String,
            refreshToken: String,
            replacementId: String,
        ): SessionMutation = tokenVault.replaceTokens(
            expectedEpoch = expectedEpoch,
            accessToken = accessToken,
            refreshToken = refreshToken,
            replacementId = replacementId,
        )

        suspend fun clear(expectedEpoch: Long? = null): SessionMutation = tokenVault.clear(expectedEpoch).also {
            if (it.applied) revokeAuthenticatedAccount()
        }

        fun beginAccountAuthentication(incarnationId: String) {
            require(incarnationId.isNotBlank())
            authenticatedAccountLease = null
            pendingAccountIncarnationId = incarnationId
        }

        fun publishAuthenticatedAccount(patientId: String, incarnationId: String): AuthenticatedAccountLease? {
            if (pendingAccountIncarnationId != incarnationId || sessionSnapshot().accessToken == null) return null
            return AuthenticatedAccountLease(patientId, incarnationId).also {
                authenticatedAccountLease = it
                pendingAccountIncarnationId = null
            }
        }

        fun isPendingAccountAuthentication(incarnationId: String): Boolean =
            pendingAccountIncarnationId == incarnationId

        fun revokeAuthenticatedAccount() {
            authenticatedAccountLease = null
            pendingAccountIncarnationId = null
        }

        /** 必须紧跟成功的 token CAS 在同一会话锁内调用。 */
        fun synchronizeAppliedRefreshSession(session: AuthSession) {
            if (session.mustChangePassword) revokeAuthenticatedAccount()
            refreshSessionAppliedListeners.forEach { listener -> listener(session) }
        }

        fun claimInvalidation(invalidation: SessionInvalidation): InvalidationClaim {
            val current = tokenVault.sessionSnapshot()
            if (current.epoch != invalidation.toEpoch || current.accessToken != null) {
                return InvalidationClaim.Superseded(current)
            }
            if (lastPublishedInvalidation == invalidation) return InvalidationClaim.AlreadyPublished

            lastPublishedInvalidation = invalidation
            revokeAuthenticatedAccount()
            sessionExpiredListeners.forEach { listener -> listener(invalidation) }
            check(eventQueue.trySend(SessionLifecycleEvent.SessionExpired(invalidation)).isSuccess) {
                "会话生命周期事件队列不可用"
            }
            return InvalidationClaim.Published
        }
    }
}
