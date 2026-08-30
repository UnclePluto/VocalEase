package com.vocaease.patient.core.media

import com.vocaease.patient.feature.training.ReviewMediaKind
import com.vocaease.patient.feature.training.ReviewMediaSource
import com.vocaease.patient.feature.training.ReviewPlayer
import com.vocaease.patient.feature.training.ReviewPlayerEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ReviewEngineEvent {
    val sourceId: String

    data class Playing(
        override val sourceId: String,
        val isPlaying: Boolean,
        val positionMillis: Long,
    ) : ReviewEngineEvent

    data class Position(override val sourceId: String, val positionMillis: Long) : ReviewEngineEvent
    data class Ended(override val sourceId: String) : ReviewEngineEvent
    data class Failed(override val sourceId: String) : ReviewEngineEvent
}

interface ReviewPlayerEngine {
    fun setListener(listener: (ReviewEngineEvent) -> Unit)
    fun load(source: ReviewMediaSource, positionMillis: Long, playWhenReady: Boolean)
    fun play()
    fun pause()
    fun seekTo(positionMillis: Long)
    fun release()
}

class LinearizedReviewPlayer(
    private val engine: ReviewPlayerEngine,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ReviewPlayer {
    private val mutex = Mutex()
    private val released = AtomicBoolean()
    private val eventChannel = Channel<ReviewPlayerEvent>(Channel.UNLIMITED)
    override val events: Flow<ReviewPlayerEvent> = eventChannel.receiveAsFlow()
    @Volatile private var currentSourceId: String? = null
    private var loadGeneration = 0L

    init {
        engine.setListener { event ->
            if (!released.get() && event.sourceId == currentSourceId) {
                val mapped = when (event) {
                    is ReviewEngineEvent.Playing -> ReviewPlayerEvent.Playing(event.isPlaying, event.positionMillis)
                    is ReviewEngineEvent.Position -> ReviewPlayerEvent.Position(event.positionMillis)
                    is ReviewEngineEvent.Ended -> ReviewPlayerEvent.Ended
                    is ReviewEngineEvent.Failed -> ReviewPlayerEvent.Failed
                }
                eventChannel.trySend(mapped)
            }
        }
    }

    override suspend fun load(
        source: ReviewMediaSource,
        kind: ReviewMediaKind,
        positionMillis: Long,
        playWhenReady: Boolean,
    ) = onMain {
        val sourceGeneration = ++loadGeneration
        val boundSource = source.copy(opaqueId = "${source.opaqueId}#review-$sourceGeneration")
        currentSourceId = boundSource.opaqueId
        engine.load(boundSource, positionMillis.coerceAtLeast(0), playWhenReady)
    }

    override suspend fun play() = onMain { engine.play() }
    override suspend fun pause() = onMain { engine.pause() }
    override suspend fun seekTo(positionMillis: Long) = onMain { engine.seekTo(positionMillis.coerceAtLeast(0)) }

    override suspend fun releaseAndAwait() {
        if (!released.compareAndSet(false, true)) return
        withContext(mainDispatcher) {
            mutex.withLock {
                currentSourceId = null
                engine.release()
                eventChannel.close()
            }
        }
    }

    private suspend fun onMain(operation: () -> Unit) {
        withContext(mainDispatcher) {
            mutex.withLock {
                check(!released.get()) { "回看播放器已释放" }
                operation()
            }
        }
    }
}
