package com.vocaease.patient.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.AndroidAppContainer
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseIsolationTest {
    private lateinit var context: Context
    private lateinit var database: VocaEaseDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = VocaEaseDatabase.inMemory(context, allowMainThreadQueries = true)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sameDraftId_isolatedByAccountScope_forReadsUpdatesAndObservation() = runBlocking {
        database.draftDao().insert(draft("patient-a", "shared", "song-a"))
        database.draftDao().insert(draft("patient-b", "shared", "song-b"))

        assertEquals("song-a", database.draftDao().find("patient-a", "shared")?.songId)
        assertEquals("song-b", database.draftDao().observe("patient-b", "shared").first()?.songId)

        assertEquals(
            1,
            database.draftDao().updateState(
                accountScope = "patient-a",
                draftId = "shared",
                state = DraftState.REVIEW_READY,
                durationMs = 12_345,
                interruptionReason = null,
            ),
        )
        assertEquals(DraftState.REVIEW_READY, database.draftDao().find("patient-a", "shared")?.state)
        assertEquals(DraftState.RECORDING, database.draftDao().find("patient-b", "shared")?.state)
    }

    @Test
    fun mediaAndUploadJob_cannotReferenceDraftOwnedByAnotherAccount() = runBlocking {
        database.draftDao().insert(draft("patient-a", "draft-1", "song-a"))

        expectConstraint { database.mediaDao().insert(media("patient-b", "draft-1", MediaType.AUDIO)) }
        expectConstraint { database.uploadDao().insert(uploadJob("patient-b", "draft-1")) }

        database.mediaDao().insert(media("patient-a", "draft-1", MediaType.AUDIO))
        database.uploadDao().insert(uploadJob("patient-a", "draft-1"))
        assertEquals(MediaType.AUDIO, database.mediaDao().find("patient-a", "draft-1", MediaType.AUDIO)?.type)
        assertNull(database.mediaDao().find("patient-b", "draft-1", MediaType.AUDIO))
        assertEquals(UploadStepState.PENDING, database.uploadDao().find("patient-a", "draft-1")?.audioGrantState)
    }

    @Test
    fun deletingDraft_cascadesOnlyInsideSameAccountScope() = runBlocking {
        database.draftDao().insert(draft("patient-a", "shared", "song-a"))
        database.draftDao().insert(draft("patient-b", "shared", "song-b"))
        database.mediaDao().insert(media("patient-a", "shared", MediaType.VIDEO))
        database.mediaDao().insert(media("patient-b", "shared", MediaType.VIDEO))
        database.uploadDao().insert(uploadJob("patient-a", "shared"))
        database.uploadDao().insert(uploadJob("patient-b", "shared"))

        assertEquals(1, database.draftDao().delete("patient-a", "shared"))

        assertNull(database.mediaDao().find("patient-a", "shared", MediaType.VIDEO))
        assertNull(database.uploadDao().find("patient-a", "shared"))
        assertEquals("song-b", database.draftDao().find("patient-b", "shared")?.songId)
        assertEquals(MediaType.VIDEO, database.mediaDao().find("patient-b", "shared", MediaType.VIDEO)?.type)
        assertEquals("submit:shared", database.uploadDao().find("patient-b", "shared")?.submitKey)
    }

    @Test
    fun cleanupExpired_usesInclusiveEpochMillisecondBoundaryAndAccountScope() = runBlocking {
        database.draftDao().insert(draft("patient-a", "expired", "song-a", expiresAt = 700L))
        database.draftDao().insert(draft("patient-a", "future", "song-b", expiresAt = 701L))
        database.draftDao().insert(draft("patient-b", "expired", "song-c", expiresAt = 700L))

        assertEquals(1, database.draftDao().deleteExpired("patient-a", nowEpochMilliseconds = 700L))

        assertNull(database.draftDao().find("patient-a", "expired"))
        assertEquals("song-b", database.draftDao().find("patient-a", "future")?.songId)
        assertEquals("song-c", database.draftDao().find("patient-b", "expired")?.songId)
    }

    @Test
    fun convertersRejectUnknownPersistedEnumValues() {
        val converters = DatabaseConverters()

        assertThrows(IllegalArgumentException::class.java) { converters.toDraftState("UNKNOWN") }
        assertThrows(IllegalArgumentException::class.java) { converters.toMediaType("UNKNOWN") }
        assertThrows(IllegalArgumentException::class.java) { converters.toMediaValidationState("UNKNOWN") }
        assertThrows(IllegalArgumentException::class.java) { converters.toUploadStepState("UNKNOWN") }
    }

    @Test
    fun persistedDatabase_reopensWithDraftMediaAndUploadStateIntact() = runBlocking {
        database.close()
        val databaseFile = File(context.cacheDir, "draft-reopen-${System.nanoTime()}.db")
        context.deleteDatabase(databaseFile.name)
        var diskDatabase = VocaEaseDatabase.create(context, databaseFile.name, allowMainThreadQueries = true)
        try {
            diskDatabase.draftDao().insert(draft("patient-a", "draft-1", "song-a"))
            diskDatabase.mediaDao().insert(media("patient-a", "draft-1", MediaType.AUDIO))
            diskDatabase.uploadDao().insert(uploadJob("patient-a", "draft-1"))
            diskDatabase.close()

            diskDatabase = VocaEaseDatabase.create(context, databaseFile.name, allowMainThreadQueries = true)

            assertEquals("session-draft-1", diskDatabase.draftDao().find("patient-a", "draft-1")?.sessionId)
            assertEquals("media/v1/a${"0".repeat(31)}.vef", diskDatabase.mediaDao().find("patient-a", "draft-1", MediaType.AUDIO)?.encryptedRelativePath)
            assertEquals(2, diskDatabase.uploadDao().find("patient-a", "draft-1")?.attemptCount)
        } finally {
            diskDatabase.close()
            context.deleteDatabase(databaseFile.name)
            database = VocaEaseDatabase.inMemory(context, allowMainThreadQueries = true)
        }
    }

    @Test
    fun mediaRejectsAbsoluteOrTraversalPathBeforePersistence() {
        assertThrows(IllegalArgumentException::class.java) {
            media("patient-a", "draft-1", MediaType.AUDIO).copy(encryptedRelativePath = "/data/plain.mp4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            media("patient-a", "draft-1", MediaType.AUDIO).copy(encryptedRelativePath = "../plain.mp4")
        }
    }

    @Test
    fun draftRetentionAndIdempotencyKeysRejectUnsafePersistentStates() {
        assertThrows(IllegalArgumentException::class.java) {
            draft("patient-a", "draft-1", "song-a").copy(expiresAt = 604_800_001L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            uploadJob("patient-a", "draft-1").copy(audioGrantKey = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            uploadJob("patient-a", "draft-1").copy(videoGrantKey = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            uploadJob("patient-a", "draft-1").copy(resumePipelineStage = UploadPipelineStage.ANALYZING)
        }
    }

    @Test
    fun androidAppContainer_exposesAccountScopedStorageForRepositoryFactories() {
        val container = AndroidAppContainer(context)

        assertEquals("AccountScopedDraftStorageProvider", container.draftStorage::class.java.simpleName)
    }

    private fun draft(
        accountScope: String,
        draftId: String,
        songId: String,
        expiresAt: Long = 604_800_000L,
    ) = DraftEntity(
        accountScope = accountScope,
        draftId = draftId,
        songId = songId,
        sessionId = "session-$draftId",
        creationKey = "create-$accountScope-$draftId",
        state = DraftState.RECORDING,
        durationMs = 0,
        createdAt = 0,
        expiresAt = expiresAt,
        interruptionReason = null,
    )

    private fun media(accountScope: String, draftId: String, type: MediaType) = MediaEntity(
        accountScope = accountScope,
        draftId = draftId,
        type = type,
        encryptedRelativePath = "media/v1/${if (type == MediaType.AUDIO) "a" else "b"}${"0".repeat(31)}.vef",
        mimeType = if (type == MediaType.AUDIO) "audio/mp4" else "video/mp4",
        sizeBytes = 3_670_016,
        sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        validationState = MediaValidationState.VALID,
    )

    private fun uploadJob(accountScope: String, draftId: String) = UploadJobEntity(
        accountScope = accountScope,
        draftId = draftId,
        overallState = UploadOverallState.PAUSED,
        audioGrantState = UploadStepState.PENDING,
        videoGrantState = UploadStepState.PENDING,
        audioUploadState = UploadStepState.PENDING,
        videoUploadState = UploadStepState.PENDING,
        submitState = UploadStepState.PENDING,
        audioGrantKey = "grant:$draftId:audio",
        videoGrantKey = "grant:$draftId:video",
        submitKey = "submit:$draftId",
        audioAssetKey = null,
        videoAssetKey = null,
        audioObjectKey = null,
        videoObjectKey = null,
        attemptCount = 2,
        nextRetryAt = 1234L,
        lastSafeError = "网络暂不可用",
    )

    private suspend fun expectConstraint(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("跨账户外键写入本应失败")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            // Expected: the composite account/draft foreign key rejects the write.
        }
    }
}
