package com.vocaease.patient.feature.history

import java.util.concurrent.atomic.AtomicBoolean
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
    fun `本地上传失败与服务端分析失败使用不同状态文案`() {
        assertEquals("上传失败", statusLabel(HistoryStatus.UPLOAD_FAILED))
        assertEquals("分析失败", statusLabel(HistoryStatus.FAILED))
    }

    @Test
    fun `历史元信息按设计稿显示今天时间或月日而非歌手`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 8, 31, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val today = remote("today", HistoryStatus.COMPLETED).toHistoryItemForTest(
            ZonedDateTime.of(2026, 8, 31, 9, 42, 0, 0, zone).toInstant().toEpochMilli(),
        )
        val old = remote("old", HistoryStatus.COMPLETED).toHistoryItemForTest(
            ZonedDateTime.of(2026, 8, 24, 9, 42, 0, 0, zone).toInstant().toEpochMilli(),
        )

        assertEquals("今天 09:42 · 0:30", historyMeta(today, now, zone))
        assertEquals("8 月 24 日 · 0:30", historyMeta(old, now, zone))
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

        first.complete(HistoryPage(1, 1, 20, listOf(remote("old", HistoryStatus.COMPLETED))))
        old.await()
        second.complete(HistoryPage(1, 1, 20, listOf(remote("new", HistoryStatus.COMPLETED))))
        latest.await()

        assertEquals(listOf("new"), repository.state.value.items.map { it.sessionId })
    }

    @Test
    fun `并发分页按请求顺序原子合并且不会跳过中间页`() = runBlocking {
        val pageTwo = CompletableDeferred<HistoryPage>()
        val pageTwoEntered = CompletableDeferred<Unit>()
        val pageThreeEntered = CompletableDeferred<Unit>()
        val source = OutOfOrderPagingRemote(pageTwo, pageTwoEntered, pageThreeEntered)
        val repository = HistoryRepository(EmptyHistoryLocal, source) { true }
        repository.start()
        repository.refresh(page = 1, pageSize = 1)

        val second = async { repository.refresh(page = 2, pageSize = 1) }
        pageTwoEntered.await()
        val third = async(start = CoroutineStart.UNDISPATCHED) { repository.refresh(page = 3, pageSize = 1) }
        pageTwo.complete(HistoryPage(3, 2, 1, listOf(remote("s2", HistoryStatus.COMPLETED))))
        second.await()
        third.await()

        assertEquals(listOf("s1", "s2", "s3"), repository.state.value.items.map { it.sessionId }.sorted())
    }

    @Test
    fun `加载下一页期间page1刷新必须等待同一分页边界`() = runBlocking {
        val pageTwo = CompletableDeferred<HistoryPage>()
        val pageTwoEntered = CompletableDeferred<Unit>()
        val refreshEntered = CompletableDeferred<Unit>()
        val source = RefreshBoundaryRemote(pageTwo, pageTwoEntered, refreshEntered)
        val repository = HistoryRepository(EmptyHistoryLocal, source) { true }
        repository.start()
        repository.refresh(page = 1, pageSize = 1)

        val next = async { repository.refresh(page = 2, pageSize = 1) }
        pageTwoEntered.await()
        val refresh = async { repository.refresh(page = 1, pageSize = 1) }

        assertEquals(null, withTimeoutOrNull(300L) { refreshEntered.await() })
        pageTwo.complete(HistoryPage(3, 2, 1, listOf(remote("s2", HistoryStatus.COMPLETED))))
        next.await()
        refresh.await()
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

    private fun HistoryRemoteRecord.toHistoryItemForTest(updatedAt: Long) = HistoryItem(
        sessionId, null, songTitle, artist, durationSeconds, status, "88", analysisGeneration, updatedAt,
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

private class OutOfOrderPagingRemote(
    private val pageTwo: CompletableDeferred<HistoryPage>,
    private val pageTwoEntered: CompletableDeferred<Unit>,
    private val pageThreeEntered: CompletableDeferred<Unit>,
) : HistoryRemoteSource {
    override suspend fun load(page: Int, pageSize: Int, status: HistoryStatus?): HistoryPage = when (page) {
        1 -> HistoryPage(3, 1, 1, listOf(historyRemote("s1")))
        2 -> {
            pageTwoEntered.complete(Unit)
            pageTwo.await()
        }
        3 -> {
            pageThreeEntered.complete(Unit)
            HistoryPage(3, 3, 1, listOf(historyRemote("s3")))
        }
        else -> error("unexpected page")
    }
}

private class RefreshBoundaryRemote(
    private val pageTwo: CompletableDeferred<HistoryPage>,
    private val pageTwoEntered: CompletableDeferred<Unit>,
    private val refreshEntered: CompletableDeferred<Unit>,
) : HistoryRemoteSource {
    private var pageOneCalls = 0
    override suspend fun load(page: Int, pageSize: Int, status: HistoryStatus?): HistoryPage = when (page) {
        1 -> {
            pageOneCalls += 1
            if (pageOneCalls > 1) refreshEntered.complete(Unit)
            HistoryPage(3, 1, 1, listOf(historyRemote("s1")))
        }
        2 -> {
            pageTwoEntered.complete(Unit)
            pageTwo.await()
        }
        else -> error("unexpected page")
    }
}

private fun historyRemote(sessionId: String) = HistoryRemoteRecord(
    sessionId, "远端歌曲", "歌手", 30, HistoryStatus.COMPLETED, 88.0, 1, 30,
)
