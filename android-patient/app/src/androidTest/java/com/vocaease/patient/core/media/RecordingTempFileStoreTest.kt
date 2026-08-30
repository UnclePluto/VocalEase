package com.vocaease.patient.core.media

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingTempFileStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 临时文件位于应用私有随机目录且权限0600并以recording结尾() {
        val store = PrivateRecordingTempFiles(context)
        val first = store.createVideo()
        val second = store.createVideo()

        assertTrue(first.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        assertTrue(first.name.endsWith(".recording"))
        assertNotEquals(first.name, second.name)
        assertEquals(OsConstants.S_IRUSR or OsConstants.S_IWUSR, Os.stat(first.absolutePath).st_mode and 0x1ff)
        store.cleanup(first, second)
    }

    @Test
    fun 清理过期orphan但保留仍在宽限期内的临时文件() {
        var now = 2_000_000L
        val store = PrivateRecordingTempFiles(context, nowMillis = { now }, orphanMaxAgeMillis = 1_000L)
        val old = store.createVideo().apply { setLastModified(now - 1_001L) }
        val recent = store.createAudio().apply { setLastModified(now) }

        store.cleanupOrphans()

        assertFalse(old.exists())
        assertTrue(recent.exists())
        store.cleanup(recent)
    }

    @Test
    fun 应用重启默认立即清理上一进程遗留的全部recording明文() {
        val crashedProcessStore = PrivateRecordingTempFiles(context)
        val orphanVideo = crashedProcessStore.createVideo()
        val orphanAudio = crashedProcessStore.createAudio()

        PrivateRecordingTempFiles(context).cleanupOrphans()

        assertFalse(orphanVideo.exists())
        assertFalse(orphanAudio.exists())
    }
}
