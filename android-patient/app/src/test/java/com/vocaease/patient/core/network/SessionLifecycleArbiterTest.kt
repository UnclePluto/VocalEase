package com.vocaease.patient.core.network

import com.vocaease.patient.core.security.RefreshTokenLease
import com.vocaease.patient.core.security.RefreshTokenRead
import com.vocaease.patient.core.security.SessionInvalidation
import com.vocaease.patient.core.security.SessionMutation
import com.vocaease.patient.core.security.SessionSnapshot
import com.vocaease.patient.core.security.TokenVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionLifecycleArbiterTest {
    @Test
    fun `慢消费者仍按序收到连续有效失效且消费后新订阅者不重放`() = runBlocking {
        val vault = ArbiterTokenVault()
        val arbiter = SessionLifecycleArbiter(vault)
        val firstReceived = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            val events = mutableListOf<SessionLifecycleEvent>()
            arbiter.events.take(3).collect { event ->
                events += event
                if (events.size == 1) {
                    firstReceived.complete(Unit)
                    releaseConsumer.await()
                }
            }
            events
        }

        val first = expireCurrentSession(arbiter)
        withTimeout(1_000) { firstReceived.await() }
        installSession(arbiter, "access-2")
        val second = expireCurrentSession(arbiter)
        installSession(arbiter, "access-3")
        val third = expireCurrentSession(arbiter)
        releaseConsumer.complete(Unit)

        val delivered = withTimeout(1_000) { collected.await() }
            .map { (it as SessionLifecycleEvent.SessionExpired).invalidation }
        assertEquals(listOf(first, second, third), delivered)
        assertNull(withTimeoutOrNull(100) { arbiter.events.first() })
    }

    private suspend fun expireCurrentSession(arbiter: SessionLifecycleArbiter): SessionInvalidation =
        arbiter.mutate {
            val fromEpoch = sessionSnapshot().epoch
            val mutation = clear(fromEpoch)
            val invalidation = SessionInvalidation(fromEpoch, mutation.snapshot.epoch)
            assertEquals(InvalidationClaim.Published, claimInvalidation(invalidation))
            invalidation
        }

    private suspend fun installSession(arbiter: SessionLifecycleArbiter, accessToken: String) {
        arbiter.mutate {
            val epoch = sessionSnapshot().epoch
            val mutation = replaceTokens(
                expectedEpoch = epoch,
                accessToken = accessToken,
                refreshToken = "refresh-$epoch",
                replacementId = "replacement-$epoch",
            )
            assertEquals(true, mutation.applied)
        }
    }
}

private class ArbiterTokenVault : TokenVault {
    private var snapshot = SessionSnapshot(accessToken = "access-0", epoch = 0)
    private var refreshToken: String? = "refresh-0"

    override fun sessionSnapshot(): SessionSnapshot = snapshot

    override suspend fun readRefreshToken(expectedEpoch: Long): RefreshTokenRead =
        if (snapshot.epoch == expectedEpoch && refreshToken != null) {
            RefreshTokenRead.Available(RefreshTokenLease(requireNotNull(refreshToken), expectedEpoch))
        } else {
            RefreshTokenRead.Missing(snapshot.epoch)
        }

    override suspend fun replaceTokens(
        expectedEpoch: Long,
        accessToken: String,
        refreshToken: String,
        replacementId: String,
    ): SessionMutation {
        if (snapshot.epoch != expectedEpoch) return SessionMutation(false, snapshot)
        this.refreshToken = refreshToken
        snapshot = SessionSnapshot(accessToken, snapshot.epoch + 1, replacementId)
        return SessionMutation(true, snapshot)
    }

    override suspend fun clear(expectedEpoch: Long?): SessionMutation {
        if (expectedEpoch != null && snapshot.epoch != expectedEpoch) return SessionMutation(false, snapshot)
        refreshToken = null
        snapshot = SessionSnapshot(accessToken = null, epoch = snapshot.epoch + 1)
        return SessionMutation(true, snapshot)
    }
}
