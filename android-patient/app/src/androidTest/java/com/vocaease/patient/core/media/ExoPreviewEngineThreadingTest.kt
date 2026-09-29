package com.vocaease.patient.core.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExoPreviewEngineThreadingTest {
    @Test
    fun 主线程seek后的播放位置可由录制IO线程读取() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var engine: ExoPreviewEngine
        instrumentation.runOnMainSync {
            engine = ExoPreviewEngine(instrumentation.targetContext)
            engine.seekTo(1_234)
        }
        instrumentation.waitForIdleSync()

        val position = withContext(Dispatchers.IO) { engine.currentPositionMillis }

        assertEquals(1_234L, position)
        engine.releaseAndAwait()
    }
}
