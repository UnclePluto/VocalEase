package com.vocaease.patient.core.media

import com.vocaease.patient.feature.training.RecordingInterruption
import com.vocaease.patient.feature.training.RecordingState
import com.vocaease.patient.core.database.StaleAccountScopeException
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCoordinatorTest {
    @Test
    fun `带账户绑定接管会为视频创建可恢复暂存`() = runBlocking {
        val identity = RecordingStagingIdentity("a".repeat(64), "draft-1", "session-1", "create-1")
        val capture = FakeCapture()
        val tempFiles = FakeTempFiles()
        val coordinator = DefaultRecordingCoordinator(
            capture, FakePlayback(), { 1L }, tempFiles, FakePublisher(),
        )

        coordinator.takeOver("draft-1", identity)
        coordinator.onCountdownFinished()

        assertEquals(identity, tempFiles.stagingIdentity)
    }
    @Test
    fun `只选择前置摄像头且CameraX Start后才播放并记录单调偏移`() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val capture = FakeCapture(calls)
        val playback = FakePlayback(calls)
        val coordinator = DefaultRecordingCoordinator(
            capture = capture,
            playback = playback,
            clockNanos = { 1_000_000_000L },
            tempFiles = FakeTempFiles(),
            publisher = FakePublisher(),
        )

        coordinator.takeOver("draft-1")
        coordinator.onCountdownFinished()
        assertFalse(playback.playing)
        capture.emit(CaptureEvent.Started)

        assertEquals(listOf("bind-front", "start-with-audio", "play"), calls)
        assertTrue(capture.frontCamera)
        assertTrue(capture.audioEnabled)
        assertEquals(RecordingState.Recording(1_000_000_000L, 0L), coordinator.state.value)
    }

    @Test
    fun `重复stop与歌曲结束只调用CameraX一次且退出必停止`() = runBlocking {
        val capture = FakeCapture()
        val coordinator = coordinator(capture)
        coordinator.takeOver("draft-1")
        coordinator.onCountdownFinished()
        capture.emit(CaptureEvent.Started)

        coordinator.stop()
        coordinator.stop()
        coordinator.onPlaybackEnded()
        coordinator.close()

        assertEquals(1, capture.stopCount)
        assertEquals(RecordingState.Finalizing, coordinator.state.value)
    }

    @Test
    fun `CameraX错误中断且不能发布半成品`() = runBlocking {
        val capture = FakeCapture()
        val publisher = FakePublisher()
        val coordinator = DefaultRecordingCoordinator(capture, FakePlayback(), { 1L }, FakeTempFiles(), publisher)
        coordinator.takeOver("draft-1")
        coordinator.onCountdownFinished()
        capture.emit(CaptureEvent.Failure(RecordingInterruption.CAMERA))

        assertEquals(RecordingState.Interrupted(RecordingInterruption.CAMERA), coordinator.state.value)
        assertEquals(0, publisher.calls)
    }

    @Test
    fun `CameraX启动异常立即中断并清除已创建明文`() = runBlocking {
        val capture = FakeCapture().apply { startFailure = IllegalStateException("camera unavailable") }
        val files = FakeTempFiles()
        val coordinator = DefaultRecordingCoordinator(capture, FakePlayback(), { 1L }, files, FakePublisher())
        coordinator.takeOver("draft-1")

        coordinator.onCountdownFinished()

        assertEquals(RecordingState.Interrupted(RecordingInterruption.CAMERA), coordinator.state.value)
        assertTrue(files.cleaned)
    }

    @Test
    fun `主动中断后迟到Finalize不能发布且close清除明文`() = runBlocking {
        val capture = FakeCapture()
        val files = FakeTempFiles()
        val publisher = FakePublisher()
        val coordinator = DefaultRecordingCoordinator(capture, FakePlayback(), { 1L }, files, publisher)
        coordinator.takeOver("draft-1")
        coordinator.onCountdownFinished()
        capture.emit(CaptureEvent.Started)

        coordinator.interrupt(RecordingInterruption.ACCOUNT_CHANGED)
        capture.emit(CaptureEvent.Finalized(1_000))
        coordinator.close()

        assertEquals(RecordingState.Interrupted(RecordingInterruption.ACCOUNT_CHANGED), coordinator.state.value)
        assertEquals(0, publisher.calls)
        assertTrue(files.cleaned)
    }

    @Test
    fun `close重复调用也只释放一次CameraX`() {
        val capture = FakeCapture()
        val coordinator = coordinator(capture)

        coordinator.close()
        coordinator.close()

        assertEquals(1, capture.releaseCount)
    }

    @Test
    fun `Finalize校验失败与账号租约失效保留准确中断原因`() = runBlocking {
        listOf(
            MediaValidationException() to RecordingInterruption.VALIDATION,
            StaleAccountScopeException() to RecordingInterruption.ACCOUNT_CHANGED,
        ).forEach { (error, expected) ->
            val capture = FakeCapture()
            val coordinator = DefaultRecordingCoordinator(
                capture,
                FakePlayback(),
                { 1L },
                FakeTempFiles(),
                FakePublisher(error),
            )
            coordinator.takeOver("draft-1")
            coordinator.onCountdownFinished()
            capture.emit(CaptureEvent.Started)
            coordinator.stop()

            capture.emit(CaptureEvent.Finalized(1_000))

            assertEquals(RecordingState.Interrupted(expected), coordinator.state.value)
        }
    }

    @Test
    fun `Finalize发布途中取消会立即使发布租约失效且最终不进入Reviewable`() = runBlocking {
        val capture = FakeCapture()
        val publishEntered = CompletableDeferred<Unit>()
        val continuePublish = CompletableDeferred<Unit>()
        val publisher = object : RecordingArtifactPublisher {
            override suspend fun publish(
                draftId: String,
                video: File,
                audio: File,
                durationMillis: Long,
                publicationActive: () -> Boolean,
            ) {
                publishEntered.complete(Unit)
                continuePublish.await()
                check(publicationActive())
            }
        }
        val coordinator = DefaultRecordingCoordinator(capture, FakePlayback(), { 1L }, FakeTempFiles(), publisher)
        coordinator.takeOver("draft-1")
        coordinator.onCountdownFinished()
        capture.emit(CaptureEvent.Started)
        coordinator.stop()
        val finalize = async { capture.emit(CaptureEvent.Finalized(1_000)) }
        publishEntered.await()

        val cancel = async { coordinator.interrupt(RecordingInterruption.CANCELLED) }
        continuePublish.complete(Unit)
        finalize.await()
        cancel.await()

        assertEquals(RecordingState.Interrupted(RecordingInterruption.CANCELLED), coordinator.state.value)
    }

    @Test
    fun `stop离开Recording时冻结单调时长且Finalize后保留最终时长`() = runBlocking {
        var now = 1_000_000_000L
        val capture = FakeCapture()
        val coordinator = DefaultRecordingCoordinator(
            capture, FakePlayback(), { now }, FakeTempFiles(), FakePublisher(),
        )
        coordinator.takeOver("draft-1")
        coordinator.onCountdownFinished()
        capture.emit(CaptureEvent.Started)
        now = 1_275_000_000L

        coordinator.stop()

        assertEquals(275L, coordinator.recordingDurationMillis)
        now = 9_000_000_000L
        assertEquals(275L, coordinator.recordingDurationMillis)
        capture.emit(CaptureEvent.Finalized(270L))
        assertEquals(RecordingState.Reviewable(270L), coordinator.state.value)
        assertEquals(270L, coordinator.recordingDurationMillis)
    }

    @Test
    fun `录制开始后的错误与取消持久保留各自真实单调时长而Starting失败为零`() = runBlocking {
        suspend fun interruptedDuration(reason: RecordingInterruption, elapsedMillis: Long): Long {
            var now = 2_000_000_000L
            val capture = FakeCapture()
            val coordinator = DefaultRecordingCoordinator(
                capture, FakePlayback(), { now }, FakeTempFiles(), FakePublisher(),
            )
            coordinator.takeOver("draft-1")
            coordinator.onCountdownFinished()
            capture.emit(CaptureEvent.Started)
            now += elapsedMillis * 1_000_000L
            if (reason == RecordingInterruption.CANCELLED) coordinator.interrupt(reason)
            else capture.emit(CaptureEvent.Failure(reason))
            return coordinator.recordingDurationMillis
        }

        assertEquals(321L, interruptedDuration(RecordingInterruption.CANCELLED, 321L))
        assertEquals(432L, interruptedDuration(RecordingInterruption.CAMERA, 432L))
        assertEquals(543L, interruptedDuration(RecordingInterruption.AUDIO, 543L))

        val startingCapture = FakeCapture()
        val starting = DefaultRecordingCoordinator(
            startingCapture, FakePlayback(), { 99_000_000_000L }, FakeTempFiles(), FakePublisher(),
        )
        starting.takeOver("draft-1")
        starting.onCountdownFinished()
        startingCapture.emit(CaptureEvent.Failure(RecordingInterruption.CAMERA))
        assertEquals(0L, starting.recordingDurationMillis)
    }

    private fun coordinator(capture: FakeCapture) = DefaultRecordingCoordinator(
        capture, FakePlayback(), { 1L }, FakeTempFiles(), FakePublisher(),
    )
}

private class FakeCapture(private val calls: MutableList<String> = mutableListOf()) : RecordingCapture {
    override var listener: suspend (CaptureEvent) -> Unit = {}
    var frontCamera = false
    var audioEnabled = false
    var stopCount = 0
    var releaseCount = 0
    var startFailure: Exception? = null

    override suspend fun bindFrontCamera() { frontCamera = true; calls += "bind-front" }
    override suspend fun start(output: File) {
        startFailure?.let { throw it }
        audioEnabled = true
        calls += "start-with-audio"
    }
    override fun stop() { stopCount += 1 }
    override fun release() { releaseCount += 1 }
    suspend fun emit(event: CaptureEvent) = listener(event)
}

private class FakePlayback(private val calls: MutableList<String> = mutableListOf()) : RecordingPlayback {
    override val currentPositionMillis: Long = 0
    var playing = false
    override fun play() { playing = true; calls += "play" }
    override fun stop() { playing = false }
}

private class FakeTempFiles : RecordingTempFiles {
    var cleaned = false
    var stagingIdentity: RecordingStagingIdentity? = null
    override fun createVideo(): File = File("build/test-recording.recording")
    override fun createVideo(identity: RecordingStagingIdentity): File {
        stagingIdentity = identity
        return createVideo()
    }
    override fun createAudio(): File = File("build/test-audio.recording")
    override fun cleanup(vararg files: File) { cleaned = true }
}

private class FakePublisher(private val error: Exception? = null) : RecordingArtifactPublisher {
    var calls = 0
    override suspend fun publish(
        draftId: String,
        video: File,
        audio: File,
        durationMillis: Long,
        publicationActive: () -> Boolean,
    ) {
        calls += 1
        error?.let { throw it }
    }
}
