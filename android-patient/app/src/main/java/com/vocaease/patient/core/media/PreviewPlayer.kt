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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

data class PreviewGrant(
    val url: String,
    val expiresAt: Instant,
    val assetId: String? = null,
    val binding: com.vocaease.patient.core.network.dto.PlaybackBindingDto? = null,
    val timelineOffsetMillis:Long=0,
)

fun interface PreviewGrantSource {
    suspend fun fetch(songId: String): PreviewGrant
    suspend fun fetch(songId: String, mode: SongPlaybackMode, sessionId: String?): PreviewGrant = fetch(songId)
}

sealed interface PreviewState {
    data object Idle : PreviewState
    data object Buffering : PreviewState
    data object Buffered : PreviewState
    data object Playing : PreviewState
    data object Ended : PreviewState
    data class Error(val message: String) : PreviewState
    data object Released : PreviewState
}

sealed interface PreviewEngineEvent {
    data object Buffering : PreviewEngineEvent
    data object Ready : PreviewEngineEvent
    data class PlayingChanged(val isPlaying: Boolean) : PreviewEngineEvent
    data class HttpError(val status: Int) : PreviewEngineEvent
    data object PlaybackError : PreviewEngineEvent
    data object Ended : PreviewEngineEvent
}

interface PreviewEngine {
    val currentPositionMillis: Long
    fun setListener(listener: suspend (PreviewEngineEvent) -> Unit)
    fun load(url: String)
    fun seekTo(positionMillis: Long)
    fun play()
    fun pause()
    suspend fun releaseAndAwait()
}

interface PreviewSession {
    val playbackBinding: com.vocaease.patient.core.network.dto.PlaybackBindingDto? get() = null
    val state: StateFlow<PreviewState>
    val activeMode: StateFlow<SongPlaybackMode> get() = MutableStateFlow(SongPlaybackMode.ORIGINAL)
    suspend fun switchMode(mode: SongPlaybackMode): Boolean = true
    suspend fun bindSession(sessionId: String): Boolean = true
    val currentPositionMillis: Long get() = 0
    suspend fun prepare(songId: String)
    suspend fun play(): Boolean
    suspend fun pause(): Boolean
    suspend fun rewindToStart(): Boolean = false
    fun release()
    suspend fun awaitReleased()
}

internal enum class PreviewAdmissionPoint {
    ENGINE_EVENT,
    PUBLISH_ERROR,
    GRANT_RESULT,
    GRANT_APPLICATION,
    PREPARE_APPLICATION,
    CANCEL_APPLICATION,
    ACTOR_COMPLETION,
}

internal fun interface PreviewAdmissionProbe {
    suspend fun afterAdmission(point: PreviewAdmissionPoint)
}

class PreviewPlayer internal constructor(
    private val engine: PreviewEngine,
    private val grantSource: PreviewGrantSource,
    private val admissionProbe: PreviewAdmissionProbe = PreviewAdmissionProbe {},
) : PreviewSession {
    private val commands = Channel<PreviewCommand>(Channel.UNLIMITED)
    private val ownedJob = SupervisorJob()
    private val actorScope = CoroutineScope(ownedJob + Dispatchers.Default)
    private val submissionLock = Any()
    private val releaseCompletion = CompletableDeferred<Unit>()
    private val actorJob: Job
    private val mutableState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    override val state: StateFlow<PreviewState> = mutableState.asStateFlow()
    override val currentPositionMillis: Long get() = (engine.currentPositionMillis-(currentGrant?.timelineOffsetMillis ?: 0)).coerceAtLeast(0)
    private val modeState = MutableStateFlow(SongPlaybackMode.ORIGINAL)
    override val activeMode = modeState.asStateFlow()
    private var requestedMode = SongPlaybackMode.ORIGINAL
    private var boundSessionId: String? = null
    private var pendingSwitch: CompletableDeferred<Boolean>? = null
    private var switchBackup: SwitchBackup? = null
    private data class SwitchBackup(val grant: PreviewGrant?, val mode: SongPlaybackMode, val position: Long, val playing: Boolean)
    override var playbackBinding: com.vocaease.patient.core.network.dto.PlaybackBindingDto? = null; private set
    private var acceptingCommands = true
    private var generation = 0L
    private var songId: String? = null
    private var currentGrant: PreviewGrant? = null
    private var refreshUsed = false
    private var resumeAfterReady = false
    private var pausedDuringBuffering = false
    private var mediaLoaded = false
    private var activePreparationToken: PreparationToken? = null
    private var pendingPreparation: CompletableDeferred<Result<Unit>>? = null
    private val grantJobs = mutableListOf<Job>()

    init {
        actorJob = actorScope.launch { runActor() }
        actorJob.invokeOnCompletion { error ->
            ownedJob.cancel()
            if (error == null || error is CancellationException) {
                releaseCompletion.complete(Unit)
            } else {
                releaseCompletion.completeExceptionally(error)
            }
        }
    }

    override suspend fun prepare(songId: String) {
        val token = PreparationToken()
        val completion = CompletableDeferred<Result<Unit>>()
        if (!submit(PreviewCommand.Prepare(songId, token, completion))) return
        try {
            completion.await().getOrThrow()
        } catch (error: CancellationException) {
            token.cancel()
            val acknowledgement = CompletableDeferred<Unit>()
            val cancellationAccepted = submit(PreviewCommand.CancelPreparation(token, acknowledgement))
            withContext(NonCancellable) {
                if (cancellationAccepted) acknowledgement.await() else releaseCompletion.await()
            }
            throw error
        }
    }

    override suspend fun play(): Boolean {
        val completion = CompletableDeferred<Boolean>()
        if (!submit(PreviewCommand.Play(completion))) return false
        return completion.await()
    }

    override suspend fun pause(): Boolean {
        val completion = CompletableDeferred<Boolean>()
        if (!submit(PreviewCommand.Pause(completion))) return false
        return completion.await()
    }

    override suspend fun rewindToStart(): Boolean {
        val completion = CompletableDeferred<Boolean>()
        if (!submit(PreviewCommand.RewindToStart(completion))) return false
        return completion.await()
    }

    override suspend fun switchMode(mode: SongPlaybackMode): Boolean = requestSwitch(mode, null, false)
    override suspend fun bindSession(sessionId: String): Boolean = requestSwitch(SongPlaybackMode.ACCOMPANIMENT, sessionId, true)
    private suspend fun requestSwitch(mode: SongPlaybackMode, sessionId: String?, reset: Boolean): Boolean {
        val completion = CompletableDeferred<Boolean>()
        if (!submit(PreviewCommand.Switch(mode, sessionId, reset, completion))) return false
        return completion.await()
    }

    override fun release() {
        synchronized(submissionLock) {
            if (acceptingCommands) {
                acceptingCommands = false
                check(commands.trySend(PreviewCommand.Release).isSuccess)
            }
        }
    }

    override suspend fun awaitReleased() {
        releaseCompletion.await()
    }

    private suspend fun runActor() {
        try {
            for (command in commands) {
                when (command) {
                    is PreviewCommand.Prepare -> {
                        admissionProbe.afterAdmission(PreviewAdmissionPoint.PREPARE_APPLICATION)
                        beginPreparation(command)
                    }
                    is PreviewCommand.GrantResolved -> applyGrant(command)
                    is PreviewCommand.EngineEvent -> applyEngineEvent(command)
                    is PreviewCommand.Switch -> beginSwitch(command)
                    is PreviewCommand.Play -> applyPlay(command)
                    is PreviewCommand.Pause -> applyPause(command)
                    is PreviewCommand.RewindToStart -> applyRewindToStart(command)
                    is PreviewCommand.CancelPreparation -> {
                        admissionProbe.afterAdmission(PreviewAdmissionPoint.CANCEL_APPLICATION)
                        applyPreparationCancellation(command)
                    }
                    PreviewCommand.Release -> {
                        applyRelease()
                        return
                    }
                }
            }
        } finally {
            admissionProbe.afterAdmission(PreviewAdmissionPoint.ACTOR_COMPLETION)
        }
    }

    private fun beginSwitch(command: PreviewCommand.Switch) {
        val id = songId
        if (id == null || !mediaLoaded) { command.completion.complete(false); return }
        if (!command.reset && command.mode == modeState.value && pendingSwitch == null) { command.completion.complete(true); return }
        pendingSwitch?.complete(false)
        if (switchBackup == null) switchBackup = SwitchBackup(currentGrant, modeState.value, currentPositionMillis, mutableState.value is PreviewState.Playing)
        engine.pause()
        activePreparationToken?.cancel()
        val token = PreparationToken(); activePreparationToken = token
        generation += 1; requestedMode = command.mode
        command.sessionId?.let { boundSessionId = it }
        pendingSwitch = command.completion
        resumeAfterReady = !command.reset && switchBackup!!.playing
        mutableState.value = PreviewState.Buffering
        bindListener(generation, token)
        fetchGrant(generation, token, id, if (command.reset) 0L else switchBackup!!.position)
    }

    private fun restoreSwitch(): Boolean {
        val backup = switchBackup ?: return false
        pendingSwitch?.complete(false); pendingSwitch = null; switchBackup = null
        requestedMode = backup.mode; modeState.value = backup.mode
        generation += 1
        val token = PreparationToken(); activePreparationToken?.cancel(); activePreparationToken = token
        bindListener(generation, token)
        backup.grant?.let { currentGrant = it; engine.load(it.url); engine.seekTo((backup.position+it.timelineOffsetMillis).coerceAtLeast(0)); mediaLoaded = true }
        resumeAfterReady = backup.playing
        mutableState.value = PreviewState.Buffering
        return true
    }

    private fun beginPreparation(command: PreviewCommand.Prepare) {
        val claimed = command.token.withActive {
            activePreparationToken?.cancel()
            pendingPreparation?.complete(Result.success(Unit))
            beginActivePreparation(command)
        }
        if (!claimed) {
            command.completion.complete(Result.success(Unit))
        }
    }

    private fun beginActivePreparation(command: PreviewCommand.Prepare) {
        pendingSwitch?.complete(false); pendingSwitch = null; switchBackup = null
        activePreparationToken = command.token
        pendingPreparation = command.completion
        generation += 1
        songId = command.songId
        currentGrant = null
        refreshUsed = false
        resumeAfterReady = false
        mediaLoaded = false
        mutableState.value = PreviewState.Buffering
        bindListener(generation, command.token)
        fetchGrant(generation, command.token, command.songId, positionMillis = null)
    }

    private suspend fun applyGrant(command: PreviewCommand.GrantResolved) {
        admissionProbe.afterAdmission(PreviewAdmissionPoint.GRANT_APPLICATION)
        if (command.generation != generation || command.token !== activePreparationToken) return
        command.token.withActive { applyActiveGrant(command) }
    }

    private fun applyActiveGrant(command: PreviewCommand.GrantResolved) {
        if (command.generation != generation || command.token !== activePreparationToken) return
        command.result.fold(
            onSuccess = { grant ->
                if (grant.url.isBlank() || (boundSessionId != null && grant.assetId.isNullOrBlank())) {
                    if (restoreSwitch()) return@fold
                    mutableState.value = PreviewState.Error(ERROR_MESSAGE)
                    pendingPreparation?.complete(Result.failure(IllegalArgumentException("试听地址不能为空")))
                    pendingPreparation = null
                    return@fold
                }
                playbackBinding = grant.binding ?: playbackBinding
                currentGrant = grant
                engine.load(grant.url)
                if(command.positionMillis!=null || grant.timelineOffsetMillis!=0L) engine.seekTo(((command.positionMillis ?: 0L)+grant.timelineOffsetMillis).coerceAtLeast(0))
                mediaLoaded = true
                pendingPreparation?.complete(Result.success(Unit))
                pendingPreparation = null
            },
            onFailure = { error ->
                if (restoreSwitch()) return@fold
                mutableState.value = PreviewState.Error(ERROR_MESSAGE)
                if (error.isRecoverablePreviewFailure()) {
                    pendingPreparation?.complete(Result.success(Unit))
                } else {
                    pendingPreparation?.complete(Result.failure(error))
                }
                pendingPreparation = null
            },
        )
    }

    private fun applyEngineEvent(command: PreviewCommand.EngineEvent) {
        if (
            command.generation != generation ||
            command.token !== activePreparationToken
        ) return
        command.token.withActive { applyActiveEngineEvent(command) }
    }

    private fun applyActiveEngineEvent(command: PreviewCommand.EngineEvent) {
        if (
            command.generation != generation ||
            command.token !== activePreparationToken
        ) return
        when (val event = command.event) {
            PreviewEngineEvent.Ready -> if (mediaLoaded) {
                pausedDuringBuffering = false
                modeState.value = requestedMode
                pendingSwitch?.complete(true); pendingSwitch = null; switchBackup = null
                mutableState.value = PreviewState.Buffered
                if (resumeAfterReady) {
                    resumeAfterReady = false
                    engine.play()
                    mutableState.value = PreviewState.Playing
                }
            }
            PreviewEngineEvent.Buffering -> if (mediaLoaded) {
                mutableState.value = PreviewState.Buffering
            }
            is PreviewEngineEvent.PlayingChanged -> if (mediaLoaded) {
                when {
                    event.isPlaying && mutableState.value is PreviewState.Buffered ->
                        mutableState.value = PreviewState.Playing
                    !event.isPlaying && mutableState.value is PreviewState.Playing ->
                        mutableState.value = PreviewState.Buffered
                }
            }
            is PreviewEngineEvent.HttpError -> handleHttpError(event.status)
            PreviewEngineEvent.PlaybackError -> if (!restoreSwitch()) mutableState.value = PreviewState.Error(ERROR_MESSAGE)
            PreviewEngineEvent.Ended -> mutableState.value = PreviewState.Ended
        }
    }

    private fun handleHttpError(status: Int) {
        if (pendingSwitch != null && restoreSwitch()) return
        val activeSongId = songId
        val activeToken = activePreparationToken
        if (
            status !in setOf(401, 403) || refreshUsed || activeSongId == null ||
            activeToken == null || currentGrant == null || !mediaLoaded
        ) {
            mutableState.value = PreviewState.Error(ERROR_MESSAGE)
            return
        }
        refreshUsed = true
        resumeAfterReady = mutableState.value is PreviewState.Playing
        val position = currentPositionMillis
        generation += 1
        currentGrant = null
        mediaLoaded = false
        mutableState.value = PreviewState.Buffering
        bindListener(generation, activeToken)
        fetchGrant(generation, activeToken, activeSongId, position)
    }

    private suspend fun applyPlay(command: PreviewCommand.Play) {
        if (mediaLoaded && pausedDuringBuffering && mutableState.value is PreviewState.Buffering) {
            resumeAfterReady = true
            pausedDuringBuffering = false
            command.completion.complete(true)
            return
        }
        if (mutableState.value !is PreviewState.Buffered || !mediaLoaded) {
            command.completion.complete(false)
            return
        }
        engine.play()
        mutableState.value = PreviewState.Playing
        command.completion.complete(true)
    }

    private suspend fun applyPause(command: PreviewCommand.Pause) {
        val state = mutableState.value
        if (!mediaLoaded || (state !is PreviewState.Playing && state !is PreviewState.Buffered && state !is PreviewState.Buffering)) {
            command.completion.complete(false)
            return
        }
        engine.pause()
        resumeAfterReady = false
        pausedDuringBuffering = state is PreviewState.Buffering
        if (state !is PreviewState.Buffering) mutableState.value = PreviewState.Buffered
        command.completion.complete(true)
    }

    private fun applyRewindToStart(command: PreviewCommand.RewindToStart) {
        if (mutableState.value !is PreviewState.Buffered && mutableState.value !is PreviewState.Playing &&
            mutableState.value !is PreviewState.Ended || !mediaLoaded
        ) {
            command.completion.complete(false)
            return
        }
        if (mutableState.value is PreviewState.Playing) engine.pause()
        engine.seekTo((currentGrant?.timelineOffsetMillis ?: 0L).coerceAtLeast(0))
        mutableState.value = PreviewState.Buffered
        command.completion.complete(true)
    }

    private fun applyPreparationCancellation(command: PreviewCommand.CancelPreparation) {
        try {
            if (command.token !== activePreparationToken) return
            generation += 1
            activePreparationToken = null
            songId = null
            currentGrant = null
            refreshUsed = false
            resumeAfterReady = false
            mediaLoaded = false
            pendingPreparation?.complete(Result.success(Unit))
            pendingPreparation = null
            mutableState.value = PreviewState.Idle
        } finally {
            command.acknowledgement.complete(Unit)
        }
    }

    private suspend fun applyRelease() {
        pendingSwitch?.complete(false); pendingSwitch = null
        activePreparationToken?.cancel()
        generation += 1
        activePreparationToken = null
        currentGrant = null
        mediaLoaded = false
        mutableState.value = PreviewState.Released
        pendingPreparation?.complete(Result.success(Unit))
        pendingPreparation = null
        try {
            grantJobs.forEach { it.cancel() }
            grantJobs.joinAll()
            engine.releaseAndAwait()
        } finally {
            commands.close()
        }
    }

    private fun bindListener(expectedGeneration: Long, token: PreparationToken) {
        engine.setListener { event ->
            admissionProbe.afterAdmission(PreviewAdmissionPoint.ENGINE_EVENT)
            commands.trySend(PreviewCommand.EngineEvent(expectedGeneration, token, event))
        }
    }

    private fun fetchGrant(
        expectedGeneration: Long,
        token: PreparationToken,
        expectedSongId: String,
        positionMillis: Long?,
    ) {
        val mode = requestedMode
        val session = boundSessionId
        val job = actorScope.launch(Dispatchers.IO) {
            val result = try {
                Result.success(grantSource.fetch(expectedSongId, mode, session))
            } catch (_: CancellationException) {
                return@launch
            } catch (error: Throwable) {
                Result.failure(error)
            }
            val point = if (result.exceptionOrNull()?.isRecoverablePreviewFailure() == true) {
                PreviewAdmissionPoint.PUBLISH_ERROR
            } else {
                PreviewAdmissionPoint.GRANT_RESULT
            }
            admissionProbe.afterAdmission(point)
            commands.trySend(
                PreviewCommand.GrantResolved(expectedGeneration, token, result, positionMillis),
            )
        }
        token.attachGrantJob(job)
        grantJobs += job
    }

    private fun submit(command: PreviewCommand): Boolean = synchronized(submissionLock) {
        acceptingCommands && commands.trySend(command).isSuccess
    }

    private companion object {
        const val ERROR_MESSAGE = "试听加载失败，请重试"
    }
}

private sealed interface PreviewCommand {
    data class Switch(val mode: SongPlaybackMode, val sessionId: String?, val reset: Boolean, val completion: CompletableDeferred<Boolean>) : PreviewCommand
    data class Prepare(
        val songId: String,
        val token: PreparationToken,
        val completion: CompletableDeferred<Result<Unit>>,
    ) : PreviewCommand

    data class GrantResolved(
        val generation: Long,
        val token: PreparationToken,
        val result: Result<PreviewGrant>,
        val positionMillis: Long?,
    ) : PreviewCommand

    data class EngineEvent(
        val generation: Long,
        val token: PreparationToken,
        val event: PreviewEngineEvent,
    ) : PreviewCommand
    data class Play(val completion: CompletableDeferred<Boolean>) : PreviewCommand
    data class Pause(val completion: CompletableDeferred<Boolean>) : PreviewCommand
    data class RewindToStart(val completion: CompletableDeferred<Boolean>) : PreviewCommand
    data class CancelPreparation(
        val token: PreparationToken,
        val acknowledgement: CompletableDeferred<Unit>,
    ) : PreviewCommand
    data object Release : PreviewCommand
}

private class PreparationToken {
    private val lock = Any()
    private var cancelled = false
    private var grantJob: Job? = null

    fun cancel() {
        val job = synchronized(lock) {
            cancelled = true
            grantJob
        }
        job?.cancel()
    }

    fun attachGrantJob(job: Job) {
        val cancelNow = synchronized(lock) {
            grantJob = job
            cancelled
        }
        if (cancelNow) job.cancel()
    }

    inline fun withActive(action: () -> Unit): Boolean = synchronized(lock) {
        if (cancelled) false else {
            action()
            true
        }
    }
}

private fun Throwable.isRecoverablePreviewFailure(): Boolean =
    this is IOException || this is HttpException || this is SerializationException || this is NetworkContractException

class ExoPreviewEngine(context: Context) : PreviewEngine {
    private val engineJob = SupervisorJob()
    private val scope = CoroutineScope(engineJob + Dispatchers.Main.immediate)
    private val player = ExoPlayer.Builder(context.applicationContext).build()
    @Volatile
    private var positionSnapshotMillis = 0L
    private var positionTicker: Job? = null
    @Volatile
    private var listener: (suspend (PreviewEngineEvent) -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                positionSnapshotMillis = player.currentPosition.coerceAtLeast(0)
                val event = when (playbackState) {
                    Player.STATE_BUFFERING -> PreviewEngineEvent.Buffering
                    Player.STATE_READY -> PreviewEngineEvent.Ready
                    Player.STATE_ENDED -> PreviewEngineEvent.Ended
                    else -> null
                }
                if (event != null) dispatch(event)
            }

            override fun onPlayerError(error: PlaybackException) {
                positionSnapshotMillis = player.currentPosition.coerceAtLeast(0)
                val status = generateSequence(error.cause) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                    ?.responseCode
                dispatch(status?.let(PreviewEngineEvent::HttpError) ?: PreviewEngineEvent.PlaybackError)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                positionSnapshotMillis = player.currentPosition.coerceAtLeast(0)
                positionTicker?.cancel()
                positionTicker = if (isPlaying) scope.launch {
                    while (player.isPlaying) {
                        positionSnapshotMillis = player.currentPosition.coerceAtLeast(0)
                        delay(POSITION_SNAPSHOT_INTERVAL_MILLIS)
                    }
                } else null
                dispatch(PreviewEngineEvent.PlayingChanged(isPlaying))
            }
        })
    }

    override fun setListener(listener: suspend (PreviewEngineEvent) -> Unit) {
        this.listener = listener
    }

    override val currentPositionMillis: Long
        get() = positionSnapshotMillis

    override fun load(url: String) {
        positionSnapshotMillis = 0
        scope.launch {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
        }
    }

    override fun seekTo(positionMillis: Long) {
        positionSnapshotMillis = positionMillis.coerceAtLeast(0)
        scope.launch { player.seekTo(positionMillis) }
    }

    override fun play() {
        scope.launch { player.play() }
    }

    override fun pause() {
        scope.launch { player.pause() }
    }

    override suspend fun releaseAndAwait() {
        withContext(Dispatchers.Main.immediate) {
            positionTicker?.cancel()
            listener = null
            player.release()
        }
        engineJob.cancelAndJoin()
    }

    private fun dispatch(event: PreviewEngineEvent) {
        val callback = listener ?: return
        scope.launch { callback(event) }
    }

    private companion object {
        const val POSITION_SNAPSHOT_INTERVAL_MILLIS = 50L
    }
}
