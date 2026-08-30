package com.vocaease.patient.feature.upload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qiniu.android.common.FixedZone
import com.qiniu.android.storage.Configuration
import com.qiniu.android.storage.FileRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QiniuV2ConfigurationTest {
    @Test
    fun 配置固定V2HTTPSFixedZone与私有FileRecorder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.resolve("qiniu-config-${System.nanoTime()}").apply { mkdirs() }
        try {
            val configuration = QiniuV2Configuration.create(root, "https://upload.qiniup.com")
            assertTrue(configuration.useHttps)
            assertEquals(Configuration.RESUME_UPLOAD_VERSION_V2, configuration.resumeUploadVersion)
            assertTrue(configuration.zone is FixedZone)
            assertTrue(configuration.recorder is FileRecorder)
            assertFalse(configuration.accelerateUploading)
            assertFalse(configuration.allowBackupHost)
        } finally {
            root.deleteRecursively()
        }
    }
}
