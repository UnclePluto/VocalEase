package com.vocaease.patient.core.media

import androidx.test.platform.app.InstrumentationRegistry
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.runBlocking
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class RecordedAvMuxerTest {
    @Test fun copiesOneVideoAndOnePatientAacTrack() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val root=instrumentation.targetContext.cacheDir
        val video=java.io.File(root,"mux-video.mp4");val audio=java.io.File(root,"mux-patient.m4a");val output=java.io.File(root,"mux-output.mp4")
        try {
            instrumentation.context.assets.open("sample_avc_only.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
            val encoder=PatientAudioEncoder(audio,48000)
            try {
                for (block in 0 until 50) encoder.write(ShortArray(1920) { (10000*sin(2*PI*220*(block*1920+it)/48000)).toInt().toShort() },1920,block*40000L)
                encoder.finish(2_000_000)
            } finally { encoder.release() }
            val result=RecordedAvMuxer().merge(video,audio,output,1_000_000_000,1_010_000_000)
            assertTrue(result.durationMillis>0)
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                assertEquals(2,extractor.trackCount)
                val mimes=(0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                assertTrue(mimes.contains("video/avc"));assertTrue(mimes.contains("audio/mp4a-latm"))
            } finally { extractor.release() }
        } finally { video.delete();audio.delete();output.delete() }
        Unit
    }
}
