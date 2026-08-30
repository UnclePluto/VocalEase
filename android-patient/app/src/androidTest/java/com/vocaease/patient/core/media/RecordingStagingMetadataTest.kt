package com.vocaease.patient.core.media

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingStagingMetadataTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var files: PrivateRecordingTempFiles

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "recordings/plaintext")
        root.deleteRecursively()
        files = PrivateRecordingTempFiles(context, nowMillis = { 1_000L }, orphanMaxAgeMillis = 0L)
    }

    @After
    fun tearDown() { root.deleteRecursively() }

    @Test
    fun 创建录制同时原子发布0600账户绑定恢复元数据() {
        val identity = RecordingStagingIdentity(
            accountScopeHash = "a".repeat(64),
            draftId = "draft-1",
            sessionId = "session-1",
            creationKey = "session-create:key:draft-1",
        )

        val video = files.createVideo(identity)
        video.writeBytes(byteArrayOf(1, 2, 3))
        val entries = files.listRecoverable(identity.accountScopeHash)

        assertEquals(1, entries.size)
        assertEquals(identity, entries.single().identity)
        assertEquals(video.canonicalPath, entries.single().video.canonicalPath)
        assertEquals(1_000L, entries.single().createdAtMillis)
        listOf(video, entries.single().metadata).forEach { file ->
            assertEquals(0, Os.stat(file.absolutePath).st_mode and (OsConstants.S_IRWXG or OsConstants.S_IRWXO))
        }
    }

    @Test
    fun 孤儿清理保留带合法sidecar的24小时内录制且不碰其他账户() {
        val current = RecordingStagingIdentity("a".repeat(64), "draft-a", "session-a", "create-a")
        val other = RecordingStagingIdentity("b".repeat(64), "draft-b", "session-b", "create-b")
        val currentVideo = files.createVideo(current).apply { writeBytes(byteArrayOf(1)) }
        val otherVideo = files.createVideo(other).apply { writeBytes(byteArrayOf(2)) }
        val orphan = File(root, "video-${"c".repeat(32)}.recording").apply {
            writeBytes(byteArrayOf(3)); setLastModified(0)
        }

        files.cleanupOrphans()

        assertTrue(currentVideo.exists())
        assertTrue(otherVideo.exists())
        assertFalse(orphan.exists())
        assertTrue(files.listRecoverable(current.accountScopeHash).none { it.identity.accountScopeHash != current.accountScopeHash })
        assertEquals(1, files.listRecoverable(other.accountScopeHash).size)
    }

    @Test
    fun 恶意或损坏sidecar不会返回路径并只删除同basename文件() {
        val outside = File(context.filesDir, "must-stay-${System.nanoTime()}").apply { writeText("safe") }
        val base = "video-${"d".repeat(32)}"
        File(root, "$base.recording").writeBytes(byteArrayOf(1))
        File(root, "$base.recovery").writeText("../../${outside.name}")

        val entries = files.listRecoverable("a".repeat(64))

        assertTrue(entries.isEmpty())
        assertTrue(outside.exists())
        assertFalse(File(root, "$base.recording").exists())
        outside.delete()
    }
}
