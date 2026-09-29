package com.vocaease.patient.feature.upload

import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadOrchestratorTest {
    @Test
    fun `同一draft并发调度只有一个副作用序列`() = runBlocking {
        val timeline = mutableListOf<String>()
        val store = FakeUploadStore(record(), timeline)
        val remote = FakeUploadRemote(timeline)
        val gate = GateUploader(timeline)
        val first = async { UploadOrchestrator(store, remote, gate, FakeLeases(timeline), {}, { 1_000 }).run() }
        gate.entered.await()
        val second = async { UploadOrchestrator(store, remote, gate, FakeLeases(timeline), {}, { 1_000 }).run() }
        yield()
        gate.release.complete(Unit)

        assertEquals(UploadRunResult.Analyzing, first.await())
        assertEquals(UploadRunResult.Analyzing, second.await())
        assertEquals(1, remote.grantCount[UploadMediaKind.AUDIO])
        assertEquals(1, remote.grantCount[UploadMediaKind.VIDEO])
        assertEquals(1, remote.submitCount)
        assertTrue(store.history.any { it.stage == UploadStage.UPLOADING_AUDIO && it.progressPercent in 1..50 })
        assertTrue(store.history.any { it.stage == UploadStage.UPLOADING_VIDEO && it.progressPercent in 51..100 })
    }

    @Test
    fun `SDK成功后本地receipt崩溃时READY对象跳过重grant和重传`() = runBlocking {
        val timeline = mutableListOf<String>()
        val recovering = record().copy(
            stage = UploadStage.UPLOADING_AUDIO,
            audioBinding = binding(UploadMediaKind.AUDIO),
        )
        val store = FakeUploadStore(recovering, timeline)
        val remote = FakeUploadRemote(timeline).apply { recoveryReadyKinds += UploadMediaKind.AUDIO }

        assertEquals(
            UploadRunResult.Analyzing,
            UploadOrchestrator(store, remote, FakeUploader(timeline), FakeLeases(timeline), {}, { 1_000 }).run(),
        )

        assertEquals(0, remote.grantCount.getOrDefault(UploadMediaKind.AUDIO, 0))
        assertTrue("upload:AUDIO" !in timeline)
        assertEquals(1, remote.confirmCount[UploadMediaKind.AUDIO])
        assertTrue(store.history.any { it.stage == UploadStage.WAITING_AUDIO_RECEIPT && it.audioUploaded })
    }

    @Test
    fun `双媒体链每个副作用前先落盘且最终只交付ANALYZING`() = runBlocking {
        val timeline = mutableListOf<String>()
        val store = FakeUploadStore(record(), timeline)
        val remote = FakeUploadRemote(timeline)
        val uploader = FakeUploader(timeline)
        val leases = FakeLeases(timeline)
        val orchestrator = UploadOrchestrator(store, remote, uploader, leases, { }, { 1_000L })

        assertEquals(UploadRunResult.Analyzing, orchestrator.run())

        assertEquals(
            listOf(
                "write:REQUESTING_AUDIO_GRANT", "grant:AUDIO:grant:draft-1:audio",
                "write:UPLOADING_AUDIO", "detail:1", "write:UPLOADING_AUDIO", "lease:AUDIO", "upload:AUDIO", "close:AUDIO",
                "write:WAITING_AUDIO_RECEIPT", "write:CONFIRMING_AUDIO", "confirm:AUDIO", "write:CONFIRMING_AUDIO", "detail:2",
                "write:REQUESTING_VIDEO_GRANT", "grant:VIDEO:grant:draft-1:video",
                "write:UPLOADING_VIDEO", "detail:3", "write:UPLOADING_VIDEO", "lease:VIDEO", "upload:VIDEO", "close:VIDEO",
                "write:WAITING_VIDEO_RECEIPT", "write:CONFIRMING_VIDEO", "confirm:VIDEO", "write:CONFIRMING_VIDEO", "detail:4",
                "write:SUBMITTING", "submit:submit:draft-1", "write:ANALYZING", "cleanup",
            ),
            timeline,
        )
        assertEquals(1, remote.grantCount[UploadMediaKind.AUDIO])
        assertEquals(1, remote.grantCount[UploadMediaKind.VIDEO])
        assertEquals(1, remote.submitCount)
    }

    @Test
    fun `可信回调冲突按2 5 10 30秒等待并复用同一资产`() = runBlocking {
        val timeline = mutableListOf<String>()
        val store = FakeUploadStore(
            record().copy(
                stage = UploadStage.WAITING_AUDIO_RECEIPT,
                audioBinding = binding(UploadMediaKind.AUDIO),
                audioUploaded = true,
            ),
            timeline,
        )
        val remote = FakeUploadRemote(timeline).apply { audioConflicts = 4 }
        val delays = mutableListOf<Long>()
        val orchestrator = UploadOrchestrator(store, remote, FakeUploader(timeline), FakeLeases(timeline), delays::add, { 1_000L })

        assertEquals(UploadRunResult.Analyzing, orchestrator.run())

        assertEquals(listOf(2_000L, 5_000L, 10_000L, 30_000L), delays)
        assertEquals(5, remote.confirmCount[UploadMediaKind.AUDIO])
        assertEquals(0, remote.grantCount.getOrDefault(UploadMediaKind.AUDIO, 0))
        assertEquals(
            listOf(1 to 3_000L, 2 to 6_000L, 3 to 11_000L, 4 to 31_000L),
            store.history.filter { it.stage == UploadStage.WAITING_AUDIO_RECEIPT && it.nextRetryAtEpochMillis != null }
                .map { it.attemptCount to requireNotNull(it.nextRetryAtEpochMillis) },
        )
        assertTrue(remote.confirmedBindings.all { it == binding(UploadMediaKind.AUDIO) || it == binding(UploadMediaKind.VIDEO) })
    }

    @Test
    fun `回调deadline落盘后重启只等待剩余时间`() = runBlocking {
        val timeline = mutableListOf<String>()
        val store = FakeUploadStore(
            record().copy(
                stage = UploadStage.WAITING_AUDIO_RECEIPT,
                audioBinding = binding(UploadMediaKind.AUDIO),
                audioUploaded = true,
                receiptWaitAttempt = 1,
                attemptCount = 1,
                nextRetryAtEpochMillis = 6_000,
            ),
            timeline,
        )
        var now = 4_000L
        val delays = mutableListOf<Long>()
        val orchestrator = UploadOrchestrator(
            store,
            FakeUploadRemote(timeline),
            FakeUploader(timeline),
            FakeLeases(timeline),
            wait = { delay -> delays += delay; now += delay },
            nowEpochMillis = { now },
        )

        assertEquals(UploadRunResult.Analyzing, orchestrator.run())
        assertEquals(2_000L, delays.first())
        assertTrue(store.history.any {
            it.stage == UploadStage.CONFIRMING_AUDIO && it.nextRetryAtEpochMillis == null && it.attemptCount == 1
        })
    }

    @Test
    fun `网络异常先持久化原阶段与下次重试时间再返回retry`() = runBlocking {
        val timeline = mutableListOf<String>()
        val store = FakeUploadStore(record(), timeline)
        val remote = FakeUploadRemote(timeline).apply { retryAudioGrant = true }

        assertEquals(
            UploadRunResult.Retry,
            UploadOrchestrator(store, remote, FakeUploader(timeline), FakeLeases(timeline), {}, { 1_000 }).run(),
        )
        assertEquals(UploadStage.WAITING_NETWORK, store.current.stage)
        assertEquals(UploadStage.REQUESTING_AUDIO_GRANT, store.current.resumeStage)
        assertEquals(1, store.current.attemptCount)
        assertEquals(31_000L, store.current.nextRetryAtEpochMillis)
        assertEquals("网络暂不可用，等待重试", store.current.safeError)
    }

    @Test
    fun `confirm响应迟到时不能覆盖已持久化暂停`() = runBlocking {
        val timeline = mutableListOf<String>()
        val store = FakeUploadStore(
            record().copy(
                stage = UploadStage.CONFIRMING_AUDIO,
                audioBinding = binding(UploadMediaKind.AUDIO),
                audioUploaded = true,
            ),
            timeline,
        )
        val remote = FakeUploadRemote(timeline).apply { blockAudioConfirm = true }
        val running = async {
            UploadOrchestrator(store, remote, FakeUploader(timeline), FakeLeases(timeline), {}, { 1_000 }).run()
        }
        remote.confirmEntered.await()
        store.current = store.current.copy(
            stage = UploadStage.PAUSED,
            resumeStage = UploadStage.CONFIRMING_AUDIO,
            safeError = RoomUploadStore.USER_PAUSED_MARKER,
        )
        remote.confirmRelease.complete(Unit)

        org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { running.await() }
        }
        assertEquals(UploadStage.PAUSED, store.current.stage)
        assertEquals(0, remote.grantCount.getOrDefault(UploadMediaKind.VIDEO, 0))
    }

    @Test
    fun `上传中进程重启同键重取过期凭证且不更换资产`() = runBlocking {
        val timeline = mutableListOf<String>()
        val expected = binding(UploadMediaKind.AUDIO)
        val store = FakeUploadStore(
            record().copy(stage = UploadStage.UPLOADING_AUDIO, audioBinding = expected),
            timeline,
        )
        val remote = FakeUploadRemote(timeline)
        val orchestrator = UploadOrchestrator(store, remote, FakeUploader(timeline), FakeLeases(timeline), { }, { 99_000L })

        assertEquals(UploadRunResult.Analyzing, orchestrator.run())

        assertEquals("grant:draft-1:audio", remote.grantKeys.single { it.second == UploadMediaKind.AUDIO }.first)
        assertEquals("detail:1", timeline.first())
        assertEquals("write:UPLOADING_AUDIO", timeline[1])
        assertEquals("grant:AUDIO:grant:draft-1:audio", timeline[2])
        assertEquals(expected, store.history.first { it.stage == UploadStage.UPLOADING_AUDIO }.audioBinding)
        assertTrue(store.history.any { it.stage == UploadStage.WAITING_AUDIO_RECEIPT && it.progressPercent == 50 })
    }

    @Test
    fun `grant与confirm任一身份或媒体不匹配都fail closed`() = runBlocking {
        val cases = listOf(
            binding(UploadMediaKind.AUDIO).copy(sessionId = "other"),
            binding(UploadMediaKind.AUDIO).copy(assetId = ""),
            binding(UploadMediaKind.AUDIO).copy(objectKey = ""),
            binding(UploadMediaKind.AUDIO).copy(mimeType = "video/mp4"),
            binding(UploadMediaKind.AUDIO).copy(sizeBytes = 99),
        )
        cases.forEach { malformed ->
            val store = FakeUploadStore(record(), mutableListOf())
            val remote = FakeUploadRemote(mutableListOf()).apply { audioGrant = grant(UploadMediaKind.AUDIO).copy(binding = malformed) }
            val result = UploadOrchestrator(store, remote, FakeUploader(mutableListOf()), FakeLeases(mutableListOf()), { }, { 1_000L }).run()
            assertEquals(UploadRunResult.TerminalFailure("上传凭证与本地录制不一致"), result)
            assertEquals(UploadStage.FAILED, store.current.stage)
        }
    }

    @Test
    fun `grant持久绑定后必须用服务端detail核对再向七牛发送token`() = runBlocking {
        val timeline = mutableListOf<String>()
        val store = FakeUploadStore(record(), timeline)
        val remote = FakeUploadRemote(timeline).apply {
            detailOverrides[1] = UploadSessionDetail(
                "session-1",
                RemoteSessionState.AWAITING_UPLOAD,
                listOf(binding(UploadMediaKind.AUDIO).copy(assetId = "other-asset").asSessionMedia(UploadMediaKind.AUDIO, RemoteMediaState.UPLOADING)),
            )
        }

        assertEquals(
            UploadRunResult.TerminalFailure("上传凭证与本地录制不一致"),
            UploadOrchestrator(store, remote, FakeUploader(timeline), FakeLeases(timeline), {}, { 1_000 }).run(),
        )
        assertTrue("detail:1" in timeline)
        assertTrue(timeline.none { it == "upload:AUDIO" })
    }

    @Test
    fun `confirm成功后必须再次用服务端detail核对确认状态`() = runBlocking {
        val timeline = mutableListOf<String>()
        val store = FakeUploadStore(record(), timeline)
        val remote = FakeUploadRemote(timeline).apply {
            detailOverrides[2] = UploadSessionDetail(
                "session-1",
                RemoteSessionState.AWAITING_UPLOAD,
                listOf(binding(UploadMediaKind.AUDIO).copy(mimeType = "video/mp4").asSessionMedia(UploadMediaKind.AUDIO, RemoteMediaState.READY)),
            )
        }

        assertEquals(
            UploadRunResult.TerminalFailure("上传凭证与本地录制不一致"),
            UploadOrchestrator(store, remote, FakeUploader(timeline), FakeLeases(timeline), {}, { 1_000 }).run(),
        )
        assertTrue("detail:2" in timeline)
        assertTrue(timeline.none { it == "grant:VIDEO:grant:draft-1:video" })
    }

    @Test
    fun `submit 409先读详情并只按裁决推进或同键重试`() = runBlocking {
        val states = listOf(
            RemoteSessionState.PROCESSING to 1,
            RemoteSessionState.COMPLETED to 1,
            RemoteSessionState.UPLOADED to 2,
        )
        states.forEach { (detail, expectedSubmits) ->
            val timeline = mutableListOf<String>()
            val store = FakeUploadStore(readyToSubmit(), timeline)
            val remote = FakeUploadRemote(timeline).apply {
                submitConflicts = 1
                conflictDetail = detail
            }
            val result = UploadOrchestrator(store, remote, FakeUploader(timeline), FakeLeases(timeline), { }, { 1_000L }).run()
            assertEquals(UploadRunResult.Analyzing, result)
            assertEquals(expectedSubmits, remote.submitCount)
            assertEquals(List(expectedSubmits) { "submit:draft-1" }, remote.submitKeys)
        }

        listOf(RemoteSessionState.CANCELLED, RemoteSessionState.UNKNOWN).forEach { detail ->
            val store = FakeUploadStore(readyToSubmit(), mutableListOf())
            val remote = FakeUploadRemote(mutableListOf()).apply { submitConflicts = 1; conflictDetail = detail }
            assertTrue(
                UploadOrchestrator(store, remote, FakeUploader(mutableListOf()), FakeLeases(mutableListOf()), { }, { 1_000L }).run()
                    is UploadRunResult.TerminalFailure,
            )
        }
    }

    private fun record() = UploadRecord(
        accountScopeHash = "scope-hash",
        draftId = "draft-1",
        sessionId = "session-1",
        songTitle = "练习歌曲",
        stage = UploadStage.WAITING_NETWORK,
        audio = UploadMedia("audio/mp4", 10),
        video = UploadMedia("video/mp4", 20),
        audioGrantKey = "grant:draft-1:audio",
        videoGrantKey = "grant:draft-1:video",
        submitKey = "submit:draft-1",
    )

    private fun readyToSubmit() = record().copy(
        stage = UploadStage.SUBMITTING,
        audioBinding = binding(UploadMediaKind.AUDIO),
        videoBinding = binding(UploadMediaKind.VIDEO),
        audioUploaded = true,
        videoUploaded = true,
        audioConfirmed = true,
        videoConfirmed = true,
    )

    private fun binding(kind: UploadMediaKind) = UploadBinding(
        sessionId = "session-1",
        assetId = if (kind == UploadMediaKind.AUDIO) "asset-a" else "asset-v",
        objectKey = if (kind == UploadMediaKind.AUDIO) "object-a" else "object-v",
        mimeType = if (kind == UploadMediaKind.AUDIO) "audio/mp4" else "video/mp4",
        sizeBytes = if (kind == UploadMediaKind.AUDIO) 10 else 20,
    )

    private fun grant(kind: UploadMediaKind) = UploadGrant(
        binding = binding(kind),
        expiresAtEpochMillis = Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(),
        uploadUrl = "https://upload.qiniup.com",
        uploadToken = "secret",
    )

    private class FakeUploadStore(initial: UploadRecord, private val timeline: MutableList<String>) : UploadStore {
        var current = initial
        val history = mutableListOf(initial)
        override suspend fun load(): UploadRecord = current
        override suspend fun checkpoint(expected: UploadRecord, next: UploadRecord): UploadRecord {
            if (current != expected) throw UploadSupersededException()
            current = next
            history += next
            timeline += "write:${next.stage.name}"
            return next
        }
        override suspend fun checkpointProgress(expected: UploadRecord, progressPercent: Int): UploadRecord {
            if (current != expected) throw UploadSupersededException()
            if (progressPercent <= current.progressPercent) return current
            current = current.copy(progressPercent = progressPercent, operationVersion = current.operationVersion + 1)
            history += current
            return current
        }
        override suspend fun finishLocalCleanup() { timeline += "cleanup" }
    }

    private class FakeUploadRemote(private val timeline: MutableList<String>) : UploadRemote {
        val grantCount = mutableMapOf<UploadMediaKind, Int>()
        val confirmCount = mutableMapOf<UploadMediaKind, Int>()
        val grantKeys = mutableListOf<Pair<String, UploadMediaKind>>()
        val confirmedBindings = mutableListOf<UploadBinding>()
        val submitKeys = mutableListOf<String>()
        var submitCount = 0
        var audioConflicts = 0
        var submitConflicts = 0
        var conflictDetail = RemoteSessionState.PROCESSING
        var audioGrant = grantFor(UploadMediaKind.AUDIO)
        var retryAudioGrant = false
        var detailCount = 0
        val detailOverrides = mutableMapOf<Int, UploadSessionDetail>()
        val recoveryReadyKinds = mutableSetOf<UploadMediaKind>()
        private val confirmedKinds = mutableSetOf<UploadMediaKind>()
        var blockAudioConfirm = false
        val confirmEntered = CompletableDeferred<Unit>()
        val confirmRelease = CompletableDeferred<Unit>()

        override suspend fun grant(request: UploadGrantRequest): UploadGrant {
            timeline += "grant:${request.kind}:${request.idempotencyKey}"
            grantCount[request.kind] = grantCount.getOrDefault(request.kind, 0) + 1
            grantKeys += request.idempotencyKey to request.kind
            if (request.kind == UploadMediaKind.AUDIO && retryAudioGrant) throw UploadRemoteRetryableException()
            return if (request.kind == UploadMediaKind.AUDIO) audioGrant else grantFor(UploadMediaKind.VIDEO)
        }
        override suspend fun confirm(request: UploadConfirmRequest): UploadConfirmResult {
            timeline += "confirm:${request.kind}"
            confirmCount[request.kind] = confirmCount.getOrDefault(request.kind, 0) + 1
            confirmedBindings += request.binding
            if (request.kind == UploadMediaKind.AUDIO && blockAudioConfirm) {
                confirmEntered.complete(Unit)
                confirmRelease.await()
            }
            return if (request.kind == UploadMediaKind.AUDIO && audioConflicts-- > 0) {
                UploadConfirmResult.CallbackPending
            } else {
                confirmedKinds += request.kind
                UploadConfirmResult.Confirmed(request.binding)
            }
        }
        override suspend fun submit(sessionId: String, idempotencyKey: String): UploadSubmitResult {
            timeline += "submit:$idempotencyKey"
            submitCount++
            submitKeys += idempotencyKey
            return if (submitConflicts-- > 0) UploadSubmitResult.Conflict else UploadSubmitResult.Accepted(sessionId)
        }
        override suspend fun sessionDetail(sessionId: String): UploadSessionDetail {
            detailCount++
            timeline += "detail:$detailCount"
            return detailOverrides[detailCount] ?: UploadSessionDetail(
                sessionId,
                conflictDetail,
                listOf(
                    audioGrant.binding.asSessionMedia(
                        UploadMediaKind.AUDIO,
                        if (UploadMediaKind.AUDIO in confirmedKinds || UploadMediaKind.AUDIO in recoveryReadyKinds || submitCount > 0) RemoteMediaState.READY else RemoteMediaState.UPLOADING,
                    ),
                    bindingFor(UploadMediaKind.VIDEO).asSessionMedia(
                        UploadMediaKind.VIDEO,
                        if (UploadMediaKind.VIDEO in confirmedKinds || UploadMediaKind.VIDEO in recoveryReadyKinds || submitCount > 0) RemoteMediaState.READY else RemoteMediaState.UPLOADING,
                    ),
                ),
            )
        }
    }

    private class FakeUploader(private val timeline: MutableList<String>) : QiniuUploader {
        override suspend fun upload(request: QiniuUploadRequest, onProgress: (Int) -> Unit): QiniuUploadResult {
            timeline += "upload:${request.kind}"
            onProgress(50)
            onProgress(100)
            return QiniuUploadResult.Completed(request.grant.binding.objectKey)
        }
        override fun cancel() = Unit
    }

    private class GateUploader(private val timeline: MutableList<String>) : QiniuUploader {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun upload(request: QiniuUploadRequest, onProgress: (Int) -> Unit): QiniuUploadResult {
            timeline += "upload:${request.kind}"
            if (!entered.isCompleted) {
                entered.complete(Unit)
                release.await()
            }
            onProgress(100)
            return QiniuUploadResult.Completed(request.grant.binding.objectKey)
        }
        override fun cancel() = Unit
    }

    private class FakeLeases(private val timeline: MutableList<String>) : PlaintextUploadLeaseProvider {
        override suspend fun open(kind: UploadMediaKind, media: UploadMedia): PlaintextUploadLease {
            timeline += "lease:$kind"
            return object : PlaintextUploadLease {
                override val file = File.createTempFile("upload-test-", ".bin").apply {
                    writeBytes(ByteArray(media.sizeBytes.toInt()))
                }
                override fun close() { file.delete(); timeline += "close:$kind" }
            }
        }
    }

    private companion object {
        fun bindingFor(kind: UploadMediaKind) = UploadBinding(
            sessionId = "session-1",
            assetId = if (kind == UploadMediaKind.AUDIO) "asset-a" else "asset-v",
            objectKey = if (kind == UploadMediaKind.AUDIO) "object-a" else "object-v",
            mimeType = if (kind == UploadMediaKind.AUDIO) "audio/mp4" else "video/mp4",
            sizeBytes = if (kind == UploadMediaKind.AUDIO) 10 else 20,
        )

        fun grantFor(kind: UploadMediaKind) = UploadGrant(
            binding = bindingFor(kind),
            expiresAtEpochMillis = Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(),
            uploadUrl = "https://upload.qiniup.com",
            uploadToken = "secret",
        )

        fun UploadBinding.asSessionMedia(kind: UploadMediaKind, status: RemoteMediaState) =
            UploadSessionMedia(assetId, kind, mimeType, sizeBytes, status)
    }
}
