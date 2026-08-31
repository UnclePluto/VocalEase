package com.vocaease.patient.feature.upload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qiniu.android.common.FixedZone
import com.qiniu.android.http.ResponseInfo
import com.qiniu.android.http.request.IRequestClient
import com.qiniu.android.http.request.Request
import com.qiniu.android.storage.Configuration
import com.qiniu.android.storage.FileRecorder
import com.qiniu.android.storage.UploadManager
import com.qiniu.android.storage.UploadOptions
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QiniuRealResumeTest {
    @Test
    fun 真实V2完成首分片后重建明文只传剩余分片且内容换代不复用() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val leaseRoot = context.cacheDir.resolve("real-qiniu-lease-${System.nanoTime()}")
        val recorderRoot = context.filesDir.resolve("real-qiniu-recorder-${System.nanoTime()}")
        val bytes = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        val certificate = HeldCertificate.Builder()
            .commonName("upload.test")
            .addSubjectAlternativeName("upload.test")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false) }
        val initCount = AtomicInteger()
        val partCounts = linkedMapOf<Int, Int>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.path)
                return when {
                    path.endsWith("/uploads") && request.method == "POST" -> {
                        initCount.incrementAndGet()
                        json("""{"uploadId":"upload-1","expireAt":4102444800}""")
                    }
                    request.method == "PUT" && "/uploads/upload-1/" in path -> {
                        val part = path.substringAfterLast('/').toInt()
                        synchronized(partCounts) { partCounts[part] = partCounts.getOrDefault(part, 0) + 1 }
                        json("""{"etag":"etag-$part","md5":"md5-$part"}""")
                    }
                    request.method == "POST" && path.endsWith("/uploads/upload-1") ->
                        json("""{"key":"object-a","hash":"hash-a"}""")
                    else -> MockResponse().setResponseCode(404).setHeader("X-Reqid", "test-request")
                        .setBody("""{"error":"unexpected request"}""")
                }
            }
        }
        server.start()
        try {
            val httpClient = OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .hostnameVerifier { _, _ -> true }
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        listOf(InetAddress.getByName("127.0.0.1"))
                })
                .build()
            fun manager(content: ByteArray) = PlaintextUploadLeaseManager(
                root = leaseRoot,
                opaqueJobId = "a".repeat(64),
                source = { _, destination -> destination.writeBytes(content); content.size.toLong() },
                nowEpochMillis = { 10_000L },
            )
            val media = UploadMedia("audio/mp4", bytes.size.toLong())
            val cancel = AtomicBoolean(false)

            val first = kotlinx.coroutines.runBlocking { manager(bytes).open(UploadMediaKind.AUDIO, media) }
            val sourceId = "${first.file.name}_${first.file.lastModified()}"
            val mediaRecorder = QiniuRecorderDirectory.forMedia(
                QiniuRecorderDirectory.create(recorderRoot, "b".repeat(64), "draft-real"),
                UploadMediaKind.AUDIO,
                sourceId,
            )
            val firstClient = CancelAfterFirstPartClient(safeClient(server, httpClient), cancel)
            val firstResult = upload(first.file, configuration(server, mediaRecorder, firstClient), cancel)
            assertTrue(firstResult.isCancelled)
            first.close()
            assertTrue(!first.file.exists())
            assertEquals(1, synchronized(partCounts) { partCounts[1] })

            cancel.set(false)
            val rebuilt = kotlinx.coroutines.runBlocking { manager(bytes).open(UploadMediaKind.AUDIO, media) }
            assertEquals(sourceId, "${rebuilt.file.name}_${rebuilt.file.lastModified()}")
            val resumedResult = upload(rebuilt.file, configuration(server, mediaRecorder, safeClient(server, httpClient)), cancel)
            rebuilt.close()

            assertTrue(resumedResult.isOK)
            assertEquals(1, initCount.get())
            assertEquals(1, synchronized(partCounts) { partCounts[1] })
            assertEquals(1, synchronized(partCounts) { partCounts[2] })
            assertEquals(1, synchronized(partCounts) { partCounts[3] })

            val changedBytes = bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
            val changed = kotlinx.coroutines.runBlocking { manager(changedBytes).open(UploadMediaKind.AUDIO, media) }
            val changedSourceId = "${changed.file.name}_${changed.file.lastModified()}"
            val changedRecorder = QiniuRecorderDirectory.forMedia(
                QiniuRecorderDirectory.create(recorderRoot, "b".repeat(64), "draft-real"),
                UploadMediaKind.AUDIO,
                changedSourceId,
            )
            assertTrue(changedSourceId != sourceId)
            val changedResult = upload(changed.file, configuration(server, changedRecorder, safeClient(server, httpClient)), cancel)
            changed.close()

            assertTrue(changedResult.isOK)
            assertEquals(2, initCount.get())
            assertEquals(2, synchronized(partCounts) { partCounts[1] })
        } finally {
            server.shutdown()
            leaseRoot.deleteRecursively()
            recorderRoot.deleteRecursively()
        }
    }

    private fun safeClient(server: MockWebServer, client: OkHttpClient) = SafeQiniuRequestClient(
        QiniuRequestUrlPolicy { it.isHttps && it.host == "upload.test" && it.port == server.port },
        client,
    )

    private fun configuration(server: MockWebServer, recorder: java.io.File, client: IRequestClient): Configuration =
        Configuration.Builder()
            .useHttps(true)
            .allowBackupHost(false)
            .accelerateUploading(false)
            .chunkSize(2 * 1024 * 1024)
            .putThreshold(4 * 1024 * 1024)
            .zone(FixedZone(arrayOf("https://upload.test:${server.port}")))
            .recorder(FileRecorder(recorder.absolutePath))
            .requestClient(client)
            .buildV2()

    private fun upload(
        file: java.io.File,
        configuration: Configuration,
        cancelled: AtomicBoolean,
    ): ResponseInfo {
        val latch = CountDownLatch(1)
        lateinit var result: ResponseInfo
        UploadManager(configuration).put(
            file,
            "object-a",
            token(),
            { _, info, _ -> result = info; latch.countDown() },
            UploadOptions(emptyMap(), "audio/mp4", false, null, cancelled::get),
        )
        assertTrue(latch.await(20, TimeUnit.SECONDS))
        return result
    }

    private fun token(): String {
        val policy = """{"scope":"bucket-a:object-a","deadline":4102444800}"""
        return "access-key:signature:${Base64.getUrlEncoder().withoutPadding().encodeToString(policy.toByteArray())}"
    }

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setHeader("X-Reqid", "test-request")
        .setBody(body)

    private class CancelAfterFirstPartClient(
        private val delegate: IRequestClient,
        private val cancelled: AtomicBoolean,
    ) : IRequestClient() {
        override fun request(
            request: Request,
            options: Options?,
            progress: Progress?,
            complete: CompleteHandler,
        ) {
            delegate.request(request, options, progress) { info, metrics, response ->
                complete.complete(info, metrics, response)
                if (info.isOK && request.urlString.endsWith("/uploads/upload-1/1")) cancelled.set(true)
            }
        }

        override fun cancel() = delegate.cancel()
        override fun getClientId(): String = delegate.clientId
    }
}
