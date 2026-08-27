package com.vocaease.patient

import com.vocaease.patient.core.network.SessionExpiredException
import com.vocaease.patient.core.network.SessionChangedException
import com.vocaease.patient.core.network.SessionLifecycleEvent
import com.vocaease.patient.core.network.AuthInterceptor
import com.vocaease.patient.core.network.NetworkModule
import com.vocaease.patient.core.network.dto.CreateSessionRequestDto
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
import okhttp3.Interceptor
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
    fun `刷新CAS完成后切换患者时旧请求在线性化点终止且绝不发送新患者token`() = runBlocking {
        val vault = ProductionTokenVault("expired-a-access", "stored-a-refresh")
        val retryReachedAuthBoundary = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val protectedBoundaryCalls = AtomicInteger()
        val bSideEffects = AtomicInteger()
        val createAuthorizations = mutableListOf<String?>()
        val retryGate = Interceptor { chain ->
            if (
                chain.request().url.encodedPath == "/api/v1/patient/singing-sessions/" &&
                protectedBoundaryCalls.incrementAndGet() == 2
            ) {
                retryReachedAuthBoundary.countDown()
                check(releaseRetry.await(5, TimeUnit.SECONDS)) { "重试 gate 未获释放" }
            }
            chain.proceed(chain.request())
        }
        val client = NetworkModule.createAuthenticatedHttpClient(vault)
            .newBuilder()
            .apply { interceptors().add(0, retryGate) }
            .build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/patient/singing-sessions/" -> {
                    val authorization = request.getHeader("Authorization")
                    synchronized(createAuthorizations) { createAuthorizations += authorization }
                    when (authorization) {
                        "Bearer expired-a-access" -> json(401, unauthorized())
                        "Bearer b-access" -> {
                            bSideEffects.incrementAndGet()
                            json(200, fixture("fixtures/session.json"))
                        }
                        "Bearer refreshed-a-access" -> json(200, fixture("fixtures/session.json"))
                        else -> json(401, unauthorized())
                    }
                }
                "/api/v1/auth/refresh/" -> json(
                    200,
                    fixture("fixtures/refresh.json")
                        .replace("next-access-secret", "refreshed-a-access"),
                )
                "/api/v1/auth/logout/" -> json(200, fixture("fixtures/empty.json"))
                "/api/v1/auth/login/" -> json(
                    200,
                    fixture("fixtures/login.json")
                        .replace("access-secret", "b-access")
                        .replace("refresh-secret", "b-refresh"),
                )
                "/api/v1/patient/me/" -> json(
                    200,
                    fixture("fixtures/patient_me.json").replace(
                        "11111111-1111-4111-8111-111111111111",
                        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    ),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val graph = createProductionSessionGraph(
            baseUrl = server.url("/").toString(),
            tokenVault = vault,
            clientOverride = client,
        )

        val oldRequest = async(Dispatchers.IO) {
            runCatching {
                graph.patientApi.createSession(
                    idempotencyKey = "session-create:a-draft",
                    request = CreateSessionRequestDto("44444444-4444-4444-8444-444444444444"),
                )
            }.exceptionOrNull()
        }
        assertTrue(
            "刷新结果已构造，但 retry 尚未进入 AuthInterceptor 前的 gate",
            retryReachedAuthBoundary.await(5, TimeUnit.SECONDS),
        )

        graph.authRepository.logout()
        graph.authRepository.login("patient001", "same-login-id-password")
        assertEquals(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            graph.authRepository.currentAuthenticatedLease()?.patientId,
        )
        releaseRetry.countDown()

        assertTrue(oldRequest.await() is SessionChangedException)
        assertEquals(listOf("Bearer expired-a-access"), synchronized(createAuthorizations) { createAuthorizations.toList() })
        assertEquals(0, bSideEffects.get())
        assertEquals("b-access", vault.sessionSnapshot().accessToken)
    }

    @Test
    fun `生产登录落盘token后必须请求patient me才发布UUID lease`() = runBlocking {
        val paths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(paths) { paths += requireNotNull(request.path) }
                return when (request.path) {
                    "/api/v1/auth/login/" -> json(200, fixture("fixtures/login.json"))
                    "/api/v1/patient/me/" -> json(200, fixture("fixtures/patient_me.json"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val graph = createProductionSessionGraph(server.url("/").toString(), ProductionTokenVault(null, null))

        graph.authRepository.login("patient001", "password")

        assertEquals(AuthState.Authenticated, graph.authRepository.state.value)
        assertEquals("11111111-1111-4111-8111-111111111111", graph.authRepository.currentAuthenticatedLease()?.patientId)
        assertEquals(listOf("/api/v1/auth/login/", "/api/v1/patient/me/"), synchronized(paths) { paths.toList() })
    }

    @Test
    fun `生产启动refresh恢复后必须请求patient me才发布UUID lease`() = runBlocking {
        val paths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(paths) { paths += requireNotNull(request.path) }
                return when (request.path) {
                    "/api/v1/auth/refresh/" -> json(200, fixture("fixtures/refresh.json"))
                    "/api/v1/patient/me/" -> json(200, fixture("fixtures/patient_me.json"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val graph = createProductionSessionGraph(
            server.url("/").toString(),
            ProductionTokenVault(accessToken = null, refreshToken = "stored-refresh"),
        )

        graph.authRepository.restoreSession()

        assertEquals(AuthState.Authenticated, graph.authRepository.state.value)
        assertEquals("11111111-1111-4111-8111-111111111111", graph.authRepository.currentAuthenticatedLease()?.patientId)
        assertEquals(listOf("/api/v1/auth/refresh/", "/api/v1/patient/me/"), synchronized(paths) { paths.toList() })
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
        val expired = lifecycleEvent.await()
        assertTrue(expired is SessionLifecycleEvent.SessionExpired)
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
