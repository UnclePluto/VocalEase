package com.vocaease.patient.core.network

import com.vocaease.patient.core.security.RevocationRemoteResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RevocationHttpClientTest {
    private val server = MockWebServer()
    private val redirectTarget = MockWebServer()

    @After
    fun tearDown() {
        server.close()
        redirectTarget.close()
    }

    @Test
    fun `专用客户端拒绝跨主机重定向且一次执行只有一次物理请求`() = runBlocking {
        server.start()
        redirectTarget.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", redirectTarget.url("/steal")),
        )
        val client = NetworkModule.createRevocationHttpClient()
        val remote = OkHttpRevocationRemote(server.url("/").toString(), client)

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(
            RevocationRemoteResult.Retryable,
            remote.revoke(accessToken = "old-access", refreshToken = "old-refresh"),
        )
        assertEquals(1, server.requestCount)
        assertEquals(0, redirectTarget.requestCount)
    }

    @Test
    fun `只有成功或刷新令牌明确失效才允许收敛槽位`() = runBlocking {
        server.start()
        val remote = OkHttpRevocationRemote(
            server.url("/").toString(),
            NetworkModule.createRevocationHttpClient(),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"code\":\"ok\",\"message\":\"\",\"data\":{},\"request_id\":\"r\"}"))
        assertEquals(RevocationRemoteResult.Success, remote.revoke("access", "refresh"))
        val request = server.takeRequest()
        assertEquals("Bearer access", request.getHeader("Authorization"))
        assertEquals("/api/v1/auth/logout/", request.path)
        assertEquals("{\"client_kind\":\"android\",\"refresh\":\"refresh\"}", request.body.readUtf8())

        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"code\":\"token_not_valid\"}"))
        assertEquals(RevocationRemoteResult.InvalidOrExpired, remote.revoke("access", "refresh"))

        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"code\":\"not_authenticated\"}"))
        assertEquals(RevocationRemoteResult.Retryable, remote.revoke("access", "refresh"))
    }
}
