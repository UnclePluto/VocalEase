package com.vocaease.patient.feature.upload

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        val active = root.resolve("d".repeat(64)).apply { mkdirs(); setLastModified(1) }
        active.resolve("active.upload").apply { writeText("x"); setLastModified(1) }
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
}
