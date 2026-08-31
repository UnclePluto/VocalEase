package com.vocaease.patient.feature.upload

import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QiniuV2UploaderTest {
    @Test
    fun `cancel立即结束调用且迟到callback和progress不能二次推进`() = runBlocking {
        val root = createTempDirectory("qiniu-cancel-").toFile()
        val file = File(root, "audio-generation.upload").apply { writeBytes(ByteArray(4)) }
        val started = CountDownLatch(1)
        lateinit var lateCallback: (QiniuSdkResult) -> Unit
        lateinit var lateProgress: (Int) -> Unit
        val uploader = QiniuV2Uploader(root, UploadHostAllowlist { true }) { _, _, _, progress, _, callback ->
            lateProgress = progress
            lateCallback = callback
            started.countDown()
        }
        val progress = mutableListOf<Int>()
        val running = async(Dispatchers.Default) { uploader.upload(request(file), progress::add) }
        started.await()

        uploader.cancel()

        assertEquals(QiniuUploadResult.Cancelled, running.await())
        lateProgress(90)
        lateCallback(QiniuSdkResult.Completed("object-a", "object-a"))
        lateCallback(QiniuSdkResult.Failed)
        assertTrue(progress.isEmpty())
        root.deleteRecursively()
        Unit
    }

    @Test
    fun `竞争callback只有首个可信结果能完成continuation`() = runBlocking {
        val root = createTempDirectory("qiniu-race-").toFile()
        val file = File(root, "audio-generation.upload").apply { writeBytes(ByteArray(4)) }
        lateinit var callback: (QiniuSdkResult) -> Unit
        val started = CountDownLatch(1)
        val uploader = QiniuV2Uploader(root, UploadHostAllowlist { true }) { _, _, _, _, _, result ->
            callback = result
            started.countDown()
        }
        val running = async(Dispatchers.Default) { uploader.upload(request(file)) {} }
        started.await()
        val gate = CountDownLatch(1)
        val success = Thread { gate.await(); callback(QiniuSdkResult.Completed("object-a", "object-a")) }
        val failure = Thread { gate.await(); callback(QiniuSdkResult.Cancelled) }
        success.start(); failure.start(); gate.countDown(); success.join(); failure.join()

        val result = running.await()
        assertTrue(result == QiniuUploadResult.Completed("object-a") || result == QiniuUploadResult.Cancelled)
        root.deleteRecursively()
        Unit
    }

    @Test
    fun `上传地址拒绝降级用户信息fragment query路径和异常端口`() {
        val invalid = listOf(
            "http://upload.qiniup.com",
            "https://user@upload.qiniup.com",
            "https://upload.qiniup.com/#fragment",
            "https://upload.qiniup.com?next=x",
            "https://upload.qiniup.com/path",
            "https://upload.qiniup.com:444",
            "https://127.0.0.1",
            "https://evilqiniup.com",
            "https://qiniup.com",
            "https://upload.example.com",
        )
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { QiniuV2Configuration.validatedHost(value) }
        }
        assertEquals("https://upload.qiniup.com", QiniuV2Configuration.validatedHost("https://upload.qiniup.com/"))
        assertEquals("https://up-cn-east-2.qiniup.com", QiniuV2Configuration.validatedHost("https://up-cn-east-2.qiniup.com"))
        assertEquals(
            "https://trusted.upload.test",
            QiniuV2Configuration.validatedHost("https://trusted.upload.test", UploadHostAllowlist { it == "trusted.upload.test" }),
        )
    }

    @Test
    fun `recorder按opaque job隔离且拒绝路径穿越和symlink`() {
        val root = createTempDirectory("qiniu-root-").toFile()
        val outside = createTempDirectory("qiniu-outside-").toFile()
        try {
            val safe = QiniuRecorderDirectory.create(root, "a".repeat(64), "draft-1")
            assertTrue(safe.isDirectory)
            assertTrue(safe.canonicalPath.startsWith(root.canonicalPath + File.separator))
            val audio = QiniuRecorderDirectory.forMedia(safe, UploadMediaKind.AUDIO, "audio-source-id")
            val video = QiniuRecorderDirectory.forMedia(safe, UploadMediaKind.VIDEO, "video-source-id")
            assertTrue(audio != video)
            assertTrue(audio.canonicalPath.startsWith(safe.canonicalPath + File.separator))
            assertTrue(video.canonicalPath.startsWith(safe.canonicalPath + File.separator))
            assertThrows(IllegalArgumentException::class.java) { QiniuRecorderDirectory.create(root, "a".repeat(64), "../outside") }
            val link = File(root, "link")
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { QiniuRecorderDirectory.create(link, "a".repeat(64), "draft-1") }
        } finally {
            root.deleteRecursively(); outside.deleteRecursively()
        }
    }

    private fun request(file: File) = QiniuUploadRequest(
        kind = UploadMediaKind.AUDIO,
        grant = UploadGrant(
            binding = UploadBinding("session-a", "asset-a", "object-a", "audio/mp4", 4),
            expiresAtEpochMillis = 10_000,
            uploadUrl = "https://trusted.upload.test",
            uploadToken = "short-lived-token",
        ),
        file = file,
        mimeType = "audio/mp4",
    )
}
