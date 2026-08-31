package com.vocaease.patient.core.media

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.feature.history.PrivateVideoEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class Media3PrivateVideoEngineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun 后台调用也在线性化主线程执行并可等待释放() = runBlocking {
        val engine: PrivateVideoEngine = Media3PrivateVideoEngine.create(context)

        engine.load("asset-1#private-1-0", "https://127.0.0.1/private.mp4", 1_234L)
        assertEquals(1_234L, engine.currentPositionMillis())
        engine.stopAndClear()
        assertEquals(0L, engine.currentPositionMillis())
        engine.releaseAndAwait()
    }

    @Test
    fun 释放幂等且释放后拒绝新地址() = runBlocking {
        val engine = Media3PrivateVideoEngine.create(context)
        engine.releaseAndAwait()
        engine.releaseAndAwait()

        val failure = runCatching {
            engine.load("asset-1#private-1-0", "https://127.0.0.1/private.mp4", 0L)
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }
}
