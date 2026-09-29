package com.vocaease.patient.core.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.vocaease.patient.core.database.AccountScopedDraftStorage
import com.vocaease.patient.feature.training.ReviewMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class Media3ReviewPlayerEngine(
    context: Context,
    private val storage: AccountScopedDraftStorage,
    playerView: PlayerView? = null,
) : ReviewPlayerEngine {
    private val player = ExoPlayer.Builder(context.applicationContext).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var positionTicker: Job? = null
    private var sourceListener: Player.Listener? = null
    private var activeSourceId: String? = null
    private var listener: ((ReviewEngineEvent) -> Unit)? = null
    private val released = AtomicBoolean()
    private val leaseRegistration = storage.onLeaseInvalidated {
        scope.launch {
            if (!released.get()) {
                val id = activeSourceId
                player.stop()
                player.clearMediaItems()
                detachSourceListener()
                id?.let { listener?.invoke(ReviewEngineEvent.Failed(it)) }
            }
        }
    }

    init {
        playerView?.apply {
            player = this@Media3ReviewPlayerEngine.player
            useController = false
        }
    }

    override fun setListener(listener: (ReviewEngineEvent) -> Unit) {
        this.listener = listener
    }

    override fun load(source: ReviewMediaSource, positionMillis: Long, playWhenReady: Boolean) {
        check(storage.isLeaseActive()) { "当前账户回看已失效" }
        val relativePath = requireNotNull(source.encryptedRelativePath) { "加密媒体绑定缺失" }
        detachSourceListener()
        activeSourceId = source.opaqueId
        val immutableSourceId = source.opaqueId
        lateinit var boundListener: Player.Listener
        boundListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener?.invoke(
                    ReviewEngineEvent.Playing(
                        immutableSourceId,
                        isPlaying,
                        player.currentPosition.coerceAtLeast(0),
                    ),
                )
                positionTicker?.cancel()
                positionTicker = if (isPlaying) scope.launch {
                    while (player.isPlaying && sourceListener === boundListener && storage.isLeaseActive()) {
                        listener?.invoke(
                            ReviewEngineEvent.Position(immutableSourceId, player.currentPosition.coerceAtLeast(0)),
                        )
                        delay(POSITION_INTERVAL_MILLIS)
                    }
                } else null
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    listener?.invoke(ReviewEngineEvent.Ended(immutableSourceId))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.invoke(ReviewEngineEvent.Failed(immutableSourceId))
            }
        }
        sourceListener = boundListener
        player.addListener(boundListener)
        val dataSourceFactory = DataSource.Factory { storage.encryptedMediaDataSource(relativePath) }
        val mediaItem = MediaItem.Builder()
            .setMediaId(source.opaqueId)
            .setUri(Uri.Builder().scheme("vocaease-encrypted").path(source.opaqueId).build())
            .setMimeType(source.mimeType)
            .build()
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        player.setMediaSource(mediaSource, positionMillis.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    override fun play() {
        check(storage.isLeaseActive()) { "当前账户回看已失效" }
        player.play()
    }

    override fun pause() = player.pause()
    override fun seekTo(positionMillis: Long) = player.seekTo(positionMillis.coerceAtLeast(0))

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        leaseRegistration.unregister()
        detachSourceListener()
        listener = null
        player.release()
        scope.cancel()
    }

    private fun detachSourceListener() {
        positionTicker?.cancel()
        positionTicker = null
        sourceListener?.let(player::removeListener)
        sourceListener = null
        activeSourceId = null
    }

    private companion object {
        const val POSITION_INTERVAL_MILLIS = 100L
    }
}
