package com.vocaease.patient.core.network

import com.vocaease.patient.core.network.dto.SessionStatus
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetworkPrivacyTest {
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
    fun `动态会话 ID 查询参数和私有 URL 只产生受控路由模板诊断`() = runBlocking {
        val diagnostics = mutableListOf<NetworkDiagnostic>()
        val api = NetworkModule.createApi(
            server.url("/").toString(),
            NetworkModule.createHttpClient(diagnosticSink = diagnostics::add),
        )
        server.enqueue(jsonResponse(fixture("fixtures/sessions_page.json")))
        server.enqueue(jsonResponse(fixture("fixtures/private_url.json")))

        api.sessions(
            page = 7,
            pageSize = 19,
            status = SessionStatus.COMPLETED,
            createdFrom = "2026-08-01",
            createdTo = "2026-08-31",
        )
        api.patientMediaPrivateUrl(ASSET_ID)

        assertEquals(ApiEndpoint.SESSION_LIST, diagnostics[0].endpoint)
        assertEquals("/api/v1/patient/singing-sessions/", diagnostics[0].pathTemplate)
        assertEquals(ApiEndpoint.PATIENT_MEDIA_PRIVATE_URL, diagnostics[1].endpoint)
        assertEquals("/api/v1/patient/media/{asset_id}/private-url/", diagnostics[1].pathTemplate)
        val rendered = diagnostics.joinToString("\n", transform = NetworkDiagnostic::toLogLine)
        listOf(
            ASSET_ID, "page=7", "page_size=19", "created_from", "private.example",
            "private-secret", "token=",
        ).forEach { secret -> assertFalse("诊断泄漏 $secret", rendered.contains(secret)) }
    }

    @Test
    fun `非 Retrofit 请求无法证明模板时固定记录 unknown 而不回退实际 URL`() {
        val diagnostics = mutableListOf<NetworkDiagnostic>()
        val client = NetworkModule.createHttpClient(diagnosticSink = diagnostics::add)
        server.enqueue(MockResponse().setResponseCode(204))
        val actualPath = "/actual/$SESSION_ID?token=private-secret"

        client.newCall(Request.Builder().url(server.url(actualPath)).build()).execute().use { response ->
            assertEquals(204, response.code)
        }

        val diagnostic = diagnostics.single()
        assertEquals(ApiEndpoint.UNKNOWN, diagnostic.endpoint)
        assertEquals("/unknown", diagnostic.pathTemplate)
        assertFalse(diagnostic.toLogLine().contains(SESSION_ID))
        assertFalse(diagnostic.toLogLine().contains("private-secret"))
        assertFalse(diagnostic.toLogLine().contains("actual"))
    }

    @Test
    fun `IOException 诊断保留受控端点且不包含动态会话 ID`() = runBlocking {
        val diagnostics = mutableListOf<NetworkDiagnostic>()
        val api = NetworkModule.createApi(
            server.url("/").toString(),
            NetworkModule.createHttpClient(diagnosticSink = diagnostics::add),
        )
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val thrown = runCatching { api.session(SESSION_ID) }.exceptionOrNull()

        assertTrue(thrown is IOException)
        val diagnostic = diagnostics.single()
        assertEquals(ApiEndpoint.SESSION_DETAIL, diagnostic.endpoint)
        assertEquals("network_error", diagnostic.code)
        assertFalse(diagnostic.toLogLine().contains(SESSION_ID))
        assertFalse(diagnostic.toLogLine().contains("?"))
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun fixture(path: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(path),
    ) { "缺少测试 fixture：$path" }.bufferedReader().use { it.readText() }

    private companion object {
        const val SESSION_ID = "55555555-5555-4555-8555-555555555555"
        const val ASSET_ID = "77777777-7777-4777-8777-777777777777"
    }
}
