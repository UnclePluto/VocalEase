package com.vocaease.patient.core.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.ui.PlayerView
import com.vocaease.patient.feature.history.PrivateVideoEngine
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Media3 的所有状态变更均在线性化主线程执行。 */
@UnstableApi
class Media3PrivateVideoEngine private constructor(
    private val player: ExoPlayer,
    playerView: PlayerView?,
) : PrivateVideoEngine {
    private val operationLock = Mutex()
    private val released = AtomicBoolean()
    @Volatile private var httpErrorListener: ((String, Int) -> Unit)? = null
    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val mediaPeriodId = (error as? ExoPlaybackException)?.mediaPeriodId ?: return
            val sourceId = immutableSourceIdFor(player.currentTimeline, mediaPeriodId.periodUid) ?: return
            val responseCode = error.findHttpResponseCode() ?: return
            httpErrorListener?.invoke(sourceId, responseCode)
        }
    }

    init {
        player.addListener(playerListener)
        playerView?.apply {
            player = this@Media3PrivateVideoEngine.player
            useController = true
        }
    }

    override fun setHttpErrorListener(listener: (sourceId: String, statusCode: Int) -> Unit) {
        httpErrorListener = listener
    }

    override suspend fun load(sourceId: String, privateUrl: String, positionMillis: Long) = onMain {
        check(!released.get()) { "私有视频播放器已释放" }
        require(sourceId.isNotBlank() && sourceId.length <= 256) { "视频来源标识无效" }
        val uri = Uri.parse(privateUrl)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) { "私有视频地址无效" }
        val item = MediaItem.Builder()
            .setMediaId(sourceId)
            .setUri(uri)
            .setMimeType("video/mp4")
            .build()
        player.setMediaItem(item, positionMillis.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = false
    }

    override suspend fun currentPositionMillis(): Long = onMainResult {
        if (released.get()) 0L else player.currentPosition.coerceAtLeast(0)
    }

    override suspend fun stopAndClear() = onMain {
        if (!released.get()) {
            player.stop()
            player.clearMediaItems()
        }
    }

    override suspend fun releaseAndAwait() {
        if (!released.compareAndSet(false, true)) return
        withContext(Dispatchers.Main.immediate) {
            operationLock.withLock {
                httpErrorListener = null
                player.removeListener(playerListener)
                player.release()
            }
        }
    }

    private suspend fun onMain(operation: () -> Unit) {
        withContext(Dispatchers.Main.immediate) { operationLock.withLock { operation() } }
    }

    private suspend fun <T> onMainResult(operation: () -> T): T =
        withContext(Dispatchers.Main.immediate) { operationLock.withLock { operation() } }

    companion object {
        suspend fun create(context: Context, playerView: PlayerView? = null): Media3PrivateVideoEngine =
            withContext(Dispatchers.Main.immediate) {
                Media3PrivateVideoEngine(ExoPlayer.Builder(context.applicationContext).build(), playerView)
            }
    }
}

internal fun immutableSourceIdFor(timeline: Timeline, periodUid: Any): String? {
    val periodIndex = timeline.getIndexOfPeriod(periodUid)
    if (periodIndex == androidx.media3.common.C.INDEX_UNSET) return null
    val period = timeline.getPeriod(periodIndex, Timeline.Period())
    val window = timeline.getWindow(period.windowIndex, Timeline.Window())
    return window.mediaItem.mediaId.takeIf { it.isNotBlank() }
}

private fun Throwable.findHttpResponseCode(): Int? {
    var current: Throwable? = this
    repeat(12) {
        when (val cause = current) {
            is HttpDataSource.InvalidResponseCodeException -> return cause.responseCode
            null -> return null
            else -> current = cause.cause
        }
    }
    return null
}
