package com.vocaease.patient.feature.training

import androidx.lifecycle.ViewModelStore
import com.vocaease.patient.core.database.StaleAccountScopeException
import com.vocaease.patient.core.database.PreparationDraftStatus
import com.vocaease.patient.core.media.PreviewState
import com.vocaease.patient.core.media.PreviewSession
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationViewModelTest {
    @Test
    fun `onCleared只提交release且不等待播放器释放完成`() = runBlocking {
        val preview = FakePreviewSession(releaseCompletion = CompletableDeferred())
        val viewModel = viewModel(
            store = FakePreparationStore(),
            remote = FakeSessionCreator { createdSession() },
            preview = preview,
        )
        val viewModelStore = ViewModelStore().apply { put("preparation", viewModel) }

        withTimeout(1_000) {
            async(Dispatchers.Default) { viewModelStore.clear() }.await()
        }

        assertEquals(1, preview.releaseCount)
        assertEquals(0, preview.awaitReleasedCount)
    }

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
            drafts += pendingDraft(DRAFT_ID).copy(
                serverSessionId = SESSION_ID,
                status = PreparationDraftStatus.BOUND,
            )
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
    fun `SavedState缺失仍从Room恢复pending并复用原draft与creation key`() = runBlocking {
        val recoveredDraftId = "70000000-0000-4000-8000-000000000007"
        val store = FakePreparationStore().apply { drafts += pendingDraft(recoveredDraftId) }
        val remote = FakeSessionCreator { createdSession() }
        val viewModel = viewModel(store, remote, saver = MemoryPreparationState())
        viewModel.load()

        viewModel.startTraining()

        assertEquals(1, store.drafts.size)
        assertEquals(listOf(pendingDraft(recoveredDraftId).creationKey), remote.keys)
        assertEquals(recoveredDraftId, viewModel.state.value.navigateToRecordingDraftId)
    }

    @Test
    fun `交接意图提交后导航前崩溃且SavedState缺失仍恢复同一session`() = runBlocking {
        val recoveredDraftId = "70000000-0000-4000-8000-000000000008"
        val store = FakePreparationStore().apply {
            drafts += pendingDraft(recoveredDraftId).copy(
                serverSessionId = SESSION_ID,
                status = PreparationDraftStatus.HANDOFF_PENDING,
            )
        }
        val remote = FakeSessionCreator { error("已绑定会话不得重新创建") }
        val viewModel = viewModel(store, remote, saver = MemoryPreparationState())
        viewModel.load()

        viewModel.startTraining()

        assertTrue(remote.keys.isEmpty())
        assertEquals(1, store.drafts.size)
        assertEquals(recoveredDraftId, viewModel.state.value.navigateToRecordingDraftId)
    }

    @Test
    fun `录制导航事件消费后不会在返回准备页时重复触发`() = runBlocking {
        val store = FakePreparationStore().apply {
            drafts += pendingDraft(DRAFT_ID).copy(
                serverSessionId = SESSION_ID,
                status = PreparationDraftStatus.BOUND,
            )
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
    fun `最后一次倒计时返回时取消或换号都不能迟到导航`() = runBlocking {
        listOf(false, true).forEach { switchAccount ->
            val store = FakePreparationStore()
            lateinit var viewModel: PreparationViewModel
            viewModel = viewModel(
                store = store,
                remote = FakeSessionCreator { createdSession() },
                countdown = { second ->
                    if (second == 1) {
                        if (switchAccount) store.invalidateForAccountSwitch()
                        else viewModel.cancelPreparation()
                    }
                },
            )
            viewModel.load()

            viewModel.startTraining()

            assertNull(viewModel.state.value.navigateToRecordingDraftId)
            assertFalse(viewModel.state.value.isCreatingSession)
            assertNull(viewModel.state.value.countdownSecond)
        }
    }

    @Test
    fun `preview和readiness事件不能覆盖创建中与倒计时状态`() = runBlocking {
        val preview = FakePreviewSession()
        lateinit var viewModel: PreparationViewModel
        viewModel = viewModel(
            store = FakePreparationStore(),
            remote = FakeSessionCreator { createdSession() },
            preview = preview,
            countdown = { second ->
                preview.emit(PreviewState.Buffering)
                viewModel.refreshReadiness()
                assertTrue(viewModel.state.value.isCreatingSession)
                assertEquals(second, viewModel.state.value.countdownSecond)
                preview.emit(PreviewState.Buffered)
                viewModel.refreshReadiness()
                assertTrue(viewModel.state.value.isCreatingSession)
                assertEquals(second, viewModel.state.value.countdownSecond)
            },
        )
        viewModel.load()

        viewModel.startTraining()

        assertEquals(DRAFT_ID, viewModel.state.value.navigateToRecordingDraftId)
        assertFalse(viewModel.state.value.isCreatingSession)
        assertNull(viewModel.state.value.countdownSecond)
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

    @Test
    fun `页面恢复和网络变化会重检全部门禁且离开后注销观察`() = runBlocking {
        val readiness = FakeReadinessSource(readiness(online = false))
        val environment = FakePreparationEnvironmentMonitor()
        val viewModel = viewModel(
            store = FakePreparationStore(),
            remote = FakeSessionCreator { createdSession() },
            readinessSource = readiness,
            environmentMonitor = environment,
        )
        viewModel.load()
        assertFalse(viewModel.state.value.preflight.canStart)

        viewModel.onEnvironmentStarted()
        assertEquals(1, environment.startCount)
        readiness.value = readiness().copy(availableBytes = Long.MAX_VALUE, frontCameraAvailable = true)
        environment.emitChange()
        assertTrue(viewModel.state.value.preflight.canStart)

        val beforeResume = readiness.inspectCount
        viewModel.onEnvironmentResumed()
        assertTrue(readiness.inspectCount > beforeResume)

        viewModel.onEnvironmentStopped()
        val stoppedCount = readiness.inspectCount
        readiness.value = readiness(online = false)
        environment.emitChange()
        assertEquals(stoppedCount, readiness.inspectCount)
        assertEquals(1, environment.stopCount)
    }

    private fun viewModel(
        store: FakePreparationStore,
        remote: FakeSessionCreator,
        saver: MemoryPreparationState = MemoryPreparationState(),
        readiness: DeviceReadiness = readiness(),
        readinessSource: FakeReadinessSource = FakeReadinessSource(readiness),
        environmentMonitor: PreparationEnvironmentMonitor = FakePreparationEnvironmentMonitor(),
        preview: FakePreviewSession = FakePreviewSession(),
        countdown: suspend (Int) -> Unit = {},
    ) = PreparationViewModel(
        songId = SONG_ID,
        songSource = PreparationSongSource { song() },
        readinessSource = readinessSource,
        environmentMonitor = environmentMonitor,
        preview = preview,
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
        var inspectCount = 0
        override suspend fun inspect(durationSeconds: Long, previewBuffered: Boolean): DeviceReadiness {
            inspectCount += 1
            return value.copy(durationSeconds = durationSeconds, previewBuffered = previewBuffered)
        }
    }

    private companion object {
        const val SONG_ID = "10000000-0000-4000-8000-000000000001"
        const val PATIENT_ID = "20000000-0000-4000-8000-000000000002"
        const val SESSION_ID = "30000000-0000-4000-8000-000000000003"
        const val DRAFT_ID = "40000000-0000-4000-8000-000000000004"
    }
}

private class FakePreviewSession(
    private val releaseCompletion: CompletableDeferred<Unit> = CompletableDeferred(Unit),
) : PreviewSession {
    private val mutableState = MutableStateFlow<PreviewState>(PreviewState.Buffered)
    var releaseCount = 0
    var awaitReleasedCount = 0
    override val state: StateFlow<PreviewState> = mutableState
    override suspend fun prepare(songId: String) = Unit
    override suspend fun play(): Boolean = true
    override suspend fun pause(): Boolean = true
    override fun release() {
        releaseCount += 1
    }
    override suspend fun awaitReleased() {
        awaitReleasedCount += 1
        releaseCompletion.await()
    }
    fun emit(value: PreviewState) {
        mutableState.value = value
    }
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

private class FakePreparationEnvironmentMonitor : PreparationEnvironmentMonitor {
    var startCount = 0
    var stopCount = 0
    private var listener: (() -> Unit)? = null

    override fun start(onChanged: () -> Unit) {
        startCount += 1
        listener = onChanged
    }

    override fun stop() {
        stopCount += 1
        listener = null
    }

    fun emitChange() {
        listener?.invoke()
    }
}

private class FakePreparationStore : PreparationDraftStore {
    override val accountScopeHash = "a".repeat(64)
    val drafts = mutableListOf<PreparationDraft>()
    var createCount = 0
    var failNextBind = false
    private var valid = true

    override suspend fun find(draftId: String): PreparationDraft? = drafts.firstOrNull { it.draftId == draftId }

    override suspend fun findActive(songId: String): PreparationDraft? = drafts.firstOrNull {
        it.songId == songId && it.status in setOf(
            PreparationDraftStatus.PENDING,
            PreparationDraftStatus.BOUND,
            PreparationDraftStatus.HANDOFF_PENDING,
        )
    }

    override suspend fun create(draft: PreparationDraft) {
        check(valid)
        createCount += 1
        drafts += draft
    }

    override suspend fun findOrCreate(candidate: PreparationDraft): PreparationDraft =
        findActive(candidate.songId) ?: candidate.also { create(it) }

    override suspend fun bindServerSession(draftId: String, session: CreatedTrainingSession) {
        if (!valid) throw StaleAccountScopeException()
        if (failNextBind) {
            failNextBind = false
            throw IOException("process died before commit")
        }
        val index = drafts.indexOfFirst { it.draftId == draftId }
        drafts[index] = drafts[index].copy(
            serverSessionId = session.sessionId.toString(),
            status = PreparationDraftStatus.BOUND,
        )
    }

    override suspend fun markHandoffPending(
        draftId: String,
        serverSessionId: String,
        publishNavigation: () -> Unit,
    ) {
        if (!valid) throw StaleAccountScopeException()
        val index = drafts.indexOfFirst { it.draftId == draftId }
        val draft = drafts[index]
        if (draft.serverSessionId != serverSessionId) error("session mismatch")
        drafts[index] = draft.copy(status = PreparationDraftStatus.HANDOFF_PENDING)
        publishNavigation()
    }

    override suspend fun acknowledgeHandoff(draftId: String): Boolean {
        val index = drafts.indexOfFirst { it.draftId == draftId }
        if (index < 0 || drafts[index].status != PreparationDraftStatus.HANDOFF_PENDING) return false
        drafts[index] = drafts[index].copy(status = PreparationDraftStatus.HANDED_OFF)
        return true
    }

    override suspend fun abandon(draftId: String): Boolean {
        val index = drafts.indexOfFirst { it.draftId == draftId }
        if (index < 0) return false
        drafts[index] = drafts[index].copy(status = PreparationDraftStatus.ABANDONED)
        return true
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
    status = PreparationDraftStatus.PENDING,
    createdAt = 1_000L,
    expiresAt = 1_000L + 7L * 24L * 60L * 60L * 1_000L,
)
