package com.unclepluto.vocaease.singing

enum class CapturePhase {
    IDLE,
    PRE,
    SINGING,
    POST,
    COMPLETED,
    INTERRUPTED,
}

enum class CaptureEvent {
    RECORDING_STARTED,
    PRE_FINISHED,
    ACCOMPANIMENT_FINISHED,
    POST_FINISHED,
    INTERRUPT,
}

class ThreePhaseStateMachine {
    var phase: CapturePhase = CapturePhase.IDLE
        private set

    fun transition(event: CaptureEvent): CapturePhase {
        phase = when {
            event == CaptureEvent.INTERRUPT && phase !in TERMINAL_PHASES ->
                CapturePhase.INTERRUPTED
            phase == CapturePhase.IDLE && event == CaptureEvent.RECORDING_STARTED ->
                CapturePhase.PRE
            phase == CapturePhase.PRE && event == CaptureEvent.PRE_FINISHED ->
                CapturePhase.SINGING
            phase == CapturePhase.SINGING && event == CaptureEvent.ACCOMPANIMENT_FINISHED ->
                CapturePhase.POST
            phase == CapturePhase.POST && event == CaptureEvent.POST_FINISHED ->
                CapturePhase.COMPLETED
            else -> error("非法采集阶段转换：$phase + $event")
        }
        return phase
    }

    fun canResume(): Boolean = phase !in TERMINAL_PHASES

    private companion object {
        val TERMINAL_PHASES = setOf(CapturePhase.COMPLETED, CapturePhase.INTERRUPTED)
    }
}

enum class HeadphonePrompt {
    READY,
    FIRST_WARNING,
    SECOND_WARNING,
    CONFIRMED_WITH_RISK,
}

fun initialHeadphonePrompt(hasHeadphones: Boolean): HeadphonePrompt =
    if (hasHeadphones) HeadphonePrompt.READY else HeadphonePrompt.FIRST_WARNING

fun continueWithoutHeadphones(current: HeadphonePrompt): HeadphonePrompt = when (current) {
    HeadphonePrompt.FIRST_WARNING -> HeadphonePrompt.SECOND_WARNING
    HeadphonePrompt.SECOND_WARNING -> HeadphonePrompt.CONFIRMED_WITH_RISK
    else -> error("当前状态不能确认无耳机录制：$current")
}

enum class UploadFailureDecision {
    RETRY_AUTOMATICALLY,
    EXPOSE_MANUAL_RETRY,
}

fun uploadFailureDecision(runAttemptCount: Int, maxRetries: Int): UploadFailureDecision =
    if (runAttemptCount < maxRetries) {
        UploadFailureDecision.RETRY_AUTOMATICALLY
    } else {
        UploadFailureDecision.EXPOSE_MANUAL_RETRY
    }
