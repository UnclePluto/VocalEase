package com.vocaease.patient.feature.upload

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QiniuV2UploaderTest {
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
        )
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { QiniuV2Configuration.validatedHost(value) }
        }
        assertEquals("https://upload.qiniup.com", QiniuV2Configuration.validatedHost("https://upload.qiniup.com/"))
    }

    @Test
    fun `recorder按opaque job隔离且拒绝路径穿越和symlink`() {
        val root = createTempDirectory("qiniu-root-").toFile()
        val outside = createTempDirectory("qiniu-outside-").toFile()
        try {
            val safe = QiniuRecorderDirectory.create(root, "a".repeat(64), "draft-1")
            assertTrue(safe.isDirectory)
            assertTrue(safe.canonicalPath.startsWith(root.canonicalPath + File.separator))
            assertThrows(IllegalArgumentException::class.java) { QiniuRecorderDirectory.create(root, "a".repeat(64), "../outside") }
            val link = File(root, "link")
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { QiniuRecorderDirectory.create(link, "a".repeat(64), "draft-1") }
        } finally {
            root.deleteRecursively(); outside.deleteRecursively()
        }
    }
}
