package com.vocaease.patient.feature.catalog

import com.vocaease.patient.core.network.dto.Gender
import com.vocaease.patient.core.network.dto.PatientProfile
import com.vocaease.patient.core.network.dto.PrimaryDoctor
import com.vocaease.patient.core.network.dto.PublicationStatus
import com.vocaease.patient.core.network.dto.SingingSummary
import com.vocaease.patient.core.network.dto.Song
import com.vocaease.patient.core.network.dto.TreatmentPlan
import com.vocaease.patient.core.network.dto.TreatmentPlanStatus
import com.vocaease.patient.core.network.dto.TreatmentProgress
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogViewModelTest {
    @Test
    fun `刷新使用服务端治疗进度和终身汇总`() = runBlocking {
        val patientRemote = FakePatientRemote(profile(progressPercent = "33.33"))
        val songsRemote = FakeSongRemote(pages = mutableMapOf(1 to songPage(32, 1, song("小幸运"))))
        val viewModel = viewModel(patientRemote, songsRemote)

        viewModel.refresh()

        assertEquals("Voca", viewModel.state.value.patientName)
        assertEquals(8, viewModel.state.value.treatmentProgress?.completedCount)
        assertEquals(24, viewModel.state.value.treatmentProgress?.targetCount)
        assertEquals(33.33f, viewModel.state.value.treatmentProgress?.percent ?: -1f, 0.001f)
        assertEquals(3, viewModel.state.value.treatmentProgress?.currentWeek)
        assertEquals(28, viewModel.state.value.lifetimeCompletedSongs)
        assertEquals(8_640, viewModel.state.value.lifetimeDurationSeconds)
        assertEquals(listOf("小幸运"), viewModel.state.value.songs.map { it.title })
    }

    @Test
    fun `歌曲分页固定查询参数且刷新回到第一页`() = runBlocking {
        val remote = FakeSongRemote(
            pages = mutableMapOf(
                1 to songPage(21, 1, song("第一首")),
                2 to songPage(21, 2, song("第二首")),
            ),
        )
        val pagingSource = SongPagingSource(remote, " 舒缓 ")

        assertEquals(1, pagingSource.refreshKey())
        assertEquals("第一首", pagingSource.load(1).songs.single().title)
        assertEquals("第二首", pagingSource.load(2).songs.single().title)
        pagingSource.load(pagingSource.refreshKey())

        assertEquals(
            listOf(
                SongPageQuery(keyword = "舒缓", page = 1, pageSize = 20, sort = "-created_at"),
                SongPageQuery(keyword = "舒缓", page = 2, pageSize = 20, sort = "-created_at"),
                SongPageQuery(keyword = "舒缓", page = 1, pageSize = 20, sort = "-created_at"),
            ),
            remote.requests,
        )
    }

    @Test
    fun `搜索失败保留旧歌曲并允许按原关键词重试`() = runBlocking {
        val remote = FakeSongRemote(mutableMapOf(1 to songPage(21, 1, song("旧歌曲"))))
        val viewModel = viewModel(FakePatientRemote(profile()), remote)
        viewModel.refresh()
        remote.failure = IOException("offline")

        viewModel.search("新歌")

        assertEquals(listOf("旧歌曲"), viewModel.state.value.songs.map { it.title })
        assertEquals("歌曲加载失败，请重试", viewModel.state.value.errorMessage)
        assertEquals("新歌", viewModel.state.value.keyword)
        assertFalse(viewModel.state.value.canLoadMore)

        remote.failure = null
        remote.pages[1] = songPage(1, 1, song("新歌曲"))
        viewModel.retrySongs()

        assertEquals(listOf("新歌曲"), viewModel.state.value.songs.map { it.title })
        assertNull(viewModel.state.value.errorMessage)
        assertEquals("新歌", remote.requests.last().keyword)
    }

    @Test
    fun `无活动计划时首页禁用歌曲训练`() = runBlocking {
        val viewModel = viewModel(
            FakePatientRemote(profile(activePlan = false)),
            FakeSongRemote(mutableMapOf(1 to songPage(1, 1, song("晴天")))),
        )

        viewModel.refresh()

        assertFalse(viewModel.state.value.hasActiveTreatmentPlan)
        assertNull(viewModel.state.value.treatmentProgress)
        assertFalse(viewModel.state.value.canStartTraining)
    }

    @Test
    fun `进度语义始终限制在零到一百`() = runBlocking {
        val high = viewModel(
            FakePatientRemote(profile(progressPercent = "130.75")),
            FakeSongRemote(mutableMapOf(1 to songPage(0, 1))),
        )
        high.refresh()
        assertEquals(100f, high.state.value.treatmentProgress?.percent ?: -1f, 0f)

        val low = viewModel(
            FakePatientRemote(profile(progressPercent = "-2")),
            FakeSongRemote(mutableMapOf(1 to songPage(0, 1))),
        )
        low.refresh()
        assertEquals(0f, low.state.value.treatmentProgress?.percent ?: -1f, 0f)
        assertTrue(low.state.value.hasActiveTreatmentPlan)
    }

    @Test
    fun `较慢的旧搜索结果不能覆盖较新的搜索`() = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val remote = SongRemoteDataSource { query ->
            if (query.keyword == "慢") {
                slowStarted.complete(Unit)
                releaseSlow.await()
                songPage(1, 1, song("慢结果"))
            } else {
                songPage(1, 1, song("快结果"))
            }
        }
        val viewModel = viewModel(FakePatientRemote(profile()), remote)

        val slow = async { viewModel.search("慢") }
        slowStarted.await()
        val fast = async { viewModel.search("快") }
        fast.await()
        releaseSlow.complete(Unit)
        slow.await()

        assertEquals("快", viewModel.state.value.keyword)
        assertEquals(listOf("快结果"), viewModel.state.value.songs.map { it.title })
    }

    private fun viewModel(
        patientRemote: PatientRemoteDataSource,
        songRemote: SongRemoteDataSource,
    ) = CatalogViewModel(
        patientRepository = PatientRepository(patientRemote),
        songRepository = SongRepository { keyword -> SongPagingSource(songRemote, keyword) },
        dispatcher = Dispatchers.Unconfined,
    )
}

internal class FakePatientRemote(
    var value: PatientProfile,
) : PatientRemoteDataSource {
    override suspend fun fetchMe(): PatientProfile = value
}

internal class FakeSongRemote(
    val pages: MutableMap<Int, SongPage>,
) : SongRemoteDataSource {
    val requests = mutableListOf<SongPageQuery>()
    var failure: IOException? = null

    override suspend fun fetchSongs(query: SongPageQuery): SongPage {
        requests += query
        failure?.let { throw it }
        return requireNotNull(pages[query.page])
    }
}

internal fun profile(
    progressPercent: String = "33.33",
    activePlan: Boolean = true,
): PatientProfile = PatientProfile(
    id = UUID.fromString("10000000-0000-0000-0000-000000000001"),
    medicalRecordNo = "MR-001",
    name = "Voca",
    gender = Gender.FEMALE,
    enrollmentAge = 30,
    phone = "",
    notes = "",
    primaryDoctor = PrimaryDoctor(
        UUID.fromString("20000000-0000-0000-0000-000000000001"),
        "医生",
    ),
    activeTreatmentPlan = if (activePlan) {
        TreatmentPlan(
            id = UUID.fromString("30000000-0000-0000-0000-000000000001"),
            startDate = LocalDate.parse("2026-08-01"),
            cycleWeeks = 12,
            targetSessionCount = 24,
            status = TreatmentPlanStatus.ACTIVE,
        )
    } else {
        null
    },
    treatmentProgress = if (activePlan) TreatmentProgress(8, 24, progressPercent, 3) else null,
    singingSummary = SingingSummary(28, 8_640),
)

internal fun song(title: String): Song = Song(
    id = UUID.nameUUIDFromBytes(title.toByteArray()),
    title = title,
    artist = "歌手",
    genre = "流行",
    language = "zh-CN",
    durationSeconds = 265,
    analysisStatus = null,
    publicationStatus = PublicationStatus.PUBLISHED,
    uploadedAt = Instant.parse("2026-08-01T00:00:00Z"),
)

internal fun songPage(count: Int, page: Int, vararg songs: Song) = SongPage(
    totalCount = count,
    page = page,
    pageSize = 20,
    songs = songs.toList(),
)
