package com.unclepluto.vocaease.singing

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawVoiceQualityTest {
    @Test
    fun `WAV 使用 48kHz 16bit 单声道并准确记录长度`() {
        val file = temporaryWav(ShortArray(48_000) { 1_000 })
        val report = analyzeWav(file, expectedDurationMs = 1_000, usedHeadphones = true, routeRisk = false)

        assertTrue(report.readable)
        assertEquals(48_000, report.sampleRate)
        assertEquals(1, report.channels)
        assertEquals(16, report.bitDepth)
        assertEquals(1_000L, report.durationMs)
        assertTrue(report.stageComplete)
        file.delete()
    }

    @Test
    fun `静音与削波只产生技术警告而不是评分`() {
        val silence = temporaryWav(ShortArray(48_000))
        val silentReport =
            analyzeWav(silence, 1_000, usedHeadphones = false, routeRisk = false)
        assertEquals("warning", silentReport.status)
        assertTrue(silentReport.fileWarnings.any { it.contains("静音") })
        assertTrue(silentReport.fileWarnings.any { it.contains("无耳机") })
        assertTrue(silentReport.markers.any { it.kind == "silence" })

        val clipping = temporaryWav(ShortArray(48_000) { Short.MAX_VALUE })
        val clippedReport =
            analyzeWav(clipping, 1_000, usedHeadphones = true, routeRisk = false)
        assertTrue(clippedReport.fileWarnings.any { it.contains("削波") })
        assertTrue(clippedReport.markers.any { it.kind == "clipping" })
        silence.delete()
        clipping.delete()
    }

    @Test
    fun `损坏文件不能通过可读性检查`() {
        val file = File.createTempFile("vocaease-corrupt-", ".wav")
        file.writeText("not wav")
        val report = analyzeWav(file, 1_000, usedHeadphones = true, routeRisk = false)
        assertFalse(report.readable)
        assertEquals("invalid", report.status)
        file.delete()
    }

    @Test
    fun `仅在服务端确认七天后允许清理`() {
        val confirmed = Instant.parse("2026-08-01T00:00:00Z")
        assertFalse(
            isRetentionExpired(
                confirmed.toEpochMilli(),
                confirmed.plus(Duration.ofDays(7)).minusMillis(1),
            )
        )
        assertTrue(
            isRetentionExpired(
                confirmed.toEpochMilli(),
                confirmed.plus(Duration.ofDays(7)),
            )
        )
    }

    @Test
    fun `存储预检为原始录音和安全余量预留空间`() {
        val required = requiredRecordingBytes(60_000)
        val rawBytes = 66_000L * RAW_SAMPLE_RATE * RAW_BYTES_PER_FRAME / 1_000
        assertEquals(rawBytes * 2 + 20L * 1024 * 1024, required)
    }

    private fun temporaryWav(samples: ShortArray): File {
        val file = File.createTempFile("vocaease-test-", ".wav")
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(bytes::putShort)
        WavStreamWriter(file).use { it.write(bytes.array(), bytes.array().size) }
        return file
    }
}
