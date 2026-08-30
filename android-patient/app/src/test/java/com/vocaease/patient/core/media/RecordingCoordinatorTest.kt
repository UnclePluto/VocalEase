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
    fun `Countdown中断后倒计时协程迟到不得创建暂存启动采集或播放`() = runBlocking {
        for (reason in listOf(RecordingInterruption.AUDIO, RecordingInterruption.CAMERA)) {
            val capture = FakeCapture()
            val playback = FakePlayback()
            val files = FakeTempFiles()
            val coordinator = DefaultRecordingCoordinator(
                capture, playback, { 1L }, files, FakePublisher(),
            )
            coordinator.takeOver("draft-$reason")

            coordinator.interrupt(reason)
            assertEquals(RecordingState.Interrupted(reason), coordinator.state.value)
            assertEquals(1, capture.stopCount)

            // 模拟已经恢复执行的旧 countdown 协程，以及不可信 backend 的迟到回调。
            coordinator.onCountdownFinished()
            capture.emit(CaptureEvent.Started)
            coordinator.stop()
            coordinator.interrupt(RecordingInterruption.CAMERA)

            assertEquals(0, files.videoCreateCount)
            assertEquals(0, capture.startCount)
            assertEquals(0, playback.playCount)
            assertEquals(1, capture.stopCount)
        }
    }

    @Test
    fun `Starting中断后迟到Started不得播放且会补发有效停止`() = runBlocking {
        val capture = FakeCapture()
        val playback = FakePlayback()
        val coordinator = DefaultRecordingCoordinator(
            capture, playback, { 1L }, FakeTempFiles(), FakePublisher(),
        )
        coordinator.takeOver("draft-1")
        coordinator.onCountdownFinished()
        assertEquals(RecordingState.Starting, coordinator.state.value)
        assertEquals(1, capture.startCount)

        coordinator.interrupt(RecordingInterruption.AUDIO)
        assertEquals(1, capture.stopCount)
        capture.emit(CaptureEvent.Started)

        assertEquals(RecordingState.Interrupted(RecordingInterruption.AUDIO), coordinator.state.value)
        assertEquals(0, playback.playCount)
        // 第一次 stop 可能发生在 CameraX 真正 active 前；Started 迟到时必须再兜底一次。
        assertEquals(2, capture.stopCount)
    }

    @Test
    fun `关闭或Reviewable后迟到Started绝不重新播放`() = runBlocking {
        val closedCapture = FakeCapture()
        val closedPlayback = FakePlayback()
        val closed = DefaultRecordingCoordinator(
            closedCapture, closedPlayback, { 1L }, FakeTempFiles(), FakePublisher(),
        )
        closed.takeOver("closed")
        closed.onCountdownFinished()
        closed.close()
        closedCapture.emit(CaptureEvent.Started)
        assertEquals(0, closedPlayback.playCount)

        val reviewedCapture = FakeCapture()
        val reviewedPlayback = FakePlayback()
        val reviewed = DefaultRecordingCoordinator(
            reviewedCapture, reviewedPlayback, { 1L }, FakeTempFiles(), FakePublisher(),
        )
        reviewed.takeOver("reviewed")
        reviewed.onCountdownFinished()
        reviewedCapture.emit(CaptureEvent.Started)
        reviewed.stop()
        reviewedCapture.emit(CaptureEvent.Finalized(1_000))
        val playsBeforeLateEvent = reviewedPlayback.playCount
        reviewedCapture.emit(CaptureEvent.Started)
        assertEquals(RecordingState.Reviewable(1_000), reviewed.state.value)
        assertEquals(playsBeforeLateEvent, reviewedPlayback.playCount)
    }

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
    fun `宿主停止或音频焦点丢失会尝试发布可解析中断录制而明确取消仍销毁`() = runBlocking {
        for (reason in listOf(RecordingInterruption.CAMERA, RecordingInterruption.AUDIO)) {
            val capture = FakeCapture()
            val files = FakeTempFiles()
            val publisher = FakePublisher()
            val coordinator = DefaultRecordingCoordinator(capture, FakePlayback(), { 1L }, files, publisher)
            coordinator.takeOver("draft-$reason")
            coordinator.onCountdownFinished()
            capture.emit(CaptureEvent.Started)

            coordinator.interrupt(reason)
            capture.emit(CaptureEvent.Finalized(1_000))

            assertEquals(RecordingState.Reviewable(1_000), coordinator.state.value)
            assertEquals(listOf(reason), publisher.interruptions)
        }

        val cancelledCapture = FakeCapture()
        val cancelledFiles = FakeTempFiles()
        val cancelledPublisher = FakePublisher()
        val cancelled = DefaultRecordingCoordinator(
            cancelledCapture, FakePlayback(), { 1L }, cancelledFiles, cancelledPublisher,
        )
        cancelled.takeOver("cancelled")
        cancelled.onCountdownFinished()
        cancelledCapture.emit(CaptureEvent.Started)
        cancelled.interrupt(RecordingInterruption.CANCELLED)
        cancelledCapture.emit(CaptureEvent.Finalized(1_000))
        assertEquals(RecordingState.Interrupted(RecordingInterruption.CANCELLED), cancelled.state.value)
        assertEquals(0, cancelledPublisher.calls)
        assertTrue(cancelledFiles.cleaned)
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
                interruption: RecordingInterruption?,
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
    var startCount = 0
    var releaseCount = 0
    var startFailure: Exception? = null

    override suspend fun bindFrontCamera() { frontCamera = true; calls += "bind-front" }
    override fun start(output: File) {
        startFailure?.let { throw it }
        startCount += 1
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
    var playCount = 0
    override fun play() { playing = true; playCount += 1; calls += "play" }
    override fun stop() { playing = false }
}

private class FakeTempFiles : RecordingTempFiles {
    var cleaned = false
    var videoCreateCount = 0
    var stagingIdentity: RecordingStagingIdentity? = null
    override fun createVideo(): File {
        videoCreateCount += 1
        return File("build/test-recording.recording")
    }
    override fun createVideo(identity: RecordingStagingIdentity): File {
        stagingIdentity = identity
        return createVideo()
    }
    override fun createAudio(): File = File("build/test-audio.recording")
    override fun cleanup(vararg files: File) { cleaned = true }
}

private class FakePublisher(private val error: Exception? = null) : RecordingArtifactPublisher {
    var calls = 0
    val interruptions = mutableListOf<RecordingInterruption?>()
    override suspend fun publish(
        draftId: String,
        video: File,
        audio: File,
        durationMillis: Long,
        publicationActive: () -> Boolean,
        interruption: RecordingInterruption?,
    ) {
        calls += 1
        interruptions += interruption
        error?.let { throw it }
    }
}
