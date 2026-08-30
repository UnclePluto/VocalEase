package com.vocaease.patient.core.media

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPlayerTest {
    @Test
    fun `只有播放器STATE_READY才发布Buffered`() = runBlocking {
        val engine = FakePreviewEngine()
        val source = QueuePreviewSource(
            PreviewGrant("https://private.invalid/first?token=secret", Instant.parse("2026-08-27T10:00:00Z")),
        )
        val player = PreviewPlayer(engine, source)

        player.prepare("10000000-0000-4000-8000-000000000001")
        assertTrue(player.state.value is PreviewState.Buffering)
        engine.emit(PreviewEngineEvent.Buffering)
        assertFalse(player.state.value is PreviewState.Buffered)
        engine.emit(PreviewEngineEvent.PlayingChanged(false))
        assertFalse(player.state.value is PreviewState.Buffered)

        engine.emit(PreviewEngineEvent.Ready)
        assertTrue(player.state.value is PreviewState.Buffered)
        assertFalse(player.state.value.toString().contains("token=secret"))
    }

    @Test
    fun `401或403只刷新一次并恢复播放位置后重新缓冲`() = runBlocking {
        listOf(401, 403).forEach { status ->
            val engine = FakePreviewEngine().apply { position = 37_500 }
            val source = QueuePreviewSource(
                PreviewGrant("https://private.invalid/old", Instant.parse("2026-08-27T09:00:00Z")),
                PreviewGrant("https://private.invalid/new", Instant.parse("2026-08-27T10:00:00Z")),
            )
            val player = PreviewPlayer(engine, source)
            player.prepare(SONG_ID)

            engine.emit(PreviewEngineEvent.HttpError(status))

            assertEquals(2, source.callCount)
            assertEquals(listOf(37_500L), engine.seeks)
            assertEquals("https://private.invalid/new", engine.loadedUrls.last())
            assertTrue(player.state.value is PreviewState.Buffering)

            engine.emit(PreviewEngineEvent.HttpError(status))
            assertEquals(2, source.callCount)
            assertEquals("试听加载失败，请重试", (player.state.value as PreviewState.Error).message)
        }
    }

    @Test
    fun `只有缓冲完成后可试听且暂停后可再次播放`() = runBlocking {
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(
            engine,
            QueuePreviewSource(PreviewGrant("https://private.invalid/audio", Instant.MAX)),
        )
        player.prepare(SONG_ID)

        assertFalse(player.play())
        engine.emit(PreviewEngineEvent.Ready)
        assertTrue(player.play())
        engine.emit(PreviewEngineEvent.PlayingChanged(true))
        assertTrue(player.state.value is PreviewState.Playing)

        assertTrue(player.pause())
        engine.emit(PreviewEngineEvent.PlayingChanged(false))
        assertTrue(player.state.value is PreviewState.Buffered)
        assertEquals(1, engine.playCount)
        assertEquals(1, engine.pauseCount)
    }

    @Test
    fun `播放中的401刷新后恢复位置并继续原试听意图`() = runBlocking {
        val engine = FakePreviewEngine().apply { position = 12_345 }
        val source = QueuePreviewSource(
            PreviewGrant("https://private.invalid/old", Instant.MAX),
            PreviewGrant("https://private.invalid/new", Instant.MAX),
        )
        val player = PreviewPlayer(engine, source)
        player.prepare(SONG_ID)
        engine.emit(PreviewEngineEvent.Ready)
        assertTrue(player.play())

        engine.emit(PreviewEngineEvent.HttpError(401))
        engine.emit(PreviewEngineEvent.Ready)

        assertEquals(listOf(12_345L), engine.seeks)
        assertEquals(2, engine.playCount)
        assertTrue(player.state.value is PreviewState.Playing)
    }

    @Test
    fun `授权失败或第二次鉴权失败后手动重试开启新的单次刷新预算`() = runBlocking {
        val engine = FakePreviewEngine()
        var attempt = 0
        val player = PreviewPlayer(engine) {
            attempt += 1
            if (attempt == 1) throw java.io.IOException("offline")
            PreviewGrant("https://private.invalid/$attempt", Instant.MAX)
        }

        player.prepare(SONG_ID)
        assertTrue(player.state.value is PreviewState.Error)

        player.prepare(SONG_ID)
        engine.emit(PreviewEngineEvent.Ready)
        assertTrue(player.state.value is PreviewState.Buffered)
        assertTrue(player.play())
        assertEquals(2, attempt)
    }

    @Test
    fun `第二次401进入错误后手动重试可重新缓冲并播放`() = runBlocking {
        val engine = FakePreviewEngine()
        val source = QueuePreviewSource(
            PreviewGrant("https://private.invalid/first", Instant.MAX),
            PreviewGrant("https://private.invalid/refresh", Instant.MAX),
            PreviewGrant("https://private.invalid/manual-retry", Instant.MAX),
        )
        val player = PreviewPlayer(engine, source)
        player.prepare(SONG_ID)

        engine.emit(PreviewEngineEvent.HttpError(401))
        engine.emit(PreviewEngineEvent.HttpError(401))
        assertEquals("试听加载失败，请重试", (player.state.value as PreviewState.Error).message)

        player.prepare(SONG_ID)
        engine.emit(PreviewEngineEvent.Ready)

        assertEquals(3, source.callCount)
        assertEquals("https://private.invalid/manual-retry", engine.loadedUrls.last())
        assertTrue(player.state.value is PreviewState.Buffered)
        assertTrue(player.play())
    }

    @Test
    fun `生命周期释放底层播放器且不再处理迟到回调`() = runBlocking {
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(
            engine,
            QueuePreviewSource(PreviewGrant("https://private.invalid/audio", Instant.MAX)),
        )
        player.prepare(SONG_ID)

        player.release()
        engine.emit(PreviewEngineEvent.Ready)

        assertTrue(engine.released)
        assertTrue(player.state.value is PreviewState.Released)
    }

    @Test
    fun `释放期间迟到的私有URL不得重新加载已释放播放器`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val releaseGrant = CompletableDeferred<Unit>()
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(engine) {
            entered.complete(Unit)
            releaseGrant.await()
            PreviewGrant("https://private.invalid/late", Instant.MAX)
        }
        val preparation = async { player.prepare(SONG_ID) }
        entered.await()

        player.release()
        releaseGrant.complete(Unit)
        preparation.await()

        assertTrue(engine.released)
        assertTrue(engine.loadedUrls.isEmpty())
        assertTrue(player.state.value is PreviewState.Released)
    }

    private companion object {
        const val SONG_ID = "10000000-0000-4000-8000-000000000001"
    }
}

private class QueuePreviewSource(vararg grants: PreviewGrant) : PreviewGrantSource {
    private val values = ArrayDeque(grants.toList())
    var callCount = 0
        private set

    override suspend fun fetch(songId: String): PreviewGrant {
        callCount += 1
        return values.removeFirst()
    }
}

private class FakePreviewEngine : PreviewEngine {
    private var listener: (suspend (PreviewEngineEvent) -> Unit)? = null
    val loadedUrls = mutableListOf<String>()
    val seeks = mutableListOf<Long>()
    var position = 0L
    var released = false
    var playCount = 0
    var pauseCount = 0

    override val currentPositionMillis: Long
        get() = position

    override fun setListener(listener: suspend (PreviewEngineEvent) -> Unit) {
        this.listener = listener
    }

    override fun load(url: String) {
        loadedUrls += url
    }

    override fun seekTo(positionMillis: Long) {
        seeks += positionMillis
    }

    override fun play() {
        playCount += 1
    }

    override fun pause() {
        pauseCount += 1
    }

    override fun release() {
        released = true
        listener = null
    }

    suspend fun emit(event: PreviewEngineEvent) {
        listener?.invoke(event)
    }
}
