package com.vocaease.patient.feature.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingStateMachineTest {
    @Test
    fun `唯一正常路径依次进入Starting Recording Finalizing与Reviewable`() {
        val machine = RecordingStateMachine()

        assertEquals(RecordingState.Countdown(3), machine.state)
        assertEquals(RecordingState.Countdown(2), machine.dispatch(RecordingEvent.CountdownTick))
        assertEquals(RecordingState.Countdown(1), machine.dispatch(RecordingEvent.CountdownTick))
        assertEquals(RecordingState.Starting, machine.dispatch(RecordingEvent.CountdownTick))
        assertEquals(RecordingState.Recording(startedAtNanos = 11L, playbackOffsetMillis = 7L), machine.dispatch(RecordingEvent.CaptureStarted(11L, 7L)))
        assertEquals(RecordingState.Finalizing, machine.dispatch(RecordingEvent.StopRequested))
        assertEquals(RecordingState.Reviewable(durationMillis = 2_340L), machine.dispatch(RecordingEvent.Finalized(2_340L)))
    }

    @Test
    fun `用户提前结束与歌曲结束都只触发一次停止`() {
        listOf(RecordingEvent.StopRequested, RecordingEvent.PlaybackEnded).forEach { stop ->
            val machine = recordingMachine()
            assertEquals(RecordingState.Finalizing, machine.dispatch(stop))
            val terminal = machine.dispatch(RecordingEvent.StopRequested)
            assertSame(RecordingState.Finalizing, terminal)
            assertSame(terminal, machine.dispatch(RecordingEvent.PlaybackEnded))
        }
    }

    @Test
    fun `相机音频封装错误或取消都进入Interrupted且迟到Finalize不能复活`() {
        val events = listOf(
            RecordingEvent.Failed(RecordingInterruption.CAMERA),
            RecordingEvent.Failed(RecordingInterruption.AUDIO),
            RecordingEvent.Failed(RecordingInterruption.FINALIZE),
            RecordingEvent.Cancelled,
        )
        events.forEach { event ->
            val machine = recordingMachine()
            val interrupted = machine.dispatch(event)
            assertEquals(RecordingState.Interrupted((event as? RecordingEvent.Failed)?.reason ?: RecordingInterruption.CANCELLED), interrupted)
            assertSame(interrupted, machine.dispatch(RecordingEvent.Finalized(999)))
        }
    }

    @Test
    fun `Starting阶段的相机错误可终止且非法事件明确失败`() {
        val machine = RecordingStateMachine()
        repeat(3) { machine.dispatch(RecordingEvent.CountdownTick) }
        assertEquals(RecordingState.Interrupted(RecordingInterruption.CAMERA), machine.dispatch(RecordingEvent.Failed(RecordingInterruption.CAMERA)))

        assertThrows(IllegalRecordingTransitionException::class.java) {
            RecordingStateMachine().dispatch(RecordingEvent.CaptureStarted(1, 0))
        }
        assertThrows(IllegalRecordingTransitionException::class.java) {
            recordingMachine().dispatch(RecordingEvent.CountdownTick)
        }
    }

    private fun recordingMachine() = RecordingStateMachine().apply {
        repeat(3) { dispatch(RecordingEvent.CountdownTick) }
        dispatch(RecordingEvent.CaptureStarted(10L, 0L))
    }
}
