package com.vocaease.patient.core.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs

class MediaValidationException internal constructor() : java.io.IOException("录制媒体校验失败")

data class ExtractedAudioTrack(
    val videoContainerMimeType: String,
    val audioContainerMimeType: String,
    val videoCodecMimeType: String,
    val audioCodecMimeType: String,
    val sourceDurationUs: Long,
    val audioDurationUs: Long,
    val sampleCount: Int,
    val videoSampleCount: Int,
    val firstPresentationTimeUs: Long,
    val lastPresentationTimeUs: Long,
)

/** 只复制 MP4 中现有的音频 sample；不解码、不重编码。 */
class Mp4AudioTrackExtractor {
    fun extract(sourceVideo: File, outputAudio: File, expectedDurationMillis: Long? = null): ExtractedAudioTrack {
        if (!sourceVideo.isFile || sourceVideo.length() <= 0) fail(outputAudio)
        if (outputAudio.exists()) {
            if (!outputAudio.isFile || outputAudio.length() != 0L || !outputAudio.delete()) fail(outputAudio)
        }
        val source = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            source.setDataSource(sourceVideo.absolutePath)
            val videoContainerMime = containerMime(sourceVideo)
            if (videoContainerMime != VIDEO_CONTAINER_MIME) fail(outputAudio)
            val tracks = (0 until source.trackCount).map { index -> index to source.getTrackFormat(index) }
            val audioTracks = tracks.filter { (_, format) -> format.mime().startsWith("audio/") }
            val videoTracks = tracks.filter { (_, format) -> format.mime().startsWith("video/") }
            if (audioTracks.size != 1 || videoTracks.size != 1 || tracks.size != 2) fail(outputAudio)
            val (audioIndex, audioFormat) = audioTracks.single()
            val audioCodecMime = audioFormat.mime()
            val videoCodecMime = videoTracks.single().second.mime()
            if (audioCodecMime != AAC_MIME) fail(outputAudio)
            if (videoCodecMime != AVC_MIME) fail(outputAudio)
            val videoDurationUs = videoTracks.single().second.requiredDurationUs()
            val audioDurationUs = audioFormat.requiredDurationUs()
            if (videoDurationUs <= 0 || audioDurationUs <= 0 ||
                abs(videoDurationUs - audioDurationUs) > DURATION_TOLERANCE_US
            ) fail(outputAudio)
            expectedDurationMillis?.let { expected ->
                if (expected <= 0 || expected > Long.MAX_VALUE / 1_000L ||
                    abs(videoDurationUs - expected * 1_000L) > DURATION_TOLERANCE_US
                ) fail(outputAudio)
            }
            val videoSampleCount = inspectTrackSamples(sourceVideo, videoTracks.single().first, requireMonotonicPts = false)
            outputAudio.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) fail(outputAudio)
            }
            muxer = MediaMuxer(outputAudio.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            securePlaintext(outputAudio)
            val outputTrack = muxer.addTrack(audioFormat)
            muxer.start()
            muxerStarted = true
            source.selectTrack(audioIndex)
            val capacity = audioFormat.maxInputSize().coerceIn(MIN_BUFFER_BYTES, MAX_BUFFER_BYTES)
            val buffer = ByteBuffer.allocateDirect(capacity)
            val info = MediaCodec.BufferInfo()
            var sampleCount = 0
            var firstPts = -1L
            var lastPts = -1L
            var previousSourcePts = Long.MIN_VALUE
            var ptsOffset = 0L
            while (true) {
                buffer.clear()
                val size = source.readSampleData(buffer, 0)
                if (size < 0) break
                val sourcePts = source.sampleTime
                if (size == 0 || sourcePts < previousSourcePts) fail(outputAudio)
                if (sampleCount == 0 && sourcePts < 0) ptsOffset = -sourcePts
                val pts = sourcePts + ptsOffset
                if (firstPts < 0) firstPts = pts
                lastPts = pts
                previousSourcePts = sourcePts
                info.set(0, size, pts, mediaMuxerFlags(source.sampleFlags))
                buffer.position(0)
                buffer.limit(size)
                muxer.writeSampleData(outputTrack, buffer, info)
                sampleCount += 1
                if (!source.advance()) break
            }
            if (sampleCount == 0 || firstPts < 0 || lastPts < firstPts) fail(outputAudio)
            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null
            securePlaintext(outputAudio)
            val output = inspectAudioOnly(outputAudio)
            val audioContainerMime = containerMime(outputAudio)
            if (audioContainerMime != AUDIO_CONTAINER_MIME) fail(outputAudio)
            if (abs(videoDurationUs - output.durationUs) > DURATION_TOLERANCE_US) fail(outputAudio)
            if (output.sampleCount != sampleCount) fail(outputAudio)
            if (outputAudio.length() <= 0) fail(outputAudio)
            return ExtractedAudioTrack(
                videoContainerMimeType = videoContainerMime,
                audioContainerMimeType = audioContainerMime,
                videoCodecMimeType = videoCodecMime,
                audioCodecMimeType = audioCodecMime,
                sourceDurationUs = videoDurationUs,
                audioDurationUs = output.durationUs,
                sampleCount = sampleCount,
                videoSampleCount = videoSampleCount,
                firstPresentationTimeUs = firstPts,
                lastPresentationTimeUs = lastPts,
            )
        } catch (error: MediaValidationException) {
            throw error
        } catch (_: Exception) {
            fail(outputAudio)
        } finally {
            source.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (outputAudio.exists() && outputAudio.length() == 0L) outputAudio.delete()
        }
    }

    private fun inspectAudioOnly(file: File): AudioInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            if (extractor.trackCount != 1) throw MediaValidationException()
            val format = extractor.getTrackFormat(0)
            if (format.mime() != AAC_MIME) throw MediaValidationException()
            val samples = inspectTrackSamples(file, 0, requireMonotonicPts = true)
            return AudioInfo(format.requiredDurationUs(), samples)
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.mime(): String = getString(MediaFormat.KEY_MIME).orEmpty()
        .substringBefore(';')
        .trim()
        .lowercase(Locale.ROOT)
    private fun MediaFormat.requiredDurationUs(): Long =
        if (containsKey(MediaFormat.KEY_DURATION)) getLong(MediaFormat.KEY_DURATION) else -1L
    private fun MediaFormat.maxInputSize(): Int =
        if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else MIN_BUFFER_BYTES

    private fun fail(output: File): Nothing {
        output.delete()
        throw MediaValidationException()
    }

    private fun inspectTrackSamples(file: File, trackIndex: Int, requireMonotonicPts: Boolean): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            if (trackIndex !in 0 until extractor.trackCount) throw MediaValidationException()
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val buffer = ByteBuffer.allocateDirect(format.maxInputSize().coerceIn(MIN_BUFFER_BYTES, MAX_BUFFER_BYTES))
            var count = 0
            var previousPts = Long.MIN_VALUE
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (size == 0 || requireMonotonicPts && pts < previousPts) throw MediaValidationException()
                previousPts = pts
                count += 1
                if (!extractor.advance()) break
            }
            if (count == 0) throw MediaValidationException()
            return count
        } finally {
            extractor.release()
        }
    }

    private fun securePlaintext(file: File) {
        Os.chmod(file.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
    }

    private fun containerMime(file: File): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
                .substringBefore(';')
                .trim()
                .lowercase(Locale.ROOT)
        } finally {
            retriever.release()
        }
    }

    private data class AudioInfo(val durationUs: Long, val sampleCount: Int)

    private companion object {
        const val DURATION_TOLERANCE_US = 50_000L
        const val MIN_BUFFER_BYTES = 64 * 1024
        const val MAX_BUFFER_BYTES = 4 * 1024 * 1024
        const val AAC_MIME = "audio/mp4a-latm"
        const val AVC_MIME = "video/avc"
        const val VIDEO_CONTAINER_MIME = "video/mp4"
        const val AUDIO_CONTAINER_MIME = "audio/mp4"
    }
}

internal fun mediaMuxerFlags(extractorFlags: Int): Int {
    val supported = MediaExtractor.SAMPLE_FLAG_SYNC or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME
    if (extractorFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0 || extractorFlags and supported.inv() != 0) {
        throw MediaValidationException()
    }
    var muxerFlags = 0
    if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
        muxerFlags = muxerFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
    }
    if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
        muxerFlags = muxerFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
    }
    return muxerFlags
}
