package com.vocaease.patient

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.network.NetworkModule
import com.vocaease.patient.core.network.SessionExpiredException
import com.vocaease.patient.core.network.SessionLifecycleEvent
import com.vocaease.patient.core.security.AndroidTokenVault
import com.vocaease.patient.core.security.SessionInvalidation
import com.vocaease.patient.core.security.VaultFileStore
import com.vocaease.patient.feature.auth.AuthEvent
import com.vocaease.patient.feature.auth.AuthState
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionVaultInvalidationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanBefore() = cleanVault()

    @After
    fun cleanAfter() = cleanVault()

    @Test
    fun refresh密文篡改自失效只广播一次且生产患者API保持失败() = runBlocking {
        val vault = AndroidTokenVault(context)
        val failedEpoch = seedExpiredSession(vault)
        val file = context.getFileStreamPath(AndroidTokenVault.FILE_NAME)
        file.writeBytes(file.readBytes().also { bytes -> bytes[bytes.lastIndex] = (bytes.last() + 1).toByte() })

        assertInvalidationIsTerminal(vault, failedEpoch, refreshSucceeds = false)
    }

    @Test
    fun refresh密文版本错误自失效只广播一次且生产患者API保持失败() = runBlocking {
        val vault = AndroidTokenVault(context)
        val failedEpoch = seedExpiredSession(vault)
        val file = context.getFileStreamPath(AndroidTokenVault.FILE_NAME)
        file.writeBytes(file.readBytes().also { bytes ->
            bytes[0] = 0
            bytes[1] = 0
            bytes[2] = 0
            bytes[3] = 99
        })

        assertInvalidationIsTerminal(vault, failedEpoch, refreshSucceeds = false)
    }

    @Test
    fun Keystore别名删除后解密自失效只广播一次且生产患者API保持失败() = runBlocking {
        val vault = AndroidTokenVault(context)
        val failedEpoch = seedExpiredSession(vault)
        androidKeyStore().deleteEntry(AndroidTokenVault.KEY_ALIAS)

        assertInvalidationIsTerminal(vault, failedEpoch, refreshSucceeds = false)
    }

    @Test
    fun refresh替换写后失败自失效只广播一次且生产患者API保持失败() = runBlocking {
        val store = FailAfterRefreshWriteStore(context)
        val vault = AndroidTokenVault(context, fileStore = store)
        val failedEpoch = seedExpiredSession(vault)
        store.failNextWrite = true

        assertInvalidationIsTerminal(vault, failedEpoch, refreshSucceeds = true)
        assertFalse(store.exists())
    }

    @Test
    fun 旧失效通知暂停后新登录获胜不会污染新会话状态或事件() = runBlocking {
        val vault = AndroidTokenVault(context)
        seedExpiredSession(vault)
        corruptCiphertext()
        val gate = InvalidationPublishGate()
        val transport = AuthTestTransport(refreshSucceeds = false)
        val graph = productionGraph(vault, transport, gate)
        graph.authRepository.restoreSession()
        val authExpiredEvents = AtomicInteger()
        val lifecycleExpiredEvents = AtomicInteger()
        val authCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            graph.authRepository.events.collect { event ->
                if (event == AuthEvent.SessionExpired) authExpiredEvents.incrementAndGet()
            }
        }
        val lifecycleCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            graph.sessionEvents.collect { lifecycleExpiredEvents.incrementAndGet() }
        }
        val staleRequest = async { runCatching { graph.patientApi.patientMe() }.exceptionOrNull() }

        try {
            val invalidation = withTimeout(5_000) { gate.claimed.await() }
            graph.authRepository.login("patient001", "password")
            assertEquals(AuthState.Authenticated, graph.authRepository.state.value)
            assertEquals("access-secret", vault.sessionSnapshot().accessToken)
            assertEquals(invalidation.toEpoch + 1, vault.sessionSnapshot().epoch)
        } finally {
            gate.release.complete(Unit)
        }
        assertTrue(withTimeout(5_000) { staleRequest.await() } != null)
        yield()

        assertEquals("access-secret", vault.sessionSnapshot().accessToken)
        assertEquals(AuthState.Authenticated, graph.authRepository.state.value)
        assertEquals(0, authExpiredEvents.get())
        assertEquals(0, lifecycleExpiredEvents.get())
        authCollector.cancel()
        lifecycleCollector.cancel()
    }

    @Test
    fun 旧失效通知暂停后显式登出获胜不会暴露误导过期事件() = runBlocking {
        val vault = AndroidTokenVault(context)
        seedExpiredSession(vault)
        corruptCiphertext()
        val gate = InvalidationPublishGate()
        val transport = AuthTestTransport(refreshSucceeds = false)
        val graph = productionGraph(vault, transport, gate)
        graph.authRepository.restoreSession()
        val authExpiredEvents = AtomicInteger()
        val lifecycleExpiredEvents = AtomicInteger()
        val authCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            graph.authRepository.events.collect { event ->
                if (event == AuthEvent.SessionExpired) authExpiredEvents.incrementAndGet()
            }
        }
        val lifecycleCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            graph.sessionEvents.collect { lifecycleExpiredEvents.incrementAndGet() }
        }
        val staleRequest = async { runCatching { graph.patientApi.patientMe() }.exceptionOrNull() }

        try {
            withTimeout(5_000) { gate.claimed.await() }
            graph.authRepository.logout()
            assertEquals(AuthState.LoggedOut, graph.authRepository.state.value)
            assertNull(vault.sessionSnapshot().accessToken)
        } finally {
            gate.release.complete(Unit)
        }
        assertTrue(withTimeout(5_000) { staleRequest.await() } != null)
        yield()

        assertEquals(AuthState.LoggedOut, graph.authRepository.state.value)
        assertEquals(0, authExpiredEvents.get())
        assertEquals(0, lifecycleExpiredEvents.get())
        authCollector.cancel()
        lifecycleCollector.cancel()
    }

    private suspend fun seedExpiredSession(vault: AndroidTokenVault): Long {
        val mutation = vault.replaceTokens(
            expectedEpoch = vault.sessionSnapshot().epoch,
            accessToken = "expired-access",
            refreshToken = "stored-refresh",
            replacementId = "seed-expired-session",
        )
        assertTrue(mutation.applied)
        return mutation.snapshot.epoch
    }

    private suspend fun assertInvalidationIsTerminal(
        vault: AndroidTokenVault,
        failedEpoch: Long,
        refreshSucceeds: Boolean,
    ) = coroutineScope {
        val transport = AuthTestTransport(refreshSucceeds)
        val graph = productionGraph(vault, transport)
        graph.authRepository.restoreSession()
        assertEquals(AuthState.Authenticated, graph.authRepository.state.value)
        val eventCount = AtomicInteger()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            graph.sessionEvents.collect { event ->
                if (event is SessionLifecycleEvent.SessionExpired) eventCount.incrementAndGet()
            }
        }

        val firstFailure = runCatching { graph.patientApi.patientMe() }.exceptionOrNull()
        yield()

        assertTrue(firstFailure is SessionExpiredException)
        assertEquals(1, eventCount.get())
        assertEquals(AuthState.LoggedOut, graph.authRepository.state.value)
        assertNull(vault.sessionSnapshot().accessToken)
        assertEquals(failedEpoch + 1, vault.sessionSnapshot().epoch)

        repeat(3) {
            val laterFailure = runCatching { graph.patientApi.patientMe() }.exceptionOrNull()
            assertTrue(laterFailure is SessionExpiredException)
        }
        yield()
        assertEquals(1, eventCount.get())
        assertEquals(2, transport.patientCalls.get())
        assertEquals(if (refreshSucceeds) 1 else 0, transport.refreshCalls.get())
        collector.cancel()
    }

    private fun productionGraph(
        vault: AndroidTokenVault,
        transport: AuthTestTransport,
        gate: InvalidationPublishGate? = null,
    ): ProductionSessionGraph {
        val authenticatedClient = NetworkModule.createAuthenticatedHttpClient(vault)
            .newBuilder()
            .addInterceptor(transport)
            .build()
        return createProductionSessionGraph(
            baseUrl = "https://patient.test/",
            tokenVault = vault,
            clientOverride = authenticatedClient,
            beforeInvalidationPublish = { invalidation -> gate?.pause(invalidation) },
        )
    }

    private fun corruptCiphertext() {
        val file = context.getFileStreamPath(AndroidTokenVault.FILE_NAME)
        file.writeBytes(file.readBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
        })
    }

    private fun cleanVault() {
        context.getFileStreamPath(AndroidTokenVault.FILE_NAME).delete()
        context.getFileStreamPath(FailAfterRefreshWriteStore.FILE_NAME).delete()
        androidKeyStore().deleteEntry(AndroidTokenVault.KEY_ALIAS)
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}

private class AuthTestTransport(
    private val refreshSucceeds: Boolean,
) : Interceptor {
    val patientCalls = AtomicInteger()
    val refreshCalls = AtomicInteger()
    private val initialIdentityDelivered = java.util.concurrent.atomic.AtomicBoolean()
    private val loginIdentityDelivered = java.util.concurrent.atomic.AtomicBoolean()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val (code, body) = when (request.url.encodedPath) {
            "/api/v1/patient/me/" -> {
                patientCalls.incrementAndGet()
                if (
                    initialIdentityDelivered.compareAndSet(false, true) ||
                    (request.header("Authorization") == "Bearer access-secret" &&
                        loginIdentityDelivered.compareAndSet(false, true))
                ) {
                    200 to patientMeSuccess()
                } else {
                    401 to unauthorized()
                }
            }
            "/api/v1/auth/refresh/" -> {
                refreshCalls.incrementAndGet()
                if (refreshSucceeds) 200 to refreshSuccess() else 401 to unauthorized()
            }
            "/api/v1/auth/login/" -> 200 to loginSuccess()
            "/api/v1/auth/logout/" -> 200 to emptySuccess("auth-logout-1")
            else -> error("未预期请求：${request.url.encodedPath}")
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Unauthorized")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun unauthorized(): String =
        """{"code":"unauthorized","message":"unauthorized","data":null,"request_id":"auth-401"}"""

    private fun refreshSuccess(): String = """
        {
          "code":"ok",
          "message":"",
          "data":{
            "access":"next-access",
            "refresh":"next-refresh",
            "refresh_expires_at":"2026-09-02T08:00:00Z",
            "user":{"login_id":"patient001","role":"patient","must_change_password":false}
          },
          "request_id":"auth-refresh-1"
        }
    """.trimIndent()

    private fun loginSuccess(): String = """
        {
          "code":"ok",
          "message":"",
          "data":{
            "access":"access-secret",
            "refresh":"refresh-secret",
            "refresh_expires_at":"2026-09-02T08:00:00Z",
            "user":{"login_id":"patient001","role":"patient","must_change_password":false}
          },
          "request_id":"auth-login-1"
        }
    """.trimIndent()

    private fun patientMeSuccess(): String = """
        {
          "code":"ok",
          "message":"",
          "data":{
            "id":"11111111-1111-4111-8111-111111111111",
            "medical_record_no":"MR-2026-001",
            "name":"患者甲",
            "gender":"female",
            "enrollment_age":36,
            "phone":"13800000001",
            "notes":"",
            "primary_doctor":{"id":"22222222-2222-4222-8222-222222222222","name":"李医生"},
            "active_treatment_plan":null,
            "treatment_progress":null,
            "singing_summary":{"completed_session_count":0,"total_duration_seconds":0}
          },
          "request_id":"patient-me-auth-1"
        }
    """.trimIndent()

    private fun emptySuccess(requestId: String): String =
        """{"code":"ok","message":"","data":{},"request_id":"$requestId"}"""
}

private class InvalidationPublishGate {
    val claimed = CompletableDeferred<SessionInvalidation>()
    val release = CompletableDeferred<Unit>()

    suspend fun pause(invalidation: SessionInvalidation) {
        claimed.complete(invalidation)
        release.await()
    }
}

private class FailAfterRefreshWriteStore(context: Context) : VaultFileStore {
    private val file = context.getFileStreamPath(FILE_NAME)
    var failNextWrite = false

    override fun exists(): Boolean = file.exists()
    override fun readFully(): ByteArray = file.readBytes()
    override fun writeAtomically(payload: ByteArray) {
        file.writeBytes(payload)
        if (failNextWrite) throw IOException("模拟 refresh 密文写后失败")
    }
    override fun delete() {
        file.delete()
    }

    companion object {
        const val FILE_NAME = "vocaease-refresh-token-failure-test.vault"
    }
}
