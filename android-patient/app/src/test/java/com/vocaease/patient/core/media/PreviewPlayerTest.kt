package com.vocaease.patient.core.media

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPlayerTest {
    @Test fun `缓冲中的返回确认仍可暂停取消后在缓冲结束继续播放`()=runBlocking {
        val engine=FakePreviewEngine()
        val player=PreviewPlayer(engine,PreviewGrantSource { PreviewGrant("https://private.invalid/source",Instant.MAX) })
        try {
            player.prepare(SONG_ID);engine.emit(PreviewEngineEvent.Ready)
            withTimeout(2000){while(player.state.value !is PreviewState.Buffered) yield()}
            player.play();engine.position=1000;engine.emit(PreviewEngineEvent.Buffering)
            withTimeout(2000){while(player.state.value !is PreviewState.Buffering) yield()}
            assertTrue("缓冲中必须能暂停",player.pause())
            engine.emit(PreviewEngineEvent.Ready)
            withTimeout(2000){while(player.state.value !is PreviewState.Buffered) yield()}
            assertEquals(1000L,player.currentPositionMillis)
            player.play();engine.emit(PreviewEngineEvent.Buffering)
            withTimeout(2000){while(player.state.value !is PreviewState.Buffering) yield()}
            assertTrue(player.pause())
            assertTrue("取消退出应允许缓冲完成后恢复播放",player.play())
            engine.emit(PreviewEngineEvent.Ready)
            withTimeout(2000){while(player.state.value !is PreviewState.Playing) yield()}
            assertEquals(1000L,player.currentPositionMillis)
        } finally {player.release();player.awaitReleased()}
    }
    @Test fun verifiedOffsetKeepsCanonicalSongPositionAcrossSwitchAndRewind()=runBlocking {
        val engine=FakePreviewEngine()
        val source=object:PreviewGrantSource {
            override suspend fun fetch(songId:String)=PreviewGrant("https://private.invalid/source",Instant.MAX)
            override suspend fun fetch(songId:String,mode:SongPlaybackMode,sessionId:String?)=PreviewGrant("https://private.invalid/${mode.wire}",Instant.MAX,timelineOffsetMillis=if(mode==SongPlaybackMode.ACCOMPANIMENT) 2000 else 0)
        }
        val player=PreviewPlayer(engine,source)
        try {
            player.prepare(SONG_ID);engine.emit(PreviewEngineEvent.Ready)
            withTimeout(2000){while(player.state.value !is PreviewState.Buffered) yield()}
            engine.position=1000
            val switched=async { player.switchMode(SongPlaybackMode.ACCOMPANIMENT) }
            withTimeout(2000){while(engine.loadedUrls.size<2) yield()}
            engine.emit(PreviewEngineEvent.Ready);assertTrue(switched.await())
            assertEquals(3000L,engine.position);assertEquals(1000L,player.currentPositionMillis)
            player.rewindToStart();assertEquals(2000L,engine.position);assertEquals(0L,player.currentPositionMillis)
        } finally { player.release();player.awaitReleased() }
    }

    @Test
    fun lastSwitchWinsAndFailureRestoresOldTrack() = runBlocking {
        val engine = FakePreviewEngine()
        val first = CompletableDeferred<PreviewGrant>()
        val second = CompletableDeferred<PreviewGrant>()
        val count = AtomicInteger()
        val source = object : PreviewGrantSource {
            override suspend fun fetch(songId: String) = PreviewGrant("https://private.invalid/original", Instant.MAX)
            override suspend fun fetch(songId: String, mode: SongPlaybackMode, sessionId: String?): PreviewGrant =
                when (count.incrementAndGet()) {
                    1 -> fetch(songId)
                    2 -> withContext(NonCancellable) { first.await() }
                    3 -> second.await()
                    else -> throw java.io.IOException("授权失败")
                }
        }
        val player=PreviewPlayer(engine, source)
        player.prepare(SONG_ID);engine.emit(PreviewEngineEvent.Ready)
        withTimeout(2000) { while (player.state.value !is PreviewState.Buffered) yield() }
        engine.position=56_000
        val stale=async { player.switchMode(SongPlaybackMode.ACCOMPANIMENT) }
        withTimeout(2000) { while (count.get()<2) yield() }
        val latest=async { player.switchMode(SongPlaybackMode.ORIGINAL) }
        withTimeout(2000) { while (count.get()<3) yield() }
        second.complete(PreviewGrant("https://private.invalid/latest",Instant.MAX))
        withTimeout(2000) { while (engine.loadedUrls.size<2) yield() }
        engine.emit(PreviewEngineEvent.Ready)
        assertTrue(latest.await());assertFalse(stale.await())
        first.complete(PreviewGrant("https://private.invalid/stale",Instant.MAX))
        assertFalse(withTimeout(2000) { player.switchMode(SongPlaybackMode.ACCOMPANIMENT) })
        assertEquals(SongPlaybackMode.ORIGINAL,player.activeMode.value)
        assertEquals(56_000L,player.currentPositionMillis)
        assertFalse(engine.loadedUrls.contains("https://private.invalid/stale"))
        player.release();player.awaitReleased()
    }

    @Test
    fun handoffAlwaysStartsAccompanimentAtZero() = runBlocking {
        val engine=FakePreviewEngine()
        val sessions=CopyOnWriteArrayList<String?>()
        val source=object : PreviewGrantSource {
            override suspend fun fetch(songId: String)=PreviewGrant("https://private.invalid/preview",Instant.MAX)
            override suspend fun fetch(songId: String,mode:SongPlaybackMode,sessionId:String?):PreviewGrant {
                sessions+=sessionId
                return PreviewGrant("https://private.invalid/${mode.wire}",Instant.MAX,"bound-asset")
            }
        }
        val player=PreviewPlayer(engine,source)
        player.prepare(SONG_ID);engine.emit(PreviewEngineEvent.Ready)
        withTimeout(2000) { while (player.state.value !is PreviewState.Buffered) yield() }
        engine.position=56_000
        val bind=async { player.bindSession("session-bound") }
        withTimeout(2000) { while (engine.loadedUrls.size<2) yield() }
        engine.emit(PreviewEngineEvent.Ready)
        assertTrue(bind.await());assertEquals(0L,player.currentPositionMillis)
        assertEquals(SongPlaybackMode.ACCOMPANIMENT,player.activeMode.value)
        assertEquals("session-bound",sessions.last())
        player.release();player.awaitReleased()
    }

    @Test
    fun previewDefaultsToOriginalAndSwitchKeepsPosition() = runBlocking {
        val engine = FakePreviewEngine()
        val requested = CopyOnWriteArrayList<SongPlaybackMode>()
        val source = object : PreviewGrantSource {
            override suspend fun fetch(songId: String) = PreviewGrant("https://private.invalid/original", Instant.MAX)
            override suspend fun fetch(songId: String, mode: SongPlaybackMode, sessionId: String?): PreviewGrant {
                requested += mode
                return PreviewGrant("https://private.invalid/${mode.wire}", Instant.MAX, "asset")
            }
        }
        val player = PreviewPlayer(engine, source)
        player.prepare(SONG_ID); engine.emit(PreviewEngineEvent.Ready)
        while (player.state.value !is PreviewState.Buffered) yield()
        assertEquals(SongPlaybackMode.ORIGINAL, player.activeMode.value)
        player.play(); engine.position = 56_000
        val switched = async { player.switchMode(SongPlaybackMode.ACCOMPANIMENT) }
        withTimeout(2000) { while (engine.loadedUrls.size < 2) yield() }
        engine.emit(PreviewEngineEvent.Ready)
        assertTrue(withTimeout(2000) { switched.await() })
        assertEquals(56_000L, player.currentPositionMillis)
        assertEquals(SongPlaybackMode.ACCOMPANIMENT, player.activeMode.value)
        assertTrue(player.state.value is PreviewState.Playing)
        player.release(); player.awaitReleased()
    }

    @Test
    fun `release立即返回且底层资源仍按actor顺序最终释放`() = runBlocking {
        val engine = FakePreviewEngine().apply { blockRelease = true }
        val player = PreviewPlayer(
            engine,
            QueuePreviewSource(PreviewGrant("https://private.invalid/audio", Instant.MAX)),
        )
        player.prepare(SONG_ID)

        val releaseCall = async(Dispatchers.Default) { player.release() }
        engine.releaseEntered.await()
        val returnedBeforeEngineFinished = withTimeoutOrNull(1_000) {
            releaseCall.await()
            true
        } ?: false
        engine.continueRelease.complete(Unit)
        releaseCall.await()

        assertTrue(returnedBeforeEngineFinished)
        withTimeout(1_000) { player.awaitReleased() }
        assertTrue(player.state.value is PreviewState.Released)
        assertEquals(1, engine.releaseCount)
    }

    @Test
    fun `awaitReleased必须等待异步投递的底层release真正返回`() = runBlocking {
        val engine = FakePreviewEngine().apply { dispatchReleaseAsynchronously = true }
        val player = PreviewPlayer(
            engine,
            QueuePreviewSource(PreviewGrant("https://private.invalid/audio", Instant.MAX)),
        )
        player.prepare(SONG_ID)

        player.release()
        engine.releaseEntered.await()
        val completedBeforeEngineReturned = withTimeoutOrNull(250) {
            player.awaitReleased()
            true
        } ?: false

        assertFalse(completedBeforeEngineReturned)
        engine.continueRelease.complete(Unit)
        withTimeout(1_000) { player.awaitReleased() }
        assertTrue(engine.released)
        assertEquals(1, engine.releaseCount)
    }

    @Test
    fun `awaitReleased必须等待NonCancellable授权子任务终结`() = runBlocking {
        val grantEntered = CompletableDeferred<Unit>()
        val continueGrant = CompletableDeferred<Unit>()
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(engine, grantSource = PreviewGrantSource {
            withContext(NonCancellable) {
                grantEntered.complete(Unit)
                continueGrant.await()
            }
            PreviewGrant("https://private.invalid/late", Instant.MAX)
        })
        val preparation = async { player.prepare(SONG_ID) }
        grantEntered.await()

        player.release()
        val completedBeforeGrantFinished = withTimeoutOrNull(250) {
            player.awaitReleased()
            true
        } ?: false

        assertFalse(completedBeforeGrantFinished)
        continueGrant.complete(Unit)
        withTimeout(1_000) { player.awaitReleased() }
        preparation.await()
        assertTrue(engine.loadedUrls.isEmpty())
        assertTrue(player.state.value is PreviewState.Released)
    }

    @Test
    fun `awaitReleased必须等待actor真正退出`() = runBlocking {
        val actorCompletionEntered = CompletableDeferred<Unit>()
        val continueActorCompletion = CompletableDeferred<Unit>()
        val player = PreviewPlayer(
            engine = FakePreviewEngine(),
            grantSource = QueuePreviewSource(
                PreviewGrant("https://private.invalid/audio", Instant.MAX),
            ),
            admissionProbe = PreviewAdmissionProbe { point ->
                if (point == PreviewAdmissionPoint.ACTOR_COMPLETION) {
                    actorCompletionEntered.complete(Unit)
                    continueActorCompletion.await()
                }
            },
        )
        player.prepare(SONG_ID)

        player.release()
        actorCompletionEntered.await()
        val completedBeforeActorExited = withTimeoutOrNull(250) {
            player.awaitReleased()
            true
        } ?: false

        assertFalse(completedBeforeActorExited)
        continueActorCompletion.complete(Unit)
        withTimeout(1_000) { player.awaitReleased() }
    }

    @Test
    fun `多次release和关闭后调用保持幂等且可等待最终释放`() = runBlocking {
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(
            engine,
            QueuePreviewSource(PreviewGrant("https://private.invalid/audio", Instant.MAX)),
        )
        player.prepare(SONG_ID)

        player.release()
        player.release()
        withTimeout(1_000) { player.awaitReleased() }
        player.release()

        assertEquals(1, engine.releaseCount)
        assertFalse(player.play())
        assertFalse(player.pause())
        player.prepare(SONG_ID)
        assertEquals(1, engine.loadedUrls.size)
        assertTrue(player.state.value is PreviewState.Released)
    }

    @Test
    fun `caller取消prepare返回后非取消grant迟到也不能加载或污染状态`() = runBlocking {
        val grantEntered = CompletableDeferred<Unit>()
        val continueGrant = CompletableDeferred<Unit>()
        val grantReturned = CompletableDeferred<Unit>()
        val lateGrantDequeued = CompletableDeferred<Unit>()
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(
            engine = engine,
            grantSource = PreviewGrantSource {
                grantEntered.complete(Unit)
                try {
                    continueGrant.await()
                } catch (_: CancellationException) {
                    withContext(NonCancellable) { continueGrant.await() }
                }
                grantReturned.complete(Unit)
                PreviewGrant("https://private.invalid/late", Instant.MAX)
            },
            admissionProbe = PreviewAdmissionProbe { point ->
                if (point == PreviewAdmissionPoint.GRANT_APPLICATION) {
                    lateGrantDequeued.complete(Unit)
                }
            },
        )
        val cancellation = CancellationException("调用方主动取消")
        val preparation = async { player.prepare(SONG_ID) }
        grantEntered.await()

        preparation.cancel(cancellation)
        withTimeout(1_000) { preparation.join() }
        val propagated = runCatching { preparation.await() }.exceptionOrNull()
        assertTrue(propagated is CancellationException)
        assertEquals(cancellation.message, propagated?.message)

        continueGrant.complete(Unit)
        grantReturned.await()
        lateGrantDequeued.await()

        assertTrue(engine.loadedUrls.isEmpty())
        assertTrue(player.state.value is PreviewState.Idle)
    }

    @Test
    fun `caller取消prepare会停止普通grant任务并原样传播取消异常`() = runBlocking {
        val grantEntered = CompletableDeferred<Unit>()
        val grantStopped = CompletableDeferred<Unit>()
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(engine, grantSource = PreviewGrantSource {
            grantEntered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                grantStopped.complete(Unit)
            }
        })
        val cancellation = CancellationException("保留此取消原因")
        val preparation = async { player.prepare(SONG_ID) }
        grantEntered.await()

        preparation.cancel(cancellation)
        withTimeout(1_000) { preparation.join() }

        withTimeout(1_000) { grantStopped.await() }
        val propagated = runCatching { preparation.await() }.exceptionOrNull()
        assertTrue(propagated is CancellationException)
        assertEquals(cancellation.message, propagated?.message)
        assertTrue(engine.loadedUrls.isEmpty())
        assertTrue(player.state.value is PreviewState.Idle)
    }

    @Test
    fun `B在actor处理Prepare前取消不得替换A且返回必须等待Cancel回执`() = runBlocking {
        val prepareBEntered = CompletableDeferred<Unit>()
        val continuePrepareB = CompletableDeferred<Unit>()
        val cancelBEntered = CompletableDeferred<Unit>()
        val continueCancelB = CompletableDeferred<Unit>()
        var gatePrepareB = false
        var gateCancelB = false
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(
            engine = engine,
            grantSource = QueuePreviewSource(
                PreviewGrant("https://private.invalid/a", Instant.MAX),
                PreviewGrant("https://private.invalid/b", Instant.MAX),
            ),
            admissionProbe = PreviewAdmissionProbe { point ->
                when {
                    gatePrepareB && point == PreviewAdmissionPoint.PREPARE_APPLICATION -> {
                        prepareBEntered.complete(Unit)
                        continuePrepareB.await()
                    }
                    gateCancelB && point == PreviewAdmissionPoint.CANCEL_APPLICATION -> {
                        cancelBEntered.complete(Unit)
                        continueCancelB.await()
                    }
                }
            },
        )
        player.prepare(SONG_ID)
        engine.emit(PreviewEngineEvent.Ready)
        awaitCondition { player.state.value is PreviewState.Buffered }
        gatePrepareB = true
        gateCancelB = true

        val preparationB = async { player.prepare(SONG_B_ID) }
        prepareBEntered.await()
        preparationB.cancel(CancellationException("B预取消"))
        yield()
        assertFalse(preparationB.isCompleted)

        continuePrepareB.complete(Unit)
        cancelBEntered.await()
        assertFalse(preparationB.isCompleted)
        continueCancelB.complete(Unit)
        withTimeout(1_000) { preparationB.join() }

        assertEquals(listOf("https://private.invalid/a"), engine.loadedUrls)
        assertTrue(player.state.value is PreviewState.Buffered)
        assertTrue(player.play())
        engine.emitFromListener(0, PreviewEngineEvent.PlayingChanged(false))
        awaitCondition { player.state.value is PreviewState.Buffered }
    }

    @Test
    fun `B成功线性化接管后再取消必须清理B且A不复活`() = runBlocking {
        val grantBEntered = CompletableDeferred<Unit>()
        val continueGrantB = CompletableDeferred<Unit>()
        val lateGrantBDequeued = CompletableDeferred<Unit>()
        var grantCall = 0
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(
            engine = engine,
            grantSource = PreviewGrantSource {
                grantCall += 1
                if (grantCall == 1) {
                    PreviewGrant("https://private.invalid/a", Instant.MAX)
                } else {
                    withContext(NonCancellable) {
                        grantBEntered.complete(Unit)
                        continueGrantB.await()
                    }
                    PreviewGrant("https://private.invalid/b", Instant.MAX)
                }
            },
            admissionProbe = PreviewAdmissionProbe { point ->
                if (grantCall == 2 && point == PreviewAdmissionPoint.GRANT_APPLICATION) {
                    lateGrantBDequeued.complete(Unit)
                }
            },
        )
        player.prepare(SONG_ID)
        engine.emit(PreviewEngineEvent.Ready)
        awaitCondition { player.state.value is PreviewState.Buffered }

        val preparationB = async { player.prepare(SONG_B_ID) }
        grantBEntered.await()
        preparationB.cancel(CancellationException("B接管后取消"))
        withTimeout(1_000) { preparationB.join() }

        assertTrue(player.state.value is PreviewState.Idle)
        assertFalse(player.play())
        engine.emitFromListener(0, PreviewEngineEvent.Ready)
        assertTrue(player.state.value is PreviewState.Idle)

        continueGrantB.complete(Unit)
        lateGrantBDequeued.await()
        assertEquals(listOf("https://private.invalid/a"), engine.loadedUrls)
        assertTrue(player.state.value is PreviewState.Idle)
    }

    @Test
    fun `GrantResolved已入队但actor应用前caller取消仍不得load`() = runBlocking {
        val grantApplicationEntered = CompletableDeferred<Unit>()
        val continueGrantApplication = CompletableDeferred<Unit>()
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(
            engine = engine,
            grantSource = QueuePreviewSource(
                PreviewGrant("https://private.invalid/queued", Instant.MAX),
            ),
            admissionProbe = PreviewAdmissionProbe { point ->
                if (point == PreviewAdmissionPoint.GRANT_APPLICATION) {
                    grantApplicationEntered.complete(Unit)
                    continueGrantApplication.await()
                }
            },
        )
        val preparation = async { player.prepare(SONG_ID) }
        grantApplicationEntered.await()

        preparation.cancel(CancellationException("结果已排队后取消"))
        yield()
        continueGrantApplication.complete(Unit)
        withTimeout(1_000) { preparation.join() }

        assertTrue(engine.loadedUrls.isEmpty())
        assertTrue(player.state.value is PreviewState.Idle)
    }

    @Test
    fun `release完成后已进入的旧播放器事件不能覆盖Released`() = runBlocking {
        val eventAdmitted = CompletableDeferred<Unit>()
        val continueEvent = CompletableDeferred<Unit>()
        var gateEvent = false
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(
            engine = engine,
            grantSource = QueuePreviewSource(PreviewGrant("https://private.invalid/audio", Instant.MAX)),
            admissionProbe = PreviewAdmissionProbe { point ->
                if (gateEvent && point == PreviewAdmissionPoint.ENGINE_EVENT) {
                    eventAdmitted.complete(Unit)
                    continueEvent.await()
                }
            },
        )
        player.prepare(SONG_ID)
        engine.emit(PreviewEngineEvent.Ready)
        awaitCondition { player.state.value is PreviewState.Buffered }
        gateEvent = true

        val lateEvent = engine.emitAsync(this, PreviewEngineEvent.Buffering)
        eventAdmitted.await()
        player.release()
        player.awaitReleased()
        assertTrue(player.state.value is PreviewState.Released)

        continueEvent.complete(Unit)
        lateEvent.await()

        assertTrue(player.state.value is PreviewState.Released)
        assertEquals(1, engine.releaseCount)
        assertEquals(listOf("https://private.invalid/audio"), engine.loadedUrls)
        assertEquals(0, engine.playCount)
        assertEquals(0, engine.pauseCount)
        assertTrue(engine.seeks.isEmpty())
    }

    @Test
    fun `release完成后已进入的授权错误不能发布Error`() = runBlocking {
        val errorAdmitted = CompletableDeferred<Unit>()
        val continueError = CompletableDeferred<Unit>()
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(
            engine = engine,
            grantSource = PreviewGrantSource { throw java.io.IOException("offline") },
            admissionProbe = PreviewAdmissionProbe { point ->
                if (point == PreviewAdmissionPoint.PUBLISH_ERROR) {
                    errorAdmitted.complete(Unit)
                    continueError.await()
                }
            },
        )

        val preparation = async { player.prepare(SONG_ID) }
        withTimeout(500) { errorAdmitted.await() }
        player.release()
        player.awaitReleased()
        assertTrue(player.state.value is PreviewState.Released)

        continueError.complete(Unit)
        preparation.await()

        assertTrue(player.state.value is PreviewState.Released)
        assertEquals(1, engine.releaseCount)
    }

    @Test
    fun `手动重试后的旧generation事件被丢弃而新generation仍可播放`() = runBlocking {
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(
            engine,
            QueuePreviewSource(
                PreviewGrant("https://private.invalid/first", Instant.MAX),
                PreviewGrant("https://private.invalid/retry", Instant.MAX),
            ),
        )
        player.prepare(SONG_ID)
        player.prepare(SONG_ID)
        engine.emit(PreviewEngineEvent.Ready)
        awaitCondition { player.state.value is PreviewState.Buffered }

        engine.emitFromListener(0, PreviewEngineEvent.PlayingChanged(true))

        assertTrue(player.state.value is PreviewState.Buffered)
        assertTrue(player.play())
    }

    @Test
    fun `401刷新异步结果在release后不能加载或seek`() = runBlocking {
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val refreshReturned = CompletableDeferred<Unit>()
        var call = 0
        val engine = FakePreviewEngine().apply { position = 9_876 }
        val player = PreviewPlayer(
            engine = engine,
            grantSource = PreviewGrantSource {
                call += 1
                if (call == 1) {
                    PreviewGrant("https://private.invalid/first", Instant.MAX)
                } else {
                    withContext(NonCancellable) {
                        refreshEntered.complete(Unit)
                        releaseRefresh.await()
                        refreshReturned.complete(Unit)
                    }
                    PreviewGrant("https://private.invalid/late-refresh", Instant.MAX)
                }
            },
        )
        player.prepare(SONG_ID)

        engine.emit(PreviewEngineEvent.HttpError(401))
        refreshEntered.await()
        player.release()
        val completedBeforeRefreshFinished = withTimeoutOrNull(250) {
            player.awaitReleased()
            true
        } ?: false
        assertFalse(completedBeforeRefreshFinished)
        releaseRefresh.complete(Unit)
        refreshReturned.await()
        player.awaitReleased()

        assertTrue(player.state.value is PreviewState.Released)
        assertEquals(listOf("https://private.invalid/first"), engine.loadedUrls)
        assertTrue(engine.seeks.isEmpty())
        assertEquals(1, engine.releaseCount)
    }

    @Test
    fun `play与pause被接受后release按同一队列等待且最终保持Released`() = runBlocking {
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(
            engine,
            QueuePreviewSource(PreviewGrant("https://private.invalid/audio", Instant.MAX)),
        )
        player.prepare(SONG_ID)
        engine.emit(PreviewEngineEvent.Ready)
        awaitCondition { player.state.value is PreviewState.Buffered }

        engine.blockPlay = true
        val play = async(Dispatchers.Default) { player.play() }
        engine.playEntered.await()
        player.release()
        val releaseAfterPlay = async(Dispatchers.Default) { player.awaitReleased() }
        yield()
        assertFalse(releaseAfterPlay.isCompleted)
        engine.continuePlay.complete(Unit)
        assertTrue(play.await())
        releaseAfterPlay.await()

        assertTrue(player.state.value is PreviewState.Released)
        assertEquals(1, engine.playCount)
        assertEquals(1, engine.releaseCount)

        val pauseEngine = FakePreviewEngine()
        val pausePlayer = PreviewPlayer(
            pauseEngine,
            QueuePreviewSource(PreviewGrant("https://private.invalid/audio-2", Instant.MAX)),
        )
        pausePlayer.prepare(SONG_ID)
        pauseEngine.emit(PreviewEngineEvent.Ready)
        awaitCondition { pausePlayer.state.value is PreviewState.Buffered }
        assertTrue(pausePlayer.play())
        pauseEngine.blockPause = true
        val pause = async(Dispatchers.Default) { pausePlayer.pause() }
        pauseEngine.pauseEntered.await()
        pausePlayer.release()
        val releaseAfterPause = async(Dispatchers.Default) { pausePlayer.awaitReleased() }
        yield()
        assertFalse(releaseAfterPause.isCompleted)
        pauseEngine.continuePause.complete(Unit)
        assertTrue(pause.await())
        releaseAfterPause.await()

        assertTrue(pausePlayer.state.value is PreviewState.Released)
        assertEquals(1, pauseEngine.pauseCount)
        assertEquals(1, pauseEngine.releaseCount)
    }

    @Test
    fun `engine操作同步重入播放器回调不会死锁`() = runBlocking {
        val engine = FakePreviewEngine().apply {
            reentrantPlayEvent = PreviewEngineEvent.PlayingChanged(true)
        }
        val player = PreviewPlayer(
            engine,
            QueuePreviewSource(PreviewGrant("https://private.invalid/audio", Instant.MAX)),
        )
        player.prepare(SONG_ID)
        engine.emit(PreviewEngineEvent.Ready)
        awaitCondition { player.state.value is PreviewState.Buffered }

        assertTrue(withTimeout(1_000) { player.play() })
        assertTrue(player.state.value is PreviewState.Playing)
    }

    @Test
    fun `只有播放器STATE_READY才发布Buffered`() = runBlocking {
        val engine = FakePreviewEngine()
        val source = QueuePreviewSource(
            PreviewGrant("https://private.invalid/first?token=secret", Instant.parse("2026-08-27T10:00:00Z")),
        )
        val player = PreviewPlayer(engine, source)

        player.prepare("10000000-0000-4000-8000-000000000001")
        assertTrue(player.state.value is PreviewState.Buffering)
        engine.emit(PreviewEngineEvent.Buffering)
        assertFalse(player.state.value is PreviewState.Buffered)
        engine.emit(PreviewEngineEvent.PlayingChanged(false))
        assertFalse(player.state.value is PreviewState.Buffered)

        engine.emit(PreviewEngineEvent.Ready)
        awaitCondition { player.state.value is PreviewState.Buffered }
        assertFalse(player.state.value.toString().contains("token=secret"))
    }

    @Test
    fun `401或403只刷新一次并恢复播放位置后重新缓冲`() = runBlocking {
        listOf(401, 403).forEach { status ->
            val engine = FakePreviewEngine().apply { position = 37_500 }
            val source = QueuePreviewSource(
                PreviewGrant("https://private.invalid/old", Instant.parse("2026-08-27T09:00:00Z")),
                PreviewGrant("https://private.invalid/new", Instant.parse("2026-08-27T10:00:00Z")),
            )
            val player = PreviewPlayer(engine, source)
            player.prepare(SONG_ID)

            engine.emit(PreviewEngineEvent.HttpError(status))
            awaitCondition { source.callCount == 2 && engine.seeks.size == 1 }

            assertEquals(2, source.callCount)
            assertEquals(listOf(37_500L), engine.seeks)
            assertEquals("https://private.invalid/new", engine.loadedUrls.last())
            assertTrue(player.state.value is PreviewState.Buffering)

            engine.emit(PreviewEngineEvent.HttpError(status))
            awaitCondition { player.state.value is PreviewState.Error }
            assertEquals(2, source.callCount)
            assertEquals("试听加载失败，请重试", (player.state.value as PreviewState.Error).message)
        }
    }

    @Test
    fun `只有缓冲完成后可试听且暂停后可再次播放`() = runBlocking {
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(
            engine,
            QueuePreviewSource(PreviewGrant("https://private.invalid/audio", Instant.MAX)),
        )
        player.prepare(SONG_ID)

        assertFalse(player.play())
        engine.emit(PreviewEngineEvent.Ready)
        awaitCondition { player.state.value is PreviewState.Buffered }
        assertTrue(player.play())
        engine.emit(PreviewEngineEvent.PlayingChanged(true))
        awaitCondition { player.state.value is PreviewState.Playing }

        assertTrue(player.pause())
        engine.emit(PreviewEngineEvent.PlayingChanged(false))
        awaitCondition { player.state.value is PreviewState.Buffered }
        assertEquals(1, engine.playCount)
        assertEquals(1, engine.pauseCount)
    }

    @Test
    fun `播放中的401刷新后恢复位置并继续原试听意图`() = runBlocking {
        val engine = FakePreviewEngine().apply { position = 12_345 }
        val source = QueuePreviewSource(
            PreviewGrant("https://private.invalid/old", Instant.MAX),
            PreviewGrant("https://private.invalid/new", Instant.MAX),
        )
        val player = PreviewPlayer(engine, source)
        player.prepare(SONG_ID)
        engine.emit(PreviewEngineEvent.Ready)
        awaitCondition { player.state.value is PreviewState.Buffered }
        assertTrue(player.play())

        engine.emit(PreviewEngineEvent.HttpError(401))
        awaitCondition { source.callCount == 2 && engine.loadedUrls.size == 2 }
        engine.emit(PreviewEngineEvent.Ready)
        awaitCondition { engine.playCount == 2 }

        assertEquals(listOf(12_345L), engine.seeks)
        assertEquals(2, engine.playCount)
        assertTrue(player.state.value is PreviewState.Playing)
    }

    @Test
    fun `演唱交接前将播放中的试听暂停并归零`() = runBlocking {
        val engine = FakePreviewEngine().apply { position = 12_345 }
        val player = PreviewPlayer(
            engine,
            QueuePreviewSource(PreviewGrant("https://private.invalid/audio", Instant.MAX)),
        )
        player.prepare(SONG_ID)
        engine.emit(PreviewEngineEvent.Ready)
        awaitCondition { player.state.value is PreviewState.Buffered }
        assertTrue(player.play())

        assertTrue(player.rewindToStart())

        assertEquals(1, engine.pauseCount)
        assertEquals(listOf(0L), engine.seeks)
        assertEquals(0L, player.currentPositionMillis)
        assertTrue(player.state.value is PreviewState.Buffered)
    }

    @Test
    fun `授权失败或第二次鉴权失败后手动重试开启新的单次刷新预算`() = runBlocking {
        val engine = FakePreviewEngine()
        var attempt = 0
        val player = PreviewPlayer(engine, grantSource = PreviewGrantSource {
            attempt += 1
            if (attempt == 1) throw java.io.IOException("offline")
            PreviewGrant("https://private.invalid/$attempt", Instant.MAX)
        })

        player.prepare(SONG_ID)
        assertTrue(player.state.value is PreviewState.Error)

        player.prepare(SONG_ID)
        engine.emit(PreviewEngineEvent.Ready)
        awaitCondition { player.state.value is PreviewState.Buffered }
        assertTrue(player.play())
        assertEquals(2, attempt)
    }

    @Test
    fun `第二次401进入错误后手动重试可重新缓冲并播放`() = runBlocking {
        val engine = FakePreviewEngine()
        val source = QueuePreviewSource(
            PreviewGrant("https://private.invalid/first", Instant.MAX),
            PreviewGrant("https://private.invalid/refresh", Instant.MAX),
            PreviewGrant("https://private.invalid/manual-retry", Instant.MAX),
        )
        val player = PreviewPlayer(engine, source)
        player.prepare(SONG_ID)

        engine.emit(PreviewEngineEvent.HttpError(401))
        awaitCondition { source.callCount == 2 && engine.loadedUrls.size == 2 }
        engine.emit(PreviewEngineEvent.HttpError(401))
        awaitCondition { player.state.value is PreviewState.Error }
        assertEquals("试听加载失败，请重试", (player.state.value as PreviewState.Error).message)

        player.prepare(SONG_ID)
        engine.emit(PreviewEngineEvent.Ready)
        awaitCondition { player.state.value is PreviewState.Buffered }

        assertEquals(3, source.callCount)
        assertEquals("https://private.invalid/manual-retry", engine.loadedUrls.last())
        assertTrue(player.state.value is PreviewState.Buffered)
        assertTrue(player.play())
    }

    @Test
    fun `生命周期释放底层播放器且不再处理迟到回调`() = runBlocking {
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(
            engine,
            QueuePreviewSource(PreviewGrant("https://private.invalid/audio", Instant.MAX)),
        )
        player.prepare(SONG_ID)

        player.release()
        player.awaitReleased()
        engine.emit(PreviewEngineEvent.Ready)

        assertTrue(engine.released)
        assertTrue(player.state.value is PreviewState.Released)
    }

    @Test
    fun `释放期间迟到的私有URL不得重新加载已释放播放器`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val releaseGrant = CompletableDeferred<Unit>()
        val engine = FakePreviewEngine()
        val player = PreviewPlayer(engine, grantSource = PreviewGrantSource {
            entered.complete(Unit)
            releaseGrant.await()
            PreviewGrant("https://private.invalid/late", Instant.MAX)
        })
        val preparation = async { player.prepare(SONG_ID) }
        entered.await()

        player.release()
        releaseGrant.complete(Unit)
        preparation.await()
        player.awaitReleased()

        assertTrue(engine.released)
        assertTrue(engine.loadedUrls.isEmpty())
        assertTrue(player.state.value is PreviewState.Released)
    }

    private companion object {
        const val SONG_ID = "10000000-0000-4000-8000-000000000001"
        const val SONG_B_ID = "20000000-0000-4000-8000-000000000002"
    }
}

private suspend fun awaitCondition(condition: () -> Boolean) {
    withTimeout(1_000) {
        while (!condition()) yield()
    }
}

private class QueuePreviewSource(vararg grants: PreviewGrant) : PreviewGrantSource {
    private val values = ArrayDeque(grants.toList())
    private val calls = AtomicInteger()
    val callCount: Int
        get() = calls.get()

    override suspend fun fetch(songId: String): PreviewGrant {
        calls.incrementAndGet()
        return synchronized(values) { values.removeFirst() }
    }
}

private class FakePreviewEngine : PreviewEngine {
    @Volatile
    private var listener: (suspend (PreviewEngineEvent) -> Unit)? = null
    private val listenerHistory = CopyOnWriteArrayList<suspend (PreviewEngineEvent) -> Unit>()
    val loadedUrls = CopyOnWriteArrayList<String>()
    val seeks = CopyOnWriteArrayList<Long>()
    var position = 0L
    var released = false
    var playCount = 0
    var pauseCount = 0
    var releaseCount = 0
    var blockPlay = false
    val playEntered = CompletableDeferred<Unit>()
    val continuePlay = CompletableDeferred<Unit>()
    var blockPause = false
    val pauseEntered = CompletableDeferred<Unit>()
    val continuePause = CompletableDeferred<Unit>()
    var reentrantPlayEvent: PreviewEngineEvent? = null
    var blockRelease = false
    var dispatchReleaseAsynchronously = false
    val releaseEntered = CompletableDeferred<Unit>()
    val continueRelease = CompletableDeferred<Unit>()

    override val currentPositionMillis: Long
        get() = position

    override fun setListener(listener: suspend (PreviewEngineEvent) -> Unit) {
        this.listener = listener
        listenerHistory += listener
    }

    override fun load(url: String) {
        loadedUrls += url
    }

    override fun seekTo(positionMillis: Long) {
        seeks += positionMillis
        position = positionMillis
    }

    override fun play() {
        playCount += 1
        if (blockPlay) runBlocking {
            playEntered.complete(Unit)
            continuePlay.await()
        }
        reentrantPlayEvent?.let { event ->
            listener?.let { callback -> runBlocking { callback(event) } }
        }
    }

    override fun pause() {
        pauseCount += 1
        if (blockPause) runBlocking {
            pauseEntered.complete(Unit)
            continuePause.await()
        }
    }

    override suspend fun releaseAndAwait() {
        releaseCount += 1
        if (dispatchReleaseAsynchronously) {
            releaseEntered.complete(Unit)
            continueRelease.await()
            released = true
            listener = null
            return
        }
        released = true
        if (blockRelease) {
            releaseEntered.complete(Unit)
            continueRelease.await()
        }
        listener = null
    }

    suspend fun emit(event: PreviewEngineEvent) {
        listener?.invoke(event)
    }

    fun emitAsync(scope: CoroutineScope, event: PreviewEngineEvent) = scope.async {
        listener?.invoke(event)
    }

    suspend fun emitFromListener(index: Int, event: PreviewEngineEvent) {
        listenerHistory[index](event)
    }
}
