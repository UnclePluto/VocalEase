package com.vocaease.patient.feature.catalog

import com.vocaease.patient.core.network.PatientApi
import com.vocaease.patient.core.network.dto.Song
import com.vocaease.patient.core.network.dto.toDomain

data class SongPageQuery(
    val keyword: String?,
    val page: Int,
    val pageSize: Int,
    val sort: String,
)

data class SongPage(
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val songs: List<Song>,
)

fun interface SongRemoteDataSource {
    suspend fun fetchSongs(query: SongPageQuery): SongPage
}

class VocaEaseSongRemoteDataSource(
    private val api: PatientApi,
) : SongRemoteDataSource {
    override suspend fun fetchSongs(query: SongPageQuery): SongPage = api.songs(
        page = query.page,
        pageSize = query.pageSize,
        keyword = query.keyword,
        sort = query.sort,
    ).data.let { page ->
        SongPage(
            totalCount = page.count,
            page = page.page,
            pageSize = page.pageSize,
            songs = page.results.map { it.toDomain() },
        )
    }
}

class SongPagingSource(
    private val remote: SongRemoteDataSource,
    keyword: String,
) {
    private val keyword = keyword.trim().ifEmpty { null }

    suspend fun load(page: Int): SongPage {
        require(page >= FIRST_PAGE) { "页码必须从 1 开始" }
        return remote.fetchSongs(
            SongPageQuery(
                keyword = keyword,
                page = page,
                pageSize = PAGE_SIZE,
                sort = SORT,
            ),
        )
    }

    fun refreshKey(): Int = FIRST_PAGE

    companion object {
        const val PAGE_SIZE = 20
        const val SORT = "-created_at"
        private const val FIRST_PAGE = 1
    }
}
