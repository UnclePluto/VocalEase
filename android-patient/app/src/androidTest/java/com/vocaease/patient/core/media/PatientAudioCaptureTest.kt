package com.vocaease.patient.core.media
import androidx.test.platform.app.InstrumentationRegistry
import android.media.MediaExtractor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PatientAudioCaptureTest {
    @Test fun realMicrophoneEncodesAacAtSupportedRate() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.RECORD_AUDIO").close()
        val audio=java.io.File(instrumentation.targetContext.cacheDir,"capture-patient.m4a")
        val capture=MicrophonePcmCapture();val first=CompletableDeferred<PcmBlock>()
        try {
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
