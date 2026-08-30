package com.vocaease.patient.core.media

import com.vocaease.patient.feature.training.ReviewMediaKind
import com.vocaease.patient.feature.training.ReviewMediaSource
import com.vocaease.patient.feature.training.ReviewPlayerEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearizedReviewPlayerTest {
    @Test
    fun `一个engine顺序切换视频与独立音频且保持位置和播放意图`() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "review-main") }.asCoroutineDispatcher()
        val engine = FakeReviewEngine()
        val player = LinearizedReviewPlayer(engine, dispatcher)
        val video = ReviewMediaSource("video", "video/mp4", 10)
        val audio = ReviewMediaSource("audio", "audio/mp4", 5)

        player.load(video, ReviewMediaKind.VIDEO, 0, false)
        player.play()
        player.seekTo(42_000)
        player.load(audio, ReviewMediaKind.AUDIO, 42_000, true)

        assertEquals(listOf("video", "audio"), engine.loadedIds)
        assertEquals(42_000L, engine.lastPosition)
        assertTrue(engine.lastPlayWhenReady)
        assertTrue(engine.threads.all { it.startsWith("review-main") })
        dispatcher.close()
    }

    @Test
    fun `旧media回调和release后的迟到回调都被拒绝且release可等待`() = runBlocking {
        val engine = FakeReviewEngine()
        val player = LinearizedReviewPlayer(engine, kotlinx.coroutines.Dispatchers.Unconfined)
        player.load(ReviewMediaSource("video", "video/mp4", 10), ReviewMediaKind.VIDEO, 0, false)
        player.load(ReviewMediaSource("audio", "audio/mp4", 5), ReviewMediaKind.AUDIO, 0, false)

        engine.emit(ReviewEngineEvent.Playing("video", true, 9_000))
        assertFalse(player.events.tryFirstPlaying())
        engine.emit(ReviewEngineEvent.Playing("audio", true, 1_000))
        assertEquals(1_000L, withTimeout(1_000) { (player.events.first() as ReviewPlayerEvent.Playing).positionMillis })

        val release = async(kotlinx.coroutines.Dispatchers.Default) { player.releaseAndAwait() }
        assertTrue(engine.releaseEntered.await(2, TimeUnit.SECONDS))
        assertFalse(release.isCompleted)
        engine.releaseGate.countDown()
        release.await()
        engine.emit(ReviewEngineEvent.Playing("audio", true, 2_000))
        assertFalse(player.events.tryFirstPlaying())
        assertEquals(1, engine.releaseCount)
    }
}

private suspend fun kotlinx.coroutines.flow.Flow<ReviewPlayerEvent>.tryFirstPlaying(): Boolean =
    runCatching { withTimeout(50) { first { it is ReviewPlayerEvent.Playing } } }.isSuccess

private class FakeReviewEngine : ReviewPlayerEngine {
    private var listener: ((ReviewEngineEvent) -> Unit)? = null
    val loadedIds = mutableListOf<String>()
    val threads = mutableListOf<String>()
    var lastPosition = 0L
    var lastPlayWhenReady = false
    var releaseCount = 0
    val releaseEntered = CountDownLatch(1)
    val releaseGate = CountDownLatch(1)

    override fun setListener(listener: (ReviewEngineEvent) -> Unit) { this.listener = listener }
    override fun load(source: ReviewMediaSource, positionMillis: Long, playWhenReady: Boolean) {
        threads += Thread.currentThread().name
        loadedIds += source.opaqueId
        lastPosition = positionMillis
        lastPlayWhenReady = playWhenReady
    }
    override fun play() { threads += Thread.currentThread().name }
    override fun pause() { threads += Thread.currentThread().name }
    override fun seekTo(positionMillis: Long) { threads += Thread.currentThread().name; lastPosition = positionMillis }
    override fun release() {
        threads += Thread.currentThread().name
        releaseCount += 1
        releaseEntered.countDown()
        releaseGate.await(2, TimeUnit.SECONDS)
    }
    fun emit(event: ReviewEngineEvent) { listener?.invoke(event) }
}
