package com.unclepluto.vocaease.singing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SingingRulesTest {
    @Test
    fun `三阶段只能按唱前唱中唱后顺序推进`() {
        val machine = ThreePhaseStateMachine()
        assertEquals(CapturePhase.PRE, machine.transition(CaptureEvent.RECORDING_STARTED))
        assertEquals(CapturePhase.SINGING, machine.transition(CaptureEvent.PRE_FINISHED))
        assertEquals(
            CapturePhase.POST,
            machine.transition(CaptureEvent.ACCOMPANIMENT_FINISHED),
        )
        assertEquals(CapturePhase.COMPLETED, machine.transition(CaptureEvent.POST_FINISHED))
        assertFalse(machine.canResume())
        assertThrows(IllegalStateException::class.java) {
            machine.transition(CaptureEvent.RECORDING_STARTED)
        }
    }

    @Test
    fun `任一进行中阶段中断后都不可恢复`() {
        CapturePhase.entries
            .filter { it !in setOf(CapturePhase.COMPLETED, CapturePhase.INTERRUPTED) }
            .forEach { target ->
                val machine = machineAt(target)
                assertEquals(CapturePhase.INTERRUPTED, machine.transition(CaptureEvent.INTERRUPT))
                assertFalse(machine.canResume())
                assertThrows(IllegalStateException::class.java) {
                    machine.transition(CaptureEvent.RECORDING_STARTED)
                }
            }
    }

    @Test
    fun `无耳机必须经过两层不同警告才能确认风险`() {
        assertEquals(HeadphonePrompt.READY, initialHeadphonePrompt(hasHeadphones = true))
        val first = initialHeadphonePrompt(hasHeadphones = false)
        assertEquals(HeadphonePrompt.FIRST_WARNING, first)
        val second = continueWithoutHeadphones(first)
        assertEquals(HeadphonePrompt.SECOND_WARNING, second)
        assertEquals(HeadphonePrompt.CONFIRMED_WITH_RISK, continueWithoutHeadphones(second))
    }

    @Test
    fun `后台上传达到上限后暴露人工重试而不是继续隐藏重试`() {
        assertEquals(
            UploadFailureDecision.RETRY_AUTOMATICALLY,
            uploadFailureDecision(runAttemptCount = 4, maxRetries = 5),
        )
        assertEquals(
            UploadFailureDecision.EXPOSE_MANUAL_RETRY,
            uploadFailureDecision(runAttemptCount = 5, maxRetries = 5),
        )
        assertTrue(shouldReplaceUploadWork(manualRetry = true))
        assertFalse(shouldReplaceUploadWork(manualRetry = false))
    }

    private fun machineAt(target: CapturePhase): ThreePhaseStateMachine {
        val machine = ThreePhaseStateMachine()
        if (target == CapturePhase.IDLE) return machine
        machine.transition(CaptureEvent.RECORDING_STARTED)
        if (target == CapturePhase.PRE) return machine
        machine.transition(CaptureEvent.PRE_FINISHED)
        if (target == CapturePhase.SINGING) return machine
        machine.transition(CaptureEvent.ACCOMPANIMENT_FINISHED)
        return machine
    }
}
