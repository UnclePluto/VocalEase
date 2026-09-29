package com.vocaease.patient.feature.training

import com.vocaease.patient.core.database.DraftState
import com.vocaease.patient.core.database.StaleAccountScopeException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewViewModelTest {
    @Test
    fun `REVIEW_READY双媒体通过验证后可切换播放暂停和seek`() = runBlocking {
        val gateway = FakeReviewDraftGateway(reviewReadyDraft())
        val player = FakeReviewPlayer()
        val viewModel = ReviewViewModel("draft-1", gateway, player, Dispatchers.Unconfined)

        viewModel.load()
        assertEquals("小幸运", viewModel.state.value.songTitle)
        assertEquals("04:25", viewModel.state.value.durationText)
        assertEquals("音视频检查通过", viewModel.state.value.validationMessage)
        assertTrue(viewModel.state.value.canConfirm)
        assertEquals(listOf(ReviewMediaKind.VIDEO), player.loadedKinds)

        viewModel.playPause()
        player.events.emit(ReviewPlayerEvent.Playing(true, 0))
        assertTrue(viewModel.state.value.isPlaying)
        viewModel.seekTo(37_000)
        viewModel.switchMedia(ReviewMediaKind.AUDIO)

        assertEquals(listOf(ReviewMediaKind.VIDEO, ReviewMediaKind.AUDIO), player.loadedKinds)
        assertEquals(37_000L, player.lastSeekMillis)
        assertTrue(player.playCount >= 2)
        viewModel.playPause()
        assertEquals(1, player.pauseCount)
    }

    @Test
    fun `缺轨损坏或账户切换时禁用确认并真实释放播放器`() = runBlocking {
        val missing = ReviewViewModel(
            "draft-1",
            FakeReviewDraftGateway(reviewReadyDraft().copy(media = ReviewMediaValidation.Missing)),
            FakeReviewPlayer(),
            Dispatchers.Unconfined,
        )
        missing.load()
        assertFalse(missing.state.value.canConfirm)
        assertEquals("录制文件不完整，请重新录制", missing.state.value.errorMessage)

        val player = FakeReviewPlayer()
        val stale = ReviewViewModel(
            "draft-1",
            FakeReviewDraftGateway(reviewReadyDraft()).apply { failWithStaleLease = true },
            player,
            Dispatchers.Unconfined,
        )
        stale.load()
        assertFalse(stale.state.value.canConfirm)
        assertEquals(1, player.releaseCount)
        assertTrue(player.releaseCompleted)
    }

    @Test
    fun `迟到播放器回调不得复活已离开的回看状态`() = runBlocking {
        val player = FakeReviewPlayer()
        val viewModel = ReviewViewModel(
            "draft-1", FakeReviewDraftGateway(reviewReadyDraft()), player, Dispatchers.Unconfined,
        )
        viewModel.load()
        viewModel.leave()

        player.events.emit(ReviewPlayerEvent.Playing(true, 99_000))

        assertFalse(viewModel.state.value.isPlaying)
        assertEquals(1, player.releaseCount)
        assertTrue(player.releaseCompleted)
    }

    @Test
    fun `INTERRUPTED只能重录或删除且不能确认`() = runBlocking {
        val gateway = FakeReviewDraftGateway(reviewReadyDraft().copy(state = DraftState.INTERRUPTED))
        val player = FakeReviewPlayer()
        val viewModel = ReviewViewModel("draft-1", gateway, player, Dispatchers.Unconfined)
        viewModel.load()

        assertFalse(viewModel.state.value.canConfirm)
        viewModel.confirm()
        assertEquals(0, gateway.confirmCount)

        viewModel.rerecord()
        assertEquals(1, gateway.rerecordCount)
        assertEquals("draft-1", viewModel.state.value.navigateRecordingDraftId)
        assertEquals("session-1", gateway.identityAfterRerecord?.sessionId)
        assertEquals("session-create:key:draft-1", gateway.identityAfterRerecord?.creationKey)
    }

    @Test
    fun `确认只做幂等Room入队信号且重复点击不重复调度`() = runBlocking {
        val gateway = FakeReviewDraftGateway(reviewReadyDraft())
        val viewModel = ReviewViewModel("draft-1", gateway, FakeReviewPlayer(), Dispatchers.Unconfined)
        viewModel.load()

        viewModel.confirm()
        viewModel.confirm()

        assertEquals(1, gateway.confirmCount)
        assertEquals(1, gateway.uploadSignalCount)
        assertEquals("draft-1", viewModel.state.value.navigatePendingUploadDraftId)
        assertEquals(0, gateway.networkCallCount)
    }

    @Test
    fun `本地入队失败后恢复确认按钮并允许安全重试`() = runBlocking {
        val gateway = FakeReviewDraftGateway(reviewReadyDraft()).apply { confirmFailure = true }
        val viewModel = ReviewViewModel("draft-1", gateway, FakeReviewPlayer(), Dispatchers.Unconfined)
        viewModel.load()

        viewModel.confirm()

        assertTrue(viewModel.state.value.canConfirm)
        assertEquals("提交准备失败，请稍后重试", viewModel.state.value.errorMessage)
        gateway.confirmFailure = false
        viewModel.confirm()
        assertEquals(2, gateway.confirmCount)
        assertEquals("draft-1", viewModel.state.value.navigatePendingUploadDraftId)
    }

    @Test
    fun `重录先等待播放器释放再原子清理旧双媒体`() = runBlocking {
        val releaseGate = CountDownLatch(1)
        val player = FakeReviewPlayer(releaseGate)
        val gateway = FakeReviewDraftGateway(reviewReadyDraft())
        val viewModel = ReviewViewModel("draft-1", gateway, player, Dispatchers.Default)
        viewModel.load()

        val job = launch(Dispatchers.Default) { viewModel.rerecord() }
        assertTrue(player.releaseEntered.await(2, TimeUnit.SECONDS))
        assertEquals(0, gateway.rerecordCount)
        releaseGate.countDown()
        job.join()

        assertEquals(1, gateway.rerecordCount)
        assertFalse(gateway.oldVideoCanOpen)
        assertFalse(gateway.oldAudioCanOpen)
    }
}

private fun reviewReadyDraft() = ReviewDraft(
    draftId = "draft-1",
    songId = "song-1",
    songTitle = "小幸运",
    sessionId = "session-1",
    creationKey = "session-create:key:draft-1",
    state = DraftState.REVIEW_READY,
    durationMillis = 265_000,
    media = ReviewMediaValidation.Valid(
        video = ReviewMediaSource("video", "video/mp4", 1_024),
        audio = ReviewMediaSource("audio", "audio/mp4", 512),
    ),
)

private class FakeReviewDraftGateway(var draft: ReviewDraft) : ReviewDraftGateway {
    var failWithStaleLease = false
    var confirmCount = 0
    var uploadSignalCount = 0
    var networkCallCount = 0
    var confirmFailure = false
    var rerecordCount = 0
    var deleteCount = 0
    var oldVideoCanOpen = true
    var oldAudioCanOpen = true
    var identityAfterRerecord: ReviewDraftIdentity? = null

    override suspend fun load(draftId: String): ReviewDraft {
        if (failWithStaleLease) throw StaleAccountScopeException()
        return draft
    }

    override suspend fun confirm(draftId: String): Boolean {
        confirmCount += 1
        if (confirmFailure) error("local enqueue failed")
        uploadSignalCount += 1
        draft = draft.copy(state = DraftState.READY_TO_UPLOAD)
        return true
    }

    override suspend fun prepareRerecord(draftId: String): ReviewDraftIdentity {
        rerecordCount += 1
        oldVideoCanOpen = false
        oldAudioCanOpen = false
        return ReviewDraftIdentity(draft.draftId, draft.songId, draft.sessionId, draft.creationKey).also {
            identityAfterRerecord = it
        }
    }

    override suspend fun delete(draftId: String) {
        deleteCount += 1
    }
}

private class FakeReviewPlayer(private val releaseGate: CountDownLatch? = null) : ReviewPlayer {
    override val events = MutableSharedFlow<ReviewPlayerEvent>(extraBufferCapacity = 8)
    val loadedKinds = mutableListOf<ReviewMediaKind>()
    var lastSeekMillis = 0L
    var playCount = 0
    var pauseCount = 0
    var releaseCount = 0
    var releaseCompleted = false
    val releaseEntered = CountDownLatch(1)

    override suspend fun load(source: ReviewMediaSource, kind: ReviewMediaKind, positionMillis: Long, playWhenReady: Boolean) {
        loadedKinds += kind
        lastSeekMillis = positionMillis
        if (playWhenReady) playCount += 1
    }

    override suspend fun play() { playCount += 1 }
    override suspend fun pause() { pauseCount += 1 }
    override suspend fun seekTo(positionMillis: Long) { lastSeekMillis = positionMillis }
    override suspend fun releaseAndAwait() {
        releaseCount += 1
        releaseEntered.countDown()
        releaseGate?.await(2, TimeUnit.SECONDS)
        releaseCompleted = true
    }
}
