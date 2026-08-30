package com.vocaease.patient.core.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.vocaease.patient.core.network.NetworkContractException
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

data class PreviewGrant(
    val url: String,
    val expiresAt: Instant,
)

fun interface PreviewGrantSource {
    suspend fun fetch(songId: String): PreviewGrant
}

sealed interface PreviewState {
    data object Idle : PreviewState
    data object Buffering : PreviewState
    data object Buffered : PreviewState
    data class Error(val message: String) : PreviewState
    data object Released : PreviewState
}

sealed interface PreviewEngineEvent {
    data object Buffering : PreviewEngineEvent
    data object Ready : PreviewEngineEvent
    data class HttpError(val status: Int) : PreviewEngineEvent
    data object PlaybackError : PreviewEngineEvent
}

interface PreviewEngine {
    val currentPositionMillis: Long
    fun setListener(listener: suspend (PreviewEngineEvent) -> Unit)
    fun load(url: String)
    fun seekTo(positionMillis: Long)
    fun release()
}

interface PreviewSession {
    val state: StateFlow<PreviewState>
    suspend fun prepare(songId: String)
    fun release()
}

class PreviewPlayer(
    private val engine: PreviewEngine,
    private val grantSource: PreviewGrantSource,
) : PreviewSession {
    private val mutex = Mutex()
    private val engineLock = Any()
    private val mutableState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    override val state: StateFlow<PreviewState> = mutableState.asStateFlow()
    private var songId: String? = null
    private var currentGrant: PreviewGrant? = null
    private var refreshUsed = false
    @Volatile
    private var released = false

    init {
        engine.setListener(::onEngineEvent)
    }

    override suspend fun prepare(songId: String) {
        mutex.withLock {
            if (released) return@withLock
            this.songId = songId
            refreshUsed = false
            mutableState.value = PreviewState.Buffering
            try {
                loadGrant(grantSource.fetch(songId), positionMillis = null)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: IOException) {
                publishErrorUnlessReleased()
            } catch (_: HttpException) {
                publishErrorUnlessReleased()
            } catch (_: SerializationException) {
                publishErrorUnlessReleased()
            } catch (_: NetworkContractException) {
                publishErrorUnlessReleased()
            }
        }
    }

    override fun release() {
        synchronized(engineLock) {
            if (released) return
            released = true
            currentGrant = null
            engine.release()
        }
        mutableState.value = PreviewState.Released
    }

    private suspend fun onEngineEvent(event: PreviewEngineEvent) {
        mutex.withLock {
            if (released) return@withLock
            when (event) {
                PreviewEngineEvent.Ready -> mutableState.value = PreviewState.Buffered
                PreviewEngineEvent.Buffering -> mutableState.value = PreviewState.Buffering
                is PreviewEngineEvent.HttpError -> handleHttpError(event.status)
                PreviewEngineEvent.PlaybackError -> publishErrorUnlessReleased()
            }
        }
    }

    private suspend fun handleHttpError(status: Int) {
        val currentSongId = songId
        if (status !in setOf(401, 403) || refreshUsed || currentSongId == null || currentGrant == null) {
            mutableState.value = PreviewState.Error("试听加载失败，请重试")
            return
        }
        refreshUsed = true
        val position = engine.currentPositionMillis.coerceAtLeast(0)
        mutableState.value = PreviewState.Buffering
        try {
            loadGrant(grantSource.fetch(currentSongId), position)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: IOException) {
            publishErrorUnlessReleased()
        } catch (_: HttpException) {
            publishErrorUnlessReleased()
        } catch (_: SerializationException) {
            publishErrorUnlessReleased()
        } catch (_: NetworkContractException) {
            publishErrorUnlessReleased()
        }
    }

    private fun loadGrant(grant: PreviewGrant, positionMillis: Long?) {
        require(grant.url.isNotBlank())
        synchronized(engineLock) {
            if (released) return
            currentGrant = grant
            engine.load(grant.url)
            if (positionMillis != null) engine.seekTo(positionMillis)
        }
    }

    private fun publishErrorUnlessReleased() {
        if (!released) mutableState.value = PreviewState.Error("试听加载失败，请重试")
    }
}

class ExoPreviewEngine(context: Context) : PreviewEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player = ExoPlayer.Builder(context.applicationContext).build()
    private var listener: (suspend (PreviewEngineEvent) -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val event = when (playbackState) {
                    Player.STATE_BUFFERING -> PreviewEngineEvent.Buffering
                    Player.STATE_READY -> PreviewEngineEvent.Ready
                    else -> null
                }
                if (event != null) dispatch(event)
            }

            override fun onPlayerError(error: PlaybackException) {
                val status = generateSequence(error.cause) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                    ?.responseCode
                dispatch(status?.let(PreviewEngineEvent::HttpError) ?: PreviewEngineEvent.PlaybackError)
            }
        })
    }

    override val currentPositionMillis: Long
        get() = player.currentPosition

    override fun setListener(listener: suspend (PreviewEngineEvent) -> Unit) {
        this.listener = listener
    }

    override fun load(url: String) {
        scope.launch {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
        }
    }

    override fun seekTo(positionMillis: Long) {
        scope.launch { player.seekTo(positionMillis) }
    }

    override fun release() {
        listener = null
        scope.launch {
            player.release()
            scope.cancel()
        }
    }

    private fun dispatch(event: PreviewEngineEvent) {
        val callback = listener ?: return
        scope.launch { callback(event) }
    }
}
