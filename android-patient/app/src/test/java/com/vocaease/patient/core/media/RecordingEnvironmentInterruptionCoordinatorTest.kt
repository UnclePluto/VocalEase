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

        coordinator.start()
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
}

private class FakeRecordingAudioFocus : RecordingAudioFocus {
    private var listener: () -> Unit = {}
    var closeCount = 0
    override fun start(onLost: () -> Unit) { listener = onLost }
    override fun close() { closeCount += 1; listener = {} }
    fun lose() = listener()
}
