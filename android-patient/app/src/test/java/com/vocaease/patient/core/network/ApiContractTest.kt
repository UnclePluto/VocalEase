package com.vocaease.patient.core.network

import com.vocaease.patient.core.network.dto.AuthSession
import com.vocaease.patient.core.network.dto.AuthTokensDto
import com.vocaease.patient.core.network.dto.ClientKind
import com.vocaease.patient.core.network.dto.ChangePasswordRequestDto
import com.vocaease.patient.core.network.dto.ConfirmSessionMediaRequestDto
import com.vocaease.patient.core.network.dto.CreateSessionRequestDto
import com.vocaease.patient.core.network.dto.LoginRequestDto
import com.vocaease.patient.core.network.dto.LogoutRequestDto
import com.vocaease.patient.core.network.dto.MediaType
import com.vocaease.patient.core.network.dto.PatientMeDto
import com.vocaease.patient.core.network.dto.PatientMediaUploadGrantRequestDto
import com.vocaease.patient.core.network.dto.PatientMediaUploadGrantDto
import com.vocaease.patient.core.network.dto.PrivateUrlDto
import com.vocaease.patient.core.network.dto.RefreshRequestDto
import com.vocaease.patient.core.network.dto.SessionMutationDto
import com.vocaease.patient.core.network.dto.SessionPageDto
import com.vocaease.patient.core.network.dto.SessionStatus
import com.vocaease.patient.core.network.dto.SessionUploadGrantDto
import com.vocaease.patient.core.network.dto.SessionUploadGrantRequestDto
import com.vocaease.patient.core.network.dto.SingingAudioAnalysisPayload
import com.vocaease.patient.core.network.dto.SingingSessionDto
import com.vocaease.patient.core.network.dto.SongDto
import com.vocaease.patient.core.network.dto.SongPageDto
import com.vocaease.patient.core.network.dto.toDomain
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class ApiContractTest {
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
    fun `OpenAPI 固定患者端路径、必需字段、空值与枚举`() {
        val document = apiJson.parseToJsonElement(fixture("openapi.json")).jsonObject
        val paths = document.objectAt("paths")

        val expectedOperations = setOf(
            "post /api/v1/auth/login/",
            "post /api/v1/auth/refresh/",
            "post /api/v1/auth/change-password/",
            "post /api/v1/auth/logout/",
            "get /api/v1/patient/me/",
            "get /api/v1/patient/songs/",
            "get /api/v1/patient/songs/{song_id}/",
            "get /api/v1/patient/songs/{song_id}/reference-pitch/",
            "post /api/v1/patient/singing-sessions/{session_id}/song-playback/",
            "post /api/v1/patient/songs/{song_id}/preview/",
            "get /api/v1/patient/singing-sessions/",
            "post /api/v1/patient/singing-sessions/",
            "get /api/v1/patient/singing-sessions/{session_id}/",
            "post /api/v1/patient/singing-sessions/{session_id}/upload-grants/",
            "post /api/v1/patient/singing-sessions/{session_id}/confirm-upload/",
            "post /api/v1/patient/singing-sessions/{session_id}/submit/",
            "post /api/v1/patient/singing-sessions/{session_id}/cancel/",
            "post /api/v1/patient/singing-sessions/{session_id}/retry/",
            "post /api/v1/patient/media/upload-grants/",
            "post /api/v1/patient/media/{asset_id}/private-url/",
        )
        expectedOperations.forEach { operation ->
            val (method, path) = operation.split(" ", limit = 2)
            assertTrue("契约缺少 $operation", paths.objectAt(path).containsKey(method))
        }
        assertEquals(
            expectedOperations,
            ApiEndpoint.entries
                .filterNot { it == ApiEndpoint.UNKNOWN }
                .map { "${it.method.lowercase()} ${it.pathTemplate}" }
                .toSet(),
        )
        assertEquals(
            setOf(
                "login", "refresh", "changePassword", "logout", "patientMe",
                "songs", "song", "previewSong", "referencePitch", "sessionSongPlayback", "sessions", "createSession", "session",
                "sessionUploadGrant", "patientMediaUploadGrant", "confirmSessionMedia",
                "submitSession", "cancelSession", "retrySession", "patientMediaPrivateUrl",
            ),
            VocaEaseApi::class.java.declaredMethods
                .filterNot { it.isSynthetic }
                .map { it.name }
                .toSet(),
        )

        assertEquals(
            setOf("client_kind", "login_id", "password"),
            document.required("LoginRequest"),
        )
        assertEquals(
            setOf(
                "active_treatment_plan", "enrollment_age", "gender", "id",
                "medical_record_no", "name", "notes", "phone", "primary_doctor",
                "singing_summary", "treatment_progress",
            ),
            document.required("PatientMeData"),
        )
        assertTrue(document.property("PatientMeData", "active_treatment_plan").nullable())
        assertTrue(document.property("PatientMeData", "treatment_progress").nullable())
        assertTrue(document.property("PatientTreatmentProgress", "progress_percent").nullable())
        assertEquals(
            setOf("completed_session_count", "total_duration_seconds"),
            document.required("PatientSingingSummary"),
        )
        assertEquals(
            listOf("web", "android"),
            document.enumValues("ClientKindEnum"),
        )
        assertEquals(
            listOf("created", "awaiting_upload", "uploaded", "processing", "completed", "failed", "cancelled"),
            document.enumValues("SingingSessionStatus"),
        )
        assertEquals(
            listOf("singing_audio", "singing_video"),
            document.enumValues("SessionUploadGrantMediaTypeEnum"),
        )
        assertEquals(
            setOf("media_type", "mime", "owner_id", "size"),
            document.required("PatientMediaUploadGrantRequestRequest"),
        )
        assertEquals(emptySet<String>(), document.required("ConfirmSessionMediaRequest"))

        assertIdempotencyHeader(paths, "/api/v1/patient/singing-sessions/", required = true)
        assertIdempotencyHeader(
            paths,
            "/api/v1/patient/singing-sessions/{session_id}/upload-grants/",
            required = false,
        )
        assertIdempotencyHeader(
            paths,
            "/api/v1/patient/singing-sessions/{session_id}/submit/",
            required = true,
        )
        assertIdempotencyHeader(
            paths,
            "/api/v1/patient/singing-sessions/{session_id}/retry/",
            required = true,
        )
        listOf(
            "/api/v1/patient/singing-sessions/{session_id}/confirm-upload/",
            "/api/v1/patient/singing-sessions/{session_id}/cancel/",
            "/api/v1/patient/media/upload-grants/",
            "/api/v1/patient/media/{asset_id}/private-url/",
        ).forEach { path -> assertNoIdempotencyHeader(paths, path) }
    }

    @Test
    fun `严格 JSON 解析登录、患者资料、歌曲与会话并完成类型化边界映射`() {
        val login = apiJson.decodeFromString<ApiEnvelope<AuthTokensDto>>(fixture("fixtures/login.json"))
        val auth: AuthSession = login.data.toDomain()
        assertEquals("patient001", auth.loginId)
        assertEquals(Instant.parse("2026-09-01T08:00:00Z"), auth.refreshExpiresAt)

        val me = apiJson.decodeFromString<ApiEnvelope<PatientMeDto>>(fixture("fixtures/patient_me.json"))
        assertEquals("33.33", me.data.treatmentProgress?.progressPercent)
        assertEquals(3180, me.data.singingSummary.totalDurationSeconds)
        assertEquals(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            me.data.toDomain().id,
        )

        val songs = apiJson.decodeFromString<ApiEnvelope<SongPageDto>>(fixture("fixtures/songs_page.json"))
        assertEquals("春风", songs.data.results.single().title)
        assertEquals(Instant.parse("2026-08-20T10:30:00Z"), songs.data.results.single().toDomain().uploadedAt)

        val session = apiJson.decodeFromString<ApiEnvelope<SingingSessionDto>>(fixture("fixtures/session.json"))
        val domain = session.data.toDomain()
        assertEquals(2, domain.media.size)
        assertEquals(2, domain.analysisResults.size)
        val payload = domain.analysisResults.first().payload as SingingAudioAnalysisPayload
        assertEquals(88, payload.score)
        assertEquals(listOf(220.0, 221.5), payload.series.getValue("pitch_hz"))

        val withUnknownField = fixture("fixtures/patient_me.json").replace(
            "\"request_id\": \"patient-me-1\"",
            "\"request_id\": \"patient-me-1\", \"unexpected\": true",
        )
        assertThrows(SerializationException::class.java) {
            apiJson.decodeFromString<ApiEnvelope<PatientMeDto>>(withUnknownField)
        }
        val invalidUuid = fixture("fixtures/song.json").replace(
            "44444444-4444-4444-8444-444444444444",
            "not-a-uuid",
        )
        val invalidSong = apiJson.decodeFromString<ApiEnvelope<SongDto>>(invalidUuid)
        assertThrows(NetworkContractException::class.java) { invalidSong.data.toDomain() }
    }

    @Test
    fun `Retrofit 按真实契约调用登录刷新患者资料与歌曲接口`() {
        runBlocking {
            val api = api()

        enqueue("fixtures/login.json")
        val login = api.login(LoginRequestDto("patient001", "Password!", ClientKind.ANDROID, true))
        assertEquals("access-secret", login.data.access)
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/v1/auth/login/", request.path)
            assertJson(
                """{"login_id":"patient001","password":"Password!","client_kind":"android","remember_me":true}""",
                request.body.readUtf8(),
            )
            assertNull(request.getHeader("Idempotency-Key"))
        }

        enqueue("fixtures/refresh.json")
        val refresh = api.refresh(RefreshRequestDto(ClientKind.ANDROID, "refresh-secret"))
        assertNull(refresh.data.refresh)
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/v1/auth/refresh/", request.path)
            assertJson(
                """{"client_kind":"android","refresh":"refresh-secret"}""",
                request.body.readUtf8(),
            )
            assertNull(request.getHeader("Idempotency-Key"))
        }

        enqueue("fixtures/empty.json")
        api.changePassword(ChangePasswordRequestDto("OldPassword!", "NewPassword!"))
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/v1/auth/change-password/", request.requestUrl?.encodedPath)
            assertJson(
                """{"old_password":"OldPassword!","new_password":"NewPassword!"}""",
                request.body.readUtf8(),
            )
            assertNull(request.getHeader("Idempotency-Key"))
        }

        enqueue("fixtures/empty.json")
        api.logout(LogoutRequestDto(ClientKind.ANDROID, "refresh-secret"))
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/v1/auth/logout/", request.requestUrl?.encodedPath)
            assertJson(
                """{"client_kind":"android","refresh":"refresh-secret"}""",
                request.body.readUtf8(),
            )
            assertNull(request.getHeader("Idempotency-Key"))
        }

        enqueue("fixtures/patient_me.json")
        assertEquals("MR-2026-001", api.patientMe().data.medicalRecordNo)
        server.takeRequest().also { request ->
            assertEquals("GET", request.method)
            assertEquals("/api/v1/patient/me/", request.requestUrl?.encodedPath)
            assertNull(request.requestUrl?.query)
            assertNull(request.getHeader("Idempotency-Key"))
        }

        enqueue("fixtures/songs_page.json")
        assertEquals(1, api.songs(page = 1, pageSize = 20, keyword = "春", sort = "-created_at").data.count)
        server.takeRequest().also { request ->
            assertEquals("GET", request.method)
            assertEquals("/api/v1/patient/songs/", request.requestUrl?.encodedPath)
            assertEquals("1", request.requestUrl?.queryParameter("page"))
            assertEquals("20", request.requestUrl?.queryParameter("page_size"))
            assertEquals("春", request.requestUrl?.queryParameter("keyword"))
            assertEquals("-created_at", request.requestUrl?.queryParameter("sort"))
            assertNull(request.getHeader("Idempotency-Key"))
        }

        enqueue("fixtures/song.json")
        assertEquals("春风", api.song(SONG_ID).data.title)
        server.takeRequest().also { request ->
            assertEquals("GET", request.method)
            assertEquals("/api/v1/patient/songs/$SONG_ID/", request.requestUrl?.encodedPath)
            assertNull(request.requestUrl?.query)
            assertNull(request.getHeader("Idempotency-Key"))
        }

        enqueue("fixtures/private_url.json")
        assertTrue(api.previewSong(SONG_ID).data.url.startsWith("https://private.example/"))
            server.takeRequest().also { request ->
                assertEquals("POST", request.method)
                assertEquals("/api/v1/patient/songs/$SONG_ID/preview/?track=source", request.path)
                assertNull(request.getHeader("Idempotency-Key"))
                assertEquals(0L, request.bodySize)
            }
        }
    }

    @Test
    fun `Retrofit 仅在契约支持处发送幂等头并覆盖会话和患者媒体接口`() {
        runBlocking {
            val api = api()

        enqueue("fixtures/session.json", status = 201)
        assertEquals(SESSION_ID, api.createSession("create-key", CreateSessionRequestDto(SONG_ID)).data.id)
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/v1/patient/singing-sessions/", request.requestUrl?.encodedPath)
            assertEquals("/api/v1/patient/singing-sessions/", request.path)
            assertEquals("create-key", request.getHeader("Idempotency-Key"))
            assertJson("""{"song_id":"$SONG_ID"}""", request.body.readUtf8())
        }

        enqueue("fixtures/sessions_page.json")
        val page: ApiEnvelope<SessionPageDto> = api.sessions(
            page = 1,
            pageSize = 20,
            status = SessionStatus.COMPLETED,
            createdFrom = "2026-08-01",
            createdTo = "2026-08-31",
        )
        assertEquals(SESSION_ID, page.data.results.single().id)
        server.takeRequest().also { request ->
            assertEquals("GET", request.method)
            assertEquals("/api/v1/patient/singing-sessions/", request.requestUrl?.encodedPath)
            assertEquals("1", request.requestUrl?.queryParameter("page"))
            assertEquals("20", request.requestUrl?.queryParameter("page_size"))
            assertEquals("completed", request.requestUrl?.queryParameter("status"))
            assertEquals("2026-08-01", request.requestUrl?.queryParameter("created_from"))
            assertEquals("2026-08-31", request.requestUrl?.queryParameter("created_to"))
            assertNull(request.getHeader("Idempotency-Key"))
        }

        enqueue("fixtures/session.json")
        assertEquals(SESSION_ID, api.session(SESSION_ID).data.id)
        server.takeRequest().also { request ->
            assertEquals("GET", request.method)
            assertEquals(
                "/api/v1/patient/singing-sessions/$SESSION_ID/",
                request.requestUrl?.encodedPath,
            )
            assertNull(request.requestUrl?.query)
            assertNull(request.getHeader("Idempotency-Key"))
        }

        enqueue("fixtures/session_grant.json", status = 201)
        val sessionGrant: ApiEnvelope<SessionUploadGrantDto> = api.sessionUploadGrant(
            SESSION_ID,
            "grant-key",
            SessionUploadGrantRequestDto(MediaType.SINGING_AUDIO, "audio/mp4", 1234),
        )
        assertEquals("private/session/audio.m4a", sessionGrant.data.objectKey)
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/v1/patient/singing-sessions/$SESSION_ID/upload-grants/", request.path)
            assertEquals("grant-key", request.getHeader("Idempotency-Key"))
            assertJson(
                """{"media_type":"singing_audio","mime":"audio/mp4","size":1234}""",
                request.body.readUtf8(),
            )
        }

        enqueue("fixtures/session_grant.json", status = 200)
        api.sessionUploadGrant(
            SESSION_ID,
            null,
            SessionUploadGrantRequestDto(MediaType.SINGING_AUDIO, "audio/mp4", 1234),
        )
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/patient/singing-sessions/$SESSION_ID/upload-grants/",
                request.requestUrl?.encodedPath,
            )
            assertNull(request.getHeader("Idempotency-Key"))
            assertJson(
                """{"media_type":"singing_audio","mime":"audio/mp4","size":1234}""",
                request.body.readUtf8(),
            )
        }

        enqueue("fixtures/patient_grant.json", status = 201)
        val patientGrant: ApiEnvelope<PatientMediaUploadGrantDto> = api.patientMediaUploadGrant(
            PatientMediaUploadGrantRequestDto(PATIENT_ID, MediaType.SINGING_VIDEO, "video/mp4", 5678),
        )
        assertEquals("private/patient/video.mp4", patientGrant.data.objectKey)
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/v1/patient/media/upload-grants/", request.requestUrl?.encodedPath)
            assertNull(request.getHeader("Idempotency-Key"))
            assertJson(
                """{"owner_id":"$PATIENT_ID","media_type":"singing_video","mime":"video/mp4","size":5678}""",
                request.body.readUtf8(),
            )
        }

        enqueue("fixtures/session.json")
        assertEquals(SESSION_ID, api.confirmSessionMedia(
            SESSION_ID,
            ConfirmSessionMediaRequestDto(assetId = "66666666-6666-4666-8666-666666666666"),
        ).data.id)
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/patient/singing-sessions/$SESSION_ID/confirm-upload/",
                request.requestUrl?.encodedPath,
            )
            assertNull(request.getHeader("Idempotency-Key"))
            assertJson(
                """{"asset_id":"66666666-6666-4666-8666-666666666666"}""",
                request.body.readUtf8(),
            )
        }

        enqueue("fixtures/session_mutation.json", status = 202)
        val submit: ApiEnvelope<SessionMutationDto> = api.submitSession(SESSION_ID, "submit-key")
        assertEquals("processing", submit.data.status.serializedValue)
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("submit-key", request.getHeader("Idempotency-Key"))
            assertEquals(
                "/api/v1/patient/singing-sessions/$SESSION_ID/submit/",
                request.requestUrl?.encodedPath,
            )
            assertJson("{}", request.body.readUtf8())
        }

        enqueue("fixtures/session.json")
        assertEquals(SESSION_ID, api.cancelSession(SESSION_ID).data.id)
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals(
                "/api/v1/patient/singing-sessions/$SESSION_ID/cancel/",
                request.requestUrl?.encodedPath,
            )
            assertNull(request.getHeader("Idempotency-Key"))
            assertEquals(0L, request.bodySize)
        }

        enqueue("fixtures/session_mutation.json", status = 202)
        assertEquals(SESSION_ID, api.retrySession(SESSION_ID, "retry-key").data.sessionId)
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("retry-key", request.getHeader("Idempotency-Key"))
            assertEquals(
                "/api/v1/patient/singing-sessions/$SESSION_ID/retry/",
                request.requestUrl?.encodedPath,
            )
            assertEquals(0L, request.bodySize)
        }

        enqueue("fixtures/private_url.json")
        val privateUrl: ApiEnvelope<PrivateUrlDto> = api.patientMediaPrivateUrl(ASSET_ID)
        assertEquals(Instant.parse("2026-08-27T12:00:00Z"), privateUrl.data.toDomain().expiresAt)
            server.takeRequest().also { request ->
                assertEquals("POST", request.method)
                assertEquals(
                    "/api/v1/patient/media/$ASSET_ID/private-url/",
                    request.requestUrl?.encodedPath,
                )
                assertNull(request.getHeader("Idempotency-Key"))
                assertEquals(0L, request.bodySize)
            }
        }
    }

    @Test
    fun `错误映射提供稳定中文分类并保留最小诊断字段`() {
        val envelope = apiJson.decodeFromString<ApiErrorEnvelope>(fixture("fixtures/error.json"))
        val conflict = ApiErrorMapper.map(
            status = 409,
            envelope = envelope,
            endpoint = ApiEndpoint.SESSION_SUBMIT,
        )
        assertTrue(conflict is ApiFailure.SingingConflict)
        assertEquals("演唱记录状态冲突，请刷新后重试", conflict.userMessage)
        assertEquals("error-request-1", conflict.diagnostic.requestId)

        val validationEnvelope = apiJson.decodeFromString<ApiErrorEnvelope>(
            fixture("fixtures/validation_error.json"),
        )
        assertTrue(
            ApiErrorMapper.map(400, validationEnvelope, ApiEndpoint.SESSION_CREATE)
                is ApiFailure.SongUnavailable,
        )

        val cases = listOf(
            Triple(401, "token_not_valid", ApiFailure.Unauthorized::class.java),
            Triple(403, "permission_denied", ApiFailure.Forbidden::class.java),
            Triple(404, "not_found", ApiFailure.NotFound::class.java),
            Triple(429, "throttled", ApiFailure.RateLimited::class.java),
            Triple(503, "service_unavailable", ApiFailure.Unavailable::class.java),
            Triple(400, "validation_error", ApiFailure.Validation::class.java),
            Triple(400, "song_unavailable", ApiFailure.SongUnavailable::class.java),
        )
        cases.forEach { (status, code, expectedType) ->
            val failure = ApiErrorMapper.map(
                status,
                ApiErrorEnvelope(code, "服务端原始文案", null, "request-$status"),
                ApiEndpoint.PATIENT_ME,
            )
            assertTrue("$status/$code 映射错误", expectedType.isInstance(failure))
            assertFalse(failure.userMessage.contains("服务端原始文案"))
        }
    }

    @Test
    fun `安全网络诊断不记录请求体、查询、令牌、实体 ID 或私有 URL`() = runBlocking {
        val diagnostics = mutableListOf<NetworkDiagnostic>()
        val api = NetworkModule.createApi(
            baseUrl = server.url("/").toString(),
            client = NetworkModule.createHttpClient(diagnosticSink = diagnostics::add),
        )
        enqueue("fixtures/error.json", status = 409)

        val thrown = runCatching {
            api.login(LoginRequestDto("private-login", "private-password", ClientKind.ANDROID, true))
        }.exceptionOrNull()
        assertTrue(thrown is HttpException)
        val mapped = ApiErrorMapper.map(
            error = thrown as HttpException,
            endpoint = ApiEndpoint.AUTH_LOGIN,
        )
        assertTrue(mapped is ApiFailure.SingingConflict)

        val diagnostic = diagnostics.single()
        assertEquals("POST", diagnostic.method)
        assertEquals("/api/v1/auth/login/", diagnostic.pathTemplate)
        assertEquals(409, diagnostic.status)
        assertEquals("singing_submission_conflict", diagnostic.code)
        assertEquals("error-request-1", diagnostic.requestId)
        val rendered = diagnostic.toLogLine()
        listOf(
            "private-login", "private-password", "private.example", "token=",
            "access-secret", "refresh-secret",
        ).forEach { secret -> assertFalse("诊断泄漏 $secret", rendered.contains(secret)) }
        assertFalse(rendered.contains("?"))
    }

    @Test
    fun `真实人声分析来源可以严格解析并进入可绘制参考状态`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""
            {"code":"ok","message":"成功","request_id":"pitch-contract",
             "data":{"status":"ready","version":"bound","schema_version":1,
              "origin":{"type":"vocal_yin","fingerprint":"verified-vocal"},
              "notes":[{"start_ms":18460,"end_ms":18740,"midi_note":57.2,"confidence":0.96}]}}
        """.trimIndent()))
        val repository = com.vocaease.patient.feature.training.ReferencePitchRepository { song, version ->
            api().referencePitch(song, version).data
        }
        val result = repository.load("song", "bound")
        assertTrue("真实后台参考数据必须进入 Ready，而不是解析失败", result is com.vocaease.patient.feature.training.ReferencePitchState.Ready)
        val ready = result as com.vocaease.patient.feature.training.ReferencePitchState.Ready
        assertEquals("bound", ready.version)
        assertEquals(18460L, ready.notes.single().startMs)
        assertEquals(57.2f, ready.notes.single().midiNote, 0.001f)
        assertEquals("/api/v1/patient/songs/song/reference-pitch/?version=bound", server.takeRequest().path)
    }

    @Test
    fun `人工标注来源与缺失数据兼容且参考接口仍拒绝未知顶层字段`() {
        val annotated = apiJson.decodeFromString<com.vocaease.patient.core.network.dto.ReferencePitchDto>("""
            {"status":"ready","version":"bound","schema_version":1,
             "origin":{"type":"annotation","citation":"授权标注","annotator":"reviewed"},"notes":[]}
        """.trimIndent())
        assertEquals("授权标注", annotated.origin?.get("citation")?.jsonPrimitive?.content)
        val missing = apiJson.decodeFromString<com.vocaease.patient.core.network.dto.ReferencePitchDto>(
            """{"status":"missing","version":null,"schema_version":1,"notes":[]}""",
        )
        assertNull(missing.origin)
        assertThrows(SerializationException::class.java) {
            apiJson.decodeFromString<com.vocaease.patient.core.network.dto.ReferencePitchDto>(
                """{"status":"missing","version":null,"unexpected":true}""",
            )
        }
    }

    private fun api(): VocaEaseApi = NetworkModule.createApi(
        baseUrl = server.url("/").toString(),
        client = NetworkModule.createHttpClient(diagnosticSink = {}),
    )

    private fun enqueue(path: String, status: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(fixture(path)),
        )
    }

    private fun fixture(path: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(path),
    ) { "缺少测试 fixture：$path" }.bufferedReader().use { it.readText() }

    private fun assertJson(expected: String, actual: String) {
        assertEquals(apiJson.parseToJsonElement(expected), apiJson.parseToJsonElement(actual))
    }

    private fun assertIdempotencyHeader(paths: JsonObject, path: String, required: Boolean) {
        val parameters = paths.objectAt(path).objectAt("post")["parameters"]?.jsonArray.orEmpty()
        val header = parameters.single {
            val value = it.jsonObject
            value["in"]?.jsonPrimitive?.content == "header" &&
                value["name"]?.jsonPrimitive?.content == "Idempotency-Key"
        }.jsonObject
        assertEquals(required, header["required"]?.jsonPrimitive?.boolean ?: false)
    }

    private fun assertNoIdempotencyHeader(paths: JsonObject, path: String) {
        val parameters = paths.objectAt(path).objectAt("post")["parameters"]?.jsonArray.orEmpty()
        assertFalse(parameters.any { it.jsonObject["name"]?.jsonPrimitive?.content == "Idempotency-Key" })
    }

    private fun JsonObject.objectAt(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.components(): JsonObject = objectAt("components").objectAt("schemas")

    private fun JsonObject.schema(name: String): JsonObject = components().objectAt(name)

    private fun JsonObject.required(name: String): Set<String> =
        schema(name)["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()

    private fun JsonObject.property(schema: String, property: String): JsonObject =
        this.schema(schema).objectAt("properties").objectAt(property)

    private fun JsonObject.enumValues(name: String): List<String> =
        schema(name).getValue("enum").jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.nullable(): Boolean =
        get("nullable")?.jsonPrimitive?.boolean ?: false

    private companion object {
        const val PATIENT_ID = "11111111-1111-4111-8111-111111111111"
        const val SONG_ID = "44444444-4444-4444-8444-444444444444"
        const val SESSION_ID = "55555555-5555-4555-8555-555555555555"
        const val ASSET_ID = "77777777-7777-4777-8777-777777777777"
    }
}
