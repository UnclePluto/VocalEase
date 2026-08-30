package com.vocaease.patient.core.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPlaybackHandoffTest {
    @Test
    fun `预缓冲session只能由同一draft取走一次且保留播放位置`() = runBlocking {
        val preview = HandoffPreview().apply { position = 1_234 }
        val registry = RecordingPlaybackHandoff(Dispatchers.Unconfined)
        registry.offer("draft-a", preview)

        val playback = registry.take("draft-a")

        assertEquals(1_234L, playback?.currentPositionMillis)
        assertNull(registry.take("draft-a"))
        assertNull(registry.take("draft-b"))
        playback?.play()
        assertEquals(1, preview.playCount)
    }

    @Test
    fun `歌曲播放结束通知录制自动停止且stop释放session`() {
        val preview = HandoffPreview()
        val registry = RecordingPlaybackHandoff(Dispatchers.Unconfined)
        registry.offer("draft", preview)
        val playback = requireNotNull(registry.take("draft"))
        var ended = 0
        playback.setOnEnded { ended += 1 }

        preview.emit(PreviewState.Ended)
        playback.stop()

        assertEquals(1, ended)
        assertEquals(1, preview.releaseCount)
    }

    @Test
    fun `监听器注册前歌曲已结束也不会漏掉自动停止通知`() {
        val preview = HandoffPreview().apply { emit(PreviewState.Ended) }
        val registry = RecordingPlaybackHandoff(Dispatchers.Unconfined)
        registry.offer("draft", preview)
        val playback = requireNotNull(registry.take("draft"))
        var ended = 0

        playback.setOnEnded { ended += 1 }

        assertEquals(1, ended)
    }

    @Test
    fun `导航未接管的交接租约超时后自动释放`() = runBlocking {
        val preview = HandoffPreview()
        val registry = RecordingPlaybackHandoff(Dispatchers.Default, leaseTimeoutMillis = 20)

        registry.offer("draft", preview)
        delay(100)

        assertNull(registry.take("draft"))
        assertEquals(1, preview.releaseCount)
    }

    @Test
    fun `账号租约变化时撤销全部尚未接管的交接`() {
        val first = HandoffPreview()
        val second = HandoffPreview()
        val registry = RecordingPlaybackHandoff(Dispatchers.Unconfined)
        registry.offer("draft-a", first)
        registry.offer("draft-b", second)

        registry.discardAll()

        assertNull(registry.take("draft-a"))
        assertNull(registry.take("draft-b"))
        assertEquals(1, first.releaseCount)
        assertEquals(1, second.releaseCount)
    }
}

private class HandoffPreview : PreviewSession {
    private val mutable = MutableStateFlow<PreviewState>(PreviewState.Buffered)
    override val state: StateFlow<PreviewState> = mutable
    override val currentPositionMillis: Long get() = position
    var position = 0L
    var playCount = 0
    var releaseCount = 0
    override suspend fun prepare(songId: String) = Unit
    override suspend fun play(): Boolean { playCount += 1; return true }
    override suspend fun pause(): Boolean = true
    override fun release() { releaseCount += 1 }
    override suspend fun awaitReleased() = Unit
    fun emit(state: PreviewState) { mutable.value = state }
}
