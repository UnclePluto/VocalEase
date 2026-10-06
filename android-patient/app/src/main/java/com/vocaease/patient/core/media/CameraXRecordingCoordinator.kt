package com.vocaease.patient.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.vocaease.patient.feature.training.RecordingInterruption
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal sealed interface CameraXBackendEvent {
    data object Started : CameraXBackendEvent
    data class Finalized(
        val durationMillis: Long,
        val interruption: RecordingInterruption? = null,
    ) : CameraXBackendEvent
    data class Failed(val reason: RecordingInterruption) : CameraXBackendEvent
}

internal interface CameraXBackend {
    val captureStartNanos:Long? get()=null
    suspend fun bind(selector: CameraSelector)
    fun start(output: File, audioEnabled: Boolean, callback: (CameraXBackendEvent) -> Unit)
    fun stop()
    fun release()
}

/** CameraX 固定前摄，只录制视频；麦克风由 MicrophonePcmCapture 独占。 */
class CameraXRecordingCapture internal constructor(
    private val backend: CameraXBackend,
    callbackDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RecordingCapture {
    constructor(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        executor: Executor = ContextCompat.getMainExecutor(context),
        viewPortProvider: (() -> androidx.camera.core.ViewPort?)? = null,
    ) : this(AndroidCameraXBackend(context, lifecycleOwner, surfaceProvider, executor, viewPortProvider), Dispatchers.Default)

    private val scope = CoroutineScope(SupervisorJob() + callbackDispatcher)
    private val events = Channel<CaptureEvent>(Channel.UNLIMITED)
    private val released = AtomicBoolean()
    override var listener: suspend (CaptureEvent) -> Unit = {}
    override val captureStartNanos get()=backend.captureStartNanos

    init {
        scope.launch {
            for (event in events) listener(event)
        }
    }

    override suspend fun bindFrontCamera() {
        check(!released.get())
        backend.bind(CameraSelector.DEFAULT_FRONT_CAMERA)
    }

    override fun start(output: File) {
        check(!released.get())
        try {
            backend.start(output, audioEnabled = false) { event ->
                val mapped = when (event) {
                    CameraXBackendEvent.Started -> CaptureEvent.Started
                    is CameraXBackendEvent.Finalized -> CaptureEvent.Finalized(
                        event.durationMillis,
                        event.interruption,
                    )
                    is CameraXBackendEvent.Failed -> CaptureEvent.Failure(event.reason)
                }
                events.trySend(mapped)
            }
        } catch (_: SecurityException) {
            events.trySend(CaptureEvent.Failure(RecordingInterruption.AUDIO))
        }
    }

    override fun stop() = backend.stop()

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        backend.release()
        events.close()
        scope.cancel()
    }
}

private class AndroidCameraXBackend(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val surfaceProvider: Preview.SurfaceProvider,
    private val executor: Executor,
    private val viewPortProvider: (() -> androidx.camera.core.ViewPort?)?,
) : CameraXBackend {
    private val appContext = context.applicationContext
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val frameOrigin=java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    override val captureStartNanos get()=frameOrigin.get().takeUnless { it==Long.MIN_VALUE }

    override suspend fun bind(selector: CameraSelector) {
        val provider = awaitCameraProvider()
        withContext(Dispatchers.Main.immediate) {
            val viewPort = if(viewPortProvider==null) null else kotlinx.coroutines.withTimeout(5000) {
                var value=viewPortProvider.invoke()
                while(value==null) { kotlinx.coroutines.delay(16);value=viewPortProvider.invoke() }
                value
            }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                    ),
                )
                .build()
            val capture = VideoCapture.Builder(recorder).setMirrorMode(androidx.camera.core.MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()
            provider.unbindAll()
            if(viewPort==null) provider.bindToLifecycle(lifecycleOwner,selector,preview,capture)
            else provider.bindToLifecycle(lifecycleOwner,selector,androidx.camera.core.UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(preview).addUseCase(capture).build())
            cameraProvider = provider
            videoCapture = capture
        }
    }

    override fun start(output: File, audioEnabled: Boolean, callback: (CameraXBackendEvent) -> Unit) {
        executor.execute {
            try {
                check(activeRecording == null)
                val capture = checkNotNull(videoCapture) { "前置摄像头尚未绑定" }
                val pending = capture.output.prepareRecording(appContext, FileOutputOptions.Builder(output).build())
                check(!audioEnabled) { "CameraX仅采集视频" }
                if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    callback(CameraXBackendEvent.Failed(RecordingInterruption.AUDIO))
                    return@execute
                }
                frameOrigin.set(Long.MIN_VALUE)
                // 直接在 Recorder 顺序执行器上取首帧 PTS，随后业务事件仍经队列串行投递。
                activeRecording = pending.start(Executor { it.run() }) { event ->
                    when (event) {
                        is VideoRecordEvent.Status -> if(event.recordingStats.numBytesRecorded>0 && frameOrigin.get()==Long.MIN_VALUE) {
                            val origin=androidx.camera.video.RecorderTimeBridge.firstFrameTimeNanos(capture.output)
                            if(frameOrigin.compareAndSet(Long.MIN_VALUE,origin)) callback(CameraXBackendEvent.Started)
                        }
                        is VideoRecordEvent.Finalize -> {
                            activeRecording = null
                            val durationMillis = event.recordingStats.recordedDurationNanos / 1_000_000L
                            if (!event.hasError()) {
                                callback(CameraXBackendEvent.Finalized(durationMillis))
                            } else {
                                val interruption = when (event.error) {
                                    VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> RecordingInterruption.CAMERA
                                    VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> RecordingInterruption.AUDIO
                                    else -> RecordingInterruption.FINALIZE
                                }
                                // CameraX 已经交付 Finalize 事件；将已写文件交给解析/校验层决定能否恢复。
                                callback(CameraXBackendEvent.Finalized(durationMillis, interruption))
                            }
                        }
                    }
                }
            } catch (_: SecurityException) {
                callback(CameraXBackendEvent.Failed(RecordingInterruption.AUDIO))
            } catch (_: Exception) {
                callback(CameraXBackendEvent.Failed(RecordingInterruption.CAMERA))
            }
        }
    }

    override fun stop() {
        executor.execute { activeRecording?.stop() }
    }

    override fun release() {
        executor.execute {
            activeRecording?.stop()
            activeRecording = null
            cameraProvider?.unbindAll()
            cameraProvider = null
            videoCapture = null
        }
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                try {
                    if (continuation.isActive) continuation.resume(future.get())
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            },
            executor,
        )
        continuation.invokeOnCancellation { future.cancel(true) }
    }
}
