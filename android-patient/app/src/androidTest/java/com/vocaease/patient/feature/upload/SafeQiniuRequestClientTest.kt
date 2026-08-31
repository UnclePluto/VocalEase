package com.vocaease.patient.feature.upload

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qiniu.android.http.ResponseInfo
import com.qiniu.android.http.request.IRequestClient
import com.qiniu.android.http.request.Request
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafeQiniuRequestClientTest {
    @Test
    fun 真实请求客户端禁止所有重定向且绝不向第二跳发token() {
        val certificate = HeldCertificate.Builder()
            .commonName("upload.test")
            .addSubjectAlternativeName("upload.test")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val trusted = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start() }
        val evil = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start() }
        try {
            val client = OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .hostnameVerifier { _, _ -> true }
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        listOf(InetAddress.getByName("127.0.0.1"))
                })
                .build()
            val policy = QiniuRequestUrlPolicy { url ->
                url.isHttps && url.host == "upload.test" && url.port == trusted.port
            }

            listOf(301, 302, 307, 308).forEach { code ->
                trusted.enqueue(
                    MockResponse().setResponseCode(code)
                        .setHeader("Location", "https://evil.test:${evil.port}/stolen"),
                )
                val result = request(
                    SafeQiniuRequestClient(policy, client),
                    "https://upload.test:${trusted.port}/upload",
                )
                assertEquals(code, result.statusCode)
            }

            assertEquals(4, trusted.requestCount)
            assertEquals(0, evil.requestCount)
            repeat(4) {
                val received = trusted.takeRequest(1, TimeUnit.SECONDS)
                assertNotNull(received)
                assertEquals("UpToken highly-sensitive", received!!.headers["Authorization"])
            }
        } finally {
            trusted.shutdown()
            evil.shutdown()
        }
    }

    @Test
    fun 非白名单首跳在发送请求体前封闭失败() {
        val client = SafeQiniuRequestClient(QiniuRequestUrlPolicy { false }, OkHttpClient())
        val result = request(client, "https://evilqiniup.com/upload")

        assertTrue(!result.isOK)
        assertEquals(ResponseInfo.InvalidArgument, result.statusCode)
    }

    private fun request(client: SafeQiniuRequestClient, url: String): ResponseInfo {
        val latch = CountDownLatch(1)
        lateinit var result: ResponseInfo
        client.request(
            Request(
                url,
                Request.HttpMethodPOST,
                mapOf("Authorization" to "UpToken highly-sensitive", "Content-Type" to "application/octet-stream"),
                "payload".toByteArray(),
                10,
            ),
            IRequestClient.Options(null, true, null),
            { _, _ -> },
            { info, _, _ -> result = info; latch.countDown() },
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result
    }
}
