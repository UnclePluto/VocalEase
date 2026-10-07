package com.vocaease.patient.core.media

import org.junit.Assert.*
import org.junit.Test

class PcmRecordingGateTest {
    @Test fun `弹框期间声音不进入编码流恢复后时间戳连续`() {
        val gate = PcmRecordingGate()
        val encoded = mutableListOf<Pair<Long, String>>()
        fun voice(text: String) = gate.encode(48000) { frames -> encoded += frames / 48 to text }
        voice("第一秒演唱")
        gate.pause()
        repeat(10) { assertFalse(voice("弹框期间说话")) }
        assertEquals(48000L, gate.frameCount)
        gate.resume()
        assertTrue(voice("继续演唱"))
        assertEquals(listOf(0L to "第一秒演唱", 1000L to "继续演唱"), encoded)
        assertEquals(96000L, gate.frameCount)
    }
}
