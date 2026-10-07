package com.vocaease.patient.feature.training
import com.vocaease.patient.core.network.dto.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
class LyricsRepositoryTest {
    @Test fun `时间线以播放位置选择歌词且空行保留间奏`() = runBlocking {
        val repository = LyricsRepository { SongLyricsDto(listOf(LyricLineDto(1000,"第一句"),LyricLineDto(3000,""),LyricLineDto(5000,"第二句"))) }
        val result = repository.load("song") as LyricsState.Ready
        assertEquals(-1, result.activeIndex(999))
        assertEquals(0, result.activeIndex(1000))
        assertEquals("", result.lines[result.activeIndex(3500)].text)
        assertEquals("第二句", result.lines[result.activeIndex(6000)].text)
        assertEquals(0, result.activeIndex(1500))
    }
    @Test fun `每次加载读取最新歌词且网络错误区别于未提供`() = runBlocking {
        var calls = 0
        val repository = LyricsRepository { calls++; if(calls==1) SongLyricsDto(emptyList()) else SongLyricsDto(listOf(LyricLineDto(0,"新增歌词"))) }
        assertEquals(LyricsState.Unavailable,repository.load("song"))
        assertTrue(repository.load("song") is LyricsState.Ready)
        assertEquals(LyricsState.Failed,LyricsRepository { error("网络不可用") }.load("song"))
    }
    @Test fun `拒绝负数和无序时间线并保留取消信号`() = runBlocking {
        assertEquals(LyricsState.Failed,LyricsRepository { SongLyricsDto(listOf(LyricLineDto(-1,"歌词"))) }.load("song"))
        assertEquals(LyricsState.Failed,LyricsRepository { SongLyricsDto(listOf(LyricLineDto(5,"后"),LyricLineDto(2,"前"))) }.load("song"))
        try { LyricsRepository { throw CancellationException() }.load("song"); fail("应传播取消") } catch(_:CancellationException) {}
    }
}
