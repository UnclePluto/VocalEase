package com.vocaease.patient.feature.training

import com.vocaease.patient.core.database.StaleAccountScopeException
import com.vocaease.patient.core.media.PreviewState
import com.vocaease.patient.core.media.PreviewSession
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationViewModelTest {
    @Test
    fun `双击并发只创建一个逻辑会话且保存server id后才倒计时`() = runBlocking {
        val createEntered = CompletableDeferred<Unit>()
        val releaseCreate = CompletableDeferred<Unit>()
        val store = FakePreparationStore()
        val remote = FakeSessionCreator {
            createEntered.complete(Unit)
            releaseCreate.await()
            createdSession()
        }
        val countdownObserved = mutableListOf<Int>()
        val viewModel = viewModel(store, remote) { second ->
            assertEquals(SESSION_ID, store.drafts.single().serverSessionId)
            countdownObserved += second
        }
        viewModel.load()

        val first = async { viewModel.startTraining() }
        createEntered.await()
        val second = async { viewModel.startTraining() }
        releaseCreate.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, remote.keys.size)
        assertEquals(listOf(3, 2, 1), countdownObserved)
        assertEquals(store.drafts.single().draftId, viewModel.state.value.navigateToRecordingDraftId)
    }

    @Test
    fun `超时重试和创建前崩溃恢复复用持久draft与稳定creation key`() = runBlocking {
        val store = FakePreparationStore()
        val saver = MemoryPreparationState()
        val remote = FakeSessionCreator { throw IOException("timeout") }
        val first = viewModel(store, remote, saver = saver)
        first.load()

        first.startTraining()

        val persisted = store.drafts.single()
        val expected = "session-create:${store.accountScopeHash}:${persisted.draftId}"
        assertEquals(expected, persisted.creationKey)
        assertEquals(listOf(expected), remote.keys)
        assertNull(persisted.serverSessionId)

        remote.behavior = { createdSession() }
        val recovered = viewModel(store, remote, saver = saver)
        recovered.load()
        recovered.startTraining()

        assertEquals(listOf(expected, expected), remote.keys)
        assertEquals(1, store.createCount)
        assertEquals(SESSION_ID, store.drafts.single().serverSessionId)
    }

    @Test
    fun `API成功后持久化前崩溃仍由同一幂等键恢复同一server session`() = runBlocking {
        val store = FakePreparationStore()
        val saver = MemoryPreparationState()
        val remote = FakeSessionCreator { createdSession() }
        val first = viewModel(store, remote, saver = saver)
        first.load()
        store.failNextBind = true

        first.startTraining()

        assertNull(store.drafts.single().serverSessionId)
        assertNull(first.state.value.navigateToRecordingDraftId)
        val recovered = viewModel(store, remote, saver = saver)
        recovered.load()
        recovered.startTraining()

        assertEquals(2, remote.keys.size)
        assertEquals(remote.keys.first(), remote.keys.last())
        assertEquals(SESSION_ID, store.drafts.single().serverSessionId)
    }

    @Test
    fun `已持久server session的进程恢复不再调用创建接口`() = runBlocking {
        val store = FakePreparationStore().apply {
            drafts += pendingDraft(DRAFT_ID).copy(serverSessionId = SESSION_ID)
        }
        val saver = MemoryPreparationState(DRAFT_ID, store.accountScopeHash)
        val remote = FakeSessionCreator { error("不应调用") }
        val viewModel = viewModel(store, remote, saver = saver)
        viewModel.load()

        viewModel.startTraining()

        assertTrue(remote.keys.isEmpty())
        assertEquals(DRAFT_ID, viewModel.state.value.navigateToRecordingDraftId)
    }

    @Test
    fun `录制导航事件消费后不会在返回准备页时重复触发`() = runBlocking {
        val store = FakePreparationStore().apply {
            drafts += pendingDraft(DRAFT_ID).copy(serverSessionId = SESSION_ID)
        }
        val viewModel = viewModel(
            store = store,
            remote = FakeSessionCreator { error("不应调用") },
            saver = MemoryPreparationState(DRAFT_ID, store.accountScopeHash),
        )
        viewModel.load()
        viewModel.startTraining()

        assertEquals(DRAFT_ID, viewModel.state.value.navigateToRecordingDraftId)

        viewModel.consumeRecordingNavigation()

        assertNull(viewModel.state.value.navigateToRecordingDraftId)
    }

    @Test
    fun `A到B换号与迟到响应不能把旧患者session写入新账户`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = FakePreparationStore()
        val remote = FakeSessionCreator {
            entered.complete(Unit)
            release.await()
            createdSession()
        }
        val viewModel = viewModel(store, remote)
        viewModel.load()

        val creation = async { viewModel.startTraining() }
        entered.await()
        store.invalidateForAccountSwitch()
        release.complete(Unit)
        creation.await()

        assertNull(store.drafts.single().serverSessionId)
        assertNull(viewModel.state.value.navigateToRecordingDraftId)
        assertEquals("账号已切换，请重新进入演唱准备页", viewModel.state.value.errorMessage)
    }

    @Test
    fun `用户取消后即使API迟到成功也不能绑定或倒计时`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = FakePreparationStore()
        val remote = FakeSessionCreator {
            entered.complete(Unit)
            release.await()
            createdSession()
        }
        val countdown = mutableListOf<Int>()
        val viewModel = viewModel(store, remote, countdown = { countdown += it })
        viewModel.load()
        val creation = async { viewModel.startTraining() }
        entered.await()

        viewModel.cancelPreparation()
        release.complete(Unit)
        creation.await()

        assertNull(store.drafts.single().serverSessionId)
        assertTrue(countdown.isEmpty())
        assertNull(viewModel.state.value.navigateToRecordingDraftId)
    }

    @Test
    fun `门禁未通过或离线时绝不创建本地draft和服务端session`() = runBlocking {
        val store = FakePreparationStore()
        val remote = FakeSessionCreator { createdSession() }
        val viewModel = viewModel(store, remote, readiness = readiness(online = false))
        viewModel.load()

        viewModel.startTraining()

        assertTrue(store.drafts.isEmpty())
        assertTrue(remote.keys.isEmpty())
        assertFalse(viewModel.state.value.preflight.canStart)
    }

    private fun viewModel(
        store: FakePreparationStore,
        remote: FakeSessionCreator,
        saver: MemoryPreparationState = MemoryPreparationState(),
        readiness: DeviceReadiness = readiness(),
        countdown: suspend (Int) -> Unit = {},
    ) = PreparationViewModel(
        songId = SONG_ID,
        songSource = PreparationSongSource { song() },
        readinessSource = FakeReadinessSource(readiness),
        preview = FakePreviewSession(),
        storageProvider = PreparationDraftStoreProvider { store },
        sessionCreator = remote,
        savedState = saver,
        countdownTick = countdown,
        idFactory = { DRAFT_ID },
        clock = { 1_000L },
        dispatcher = Dispatchers.Unconfined,
    )

    private fun song() = PreparationSong(
        id = UUID.fromString(SONG_ID),
        title = "小幸运",
        artist = "田馥甄",
        durationSeconds = 265,
    )

    private fun readiness(online: Boolean = true) = DeviceReadiness(
        cameraPermission = PermissionReadiness.GRANTED,
        audioPermission = PermissionReadiness.GRANTED,
        availableBytes = Long.MAX_VALUE,
        durationSeconds = 265,
        previewBuffered = true,
        online = online,
        frontCameraAvailable = true,
        headphonesConnected = true,
    )

    private fun createdSession() = CreatedTrainingSession(
        sessionId = UUID.fromString(SESSION_ID),
        patientId = UUID.fromString(PATIENT_ID),
        song = song(),
    )

    private inner class FakeReadinessSource(var value: DeviceReadiness) : ReadinessSource {
        override suspend fun inspect(durationSeconds: Long, previewBuffered: Boolean): DeviceReadiness =
            value.copy(durationSeconds = durationSeconds, previewBuffered = previewBuffered)
    }

    private companion object {
        const val SONG_ID = "10000000-0000-4000-8000-000000000001"
        const val PATIENT_ID = "20000000-0000-4000-8000-000000000002"
        const val SESSION_ID = "30000000-0000-4000-8000-000000000003"
        const val DRAFT_ID = "40000000-0000-4000-8000-000000000004"
    }
}

private class FakePreviewSession : PreviewSession {
    override val state: StateFlow<PreviewState> = MutableStateFlow(PreviewState.Buffered)
    override suspend fun prepare(songId: String) = Unit
    override fun release() = Unit
}

private class FakeSessionCreator(
    var behavior: suspend () -> CreatedTrainingSession,
) : TrainingSessionCreator {
    val keys = mutableListOf<String>()
    override suspend fun create(songId: UUID, creationKey: String): CreatedTrainingSession {
        keys += creationKey
        return behavior()
    }
}

private class MemoryPreparationState(
    override var draftId: String? = null,
    override var accountScopeHash: String? = null,
) : PreparationSavedState

private class FakePreparationStore : PreparationDraftStore {
    override val accountScopeHash = "a".repeat(64)
    val drafts = mutableListOf<PreparationDraft>()
    var createCount = 0
    var failNextBind = false
    private var valid = true

    override suspend fun find(draftId: String): PreparationDraft? = drafts.firstOrNull { it.draftId == draftId }

    override suspend fun create(draft: PreparationDraft) {
        check(valid)
        createCount += 1
        drafts += draft
    }

    override suspend fun bindServerSession(draftId: String, session: CreatedTrainingSession) {
        if (!valid) throw StaleAccountScopeException()
        if (failNextBind) {
            failNextBind = false
            throw IOException("process died before commit")
        }
        val index = drafts.indexOfFirst { it.draftId == draftId }
        drafts[index] = drafts[index].copy(serverSessionId = session.sessionId.toString())
    }

    fun invalidateForAccountSwitch() {
        valid = false
    }
}

private fun pendingDraft(draftId: String) = PreparationDraft(
    draftId = draftId,
    songId = "10000000-0000-4000-8000-000000000001",
    songTitle = "小幸运",
    songArtist = "田馥甄",
    songDurationSeconds = 265,
    serverSessionId = null,
    creationKey = "session-create:${"a".repeat(64)}:$draftId",
    createdAt = 1_000L,
    expiresAt = 1_000L + 7L * 24L * 60L * 60L * 1_000L,
)
