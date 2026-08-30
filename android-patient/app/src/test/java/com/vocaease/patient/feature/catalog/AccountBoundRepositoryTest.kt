package com.vocaease.patient.feature.catalog

import com.vocaease.patient.core.database.AuthenticatedAccountLease
import com.vocaease.patient.core.database.AccountLeaseListenerRegistration
import com.vocaease.patient.core.database.AuthenticatedAccountSession
import com.vocaease.patient.core.database.StaleAccountScopeException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBoundRepositoryTest {
    @Test
    fun `换号立即原子清空患者和歌曲缓存且相同登录名不能复用旧计划`() = runBlocking {
        val session = TestAuthenticatedAccountSession()
        session.authenticate(PATIENT_A, "incarnation-a")
        val patientRemote = QueuePatientRemote().apply { enqueue(profile(patientId = UUID.fromString(PATIENT_A), name = "患者A")) }
        val songRemote = QueueSongRemote().apply { enqueue(songPage(1, 1, song("A的歌曲"))) }
        val patientRepository = PatientRepository(patientRemote, session)
        val songRepository = SongRepository(session) { keyword -> SongPagingSource(songRemote, keyword) }

        patientRepository.refreshMe()
        songRepository.refresh()
        assertEquals("患者A", patientRepository.profile.value?.name)
        assertEquals("A的歌曲", songRepository.snapshot.value.songs.single().title)

        // loginId刻意相同；缓存边界只认patient UUID和incarnation。
        session.authenticate(PATIENT_B, "incarnation-b", loginId = "same-login-id")

        assertNull(patientRepository.profile.value)
        assertSame(PatientLoadState.Initial, patientRepository.state.value)
        assertTrue(songRepository.snapshot.value.songs.isEmpty())
        assertEquals("", songRepository.snapshot.value.keyword)
        assertNull(songRepository.snapshot.value.errorMessage)
        patientRemote.enqueue(profile(patientId = UUID.fromString(PATIENT_B), name = "患者B", activePlan = false))
        songRemote.enqueue(songPage(1, 1, song("B的歌曲")))
        patientRepository.refreshMe()
        songRepository.refresh()
        val b = patientRepository.state.value as PatientLoadState.Content
        assertEquals("患者B", b.profile.name)
        assertNull(b.profile.treatmentProgress)
        assertEquals("B的歌曲", songRepository.snapshot.value.songs.single().title)
    }

    @Test
    fun `旧账户和同账户旧incarnation的迟到响应都不能污染当前账户`() = runBlocking {
        suspend fun staleResponseIsIgnored(nextPatientId: String, nextIncarnation: String) {
            val session = TestAuthenticatedAccountSession()
            session.authenticate(PATIENT_A, "incarnation-a")
            val patientGate = CompletableDeferred<com.vocaease.patient.core.network.dto.PatientProfile>()
            val songGate = CompletableDeferred<SongPage>()
            val patientRemote = QueuePatientRemote().apply { enqueue(patientGate) }
            val songRemote = QueueSongRemote().apply { enqueue(songGate) }
            val patientRepository = PatientRepository(patientRemote, session)
            val songRepository = SongRepository(session) { keyword -> SongPagingSource(songRemote, keyword) }
            val oldPatient = async { patientRepository.refreshMe() }
            val oldSongs = async { songRepository.refresh("旧搜索") }
            patientRemote.awaitCall()
            songRemote.awaitCall()

            session.authenticate(nextPatientId, nextIncarnation)
            patientRemote.enqueue(profile(patientId = UUID.fromString(nextPatientId), name = "当前患者", activePlan = false))
            songRemote.enqueue(songPage(1, 1, song("当前歌曲")))
            val currentPatient = async { patientRepository.refreshMe() }
            val currentSongs = async { songRepository.refresh("当前搜索") }
            currentPatient.await()
            currentSongs.await()
            patientGate.complete(profile(patientId = UUID.fromString(PATIENT_A), name = "旧患者"))
            songGate.complete(songPage(1, 1, song("旧歌曲")))
            oldPatient.await()
            oldSongs.await()

            assertEquals("当前患者", patientRepository.profile.value?.name)
            assertFalse((patientRepository.state.value as PatientLoadState.Content).profile.activeTreatmentPlan != null)
            assertEquals("当前搜索", songRepository.snapshot.value.keyword)
            assertEquals("当前歌曲", songRepository.snapshot.value.songs.single().title)
        }

        staleResponseIsIgnored(PATIENT_B, "incarnation-b")
        staleResponseIsIgnored(PATIENT_A, "incarnation-a-new")
    }

    @Test
    fun `患者状态明确区分首帧加载成功无计划和失败保留旧内容`() = runBlocking {
        val session = TestAuthenticatedAccountSession().apply { authenticate(PATIENT_A, "incarnation-a") }
        val remote = QueuePatientRemote()
        val repository = PatientRepository(remote, session)
        assertSame(PatientLoadState.Initial, repository.state.value)

        val loadingGate = CompletableDeferred<com.vocaease.patient.core.network.dto.PatientProfile>()
        remote.enqueue(loadingGate)
        val loading = async { repository.refreshMe() }
        remote.awaitCall()
        assertTrue(repository.state.value is PatientLoadState.Loading)
        loadingGate.complete(profile(patientId = UUID.fromString(PATIENT_A), name = "无计划患者", activePlan = false))
        loading.await()
        assertNull((repository.state.value as PatientLoadState.Content).profile.treatmentProgress)

        remote.enqueueFailure()
        repository.refreshMe()
        val retained = repository.state.value as PatientLoadState.Error
        assertEquals("无计划患者", retained.previous?.name)
        assertEquals("患者信息加载失败，请重试", retained.message)

        session.authenticate(PATIENT_B, "incarnation-b")
        remote.enqueueFailure()
        repository.refreshMe()
        val emptyFailure = repository.state.value as PatientLoadState.Error
        assertNull(emptyFailure.previous)
    }

    @Test
    fun `已开始的retry不能在较新搜索后反写旧关键词`() = runBlocking {
        val session = TestAuthenticatedAccountSession().apply { authenticate(PATIENT_A, "incarnation-a") }
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val blockFirstOldFactory = AtomicBoolean(true)
        val remote = SongRemoteDataSource { query ->
            songPage(1, query.page, song("${query.keyword}结果"))
        }
        val repository = SongRepository(session) { keyword ->
            if (keyword == "旧关键词" && blockFirstOldFactory.compareAndSet(true, false)) {
                factoryEntered.countDown()
                check(releaseFactory.await(5, TimeUnit.SECONDS))
            }
            SongPagingSource(remote, keyword)
        }

        val oldRefresh = async(Dispatchers.Default) { repository.refresh("旧关键词") }
        assertTrue(factoryEntered.await(5, TimeUnit.SECONDS))
        val retryInvoked = CompletableDeferred<Unit>()
        val retry = async(Dispatchers.Default) {
            retryInvoked.complete(Unit)
            repository.retry()
        }
        retryInvoked.await()
        delay(50)
        val latestSearch = async(Dispatchers.Default) { repository.refresh("最新搜索") }
        delay(50)
        releaseFactory.countDown()
        oldRefresh.await()
        retry.await()
        latestSearch.await()

        assertEquals("最新搜索", repository.snapshot.value.keyword)
        assertEquals("最新搜索结果", repository.snapshot.value.songs.single().title)
    }

    @Test
    fun `当前患者请求取消后不能永久停留在Loading`() = runBlocking {
        val session = TestAuthenticatedAccountSession().apply { authenticate(PATIENT_A, "incarnation-a") }
        val gate = CompletableDeferred<com.vocaease.patient.core.network.dto.PatientProfile>()
        val remote = QueuePatientRemote().apply { enqueue(gate) }
        val repository = PatientRepository(remote, session)
        val refresh = launch { repository.refreshMe() }
        remote.awaitCall()
        assertTrue(repository.state.value is PatientLoadState.Loading)

        refresh.cancelAndJoin()

        assertSame(PatientLoadState.Initial, repository.state.value)
    }

    @Test
    fun `当前lease首次收到异UUID患者必须阻断发布和训练`() = runBlocking {
        val session = TestAuthenticatedAccountSession().apply { authenticate(PATIENT_A, "incarnation-a") }
        val repository = PatientRepository(
            PatientRemoteDataSource { profile(patientId = UUID.fromString(PATIENT_B), name = "错误患者") },
            session,
        )

        val result = repository.refreshMe()

        assertSame(PatientRefreshResult.Failure, result)
        assertNull(repository.profile.value)
        val error = repository.state.value as PatientLoadState.Error
        assertNull(error.previous)
        assertEquals("患者身份校验失败，请重新登录", error.message)
        assertFalse(error.message.contains(PATIENT_A))
        assertFalse(error.message.contains(PATIENT_B))
    }

    @Test
    fun `异UUID响应不能替换同lease已有安全患者内容`() = runBlocking {
        val session = TestAuthenticatedAccountSession().apply { authenticate(PATIENT_A, "incarnation-a") }
        val remote = QueuePatientRemote().apply {
            enqueue(profile(patientId = UUID.fromString(PATIENT_A), name = "安全患者"))
            enqueue(profile(patientId = UUID.fromString(PATIENT_B), name = "错误患者", activePlan = false))
        }
        val repository = PatientRepository(remote, session)
        repository.refreshMe()

        val result = repository.refreshMe()

        assertSame(PatientRefreshResult.Failure, result)
        assertEquals("安全患者", repository.profile.value?.name)
        val error = repository.state.value as PatientLoadState.Error
        assertEquals("安全患者", error.previous?.name)
        assertTrue(error.previous?.activeTreatmentPlan != null)
        assertEquals("患者身份校验失败，请重新登录", error.message)
    }

    @Test
    fun `UUID类型比较不受构造字符串大小写影响`() = runBlocking {
        val session = TestAuthenticatedAccountSession().apply { authenticate(PATIENT_A, "incarnation-a") }
        val uppercaseUuid = UUID.fromString(PATIENT_A.uppercase())
        val repository = PatientRepository(PatientRemoteDataSource { profile(patientId = uppercaseUuid) }, session)

        val result = repository.refreshMe()

        assertTrue(result is PatientRefreshResult.Success)
        assertEquals(uppercaseUuid, repository.profile.value?.id)
    }

    private companion object {
        const val PATIENT_A = "11111111-1111-4111-8111-111111111111"
        const val PATIENT_B = "22222222-2222-4222-8222-222222222222"
    }
}

internal class TestAuthenticatedAccountSession : AuthenticatedAccountSession {
    private val listeners = mutableListOf<(AuthenticatedAccountLease?) -> Unit>()
    @Volatile private var lease: AuthenticatedAccountLease? = null

    fun authenticate(patientId: String, incarnationId: String, loginId: String = "unused") {
        check(loginId.isNotBlank())
        lease = AuthenticatedAccountLease(patientId, incarnationId)
        listeners.forEach { it(lease) }
    }

    override fun current(): AuthenticatedAccountLease? = lease

    override fun addLeaseChangedListener(
        listener: (AuthenticatedAccountLease?) -> Unit,
    ): AccountLeaseListenerRegistration {
        listeners += listener
        listener(lease)
        return AccountLeaseListenerRegistration { listeners.remove(listener) }
    }

    override suspend fun <T> withCurrentLease(
        expected: AuthenticatedAccountLease,
        operation: suspend () -> T,
    ): T {
        if (lease !== expected) throw StaleAccountScopeException()
        return operation()
    }
}

private class QueuePatientRemote : PatientRemoteDataSource {
    private val responses = ArrayDeque<ResultOrGate<com.vocaease.patient.core.network.dto.PatientProfile>>()
    private var calls = 0
    private var waiter = CompletableDeferred<Unit>()

    fun enqueue(value: com.vocaease.patient.core.network.dto.PatientProfile) {
        responses += ResultOrGate.Value(value)
    }

    fun enqueue(gate: CompletableDeferred<com.vocaease.patient.core.network.dto.PatientProfile>) {
        responses += ResultOrGate.Gate(gate)
    }

    fun enqueueFailure() {
        responses += ResultOrGate.Failure
    }

    suspend fun awaitCall() {
        if (calls == 0) waiter.await()
        calls = 0
        waiter = CompletableDeferred()
    }

    override suspend fun fetchMe(): com.vocaease.patient.core.network.dto.PatientProfile {
        calls += 1
        waiter.complete(Unit)
        return when (val response = responses.removeFirst()) {
            is ResultOrGate.Value -> response.value
            is ResultOrGate.Gate -> response.gate.await()
            ResultOrGate.Failure -> throw java.io.IOException("offline")
        }
    }
}

private class QueueSongRemote : SongRemoteDataSource {
    private val responses = ArrayDeque<ResultOrGate<SongPage>>()
    private var calls = 0
    private var waiter = CompletableDeferred<Unit>()

    fun enqueue(value: SongPage) {
        responses += ResultOrGate.Value(value)
    }

    fun enqueue(gate: CompletableDeferred<SongPage>) {
        responses += ResultOrGate.Gate(gate)
    }

    suspend fun awaitCall() {
        if (calls == 0) waiter.await()
        calls = 0
        waiter = CompletableDeferred()
    }

    override suspend fun fetchSongs(query: SongPageQuery): SongPage {
        check(query.page >= 1)
        calls += 1
        waiter.complete(Unit)
        return when (val response = responses.removeFirst()) {
            is ResultOrGate.Value -> response.value
            is ResultOrGate.Gate -> response.gate.await()
            ResultOrGate.Failure -> throw java.io.IOException("offline")
        }
    }
}

private sealed interface ResultOrGate<out T> {
    data class Value<T>(val value: T) : ResultOrGate<T>
    data class Gate<T>(val gate: CompletableDeferred<T>) : ResultOrGate<T>
    data object Failure : ResultOrGate<Nothing>
}
