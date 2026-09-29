package com.vocaease.patient.feature.upload

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qiniu.android.storage.FileRecorder
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaintextUploadLeaseTest {
    @Test
    fun 重建同代明文保持真实SDKsourceId和断点记录而内容或媒体变化隔离旧记录() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.resolve("upload-lease-resume-${System.nanoTime()}")
        val recorderRoot = context.filesDir.resolve("upload-recorder-resume-${System.nanoTime()}")
        val bytes = ByteArray(17) { it.toByte() }
        fun manager(content: ByteArray) = PlaintextUploadLeaseManager(
            root = root,
            opaqueJobId = "f".repeat(64),
            source = { _, destination -> destination.writeBytes(content); content.size.toLong() },
            nowEpochMillis = { 10_000 },
        )

        val first = kotlinx.coroutines.runBlocking { manager(bytes).open(UploadMediaKind.AUDIO, UploadMedia("audio/mp4", 17)) }
        val firstId = realQiniuSourceId(first.file)
        val recorder = FileRecorder(recorderRoot.absolutePath)
        recorder.set(firstId, "completed-parts=0,1".toByteArray())
        first.close()

        val rebuilt = kotlinx.coroutines.runBlocking { manager(bytes).open(UploadMediaKind.AUDIO, UploadMedia("audio/mp4", 17)) }
        try {
            assertEquals(firstId, realQiniuSourceId(rebuilt.file))
            assertEquals("completed-parts=0,1", recorder.get(realQiniuSourceId(rebuilt.file)).decodeToString())
        } finally {
            rebuilt.close()
        }

        val changed = kotlinx.coroutines.runBlocking { manager(bytes.reversedArray()).open(UploadMediaKind.AUDIO, UploadMedia("audio/mp4", 17)) }
        try {
            assertTrue(firstId != realQiniuSourceId(changed.file))
            assertEquals(null, recorder.get(realQiniuSourceId(changed.file)))
        } finally {
            changed.close()
        }
        val otherMedia = kotlinx.coroutines.runBlocking { manager(bytes).open(UploadMediaKind.VIDEO, UploadMedia("video/mp4", 17)) }
        try {
            assertTrue(firstId != realQiniuSourceId(otherMedia.file))
        } finally {
            otherMedia.close()
        }
        root.deleteRecursively(); recorderRoot.deleteRecursively()
    }

    @Test
    fun lease目录0700文件0600且close清理() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.resolve("upload-lease-${System.nanoTime()}")
        val manager = PlaintextUploadLeaseManager(
            root = root,
            opaqueJobId = "a".repeat(64),
            source = { _, destination -> destination.writeBytes(ByteArray(17) { 7 }); 17 },
            nowEpochMillis = { 10_000 },
        )
        val lease = kotlinx.coroutines.runBlocking { manager.open(UploadMediaKind.AUDIO, UploadMedia("audio/mp4", 17)) }
        try {
            assertEquals(0x1c0, Os.stat(lease.file.parentFile!!.absolutePath).st_mode and 0x1ff)
            assertEquals(0x180, Os.stat(lease.file.absolutePath).st_mode and 0x1ff)
            assertEquals(17, lease.file.length())
        } finally {
            lease.close()
        }
        assertFalse(root.resolve("a".repeat(64)).exists())
        root.deleteRecursively()
    }

    @Test
    fun 长度错误异常与取消都不遗留明文() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.resolve("upload-lease-bad-${System.nanoTime()}")
        val manager = PlaintextUploadLeaseManager(
            root, "b".repeat(64),
            source = { _, destination -> destination.writeBytes(ByteArray(3)); 3 },
            nowEpochMillis = { 1 },
        )
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { manager.open(UploadMediaKind.VIDEO, UploadMedia("video/mp4", 4)) }
        }
        assertTrue(!root.exists() || root.walkTopDown().none { it.isFile })
        root.deleteRecursively()
    }

    @Test
    fun 启动只清理超过一小时普通遗留且不跟随symlink或删除活动目录() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.resolve("upload-lease-clean-${System.nanoTime()}").apply { mkdirs() }
        val old = root.resolve("c".repeat(64)).apply { mkdirs(); setLastModified(1) }
        old.resolve("dead.upload").apply { writeText("x"); setLastModified(1) }
        old.setLastModified(1)
        val active = root.resolve("d".repeat(64)).apply { mkdirs(); setLastModified(1) }
        active.resolve("active.upload").apply { writeText("x"); setLastModified(1) }
        active.setLastModified(1)
        val outside = context.cacheDir.resolve("outside-${System.nanoTime()}").apply { writeText("keep") }
        val link = root.resolve("e".repeat(64))
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        PlaintextUploadLeaseManager.cleanupOrphans(root, nowEpochMillis = 3_700_002, activeOpaqueJobIds = setOf("d".repeat(64)))

        assertFalse(old.exists())
        assertTrue(active.isDirectory)
        assertTrue(outside.isFile)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        link.delete(); outside.delete(); root.deleteRecursively()
    }

    @Test
    fun 稳定source时间不能让新建遗留被误判为超过一小时() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.resolve("upload-lease-age-${System.nanoTime()}").apply { mkdirs() }
        val job = root.resolve("f".repeat(64)).apply { mkdirs() }
        val stableSourceTime = 946_684_800_000L
        val now = stableSourceTime + 3_700_000L
        job.resolve("audio-generation.upload").apply {
            writeText("x")
            setLastModified(stableSourceTime)
        }
        job.setLastModified(now - 1_000L)

        PlaintextUploadLeaseManager.cleanupOrphans(root, nowEpochMillis = now)
        assertTrue(job.exists())

        job.setLastModified(now - 3_600_001L)
        PlaintextUploadLeaseManager.cleanupOrphans(root, nowEpochMillis = now)
        assertFalse(job.exists())
        root.deleteRecursively()
    }

    @Test
    fun 启动同时清理账户二级目录遗留并保留其中活动job() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.resolve("upload-lease-nested-${System.nanoTime()}").apply { mkdirs() }
        val account = root.resolve("a".repeat(64)).apply { mkdirs() }
        val old = account.resolve("b".repeat(64)).apply { mkdirs(); setLastModified(1) }
        old.resolve("dead.upload").writeText("x")
        old.setLastModified(1)
        val active = account.resolve("c".repeat(64)).apply { mkdirs(); setLastModified(1) }
        active.resolve("active.upload").writeText("x")
        active.setLastModified(1)

        PlaintextUploadLeaseManager.cleanupOrphans(
            root,
            nowEpochMillis = 3_700_002,
            activeOpaqueJobIds = setOf("c".repeat(64)),
        )

        assertFalse(old.exists())
        assertTrue(active.exists())
        root.deleteRecursively()
    }

    private fun realQiniuSourceId(file: File): String {
        val type = Class.forName("com.qiniu.android.storage.UploadSourceFile")
        val constructor = type.getDeclaredConstructor(File::class.java).apply { isAccessible = true }
        val source = constructor.newInstance(file)
        return try {
            type.getMethod("getId").invoke(source) as String
        } finally {
            type.getMethod("close").invoke(source)
        }
    }
}
