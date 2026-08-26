package com.vocaease.patient.core.network

import com.vocaease.patient.core.security.AccessTokenSnapshot
import com.vocaease.patient.core.security.TokenVault
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {
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
    fun `refresh 请求明确移除 Authorization 且不标记认证 generation`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val observedGeneration = AtomicReference<AuthRequestGeneration?>()
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(InterceptorTokenVault("memory-access", 7)))
            .addInterceptor { chain ->
                observedGeneration.set(chain.request().tag(AuthRequestGeneration::class.java))
                chain.proceed(chain.request())
            }
            .build()
        val request = Request.Builder()
            .url(server.url("/api/v1/auth/refresh/"))
            .header("Authorization", "Bearer stale-token")
            .build()

        client.newCall(request).execute().close()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
        assertNull(observedGeneration.get())
    }

    @Test
    fun `患者请求携带内存 access 且记录 token generation`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(InterceptorTokenVault("memory-access", 7)))
            .addInterceptor { chain ->
                val generation = chain.request().tag(AuthRequestGeneration::class.java)?.value
                chain.proceed(chain.request().newBuilder().header("X-Test-Generation", generation.toString()).build())
            }
            .build()

        client.newCall(Request.Builder().url(server.url("/api/v1/patient/me/")).build()).execute().close()

        val recorded = server.takeRequest()
        assertEquals("Bearer memory-access", recorded.getHeader("Authorization"))
        assertEquals("7", recorded.getHeader("X-Test-Generation"))
    }
}

private class InterceptorTokenVault(
    token: String?,
    generation: Long,
) : TokenVault {
    private val snapshot = AccessTokenSnapshot(token, generation)
    override fun accessSnapshot() = snapshot
    override suspend fun readRefreshToken(): String? = null
    override suspend fun replaceTokens(accessToken: String, refreshToken: String) = Unit
    override suspend fun clear() = Unit
}
