package com.vocaease.patient

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.network.NetworkModule
import com.vocaease.patient.core.network.SessionExpiredException
import com.vocaease.patient.core.network.SessionLifecycleEvent
import com.vocaease.patient.core.security.AndroidTokenVault
import com.vocaease.patient.core.security.VaultFileStore
import com.vocaease.patient.feature.auth.AuthState
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        val authenticatedClient = NetworkModule.createAuthenticatedHttpClient(vault)
            .newBuilder()
            .addInterceptor(transport)
            .build()
        val graph = createProductionSessionGraph(
            baseUrl = "https://patient.test/",
            tokenVault = vault,
            clientOverride = authenticatedClient,
        )
        graph.authRepository.restoreSession()
        assertEquals(AuthState.Authenticated, graph.authRepository.state.value)
        val eventCount = AtomicInteger()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            graph.sessionEvents.collect { event ->
                if (event == SessionLifecycleEvent.SessionExpired) eventCount.incrementAndGet()
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
        assertEquals(1, transport.patientCalls.get())
        assertEquals(if (refreshSucceeds) 1 else 0, transport.refreshCalls.get())
        collector.cancel()
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

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = when (request.url.encodedPath) {
            "/api/v1/patient/me/" -> {
                patientCalls.incrementAndGet()
                unauthorized()
            }
            "/api/v1/auth/refresh/" -> {
                refreshCalls.incrementAndGet()
                if (refreshSucceeds) refreshSuccess() else unauthorized()
            }
            else -> error("未预期请求：${request.url.encodedPath}")
        }
        val code = if (request.url.encodedPath.endsWith("/refresh/") && refreshSucceeds) 200 else 401
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
