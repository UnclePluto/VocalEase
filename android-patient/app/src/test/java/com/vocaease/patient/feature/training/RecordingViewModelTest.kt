package com.vocaease.patient.feature.training

import com.vocaease.patient.core.media.RecordingCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingViewModelTest {
    @Test
    fun `真正进入Recording后才确认Task7 handoff并开启屏幕常亮`() = runBlocking {
        val coordinator = FakeRecordingSession()
        val gateway = FakeRecordingDraftGateway()
        val viewModel = RecordingViewModel(
            "draft-1", coordinator, gateway, countdownTick = {}, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

        viewModel.start()
        assertEquals(0, gateway.ackCount)
        assertFalse(viewModel.state.value.keepScreenOn)

        coordinator.emit(RecordingState.Starting)
        assertTrue(viewModel.state.value.keepScreenOn)
        coordinator.emit(RecordingState.Recording(10L, 2L))

        assertEquals(1, gateway.ackCount)
        assertTrue(viewModel.state.value.keepScreenOn)
        coordinator.emit(RecordingState.Recording(11L, 3L))
        assertEquals(1, gateway.ackCount)
    }

    @Test
    fun `Reviewable只导航一次而Interrupted持久化安全原因且关闭常亮`() = runBlocking {
        val coordinator = FakeRecordingSession()
        val gateway = FakeRecordingDraftGateway()
        val viewModel = RecordingViewModel(
            "draft-1", coordinator, gateway, countdownTick = {}, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        viewModel.start()
        coordinator.emit(RecordingState.Starting)
        coordinator.emit(RecordingState.Recording(1L, 0L))
        coordinator.emit(RecordingState.Finalizing)
        assertTrue(viewModel.state.value.keepScreenOn)
        coordinator.emit(RecordingState.Reviewable(2_000))

        assertEquals("draft-1", viewModel.state.value.navigateReviewDraftId)
        assertFalse(viewModel.state.value.keepScreenOn)
        viewModel.consumeReviewNavigation()
        assertNull(viewModel.state.value.navigateReviewDraftId)

        val interruptedCoordinator = FakeRecordingSession()
        val interrupted = RecordingViewModel(
            "draft-2", coordinator = interruptedCoordinator, gateway = gateway,
            countdownTick = {}, dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        interrupted.start()
        interruptedCoordinator.emit(
            RecordingState.Interrupted(RecordingInterruption.ACCOUNT_CHANGED),
        )
        assertEquals("账号已切换，录制已中断", interrupted.state.value.errorMessage)
        assertEquals(RecordingInterruption.ACCOUNT_CHANGED, gateway.interruption)
        assertFalse(interrupted.state.value.keepScreenOn)
    }

    @Test
    fun `页面退出总是stop并close`() = runBlocking {
        val coordinator = FakeRecordingSession()
        val viewModel = RecordingViewModel(
            "draft-1", coordinator, FakeRecordingDraftGateway(), {}, kotlinx.coroutines.Dispatchers.Unconfined,
        )
        viewModel.start()

        viewModel.leave()
        viewModel.leave()

        assertEquals(listOf(RecordingInterruption.CANCELLED), coordinator.interruptions)
        assertEquals(1, coordinator.closeCount)
    }

    @Test
    fun `宿主停止与音频焦点丢失交给可恢复中断而非显式取消`() = runBlocking {
        val coordinator = FakeRecordingSession()
        val viewModel = RecordingViewModel(
            "draft-1", coordinator, FakeRecordingDraftGateway(), {}, kotlinx.coroutines.Dispatchers.Unconfined,
        )
        viewModel.start()

        viewModel.onHostStopped()
        viewModel.onAudioFocusLost()

        assertEquals(
            listOf(RecordingInterruption.CAMERA, RecordingInterruption.AUDIO),
            coordinator.interruptions,
        )
        assertFalse(coordinator.interruptions.contains(RecordingInterruption.CANCELLED))
    }

    @Test
    fun `handoff确认失败主动中断并阻止迟到Finalize发布`() = runBlocking {
        val coordinator = FakeRecordingSession()
        val gateway = FakeRecordingDraftGateway().apply { ackFailure = true }
        val viewModel = RecordingViewModel(
            "draft-1", coordinator, gateway, {}, kotlinx.coroutines.Dispatchers.Unconfined,
        )
        viewModel.start()

        coordinator.emit(RecordingState.Recording(1L, 0L))

        assertEquals(listOf(RecordingInterruption.STORAGE), coordinator.interruptions)
        assertEquals(RecordingState.Interrupted(RecordingInterruption.STORAGE), viewModel.state.value.recordingState)
    }

    @Test
    fun `宿主销毁录制页也必须撤销发布并持久化取消`() = runBlocking {
        val coordinator = FakeRecordingSession()
        val gateway = FakeRecordingDraftGateway()
        val viewModel = RecordingViewModel(
            "draft-1", coordinator, gateway, {}, kotlinx.coroutines.Dispatchers.Unconfined,
        )
        viewModel.start()
        coordinator.emit(RecordingState.Recording(1L, 0L))

        viewModel.disposeRoute()

        assertEquals(listOf(RecordingInterruption.CANCELLED), coordinator.interruptions)
        assertEquals(RecordingInterruption.CANCELLED, gateway.interruption)
        assertEquals(1, coordinator.closeCount)
    }

    @Test
    fun `进入Reviewable后的正常导航销毁只释放资源而不回滚作品`() = runBlocking {
        val coordinator = FakeRecordingSession()
        val gateway = FakeRecordingDraftGateway()
        val viewModel = RecordingViewModel(
            "draft-1", coordinator, gateway, {}, kotlinx.coroutines.Dispatchers.Unconfined,
        )
        viewModel.start()
        coordinator.emit(RecordingState.Reviewable(2_000))

        viewModel.disposeRoute()

        assertNull(gateway.interruption)
        assertEquals(1, coordinator.closeCount)
    }

    @Test
    fun `ticker捕获旧录制态后Finalize仍不得覆盖Reviewable导航与常亮终态`() = runBlocking {
        val coordinator = FakeRecordingSession()
        val viewModel = RecordingViewModel(
            "draft-1",
            coordinator,
            FakeRecordingDraftGateway(),
            {},
            kotlinx.coroutines.Dispatchers.Default,
        )
        viewModel.start()
        coordinator.emit(RecordingState.Recording(1L, 0L))
        withTimeout(2_000) { viewModel.state.first { it.recordingState is RecordingState.Recording } }
        coordinator.blockNextDurationRead.set(true)
        assertTrue(coordinator.durationReadEntered.await(2, TimeUnit.SECONDS))

        coordinator.emit(RecordingState.Reviewable(2_000))
        withTimeout(2_000) { viewModel.state.first { it.recordingState is RecordingState.Reviewable } }
        coordinator.releaseDurationRead.countDown()
        kotlinx.coroutines.delay(300)

        assertEquals(RecordingState.Reviewable(2_000), viewModel.state.value.recordingState)
        assertEquals("draft-1", viewModel.state.value.navigateReviewDraftId)
        assertFalse(viewModel.state.value.keepScreenOn)
        viewModel.disposeRoute()
    }
}

private class FakeRecordingSession : RecordingCoordinator {
    private val mutable = MutableStateFlow<RecordingState>(RecordingState.Countdown(3))
    override val state: StateFlow<RecordingState> = mutable
    var stopCount = 0
    var closeCount = 0
    val interruptions = mutableListOf<RecordingInterruption>()
    val blockNextDurationRead = AtomicBoolean()
    val durationReadEntered = CountDownLatch(1)
    val releaseDurationRead = CountDownLatch(1)
    override val recordingDurationMillis: Long
        get() {
            if (blockNextDurationRead.compareAndSet(true, false)) {
                durationReadEntered.countDown()
                check(releaseDurationRead.await(2, TimeUnit.SECONDS))
            }
            return 1_000L
        }
    override suspend fun takeOver(draftId: String) = Unit
    override suspend fun onCountdownFinished() = Unit
    override suspend fun stop() { stopCount += 1 }
    override suspend fun onPlaybackEnded() = Unit
    override suspend fun interrupt(reason: RecordingInterruption) { interruptions += reason; mutable.value = RecordingState.Interrupted(reason) }
    override fun close() { closeCount += 1 }
    fun emit(state: RecordingState) { mutable.value = state }
}

private class FakeRecordingDraftGateway : RecordingDraftGateway {
    var ackCount = 0
    var interruption: RecordingInterruption? = null
    var ackFailure = false
    override suspend fun load(draftId: String) = RecordingDraftInfo(draftId, "小幸运", 265_000)
    override suspend fun acknowledgeHandoff(draftId: String) { ackCount += 1; if (ackFailure) error("ack failed") }
    override suspend fun markInterrupted(draftId: String, reason: RecordingInterruption, durationMillis: Long) {
        interruption = reason
    }
}
