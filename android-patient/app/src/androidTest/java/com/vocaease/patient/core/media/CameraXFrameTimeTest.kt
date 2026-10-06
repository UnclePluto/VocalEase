package com.vocaease.patient.core.media

import android.Manifest
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.camera.view.PreviewView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CameraXFrameTimeTest {
    @get:Rule val activity=ActivityScenarioRule(ComponentActivity::class.java)
    @Test fun delayedStartedKeepsActualEncodedFrameOrigin()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        for(permission in listOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO))
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName,permission)
        lateinit var camera:CameraXRecordingCapture
        activity.scenario.onActivity { host ->
            val preview=PreviewView(host);host.setContentView(preview)
            camera=CameraXRecordingCapture(host,host,preview.surfaceProvider,viewPortProvider={preview.viewPort})
        }
        var target:suspend(CaptureEvent)->Unit={}
        val delayed=object:RecordingCapture by camera {
            override var listener:suspend(CaptureEvent)->Unit
                get()=target
                set(value){target=value;camera.listener={event -> if(event===CaptureEvent.Started) delay(250);target(event)}}
        }
        val capture=PatientRecordingCapture(delayed)
        val started=CompletableDeferred<Unit>();val finished=CompletableDeferred<CaptureEvent.Finalized>()
        val output=File(instrumentation.targetContext.cacheDir,"frame-time.mp4")
        capture.listener={event -> when(event){CaptureEvent.Started->started.complete(Unit);is CaptureEvent.Finalized->finished.complete(event);is CaptureEvent.Failure->{val error=IllegalStateException(event.reason.name);started.completeExceptionally(error);finished.completeExceptionally(error)}}}
        try {
            capture.bindFrontCamera();capture.start(output)
            withTimeout(15000){started.await()}
            assertTrue(System.nanoTime()-checkNotNull(capture.captureStartNanos)>=250_000_000L)
            delay(1500);capture.stop()
            val event=withTimeout(15000){finished.await()}
            assertTrue(event.durationMillis>=1000)
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath);assertEquals(2,extractor.trackCount)
                val audio=(0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm" }
                extractor.selectTrack(audio)
                assertTrue("患者 AAC 首样本不能带入 250 ms 回调延迟",extractor.sampleTime in 0..100_000)
            } finally {extractor.release()}
        } finally {capture.release();output.delete()}
        Unit
    }
}
