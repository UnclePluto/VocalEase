package com.vocaease.patient.core.media

import androidx.camera.core.CameraSelector
import com.vocaease.patient.feature.training.RecordingInterruption
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraXRecordingCaptureTest {
    @Test
    fun `生产适配层固定前摄且唯一CameraX录制链启用音频`() = runBlocking {
        val backend = FakeCameraXBackend()
        val capture = CameraXRecordingCapture(backend, kotlinx.coroutines.Dispatchers.Unconfined)
        val events = mutableListOf<CaptureEvent>()
        capture.listener = { events += it }

        capture.bindFrontCamera()
        capture.start(File("build/camera-x.recording"))
        backend.emit(CameraXBackendEvent.Started)

        assertSame(CameraSelector.DEFAULT_FRONT_CAMERA, backend.selector)
        assertTrue(backend.audioEnabled)
        assertEquals(listOf(CaptureEvent.Started), events)
    }

    @Test
    fun `CameraX错误映射为安全中断且stop幂等下沉`() = runBlocking {
        val backend = FakeCameraXBackend()
        val capture = CameraXRecordingCapture(backend, kotlinx.coroutines.Dispatchers.Unconfined)
        val events = mutableListOf<CaptureEvent>()
        capture.listener = { events += it }
        capture.bindFrontCamera()
        capture.start(File("build/camera-x.recording"))

        backend.emit(CameraXBackendEvent.Failed(RecordingInterruption.AUDIO))
        capture.stop()
        capture.stop()

        assertEquals(listOf(CaptureEvent.Failure(RecordingInterruption.AUDIO)), events)
        assertEquals(2, backend.stopCount)
    }

    @Test
    fun `CameraX带错误Finalize保留时长和中断原因以尝试恢复可解析文件`() = runBlocking {
        val backend = FakeCameraXBackend()
        val capture = CameraXRecordingCapture(backend, kotlinx.coroutines.Dispatchers.Unconfined)
        val events = mutableListOf<CaptureEvent>()
        capture.listener = { events += it }
        capture.bindFrontCamera()
        capture.start(File("build/camera-x-error.recording"))

        backend.emit(CameraXBackendEvent.Started)
        backend.emit(CameraXBackendEvent.Finalized(1_234, RecordingInterruption.CAMERA))

        assertEquals(
            listOf(
                CaptureEvent.Started,
                CaptureEvent.Finalized(1_234, RecordingInterruption.CAMERA),
            ),
            events,
        )
    }

    @Test
    fun `录音权限在启动瞬间被撤销时安全映射为音频中断`() = runBlocking {
        val backend = FakeCameraXBackend().apply { startFailure = SecurityException("revoked") }
        val capture = CameraXRecordingCapture(backend, kotlinx.coroutines.Dispatchers.Unconfined)
        val events = mutableListOf<CaptureEvent>()
        capture.listener = { events += it }
        capture.bindFrontCamera()

        capture.start(File("build/camera-x.recording"))

        assertEquals(listOf(CaptureEvent.Failure(RecordingInterruption.AUDIO)), events)
    }

    @Test
    fun `Start监听尚未处理完时Finalize必须保持CameraX原始顺序`() = runBlocking {
        val backend = FakeCameraXBackend()
        val capture = CameraXRecordingCapture(backend, kotlinx.coroutines.Dispatchers.Default)
        val startEntered = CompletableDeferred<Unit>()
        val continueStart = CompletableDeferred<Unit>()
        val events = mutableListOf<CaptureEvent>()
        capture.listener = { event ->
            if (event === CaptureEvent.Started) {
                startEntered.complete(Unit)
                continueStart.await()
            }
            events += event
        }
        capture.bindFrontCamera()
        capture.start(File("build/camera-x.recording"))

        backend.emit(CameraXBackendEvent.Started)
        startEntered.await()
        backend.emit(CameraXBackendEvent.Finalized(1_000))
        delay(100)

        assertTrue(events.isEmpty())
        continueStart.complete(Unit)
        withTimeout(1_000) { while (events.size < 2) delay(1) }
        assertEquals(listOf(CaptureEvent.Started, CaptureEvent.Finalized(1_000)), events)
    }
}

private class FakeCameraXBackend : CameraXBackend {
    var selector: CameraSelector? = null
    var audioEnabled = false
    var stopCount = 0
    var startFailure: SecurityException? = null
    private var callback: (CameraXBackendEvent) -> Unit = {}
    override suspend fun bind(selector: CameraSelector) { this.selector = selector }
    override fun start(output: File, audioEnabled: Boolean, callback: (CameraXBackendEvent) -> Unit) {
        startFailure?.let { throw it }
        this.audioEnabled = audioEnabled
        this.callback = callback
    }
    override fun stop() { stopCount += 1 }
    override fun release() = Unit
    fun emit(event: CameraXBackendEvent) = callback(event)
}
