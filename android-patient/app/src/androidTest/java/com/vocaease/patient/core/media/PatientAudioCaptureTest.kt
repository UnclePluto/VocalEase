package com.vocaease.patient.core.media
import androidx.test.platform.app.InstrumentationRegistry
import android.media.MediaExtractor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PatientAudioCaptureTest {
    @get:org.junit.Rule val activity=androidx.test.ext.junit.rules.ActivityScenarioRule(androidx.activity.ComponentActivity::class.java)
    @Test fun realMicrophoneEncodesAacAtSupportedRate() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName,android.Manifest.permission.RECORD_AUDIO)
        val audio=java.io.File(instrumentation.targetContext.cacheDir,"capture-patient.m4a")
        val capture=MicrophonePcmCapture();val first=CompletableDeferred<PcmBlock>()
        try {
            capture.onFailure = { first.completeExceptionally(it) }
            capture.start(audio) { first.complete(it) }
            val block=withTimeout(5000) { first.await() }
            assertTrue(block.sampleRate in listOf(48000,44100));delay(1500)
            val result=capture.stop();assertTrue(result.durationMillis>=1000)
            val extractor=MediaExtractor()
            try { extractor.setDataSource(audio.absolutePath);assertEquals(1,extractor.trackCount) } finally { extractor.release() }
        } finally { capture.release();audio.delete() }
        Unit
    }
}
