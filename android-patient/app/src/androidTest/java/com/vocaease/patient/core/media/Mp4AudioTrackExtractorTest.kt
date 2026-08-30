package com.vocaease.patient.core.media

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import android.system.Os
import android.system.OsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Mp4AudioTrackExtractorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 真实AVC_AAC样本无损抽取为仅含AAC的audioMp4并保留时长与样本标志() {
        val source = asset("sample_avc_aac.mp4")
        val output = File(context.cacheDir, "audio-${System.nanoTime()}.recording")

        val result = Mp4AudioTrackExtractor().extract(source, output)
        val sourceVideo = mediaInfo(source).single { it.mime.startsWith("video/") }
        val outputTracks = mediaInfo(output)
        val sourceFlags = audioSampleFlags(source)
        val outputFlags = audioSampleFlags(output)

        assertEquals("video/mp4", result.videoMimeType)
        assertEquals("audio/mp4", result.audioMimeType)
        assertEquals(1, outputTracks.size)
        assertEquals("audio/mp4a-latm", outputTracks.single().mime)
        assertTrue(kotlin.math.abs(sourceVideo.durationUs - outputTracks.single().durationUs) <= 50_000)
        assertTrue(result.sampleCount > 0)
        assertTrue(result.videoSampleCount > 0)
        assertTrue(result.firstPresentationTimeUs >= 0)
        assertTrue(result.lastPresentationTimeUs >= result.firstPresentationTimeUs)
        assertEquals(sourceFlags.size, result.sampleCount)
        assertEquals(sourceFlags.map(::expectedMuxerFlags), outputFlags)
        assertTrue(output.length() > 0)
        output.delete()
        source.delete()
    }

    @Test
    fun 无音轨与损坏容器拒绝且不留下输出() {
        val videoOnly = asset("sample_avc_only.mp4")
        val corrupt = File(context.cacheDir, "corrupt-${System.nanoTime()}.recording").apply { writeText("not mp4") }
        val output = File(context.cacheDir, "bad-output-${System.nanoTime()}.recording")

        assertThrows(MediaValidationException::class.java) {
            Mp4AudioTrackExtractor().extract(videoOnly, output)
        }
        assertThrows(MediaValidationException::class.java) {
            Mp4AudioTrackExtractor().extract(corrupt, output)
        }
        assertTrue(!output.exists() || output.length() == 0L)
        videoOnly.delete()
        corrupt.delete()
        output.delete()
    }

    @Test
    fun 视频与音频时长相差超过50毫秒时拒绝发布() {
        val mismatch = asset("sample_avc_aac_mismatch.mp4")
        val output = File(context.cacheDir, "mismatch-output-${System.nanoTime()}.recording")

        assertThrows(MediaValidationException::class.java) {
            Mp4AudioTrackExtractor().extract(mismatch, output)
        }

        assertTrue(!output.exists() || output.length() == 0L)
        mismatch.delete()
        output.delete()
    }

    @Test
    fun 接受私有临时工厂预创建的零长度0600输出并安全替换() {
        val source = asset("sample_avc_aac.mp4")
        val output = PrivateRecordingTempFiles(context).createAudio()

        val result = Mp4AudioTrackExtractor().extract(source, output)

        assertEquals("audio/mp4", result.audioMimeType)
        assertTrue(output.length() > 0)
        assertEquals(OsConstants.S_IRUSR or OsConstants.S_IWUSR, Os.stat(output.absolutePath).st_mode and 0x1ff)
        output.delete()
        source.delete()
    }

    @Test
    fun CameraX上报时长溢出值必须拒绝且不留下输出() {
        val source = asset("sample_avc_aac.mp4")
        val output = File(context.cacheDir, "overflow-output-${System.nanoTime()}.recording")

        assertThrows(MediaValidationException::class.java) {
            Mp4AudioTrackExtractor().extract(source, output, Long.MAX_VALUE)
        }

        assertTrue(!output.exists())
        source.delete()
    }

    private fun asset(name: String): File = File(context.cacheDir, "fixture-${System.nanoTime()}-$name").also { target ->
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            target.outputStream().use(input::copyTo)
        }
    }

    private fun mediaInfo(file: File): List<TrackInfo> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map { index ->
                val format = extractor.getTrackFormat(index)
                TrackInfo(
                    format.getString(MediaFormat.KEY_MIME).orEmpty(),
                    format.getLong(MediaFormat.KEY_DURATION),
                )
            }
        } finally {
            extractor.release()
        }
    }

    private fun audioSampleFlags(file: File): List<Int> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val audioTrack = (0 until extractor.trackCount).single { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            }
            extractor.selectTrack(audioTrack)
            val sample = ByteBuffer.allocate(64 * 1024)
            buildList {
                while (true) {
                    sample.clear()
                    if (extractor.readSampleData(sample, 0) < 0) break
                    add(extractor.sampleFlags)
                    if (!extractor.advance()) break
                }
            }
        } finally {
            extractor.release()
        }
    }

    private fun expectedMuxerFlags(sourceFlags: Int): Int {
        var flags = 0
        if (sourceFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (sourceFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            flags = flags or android.media.MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    private data class TrackInfo(val mime: String, val durationUs: Long)
}
