package com.vocaease.patient.core.media

import com.vocaease.patient.feature.training.RecordingInterruption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingEnvironmentInterruptionCoordinatorTest {
    @Test
    fun `宿主停止与音频焦点丢失经同一协调器串行交付且关闭后不复活`() = runBlocking {
        val focus = FakeRecordingAudioFocus()
        val interruptions = mutableListOf<RecordingInterruption>()
        val coordinator = RecordingEnvironmentInterruptionCoordinator(
            scope = this,
            dispatcher = Dispatchers.Unconfined,
            audioFocus = focus,
            interrupt = { interruptions += it },
        )

        coordinator.startAndAwaitReady()
        coordinator.onHostStopped()
        focus.lose()
        assertEquals(
            listOf(RecordingInterruption.CAMERA, RecordingInterruption.AUDIO),
            interruptions,
        )

        coordinator.close()
        focus.lose()
        coordinator.onHostStopped()
        assertEquals(2, interruptions.size)
        assertEquals(1, focus.closeCount)
    }

    @Test
    fun `同步音频焦点拒绝必须在录制启动ready gate前完成裁决`() = runBlocking {
        val order = mutableListOf<String>()
        val focus = FakeRecordingAudioFocus(rejectSynchronously = true)
        val coordinator = RecordingEnvironmentInterruptionCoordinator(
            scope = this,
            dispatcher = Dispatchers.Default,
            audioFocus = focus,
            interrupt = { order += "interrupt:$it" },
        )

        coordinator.onHostStopped()
        coordinator.startAndAwaitReady()
        order += "view-model-start"

        assertEquals(
            listOf(
                "interrupt:${RecordingInterruption.CAMERA}",
                "interrupt:${RecordingInterruption.AUDIO}",
                "view-model-start",
            ),
            order,
        )
        coordinator.close()
    }
}

private class FakeRecordingAudioFocus(
    private val rejectSynchronously: Boolean = false,
) : RecordingAudioFocus {
    private var listener: () -> Unit = {}
    var closeCount = 0
    override fun start(onLost: () -> Unit) {
        listener = onLost
        if (rejectSynchronously) onLost()
    }
    override fun close() { closeCount += 1; listener = {} }
    fun lose() = listener()
}
