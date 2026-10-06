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
    data class Finalized(
        val durationMillis: Long,
        val interruption: RecordingInterruption? = null,
        val encoded: EncodedRecording? = null,
    ) : CaptureEvent
    data class Failure(val reason: RecordingInterruption) : CaptureEvent
}

interface RecordingCapture {
    val pitch: StateFlow<PitchSample> get() = MutableStateFlow(PitchSample(0, null, 0f))
    val captureStartNanos: Long? get() = null
    var listener: suspend (CaptureEvent) -> Unit
    suspend fun bindFrontCamera()
    fun start(output: File)
    fun stop()
    fun release() = Unit
}

interface RecordingPlayback {
    val playbackBinding: com.vocaease.patient.core.network.dto.PlaybackBindingDto? get() = null
    val activeMode: kotlinx.coroutines.flow.StateFlow<SongPlaybackMode> get() = kotlinx.coroutines.flow.MutableStateFlow(SongPlaybackMode.ACCOMPANIMENT)
    val playbackState: kotlinx.coroutines.flow.StateFlow<PreviewState> get() = kotlinx.coroutines.flow.MutableStateFlow(PreviewState.Playing)
    suspend fun switchMode(mode: SongPlaybackMode): Boolean = false
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
        interruption: RecordingInterruption?,
    )
}

interface RecordingCoordinator : AutoCloseable {
    val pitch: StateFlow<PitchSample> get() = MutableStateFlow(PitchSample(0,null,0f))
    val activeMode: StateFlow<SongPlaybackMode> get() = MutableStateFlow(SongPlaybackMode.ACCOMPANIMENT)
    val playbackBinding: com.vocaease.patient.core.network.dto.PlaybackBindingDto? get() = null
    val anchors: List<PlaybackAnchor> get() = emptyList()
    suspend fun switchMode(mode: SongPlaybackMode): Boolean = false
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
    private val persistMetadata: suspend (String, PlaybackMetadata) -> Unit = { _, _ -> },
) : RecordingCoordinator {
    override val pitch get() = capture.pitch
    override val activeMode get() = playback.activeMode
    override val playbackBinding get() = playback.playbackBinding
    private var metadataRecorder: PlaybackMetadataRecorder? = null
    override val anchors get() = metadataRecorder?.snapshot()?.anchors ?: emptyList()
    private var metadataJob: kotlinx.coroutines.Job? = null
    override suspend fun switchMode(mode: SongPlaybackMode): Boolean {
        val result = playback.switchMode(mode)
        if(result) mutex.withLock { recordAnchor() }
        return result
    }
    private suspend fun recordAnchor() {
        metadataRecorder?.let { recorder ->
            try {
                recorder.record(recordingDurationMillis,playbackPositionMillis,activeMode.value,playback.playbackState.value is PreviewState.Playing)
                persistMetadata(requireNotNull(draftId),recorder.snapshot())
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publicationAllowed.set(false)
                metadataJob?.cancel()
                issueStopOnce()
                capture.release()
                interruptInternal(if (generateSequence<Throwable>(failure) { it.cause }.any { it is StaleAccountScopeException }) RecordingInterruption.ACCOUNT_CHANGED else RecordingInterruption.STORAGE)
            }
        }
    }
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val machine = RecordingStateMachine()
    private val mutex = Mutex()
    private val captureGate = Any()
    private val stopIssued = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val publicationAllowed = AtomicBoolean(true)
    private val terminalDurationMillis = AtomicLong()
    private val nextOperationGeneration = AtomicLong()
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
    private var pendingInterruption: RecordingInterruption? = null
    private var operationGeneration = 0L
    private var captureStartGeneration = 0L
    private var acceptedStartedGeneration = 0L
    private var lateStartFallbackStoppedGeneration = 0L

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
        operationGeneration = nextOperationGeneration.incrementAndGet()
        capture.bindFrontCamera()
    }

    override suspend fun onCountdownFinished() = mutex.withLock {
        val generation = operationGeneration
        if (
            closed.get() ||
            generation == 0L ||
            machine.state !is RecordingState.Countdown
        ) return@withLock
        repeat(3) { update(RecordingEvent.CountdownTick) }
        if (machine.state !== RecordingState.Starting || generation != operationGeneration) return@withLock
        try {
            synchronized(captureGate) {
                if (closed.get() || generation != operationGeneration) return@withLock
                val output = stagingIdentity?.let(tempFiles::createVideo) ?: tempFiles.createVideo()
                video = output
                captureStartGeneration = generation
                capture.start(output)
            }
        } catch (_: Exception) {
            interruptInternal(RecordingInterruption.CAMERA)
            issueStopOnce()
        }
    }

    override suspend fun stop() = mutex.withLock { requestStop() }

    override suspend fun onPlaybackEnded() = mutex.withLock { requestStop(playbackEnded = true) }

    override suspend fun interrupt(reason: RecordingInterruption) {
        if (!reason.isRecoverableSystemInterruption()) publicationAllowed.set(false)
        mutex.withLock {
            if (reason.isRecoverableSystemInterruption() && machine.state is RecordingState.Recording) {
                pendingInterruption = pendingInterruption ?: reason
                requestStop()
            } else if (reason.isRecoverableSystemInterruption() && machine.state === RecordingState.Finalizing) {
                pendingInterruption = pendingInterruption ?: reason
            } else {
                interruptInternal(reason)
                issueStopOnce()
            }
        }
    }

    override fun close() {
        synchronized(captureGate) {
            if (!closed.compareAndSet(false, true)) return
            publicationAllowed.set(false)
            issueStopOnce()
            capture.release()
        }
        cleanupPlaintext()
        callbackScope.cancel()
    }

    private suspend fun onCaptureEvent(event: CaptureEvent) = mutex.withLock {
        when (event) {
            CaptureEvent.Started -> {
                synchronized(captureGate) {
                    val generation = captureStartGeneration
                    if (
                        closed.get() ||
                        generation == 0L ||
                        generation != operationGeneration ||
                        machine.state !== RecordingState.Starting
                    ) {
                        if (
                            generation != 0L &&
                            generation != acceptedStartedGeneration &&
                            lateStartFallbackStoppedGeneration != generation
                        ) {
                            lateStartFallbackStoppedGeneration = generation
                            playback.stop()
                            capture.stop()
                        }
                    } else {
                        val now = capture.captureStartNanos ?: clockNanos()
                        val offset = playback.currentPositionMillis.coerceAtLeast(0)
                        update(RecordingEvent.CaptureStarted(now, offset))
                        acceptedStartedGeneration = generation
                        playback.play()
                        playbackBinding?.let { binding ->
                            if(binding.sourceAssetId != null && binding.accompanimentAssetId != null) {
                                metadataRecorder=PlaybackMetadataRecorder(48000,binding.sourceAssetId,binding.accompanimentAssetId,binding.referenceVersion)
                                metadataRecorder!!.record(0,offset,activeMode.value,false)
                                metadataJob=callbackScope.launch {
                                    var lastPlaying: Boolean? = null;var lastCalibration=0L
                                    while(!closed.get()) {
                                        kotlinx.coroutines.delay(50)
                                        mutex.withLock {
                                            if(machine.state is RecordingState.Recording) {
                                                val playing=playback.playbackState.value is PreviewState.Playing
                                                val now=recordingDurationMillis
                                                if(lastPlaying != playing || now-lastCalibration>=5000) { recordAnchor();lastPlaying=playing;lastCalibration=now }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            is CaptureEvent.Failure -> {
                playback.stop()
                interruptInternal(event.reason)
            }
            is CaptureEvent.Finalized -> {
                if (event.interruption != null && machine.state is RecordingState.Recording) {
                    pendingInterruption = pendingInterruption ?: event.interruption
                    requestStop()
                }
                if (!publicationAllowed.get() || machine.state !== RecordingState.Finalizing) {
                    cleanupPlaintext()
                    return@withLock
                }
                val currentVideo = video ?: return@withLock interruptInternal(RecordingInterruption.FINALIZE)
                val currentAudio = tempFiles.createAudio().also { audio = it }
                try {
                    metadataJob?.cancel()
                    metadataRecorder?.let { recorder ->
                        persistMetadata(requireNotNull(draftId),recorder.finish(event.durationMillis,event.encoded?.sampleRate ?: 48000,event.encoded?.effectiveStartOffsetMillis ?: 0))
                    }
                    publisher.publish(
                        requireNotNull(draftId),
                        currentVideo,
                        currentAudio,
                        event.durationMillis,
                        publicationAllowed::get,
                        pendingInterruption ?: event.interruption,
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
        metadataRecorder?.record(recordingDurationMillis,playbackPositionMillis,activeMode.value,playback.playbackState.value is PreviewState.Playing)
        if (machine.state !is RecordingState.Recording && machine.state !== RecordingState.Finalizing) return
        snapshotRecordingDuration()
        update(if (playbackEnded) RecordingEvent.PlaybackEnded else RecordingEvent.StopRequested)
        issueStopOnce()
    }

    private fun issueStopOnce() {
        if (!stopIssued.compareAndSet(false, true)) return
        playback.stop()
        capture.stop()
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

private fun RecordingInterruption.isRecoverableSystemInterruption(): Boolean = when (this) {
    RecordingInterruption.CAMERA,
    RecordingInterruption.AUDIO,
    RecordingInterruption.FINALIZE,
    -> true
    RecordingInterruption.VALIDATION,
    RecordingInterruption.STORAGE,
    RecordingInterruption.ACCOUNT_CHANGED,
    RecordingInterruption.CANCELLED,
    -> false
}
