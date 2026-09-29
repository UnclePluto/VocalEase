package com.vocaease.patient.feature.catalog

import com.vocaease.patient.core.network.dto.Song
import com.vocaease.patient.core.database.AuthenticatedAccountLease
import com.vocaease.patient.core.database.AuthenticatedAccountSession
import com.vocaease.patient.core.database.StaleAccountScopeException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.util.concurrent.atomic.AtomicLong

data class SongCatalogSnapshot(
    val songs: List<Song> = emptyList(),
    val totalCount: Int = 0,
    val keyword: String = "",
    val nextPage: Int? = null,
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
)

class SongRepository(
    private val accountSession: AuthenticatedAccountSession,
    private val pagingSourceFactory: (String) -> SongPagingSource,
) {
    private val lock = Any()
    private val mutableSnapshot = MutableStateFlow(SongCatalogSnapshot())
    val snapshot: StateFlow<SongCatalogSnapshot> = mutableSnapshot.asStateFlow()
    private var pagingSource = pagingSourceFactory("")
    private var requestedKeyword = ""
    private var requestGeneration = 0L
    private val invocationSequence = AtomicLong()
    private var latestStartedInvocation = 0L
    private var lease: AuthenticatedAccountLease? = accountSession.current()

    init {
        accountSession.addLeaseChangedListener(::accountChanged)
    }

    suspend fun refresh() {
        refreshInternal(keyword = null, invocation = invocationSequence.incrementAndGet())
    }

    suspend fun refresh(keyword: String) {
        refreshInternal(keyword.trim(), invocationSequence.incrementAndGet())
    }

    private suspend fun refreshInternal(keyword: String?, invocation: Long) {
        val request = synchronized(lock) {
            synchronizeLeaseLocked()
            val currentLease = lease ?: return
            if (invocation < latestStartedInvocation) return
            latestStartedInvocation = invocation
            if (keyword != null) requestedKeyword = keyword
            requestGeneration += 1
            mutableSnapshot.value = mutableSnapshot.value.copy(
                keyword = requestedKeyword,
                errorMessage = null,
                isLoading = true,
                isLoadingMore = false,
            )
            RefreshRequest(
                lease = currentLease,
                generation = requestGeneration,
                keyword = requestedKeyword,
                source = pagingSourceFactory(requestedKeyword),
            )
        }
        try {
            val page = request.source.load(request.source.refreshKey())
            accountSession.withCurrentLease(request.lease) {
                synchronized(lock) {
                    if (lease === request.lease && requestGeneration == request.generation) {
                        pagingSource = request.source
                        mutableSnapshot.value = page.toSnapshot(request.keyword)
                    }
                }
            }
        } catch (error: CancellationException) {
            restoreAfterCancellation(request)
            throw error
        } catch (_: StaleAccountScopeException) {
            Unit
        } catch (_: IOException) {
            publishRefreshFailure(request)
        } catch (_: HttpException) {
            publishRefreshFailure(request)
        } catch (_: SerializationException) {
            publishRefreshFailure(request)
        }
    }

    suspend fun retry() = refreshInternal(keyword = null, invocation = invocationSequence.incrementAndGet())

    suspend fun loadMore() {
        val request = synchronized(lock) {
            synchronizeLeaseLocked()
            val currentLease = lease ?: return
            val current = mutableSnapshot.value
            val nextPage = current.nextPage ?: return
            mutableSnapshot.value = current.copy(isLoadingMore = true, errorMessage = null)
            LoadMoreRequest(currentLease, requestGeneration, pagingSource, current.keyword, nextPage)
        }
        try {
            val page = request.source.load(request.page)
            accountSession.withCurrentLease(request.lease) {
                synchronized(lock) {
                    val current = mutableSnapshot.value
                    if (lease === request.lease && requestGeneration == request.generation && current.keyword == request.keyword) {
                        mutableSnapshot.value = current.copy(
                            songs = (current.songs + page.songs).distinctBy { it.id },
                            totalCount = page.totalCount,
                            nextPage = page.nextPage(),
                        errorMessage = null,
                        isLoadingMore = false,
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            restoreAfterLoadMoreCancellation(request)
            throw error
        } catch (_: StaleAccountScopeException) {
            Unit
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

    private fun publishRefreshFailure(request: RefreshRequest) = synchronized(lock) {
        if (lease === request.lease && requestGeneration == request.generation) {
            retainSongsWithFailure(request.keyword)
        }
    }

    private fun publishLoadMoreFailure(request: LoadMoreRequest) = synchronized(lock) {
        if (lease === request.lease && requestGeneration == request.generation && mutableSnapshot.value.keyword == request.keyword) {
            retainSongsWithFailure(request.keyword)
        }
    }

    private fun restoreAfterCancellation(request: RefreshRequest) = synchronized(lock) {
        if (lease === request.lease && requestGeneration == request.generation) {
            mutableSnapshot.value = mutableSnapshot.value.copy(isLoading = false)
        }
    }

    private fun restoreAfterLoadMoreCancellation(request: LoadMoreRequest) = synchronized(lock) {
        if (lease === request.lease && requestGeneration == request.generation) {
            mutableSnapshot.value = mutableSnapshot.value.copy(isLoadingMore = false)
        }
    }

    private fun accountChanged(updated: AuthenticatedAccountLease?) = synchronized(lock) {
        if (lease !== updated) {
            lease = updated
            requestGeneration += 1
            requestedKeyword = ""
            pagingSource = pagingSourceFactory("")
            mutableSnapshot.value = SongCatalogSnapshot()
        }
    }

    private fun synchronizeLeaseLocked() {
        val current = accountSession.current()
        if (lease !== current) accountChanged(current)
    }

    private fun retainSongsWithFailure(keyword: String) {
        mutableSnapshot.value = mutableSnapshot.value.copy(
            keyword = keyword,
            nextPage = null,
            errorMessage = "歌曲加载失败，请重试",
            isLoading = false,
            isLoadingMore = false,
        )
    }

    private data class RefreshRequest(
        val lease: AuthenticatedAccountLease,
        val generation: Long,
        val keyword: String,
        val source: SongPagingSource,
    )

    private data class LoadMoreRequest(
        val lease: AuthenticatedAccountLease,
        val generation: Long,
        val source: SongPagingSource,
        val keyword: String,
        val page: Int,
    )
}
