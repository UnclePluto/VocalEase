package com.unclepluto.vocaease.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogTest {
    private val lines = listOf(
        LyricLine(1_000, "第一句"),
        LyricLine(2_500, "第二句"),
        LyricLine(4_000, "第三句"),
    )

    @Test
    fun `歌词时间轴在时间点之间保持当前行`() {
        assertEquals(-1, activeLyricIndex(lines, 999))
        assertEquals(0, activeLyricIndex(lines, 1_000))
        assertEquals(1, activeLyricIndex(lines, 3_999))
        assertEquals(2, activeLyricIndex(lines, 9_000))
    }

    @Test
    fun `媒体地址使用服务端地址并携带参与者令牌`() {
        assertEquals(
            "http://10.0.2.2:8000/api/v1/media/track-id",
            resolveUrl("http://10.0.2.2:8000", "/api/v1/media/track-id"),
        )
        assertEquals(
            mapOf("Authorization" to "Bearer participant-token"),
            authorizationHeaders("participant-token"),
        )
    }

    @Test
    fun `歌曲时长按分秒展示`() {
        assertEquals("0:00", formatDuration(-1))
        assertEquals("3:07", formatDuration(187_900))
    }
}
