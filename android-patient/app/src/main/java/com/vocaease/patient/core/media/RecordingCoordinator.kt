package com.vocaease.patient.core.media

import com.vocaease.patient.feature.training.IllegalRecordingTransitionException
import com.vocaease.patient.feature.training.RecordingEvent
import com.vocaease.patient.feature.training.RecordingInterruption
import com.vocaease.patient.feature.training.RecordingState
import com.vocaease.patient.feature.training.RecordingStateMachine
import com.vocaease.patient.core.database.StaleAccountScopeException
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

sealed interface CaptureEvent {
    data object Started : CaptureEvent
    data class Finalized(val durationMillis: Long) : CaptureEvent
    data class Failure(val reason: RecordingInterruption) : CaptureEvent
}

interface RecordingCapture {
    var listener: suspend (CaptureEvent) -> Unit
    suspend fun bindFrontCamera()
    suspend fun start(output: File)
    fun stop()
    fun release() = Unit
}

interface RecordingPlayback {
    val currentPositionMillis: Long
    fun play()
    fun stop()
    fun setOnEnded(listener: () -> Unit) = Unit
}

interface RecordingTempFiles {
    fun createVideo(): File
    fun createVideo(identity: RecordingStagingIdentity): File = createVideo()
    fun createAudio(): File
    fun cleanup(vararg files: File)
}

fun interface RecordingArtifactPublisher {
    suspend fun publish(
        draftId: String,
        video: File,
        audio: File,
        durationMillis: Long,
        publicationActive: () -> Boolean,
    )
}

interface RecordingCoordinator : AutoCloseable {
    val state: StateFlow<RecordingState>
    val playbackPositionMillis: Long get() = 0
    val recordingDurationMillis: Long get() = 0
    suspend fun takeOver(draftId: String)
    suspend fun takeOver(draftId: String, stagingIdentity: RecordingStagingIdentity) = takeOver(draftId)
    suspend fun onCountdownFinished()
    suspend fun stop()
    suspend fun onPlaybackEnded()
    suspend fun interrupt(reason: RecordingInterruption)
    override fun close()
}

class DefaultRecordingCoordinator(
    private val capture: RecordingCapture,
    private val playback: RecordingPlayback,
    private val clockNanos: () -> Long,
    private val tempFiles: RecordingTempFiles,
    private val publisher: RecordingArtifactPublisher,
) : RecordingCoordinator {
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val machine = RecordingStateMachine()
    private val mutex = Mutex()
    private val stopIssued = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val publicationAllowed = AtomicBoolean(true)
    private val terminalDurationMillis = AtomicLong()
    private val mutableState = MutableStateFlow<RecordingState>(machine.state)
    override val state: StateFlow<RecordingState> = mutableState.asStateFlow()
    override val playbackPositionMillis: Long get() = playback.currentPositionMillis.coerceAtLeast(0)
    override val recordingDurationMillis: Long
        get() = (mutableState.value as? RecordingState.Recording)?.let {
            ((clockNanos() - it.startedAtNanos) / 1_000_000L).coerceAtLeast(0)
        } ?: terminalDurationMillis.get()
    private var draftId: String? = null
    private var stagingIdentity: RecordingStagingIdentity? = null
    private var video: File? = null
    private var audio: File? = null

    init {
        capture.listener = ::onCaptureEvent
        playback.setOnEnded { callbackScope.launch { onPlaybackEnded() } }
    }

    override suspend fun takeOver(draftId: String) = mutex.withLock {
        takeOverLocked(draftId, null)
    }

    override suspend fun takeOver(draftId: String, stagingIdentity: RecordingStagingIdentity) = mutex.withLock {
        require(stagingIdentity.draftId == draftId)
        takeOverLocked(draftId, stagingIdentity)
    }

    private suspend fun takeOverLocked(draftId: String, stagingIdentity: RecordingStagingIdentity?) {
        require(draftId.isNotBlank())
        check(this.draftId == null) { "录制协调器已接管" }
        this.draftId = draftId
        this.stagingIdentity = stagingIdentity
        capture.bindFrontCamera()
    }

    override suspend fun onCountdownFinished() = mutex.withLock {
        repeat(3) { update(RecordingEvent.CountdownTick) }
        val output = stagingIdentity?.let(tempFiles::createVideo) ?: tempFiles.createVideo()
        video = output
        try {
            capture.start(output)
        } catch (_: Exception) {
            interruptInternal(RecordingInterruption.CAMERA)
        }
    }

    override suspend fun stop() = mutex.withLock { requestStop() }

    override suspend fun onPlaybackEnded() = mutex.withLock { requestStop(playbackEnded = true) }

    override suspend fun interrupt(reason: RecordingInterruption) {
        publicationAllowed.set(false)
        mutex.withLock {
            interruptInternal(reason)
            if (stopIssued.compareAndSet(false, true)) {
                playback.stop()
                capture.stop()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        publicationAllowed.set(false)
        if (stopIssued.compareAndSet(false, true)) {
            playback.stop()
            capture.stop()
        }
        capture.release()
        cleanupPlaintext()
        callbackScope.cancel()
    }

    private suspend fun onCaptureEvent(event: CaptureEvent) = mutex.withLock {
        when (event) {
            CaptureEvent.Started -> {
                val now = clockNanos()
                val offset = playback.currentPositionMillis.coerceAtLeast(0)
                update(RecordingEvent.CaptureStarted(now, offset))
                playback.play()
            }
            is CaptureEvent.Failure -> {
                playback.stop()
                interruptInternal(event.reason)
            }
            is CaptureEvent.Finalized -> {
                if (!publicationAllowed.get() || machine.state !== RecordingState.Finalizing) {
                    cleanupPlaintext()
                    return@withLock
                }
                val currentVideo = video ?: return@withLock interruptInternal(RecordingInterruption.FINALIZE)
                val currentAudio = tempFiles.createAudio().also { audio = it }
                try {
                    publisher.publish(
                        requireNotNull(draftId),
                        currentVideo,
                        currentAudio,
                        event.durationMillis,
                        publicationAllowed::get,
                    )
                    if (!publicationAllowed.get()) throw RecordingPublicationCancelledException()
                    terminalDurationMillis.set(event.durationMillis.coerceAtLeast(0))
                    update(RecordingEvent.Finalized(event.durationMillis))
                } catch (_: MediaValidationException) {
                    interruptInternal(RecordingInterruption.VALIDATION)
                } catch (_: StaleAccountScopeException) {
                    interruptInternal(RecordingInterruption.ACCOUNT_CHANGED)
                } catch (_: RecordingPublicationCancelledException) {
                    interruptInternal(RecordingInterruption.CANCELLED)
                } catch (_: Exception) {
                    interruptInternal(
                        if (publicationAllowed.get()) RecordingInterruption.STORAGE else RecordingInterruption.CANCELLED,
                    )
                } finally {
                    cleanupPlaintext()
                }
            }
        }
    }

    private fun requestStop(playbackEnded: Boolean = false) {
        if (machine.state !is RecordingState.Recording && machine.state !== RecordingState.Finalizing) return
        snapshotRecordingDuration()
        update(if (playbackEnded) RecordingEvent.PlaybackEnded else RecordingEvent.StopRequested)
        if (stopIssued.compareAndSet(false, true)) {
            playback.stop()
            capture.stop()
        }
    }

    private fun interruptInternal(reason: RecordingInterruption) {
        snapshotRecordingDuration()
        runCatching { update(RecordingEvent.Failed(reason)) }
        cleanupPlaintext()
    }

    private fun snapshotRecordingDuration() {
        val recording = mutableState.value as? RecordingState.Recording ?: return
        terminalDurationMillis.set(
            ((clockNanos() - recording.startedAtNanos) / 1_000_000L).coerceAtLeast(0),
        )
    }

    private fun cleanupPlaintext() {
        tempFiles.cleanup(*listOfNotNull(video, audio).toTypedArray())
    }

    private fun update(event: RecordingEvent) {
        try {
            mutableState.value = machine.dispatch(event)
        } catch (error: IllegalRecordingTransitionException) {
            throw error
        }
    }
}

internal class RecordingPublicationCancelledException : IllegalStateException()
