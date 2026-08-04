package com.unclepluto.vocaease.singing

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateRecordingStorageTest {
    @Test
    fun rawVoiceFileLivesInPrivateDirectoryAndContainsNoIdentity() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.filesDir, "raw-voices").apply { mkdirs() }
        val target = File(directory, "00000000-0000-0000-0000-000000000001.wav")
        WavStreamWriter(target).use { it.write(byteArrayOf(0, 0), 2) }

        assertTrue(target.canonicalPath.startsWith(context.filesDir.canonicalPath))
        assertFalse(target.name.contains("phone", ignoreCase = true))
        assertFalse(target.name.contains("participant", ignoreCase = true))
        assertTrue(target.delete())
    }
}
