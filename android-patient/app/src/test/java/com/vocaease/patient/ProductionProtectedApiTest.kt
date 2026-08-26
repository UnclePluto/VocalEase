package com.vocaease.patient

import com.vocaease.patient.core.network.SessionExpiredException
import com.vocaease.patient.core.network.SessionLifecycleEvent
import com.vocaease.patient.core.security.RefreshTokenLease
import com.vocaease.patient.core.security.RefreshTokenRead
import com.vocaease.patient.core.security.SessionMutation
import com.vocaease.patient.core.security.SessionSnapshot
import com.vocaease.patient.core.security.TokenVault
import com.vocaease.patient.feature.auth.AuthEvent
import com.vocaease.patient.feature.auth.AuthState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProductionProtectedApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `生产患者 API 边界让二十个真实 401 只刷新一次且每请求只重试一次`() = runBlocking {
        val firstAttempts = CountDownLatch(20)
        val expiredCalls = AtomicInteger()
        val retriedCalls = AtomicInteger()
        val refreshCalls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/auth/refresh/" -> {
                    refreshCalls.incrementAndGet()
                    json(200, fixture("fixtures/refresh.json"))
                }
                "/api/v1/patient/me/" -> when (request.getHeader("Authorization")) {
                    "Bearer expired-access" -> {
                        expiredCalls.incrementAndGet()
                        firstAttempts.countDown()
                        assertTrue("20 个首请求应全部到达服务端", firstAttempts.await(5, TimeUnit.SECONDS))
                        json(401, unauthorized())
                    }
                    "Bearer next-access-secret" -> {
                        retriedCalls.incrementAndGet()
                        json(200, fixture("fixtures/patient_me.json"))
                    }
                    else -> json(401, unauthorized())
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val graph = createProductionSessionGraph(
            baseUrl = server.url("/").toString(),
            tokenVault = ProductionTokenVault("expired-access", "stored-refresh"),
        )

        val results = List(20) {
            async(Dispatchers.IO) { graph.patientApi.patientMe().data.medicalRecordNo }
        }.awaitAll()

        assertTrue(results.all { it == "MR-2026-001" })
        assertEquals(20, expiredCalls.get())
        assertEquals(20, retriedCalls.get())
        assertEquals(1, refreshCalls.get())
    }

    @Test
    fun `生产患者 API 刷新失败会实际驱动认证仓库过期`() = runBlocking {
        val refreshCalls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/auth/refresh/" -> {
                    refreshCalls.incrementAndGet()
                    json(401, unauthorized())
                }
                "/api/v1/patient/me/" -> json(401, unauthorized())
                else -> MockResponse().setResponseCode(404)
            }
        }
        val graph = createProductionSessionGraph(
            baseUrl = server.url("/").toString(),
            tokenVault = ProductionTokenVault("expired-access", "stored-refresh"),
        )
        graph.authRepository.restoreSession()
        val event = async { graph.authRepository.events.first() }
        val lifecycleEvent = async { graph.sessionEvents.first() }

        assertThrows(SessionExpiredException::class.java) {
            runBlocking { graph.patientApi.patientMe() }
        }

        assertEquals(1, refreshCalls.get())
        assertEquals(AuthState.LoggedOut, graph.authRepository.state.value)
        assertEquals(AuthEvent.SessionExpired, event.await())
        assertEquals(SessionLifecycleEvent.SessionExpired, lifecycleEvent.await())
    }

    private fun json(status: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun unauthorized(): String =
        """{"code":"unauthorized","message":"unauthorized","data":null,"request_id":"auth-401"}"""

    private fun fixture(path: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(path),
    ).bufferedReader().use { it.readText() }
}

private class ProductionTokenVault(
    accessToken: String?,
    private var refreshToken: String?,
) : TokenVault {
    private var snapshot = SessionSnapshot(accessToken, 1)

    override fun sessionSnapshot(): SessionSnapshot = synchronized(this) { snapshot }
    override suspend fun readRefreshToken(expectedEpoch: Long): RefreshTokenRead = synchronized(this) {
        if (snapshot.epoch == expectedEpoch && refreshToken != null) {
            RefreshTokenRead.Available(RefreshTokenLease(requireNotNull(refreshToken), expectedEpoch))
        } else {
            RefreshTokenRead.Missing(snapshot.epoch)
        }
    }
    override suspend fun replaceTokens(
        expectedEpoch: Long,
        accessToken: String,
        refreshToken: String,
        replacementId: String,
    ): SessionMutation =
        synchronized(this) {
            if (snapshot.epoch != expectedEpoch) return@synchronized SessionMutation(false, snapshot)
            this.refreshToken = refreshToken
            snapshot = SessionSnapshot(accessToken, snapshot.epoch + 1, replacementId)
            SessionMutation(true, snapshot)
        }
    override suspend fun clear(expectedEpoch: Long?): SessionMutation = synchronized(this) {
        if (expectedEpoch != null && snapshot.epoch != expectedEpoch) {
            SessionMutation(false, snapshot)
        } else {
            refreshToken = null
            snapshot = SessionSnapshot(null, snapshot.epoch + 1)
            SessionMutation(true, snapshot)
        }
    }
}
