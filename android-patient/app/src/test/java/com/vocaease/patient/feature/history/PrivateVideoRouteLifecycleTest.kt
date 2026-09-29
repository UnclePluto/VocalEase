package com.vocaease.patient.feature.history

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateVideoRouteLifecycleTest {
    @Test
    fun `路由退出非阻塞启动但等待真实播放器释放`() = runBlocking {
        val lifecycle = PrivateVideoRouteLifecycle(Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        val release = lifecycle.release {
            entered.complete(Unit)
            gate.await()
        }
        entered.await()
        assertFalse(release.isCompleted)
        gate.complete(Unit)
        release.join()

        assertTrue(release.isCompleted)
        assertTrue(lifecycle.isClosed)
    }

    @Test
    fun `关闭后播放器迟到回调不能复活`() = runBlocking {
        val lifecycle = PrivateVideoRouteLifecycle(Dispatchers.Unconfined)
        var callbacks = 0
        lifecycle.launch { callbacks += 1 }
        lifecycle.release {}.join()

        lifecycle.launch { callbacks += 10 }
        yield()

        assertEquals(1, callbacks)
    }

    @Test
    fun `播放器未就绪的重组不得提前关闭真实release作用域`() = runBlocking {
        val lifecycle = PrivateVideoRouteLifecycle(Dispatchers.Unconfined)
        assertNull(lifecycle.releaseWhenReady(null))
        assertFalse(lifecycle.isClosed)

        var releases = 0
        lifecycle.releaseWhenReady { releases += 1 }!!.join()

        assertEquals(1, releases)
        assertTrue(lifecycle.isClosed)
    }
}
