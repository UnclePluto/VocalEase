package com.vocaease.patient.core.network

import com.vocaease.patient.core.network.dto.ClientKind
import com.vocaease.patient.core.network.dto.LoginRequestDto
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ApiErrorMappingTest {
    private lateinit var server: MockWebServer
    private lateinit var api: VocaEaseApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = NetworkModule.createApi(
            server.url("/").toString(),
            NetworkModule.createHttpClient(diagnosticSink = {}),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `真实 validation error 的 song_id 字段映射为歌曲不可用而普通字段仍为参数错误`() =
        runBlocking {
            val song = httpError(400, fixture("fixtures/validation_error.json"))
            val general = httpError(400, fixture("fixtures/validation_general.json"))

            assertTrue(ApiErrorMapper.map(song, ApiEndpoint.SESSION_CREATE) is ApiFailure.SongUnavailable)
            assertTrue(ApiErrorMapper.map(general, ApiEndpoint.AUTH_LOGIN) is ApiFailure.Validation)
        }

    @Test
    fun `空 body HTML 与非 JSON 仍按身份限流服务和冲突状态分类`() = runBlocking {
        val cases = listOf(
            Triple(401, "", ApiFailure.Unauthorized::class.java),
            Triple(403, "<html>forbidden</html>", ApiFailure.Forbidden::class.java),
            Triple(404, "not-json", ApiFailure.NotFound::class.java),
            Triple(409, "", ApiFailure.Conflict::class.java),
            Triple(429, "<html>slow down</html>", ApiFailure.RateLimited::class.java),
            Triple(503, "not-json", ApiFailure.Unavailable::class.java),
        )

        cases.forEach { (status, body, expected) ->
            val failure = ApiErrorMapper.map(httpError(status, body), ApiEndpoint.AUTH_LOGIN)
            assertTrue("status=$status", expected.isInstance(failure))
        }
    }

    @Test
    fun `HTTP 状态优先于冲突业务码且 409 可细分演唱冲突`() = runBlocking {
        val singingBody = fixture("fixtures/error.json")
        val songValidation = fixture("fixtures/validation_error.json")

        assertTrue(
            ApiErrorMapper.map(httpError(401, singingBody), ApiEndpoint.AUTH_LOGIN)
                is ApiFailure.Unauthorized,
        )
        assertTrue(
            ApiErrorMapper.map(httpError(404, singingBody), ApiEndpoint.SESSION_DETAIL)
                is ApiFailure.NotFound,
        )
        assertTrue(
            ApiErrorMapper.map(httpError(429, songValidation), ApiEndpoint.SESSION_CREATE)
                is ApiFailure.RateLimited,
        )
        assertTrue(
            ApiErrorMapper.map(httpError(503, singingBody), ApiEndpoint.SESSION_SUBMIT)
                is ApiFailure.Unavailable,
        )
        assertTrue(
            ApiErrorMapper.map(httpError(409, singingBody), ApiEndpoint.SESSION_SUBMIT)
                is ApiFailure.SingingConflict,
        )
        assertTrue(
            ApiErrorMapper.map(httpError(409, songValidation), ApiEndpoint.SESSION_CREATE)
                is ApiFailure.Conflict,
        )
    }

    @Test
    fun `统一 Throwable 入口映射传输和序列化失败`() {
        assertTrue(
            ApiErrorMapper.map(IOException("socket token=secret"), ApiEndpoint.SESSION_DETAIL)
                is ApiFailure.Transport,
        )
        assertTrue(
            ApiErrorMapper.map(SerializationException("private-url"), ApiEndpoint.SESSION_DETAIL)
                is ApiFailure.Malformed,
        )
    }

    @Test
    fun `HttpException body 只读取一次且诊断不包含 body`() {
        val body = CountingResponseBody(fixture("fixtures/validation_general.json"))
        val error = HttpException(Response.error<Any>(400, body))

        val failure = ApiErrorMapper.map(error, ApiEndpoint.AUTH_LOGIN)

        assertTrue(failure is ApiFailure.Validation)
        assertEquals(1, body.sourceCalls)
        assertFalse(failure.diagnostic.toLogLine().contains("password"))
        assertFalse(failure.diagnostic.toLogLine().contains("输入无效"))
    }

    private suspend fun httpError(status: Int, body: String): HttpException {
        server.enqueue(
            MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
        val thrown = runCatching {
            api.login(LoginRequestDto("private-login", "private-password", ClientKind.ANDROID))
        }.exceptionOrNull()
        check(thrown is HttpException) { "预期 HttpException，实际为 $thrown" }
        return thrown
    }

    private fun fixture(path: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(path),
    ) { "缺少测试 fixture：$path" }.bufferedReader().use { it.readText() }

    private class CountingResponseBody(private val content: String) : ResponseBody() {
        var sourceCalls: Int = 0
            private set

        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = content.toByteArray().size.toLong()

        override fun source(): BufferedSource {
            sourceCalls += 1
            return Buffer().writeUtf8(content)
        }
    }
}
