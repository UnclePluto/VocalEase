package com.vocaease.patient.core.media

import android.media.MediaCodec
import android.media.MediaExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Mp4SampleFlagsTest {
    @Test
    fun `Extractor标志按语义转换为Muxer接受的Buffer标志`() {
        assertEquals(
            MediaCodec.BUFFER_FLAG_KEY_FRAME,
            mediaMuxerFlags(MediaExtractor.SAMPLE_FLAG_SYNC),
        )
        assertEquals(
            MediaCodec.BUFFER_FLAG_PARTIAL_FRAME,
            mediaMuxerFlags(MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME),
        )
        assertThrows(MediaValidationException::class.java) {
            mediaMuxerFlags(MediaExtractor.SAMPLE_FLAG_ENCRYPTED)
        }
    }
}
