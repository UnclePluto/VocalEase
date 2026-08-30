package com.vocaease.patient.feature.training

enum class RecordingInterruption {
    CAMERA,
    AUDIO,
    FINALIZE,
    VALIDATION,
    STORAGE,
    ACCOUNT_CHANGED,
    CANCELLED,
}

sealed interface RecordingState {
    data class Countdown(val remainingSeconds: Int) : RecordingState
    data object Starting : RecordingState
    data class Recording(
        val startedAtNanos: Long,
        val playbackOffsetMillis: Long,
    ) : RecordingState
    data object Finalizing : RecordingState
    data class Reviewable(val durationMillis: Long) : RecordingState
    data class Interrupted(val reason: RecordingInterruption) : RecordingState
}

sealed interface RecordingEvent {
    data object CountdownTick : RecordingEvent
    data class CaptureStarted(val startedAtNanos: Long, val playbackOffsetMillis: Long) : RecordingEvent
    data object StopRequested : RecordingEvent
    data object PlaybackEnded : RecordingEvent
    data class Finalized(val durationMillis: Long) : RecordingEvent
    data class Failed(val reason: RecordingInterruption) : RecordingEvent
    data object Cancelled : RecordingEvent
}

class IllegalRecordingTransitionException(
    state: RecordingState,
    event: RecordingEvent,
) : IllegalStateException("非法录制状态转换：${state::class.simpleName}/${event::class.simpleName}")

class RecordingStateMachine {
    var state: RecordingState = RecordingState.Countdown(3)
        private set

    @Synchronized
    fun dispatch(event: RecordingEvent): RecordingState {
        val current = state
        val next = when {
            current is RecordingState.Interrupted || current is RecordingState.Reviewable -> current
            event is RecordingEvent.Failed -> RecordingState.Interrupted(event.reason)
            event === RecordingEvent.Cancelled -> RecordingState.Interrupted(RecordingInterruption.CANCELLED)
            current is RecordingState.Countdown && event === RecordingEvent.CountdownTick ->
                if (current.remainingSeconds > 1) current.copy(remainingSeconds = current.remainingSeconds - 1)
                else RecordingState.Starting
            current === RecordingState.Starting && event is RecordingEvent.CaptureStarted ->
                RecordingState.Recording(event.startedAtNanos, event.playbackOffsetMillis)
            current is RecordingState.Recording &&
                (event === RecordingEvent.StopRequested || event === RecordingEvent.PlaybackEnded) -> RecordingState.Finalizing
            current === RecordingState.Finalizing &&
                (event === RecordingEvent.StopRequested || event === RecordingEvent.PlaybackEnded) -> current
            current === RecordingState.Finalizing && event is RecordingEvent.Finalized ->
                RecordingState.Reviewable(event.durationMillis.coerceAtLeast(0))
            else -> throw IllegalRecordingTransitionException(current, event)
        }
        state = next
        return next
    }
}
