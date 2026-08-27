package com.vocaease.patient.feature.catalog

import com.vocaease.patient.core.network.dto.Song
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

data class SongCatalogSnapshot(
    val songs: List<Song> = emptyList(),
    val totalCount: Int = 0,
    val keyword: String = "",
    val nextPage: Int? = null,
    val errorMessage: String? = null,
)

class SongRepository(
    private val pagingSourceFactory: (String) -> SongPagingSource,
) {
    private val mutableSnapshot = MutableStateFlow(SongCatalogSnapshot())
    val snapshot: StateFlow<SongCatalogSnapshot> = mutableSnapshot.asStateFlow()
    private var pagingSource = pagingSourceFactory("")
    private var requestedKeyword = ""
    private var requestGeneration = 0L
    private val mutex = Mutex()

    suspend fun refresh() {
        refresh(mutex.withLock { requestedKeyword })
    }

    suspend fun refresh(keyword: String) {
        val request = mutex.withLock {
            requestedKeyword = keyword.trim()
            requestGeneration += 1
            RefreshRequest(
                generation = requestGeneration,
                keyword = requestedKeyword,
                source = pagingSourceFactory(requestedKeyword),
            )
        }
        try {
            val page = request.source.load(request.source.refreshKey())
            mutex.withLock {
                if (requestGeneration == request.generation) {
                    pagingSource = request.source
                    mutableSnapshot.value = page.toSnapshot(request.keyword)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            publishRefreshFailure(request)
        } catch (_: HttpException) {
            publishRefreshFailure(request)
        } catch (_: SerializationException) {
            publishRefreshFailure(request)
        }
    }

    suspend fun retry() = refresh(mutex.withLock { requestedKeyword })

    suspend fun loadMore() {
        val request = mutex.withLock {
            val current = mutableSnapshot.value
            val nextPage = current.nextPage ?: return
            LoadMoreRequest(requestGeneration, pagingSource, current.keyword, nextPage)
        }
        try {
            val page = request.source.load(request.page)
            mutex.withLock {
                val current = mutableSnapshot.value
                if (requestGeneration == request.generation && current.keyword == request.keyword) {
                    mutableSnapshot.value = current.copy(
                        songs = (current.songs + page.songs).distinctBy { it.id },
                        totalCount = page.totalCount,
                        nextPage = page.nextPage(),
                        errorMessage = null,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            publishLoadMoreFailure(request)
        } catch (_: HttpException) {
            publishLoadMoreFailure(request)
        } catch (_: SerializationException) {
            publishLoadMoreFailure(request)
        }
    }

    private fun SongPage.toSnapshot(keyword: String) = SongCatalogSnapshot(
        songs = songs,
        totalCount = totalCount,
        keyword = keyword,
        nextPage = nextPage(),
        errorMessage = null,
    )

    private fun SongPage.nextPage(): Int? =
        if (page * pageSize < totalCount) page + 1 else null

    private suspend fun publishRefreshFailure(request: RefreshRequest) = mutex.withLock {
        if (requestGeneration == request.generation) {
            retainSongsWithFailure(request.keyword)
        }
    }

    private suspend fun publishLoadMoreFailure(request: LoadMoreRequest) = mutex.withLock {
        if (requestGeneration == request.generation && mutableSnapshot.value.keyword == request.keyword) {
            retainSongsWithFailure(request.keyword)
        }
    }

    private fun retainSongsWithFailure(keyword: String) {
        mutableSnapshot.value = mutableSnapshot.value.copy(
            keyword = keyword,
            nextPage = null,
            errorMessage = "歌曲加载失败，请重试",
        )
    }

    private data class RefreshRequest(
        val generation: Long,
        val keyword: String,
        val source: SongPagingSource,
    )

    private data class LoadMoreRequest(
        val generation: Long,
        val source: SongPagingSource,
        val keyword: String,
        val page: Int,
    )
}
