package com.vocaease.patient.feature.history

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HistoryRepositoryTest {
    @Test
    fun `同一session不同draft只保留一行且本地暂态覆盖远端暂态`() = runBlocking {
        val repository = repository(
            local = listOf(local("s1", "d-new", HistoryStatus.UPLOADING)),
            remote = listOf(remote("s1", HistoryStatus.PROCESSING)),
        )

        repository.start()
        repository.refresh()

        assertEquals(1, repository.state.value.items.size)
        assertEquals("d-new", repository.state.value.items.single().draftId)
        assertEquals(HistoryStatus.UPLOADING, repository.state.value.items.single().status)
    }

    @Test
    fun `可信远端terminal不能被陈旧本地进度倒退`() = runBlocking {
        listOf(HistoryStatus.COMPLETED, HistoryStatus.FAILED, HistoryStatus.CANCELLED).forEach { terminal ->
            val repository = repository(
                local = listOf(local("s1", "d1", HistoryStatus.ANALYZING)),
                remote = listOf(remote("s1", terminal)),
            )
            repository.start()
            repository.refresh()
            assertEquals(terminal, repository.state.value.items.single().status)
            assertEquals(if (terminal == HistoryStatus.COMPLETED) "88" else null, repository.state.value.items.single().score)
        }
    }

    @Test
    fun `分页重复session按主键去重并保持最新服务端版本`() = runBlocking {
        val source = QueueHistoryRemote(
            HistoryPage(3, 1, 2, listOf(remote("s1", HistoryStatus.PROCESSING), remote("s2", HistoryStatus.COMPLETED))),
            HistoryPage(3, 2, 2, listOf(remote("s1", HistoryStatus.COMPLETED))),
        )
        val repository = HistoryRepository(EmptyHistoryLocal, source) { true }

        repository.start()
        repository.refresh(page = 1, pageSize = 2)
        repository.refresh(page = 2, pageSize = 2)

        assertEquals(listOf("s1", "s2"), repository.state.value.items.map { it.sessionId }.sorted())
        assertEquals(HistoryStatus.COMPLETED, repository.state.value.items.first { it.sessionId == "s1" }.status)
    }

    @Test
    fun `刷新失败保留当前账户旧列表并给出可重试错误`() = runBlocking {
        val remote = QueueHistoryRemote(HistoryPage(1, 1, 20, listOf(remote("s1", HistoryStatus.COMPLETED))))
        val repository = HistoryRepository(EmptyHistoryLocal, remote) { true }
        repository.start()
        repository.refresh()
        remote.failure = true

        repository.refresh()

        assertEquals(listOf("s1"), repository.state.value.items.map { it.sessionId })
        assertEquals("暂时无法刷新演唱记录，请重试", repository.state.value.errorMessage)
        assertTrue(repository.state.value.canRetry)
    }

    @Test
    fun `离线仍展示当前账户尚未完成的本地任务`() = runBlocking {
        val repository = HistoryRepository(
            FixedHistoryLocal(listOf(local("s-local", "d1", HistoryStatus.WAITING_NETWORK))),
            QueueHistoryRemote().apply { failure = true },
        ) { true }

        repository.start()
        repository.refresh()

        assertEquals(listOf("s-local"), repository.state.value.items.map { it.sessionId })
        assertEquals(HistoryStatus.WAITING_NETWORK, repository.state.value.items.single().status)
    }

    @Test
    fun `换号失效立即清空且迟到响应不能恢复其他账户内容`() = runBlocking {
        val active = AtomicBoolean(true)
        val response = CompletableDeferred<HistoryPage>()
        val repository = HistoryRepository(
            EmptyHistoryLocal,
            SuspendingHistoryRemote(response),
            leaseActive = active::get,
        )
        repository.start()
        val refresh = async { repository.refresh() }

        active.set(false)
        repository.invalidateLease()
        response.complete(HistoryPage(1, 1, 20, listOf(remote("patient-a", HistoryStatus.COMPLETED))))
        refresh.await()

        assertTrue(repository.state.value.items.isEmpty())
        assertFalse(repository.state.value.canRetry)
    }

    @Test
    fun `同患者新incarnation使旧仓库迟到响应fail closed`() = runBlocking {
        val active = AtomicBoolean(true)
        val response = CompletableDeferred<HistoryPage>()
        val old = HistoryRepository(EmptyHistoryLocal, SuspendingHistoryRemote(response), active::get)
        old.start()
        val refresh = async { old.refresh() }

        active.set(false)
        old.invalidateLease()
        response.complete(HistoryPage(1, 1, 20, listOf(remote("old-incarnation", HistoryStatus.COMPLETED))))
        refresh.await()

        assertTrue(old.state.value.items.isEmpty())
    }

    @Test
    fun `较新的刷新generation胜出且旧响应不覆盖`() = runBlocking {
        val first = CompletableDeferred<HistoryPage>()
        val second = CompletableDeferred<HistoryPage>()
        val remote = SequencedSuspendingRemote(first, second)
        val repository = HistoryRepository(EmptyHistoryLocal, remote) { true }
        repository.start()
        val old = async { repository.refresh() }
        val latest = async { repository.refresh() }

        second.complete(HistoryPage(1, 1, 20, listOf(remote("new", HistoryStatus.COMPLETED))))
        latest.await()
        first.complete(HistoryPage(1, 1, 20, listOf(remote("old", HistoryStatus.COMPLETED))))
        old.await()

        assertEquals(listOf("new"), repository.state.value.items.map { it.sessionId })
    }

    @Test
    fun `本地启动与远端刷新的取消都必须原样传播`() = runBlocking {
        val startCancellation = CancellationException("离开历史页")
        val startRepository = HistoryRepository(
            HistoryLocalSource { throw startCancellation },
            QueueHistoryRemote(),
        ) { true }
        try {
            startRepository.start()
            fail("启动取消不应被转换为空历史")
        } catch (actual: CancellationException) {
            assertTrue(actual === startCancellation)
        }

        val refreshCancellation = CancellationException("取消历史刷新")
        val refreshRepository = HistoryRepository(
            EmptyHistoryLocal,
            HistoryRemoteSource { _, _, _ -> throw refreshCancellation },
        ) { true }
        refreshRepository.start()
        try {
            refreshRepository.refresh()
            fail("刷新取消不应被转换为可重试错误")
        } catch (actual: CancellationException) {
            assertTrue(actual === refreshCancellation)
        }
    }

    private fun repository(local: List<HistoryLocalRecord>, remote: List<HistoryRemoteRecord>) = HistoryRepository(
        FixedHistoryLocal(local),
        QueueHistoryRemote(HistoryPage(remote.size, 1, 20, remote)),
    ) { true }

    private fun local(session: String, draft: String, status: HistoryStatus) = HistoryLocalRecord(
        sessionId = session,
        draftId = draft,
        songTitle = "本地歌曲",
        artist = "",
        durationSeconds = 20,
        status = status,
        updatedAtEpochMillis = 20,
    )

    private fun remote(session: String, status: HistoryStatus) = HistoryRemoteRecord(
        sessionId = session,
        songTitle = "远端歌曲",
        artist = "歌手",
        durationSeconds = 30,
        status = status,
        score = 88.0,
        analysisGeneration = 1,
        updatedAtEpochMillis = if (status == HistoryStatus.COMPLETED) 30 else 10,
    )
}

private object EmptyHistoryLocal : HistoryLocalSource {
    override suspend fun loadPending(): List<HistoryLocalRecord> = emptyList()
}

private class FixedHistoryLocal(private val records: List<HistoryLocalRecord>) : HistoryLocalSource {
    override suspend fun loadPending(): List<HistoryLocalRecord> = records
}

private class QueueHistoryRemote(vararg pages: HistoryPage) : HistoryRemoteSource {
    private val pages = ArrayDeque(pages.toList())
    var failure = false
    override suspend fun load(page: Int, pageSize: Int, status: HistoryStatus?): HistoryPage {
        if (failure) throw java.io.IOException("offline")
        return pages.removeFirstOrNull() ?: HistoryPage(0, page, pageSize, emptyList())
    }
}

private class SuspendingHistoryRemote(private val response: CompletableDeferred<HistoryPage>) : HistoryRemoteSource {
    override suspend fun load(page: Int, pageSize: Int, status: HistoryStatus?): HistoryPage = response.await()
}

private class SequencedSuspendingRemote(
    private val first: CompletableDeferred<HistoryPage>,
    private val second: CompletableDeferred<HistoryPage>,
) : HistoryRemoteSource {
    private var calls = 0
    override suspend fun load(page: Int, pageSize: Int, status: HistoryStatus?): HistoryPage =
        if (calls++ == 0) first.await() else second.await()
}
