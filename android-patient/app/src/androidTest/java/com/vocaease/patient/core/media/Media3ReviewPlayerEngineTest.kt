package com.vocaease.patient.core.media

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocaease.patient.core.database.AccountLeaseListenerRegistration
import com.vocaease.patient.core.database.AccountScopedDraftStorageProvider
import com.vocaease.patient.core.database.AuthenticatedAccountLease
import com.vocaease.patient.core.database.AuthenticatedAccountSession
import com.vocaease.patient.core.database.StaleAccountScopeException
import com.vocaease.patient.core.database.VocaEaseDatabase
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.feature.training.ReviewMediaSource
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class Media3ReviewPlayerEngineTest {
    private lateinit var context: Context
    private lateinit var database: VocaEaseDatabase
    private lateinit var root: File
    private lateinit var session: CountingAccountSession

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = VocaEaseDatabase.inMemory(context, allowMainThreadQueries = true)
        root = File(context.filesDir, "review-engine-${System.nanoTime()}")
        session = CountingAccountSession()
        ChunkedAesGcmFileStore(context, root).destroyAccountEncryption("patient-listener")
    }

    @After
    fun tearDown() {
        ChunkedAesGcmFileStore(context, root).destroyAccountEncryption("patient-listener")
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun 真实加密视频播放结束后可以再次从头播放() = runBlocking {
        val storage = AccountScopedDraftStorageProvider(
            database, ChunkedAesGcmFileStore(context, root), session,
        ).current()
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("sample_avc_aac.mp4").use { it.readBytes() }
        val encrypted = storage.encryptMedia(bytes.inputStream(), bytes.size.toLong())
        val ended = CountDownLatch(1)
        val replayStarted = CountDownLatch(1)
        val replayRequested = AtomicBoolean(false)
        lateinit var engine: Media3ReviewPlayerEngine
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            engine = Media3ReviewPlayerEngine(context, storage)
            engine.setListener { event ->
                if (event is ReviewEngineEvent.Ended) ended.countDown()
                if (event is ReviewEngineEvent.Playing && event.isPlaying && replayRequested.get()) {
                    replayStarted.countDown()
                }
            }
            engine.load(
                ReviewMediaSource("replay-test", "video/mp4", bytes.size.toLong(), encrypted.relativePath),
                0, true,
            )
        }
        try {
            assertTrue("首次播放应正常结束", ended.await(10, TimeUnit.SECONDS))
            replayRequested.set(true)
            instrumentation.runOnMainSync { engine.play() }
            assertTrue("结束后再次播放应重新启动", replayStarted.await(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { engine.release() }
        }
    }

    @Test
    fun 循环进入离开回看页会注销lease监听且release后回调不能复活() {
        val storage = AccountScopedDraftStorageProvider(
            database,
            ChunkedAesGcmFileStore(context, root),
            session,
        ).current()
        var lateEvents = 0

        repeat(20) {
            lateinit var engine: Media3ReviewPlayerEngine
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                engine = Media3ReviewPlayerEngine(context, storage)
                engine.setListener { lateEvents += 1 }
            }
            assertEquals(1, session.listenerCount)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.release() }
            assertEquals(0, session.listenerCount)
        }

        session.clear()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(0, lateEvents)
    }
}

private class CountingAccountSession : AuthenticatedAccountSession {
    private val listeners = CopyOnWriteArrayList<(AuthenticatedAccountLease?) -> Unit>()
    private var lease: AuthenticatedAccountLease? = AuthenticatedAccountLease("patient-listener")
    val listenerCount: Int get() = listeners.size

    override fun current(): AuthenticatedAccountLease? = lease

    override fun addLeaseChangedListener(
        listener: (AuthenticatedAccountLease?) -> Unit,
    ): AccountLeaseListenerRegistration {
        listeners += listener
        listener(lease)
        return AccountLeaseListenerRegistration { listeners.remove(listener) }
    }

    fun clear() {
        lease = null
        listeners.forEach { it(null) }
    }

    override suspend fun <T> withCurrentLease(
        expected: AuthenticatedAccountLease,
        operation: suspend () -> T,
    ): T {
        if (lease !== expected) throw StaleAccountScopeException()
        return operation()
    }
}
