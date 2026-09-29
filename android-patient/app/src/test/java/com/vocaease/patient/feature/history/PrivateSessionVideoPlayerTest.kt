package com.vocaease.patient.feature.history

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSessionVideoPlayerTest {
    @Test
    fun `公开状态流只发布安全播放状态而不暴露私有URL`() = runBlocking {
        val remote = FakePrivateVideoRemote(videoSession()).apply {
            grants += privateGrant("https://private.example/one?token=secret", 100_000)
        }
        val player = PrivateSessionVideoPlayer(remote, FakePrivateVideoEngine(), { 0L }, { true })

        assertEquals(PrivateVideoState.Unavailable, player.stateFlow.value)
        player.open("s1")

        assertTrue(player.stateFlow.value is PrivateVideoState.Ready)
        assertFalse(player.stateFlow.value.toString().contains("private.example"))
        assertFalse(player.stateFlow.value.toString().contains("token="))
    }

    @Test
    fun `只接受当前会话唯一ready mp4 singing video`() = runBlocking {
        val invalid = listOf(
            videoSession("other"),
            videoSession(media = listOf(videoAsset(mediaType = "singing_audio"))),
            videoSession(media = listOf(videoAsset(status = "pending"))),
            videoSession(media = listOf(videoAsset(mimeType = "video/webm"))),
            videoSession(media = listOf(videoAsset(sizeBytes = 0))),
            videoSession(media = listOf(videoAsset(), videoAsset(assetId = "asset-2"))),
        )
        invalid.forEach { detail ->
            val remote = FakePrivateVideoRemote(detail)
            val engine = FakePrivateVideoEngine()
            val player = PrivateSessionVideoPlayer(remote, engine, { 0L }, { true })

            player.open("s1")

            assertEquals(PrivateVideoState.Failed("视频暂不可用"), player.state)
            assertEquals(0, remote.grantCalls)
            assertTrue(engine.loads.isEmpty())
        }
    }

    @Test
    fun `短期私有URL只保存在内存并在安全窗口刷新`() = runBlocking {
        var now = 0L
        val remote = FakePrivateVideoRemote(videoSession()).apply {
            grants += privateGrant("https://private.example/one?token=secret", 100_000)
            grants += privateGrant("https://private.example/two?token=secret", 200_000)
        }
        val engine = FakePrivateVideoEngine()
        val player = PrivateSessionVideoPlayer(remote, engine, { now }, { true })

        player.open("s1")
        player.open("s1")
        assertEquals(1, remote.grantCalls)
        assertFalse(player.state.toString().contains("private.example"))
        assertFalse(player.state.toString().contains("token="))

        now = 70_000L
        player.open("s1")
        assertEquals(2, remote.grantCalls)
        assertEquals(3, engine.loads.size)
        assertNull(player.publicPlaybackUrl)
    }

    @Test
    fun `首次401或403刷新一次并保留播放位置第二次安全失败`() = runBlocking {
        val remote = FakePrivateVideoRemote(videoSession()).apply {
            grants += privateGrant("https://private.example/one", 100_000)
            grants += privateGrant("https://private.example/two", 100_000)
        }
        val engine = FakePrivateVideoEngine().apply { positionMillis = 12_345 }
        val player = PrivateSessionVideoPlayer(remote, engine, { 0L }, { true })
        player.open("s1")
        val firstSource = engine.loads.single().sourceId

        player.onPlaybackHttpError(firstSource, 403)
        assertEquals(2, remote.grantCalls)
        assertEquals(12_345L, engine.loads.last().positionMillis)
        val refreshedSource = engine.loads.last().sourceId

        player.onPlaybackHttpError(refreshedSource, 401)
        assertEquals(2, remote.grantCalls)
        assertEquals(PrivateVideoState.Failed("视频加载失败，请重试"), player.state)
    }

    @Test
    fun `非鉴权播放错误不刷新私有URL`() = runBlocking {
        val remote = FakePrivateVideoRemote(videoSession()).apply {
            grants += privateGrant("https://private.example/one", 100_000)
        }
        val engine = FakePrivateVideoEngine()
        val player = PrivateSessionVideoPlayer(remote, engine, { 0L }, { true })
        player.open("s1")

        player.onPlaybackHttpError(engine.loads.single().sourceId, 500)

        assertEquals(1, remote.grantCalls)
        assertEquals(PrivateVideoState.Failed("视频加载失败，请重试"), player.state)
    }

    @Test
    fun `切换会话和lease失效会丢弃迟到grant`() = runBlocking {
        val remote = SuspendingPrivateVideoRemote()
        val engine = FakePrivateVideoEngine()
        var leaseActive = true
        val player = PrivateSessionVideoPlayer(remote, engine, { 0L }, { leaseActive })
        val first = async { player.open("s1") }
        remote.firstGrantEntered.await()
        remote.nextDetail = videoSession("s2", listOf(videoAsset(assetId = "asset-2")))
        remote.nextGrant = privateGrant("https://private.example/two", 100_000)

        player.open("s2")
        remote.firstGrant.complete(privateGrant("https://private.example/one", 100_000))
        first.await()

        assertEquals(1, engine.loads.size)
        assertTrue(engine.loads.single().sourceId.startsWith("asset-2#"))

        leaseActive = false
        player.invalidateLease()
        assertEquals(PrivateVideoState.Unavailable, player.state)
        assertEquals(2, engine.stopCalls)
    }

    @Test
    fun `release等待真实引擎释放并拒绝迟到回调`() = runBlocking {
        val remote = FakePrivateVideoRemote(videoSession()).apply {
            grants += privateGrant("https://private.example/one", 100_000)
        }
        val releaseGate = CompletableDeferred<Unit>()
        val engine = FakePrivateVideoEngine(releaseGate)
        val player = PrivateSessionVideoPlayer(remote, engine, { 0L }, { true })
        player.open("s1")
        val source = engine.loads.single().sourceId

        val release = async { player.releaseAndAwait() }
        engine.releaseEntered.await()
        assertFalse(release.isCompleted)
        releaseGate.complete(Unit)
        release.await()
        player.onPlaybackHttpError(source, 401)

        assertEquals(1, remote.grantCalls)
        assertEquals(PrivateVideoState.Unavailable, player.state)
    }

    @Test
    fun `切换会话在请求新地址前清除旧画面`() = runBlocking {
        val remote = FakePrivateVideoRemote(videoSession()).apply {
            grants += privateGrant("https://private.example/one", 100_000)
            grants += privateGrant("https://private.example/two", 100_000)
        }
        val engine = FakePrivateVideoEngine()
        val player = PrivateSessionVideoPlayer(remote, engine, { 0L }, { true })
        player.open("s1")
        remote.detail = videoSession("s2", listOf(videoAsset(assetId = "asset-2")))

        player.open("s2")

        assertEquals(1, engine.stopCalls)
        assertTrue(engine.loads.last().sourceId.startsWith("asset-2#"))
    }

    @Test
    fun `取消当前open会清除旧画面且不得发布失败状态`() = runBlocking {
        val remote = SuspendingPrivateVideoRemote()
        val engine = FakePrivateVideoEngine()
        val player = PrivateSessionVideoPlayer(remote, engine, { 0L }, { true })
        val opening = launch { player.open("s1") }
        remote.firstGrantEntered.await()

        opening.cancelAndJoin()

        assertEquals(PrivateVideoState.Unavailable, player.state)
        assertEquals(1, engine.stopCalls)
    }
}

private fun videoAsset(
    assetId: String = "asset-1",
    mediaType: String = "singing_video",
    status: String = "ready",
    mimeType: String = "video/mp4",
    sizeBytes: Long = 1_024,
) = PrivateVideoAsset(assetId, mediaType, status, mimeType, sizeBytes)

private fun videoSession(
    sessionId: String = "s1",
    media: List<PrivateVideoAsset> = listOf(videoAsset()),
) = PrivateVideoSession(sessionId, media)

private fun privateGrant(url: String, expiresAt: Long) = PrivateVideoGrant(url, expiresAt)

private class FakePrivateVideoRemote(var detail: PrivateVideoSession) : PrivateVideoRemote {
    val grants = ArrayDeque<PrivateVideoGrant>()
    var grantCalls = 0
    override suspend fun session(sessionId: String): PrivateVideoSession = detail
    override suspend fun privateUrl(assetId: String): PrivateVideoGrant {
        grantCalls += 1
        return grants.removeFirst()
    }
}

private class SuspendingPrivateVideoRemote : PrivateVideoRemote {
    val firstGrantEntered = CompletableDeferred<Unit>()
    val firstGrant = CompletableDeferred<PrivateVideoGrant>()
    var nextDetail = videoSession()
    var nextGrant = privateGrant("https://private.example/default", 100_000)
    private var grantCalls = 0

    override suspend fun session(sessionId: String): PrivateVideoSession =
        if (sessionId == "s1") videoSession("s1") else nextDetail

    override suspend fun privateUrl(assetId: String): PrivateVideoGrant {
        grantCalls += 1
        return if (grantCalls == 1) {
            firstGrantEntered.complete(Unit)
            firstGrant.await()
        } else nextGrant
    }
}

private class FakePrivateVideoEngine(
    private val releaseGate: CompletableDeferred<Unit>? = null,
) : PrivateVideoEngine {
    data class Load(val sourceId: String, val privateUrl: String, val positionMillis: Long)

    val loads = mutableListOf<Load>()
    var positionMillis = 0L
    var stopCalls = 0
    val releaseEntered = CompletableDeferred<Unit>()

    override suspend fun load(sourceId: String, privateUrl: String, positionMillis: Long) {
        loads += Load(sourceId, privateUrl, positionMillis)
    }

    override suspend fun currentPositionMillis(): Long = positionMillis

    override suspend fun stopAndClear() {
        stopCalls += 1
    }

    override suspend fun releaseAndAwait() {
        releaseEntered.complete(Unit)
        releaseGate?.await()
    }
}
