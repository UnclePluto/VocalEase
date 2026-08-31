package com.vocaease.patient.feature.history

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryViewModelTest {
    @Test
    fun `加载更多双击只提交一个下一页请求`() {
        val dispatcher = QueuedDispatcher()
        val remote = RecordingPagingRemote()
        val repository = HistoryRepository(HistoryLocalSource { emptyList() }, remote) { true }
        val viewModel = HistoryViewModel(repository, dispatcher)
        viewModel.start()
        dispatcher.runAll()

        viewModel.loadMore()
        viewModel.loadMore()
        dispatcher.runAll()

        assertEquals(listOf(1, 2), remote.requestedPages)
        assertEquals(listOf("s1", "s2"), viewModel.state.value.items.map { it.sessionId }.sorted())
    }

    @Test
    fun `下一页失败后再次加载仍请求同一页`() {
        val dispatcher = QueuedDispatcher()
        val remote = RecordingPagingRemote(failingPages = mutableSetOf(2))
        val repository = HistoryRepository(HistoryLocalSource { emptyList() }, remote) { true }
        val viewModel = HistoryViewModel(repository, dispatcher)
        viewModel.start()
        dispatcher.runAll()

        viewModel.loadMore()
        dispatcher.runAll()
        remote.failingPages.clear()
        viewModel.loadMore()
        dispatcher.runAll()

        assertEquals(listOf(1, 2, 2), remote.requestedPages)
        assertEquals(listOf("s1", "s2"), viewModel.state.value.items.map { it.sessionId }.sorted())
    }
}

private class QueuedDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { tasks += block }
    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

private class RecordingPagingRemote(
    val failingPages: MutableSet<Int> = mutableSetOf(),
) : HistoryRemoteSource {
    val requestedPages = mutableListOf<Int>()
    override suspend fun load(page: Int, pageSize: Int, status: HistoryStatus?): HistoryPage {
        requestedPages += page
        if (page in failingPages) throw java.io.IOException("offline")
        return HistoryPage(
            count = 3,
            page = page,
            pageSize = pageSize,
            results = listOf(
                HistoryRemoteRecord(
                    sessionId = "s$page",
                    songTitle = "歌曲$page",
                    artist = "歌手",
                    durationSeconds = 30,
                    status = HistoryStatus.COMPLETED,
                    score = 88.0,
                    analysisGeneration = 1,
                    updatedAtEpochMillis = page.toLong(),
                ),
            ),
        )
    }
}
