package com.vocaease.patient.feature.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.database.AnalysisCheckpointEntity
import com.vocaease.patient.core.database.DraftEntity
import com.vocaease.patient.core.database.DraftState
import com.vocaease.patient.core.database.MediaEntity
import com.vocaease.patient.core.database.MediaType
import com.vocaease.patient.core.database.MediaValidationState
import com.vocaease.patient.core.database.PreparationDraftEntity
import com.vocaease.patient.core.database.PreparationDraftStatus
import com.vocaease.patient.core.database.UploadJobEntity
import com.vocaease.patient.core.database.UploadLocalActionEntity
import com.vocaease.patient.core.database.UploadLocalActionStage
import com.vocaease.patient.core.database.UploadLocalActionType
import com.vocaease.patient.core.database.VocaEaseDatabase
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountDataEraserTest {
    private lateinit var context: Context
    private lateinit var database: VocaEaseDatabase
    private lateinit var fileStore: ChunkedAesGcmFileStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = VocaEaseDatabase.inMemory(context, allowMainThreadQueries = true)
        fileStore = ChunkedAesGcmFileStore(context, File(context.cacheDir, "eraser-media-${System.nanoTime()}"))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun 删除覆盖账户所有Room行密文密钥和上传临时文件且重复执行幂等() = runBlocking {
        val hash = ChunkedAesGcmFileStore.sha256(ACCOUNT)
        val encrypted = fileStore.encrypt(ACCOUNT, ByteArrayInputStream("secret-media".toByteArray()), 12)
        database.draftDao().insert(draft(ACCOUNT, DRAFT))
        database.draftDao().insert(draft(OTHER_ACCOUNT, "other-draft"))
        database.mediaDao().insert(
            MediaEntity(ACCOUNT, DRAFT, MediaType.AUDIO, encrypted.relativePath, "audio/mp4", encrypted.encryptedSizeBytes, "a".repeat(64), MediaValidationState.VALID),
        )
        database.uploadDao().insert(
            UploadJobEntity.newPending(
                ACCOUNT,
                DRAFT,
                "grant:$DRAFT:audio",
                "grant:$DRAFT:video",
                "submit:$DRAFT",
            ),
        )
        database.uploadLocalActionDao().insert(
            UploadLocalActionEntity(
                ACCOUNT, DRAFT, UploadLocalActionType.DELETE_QUEUED, UploadLocalActionStage.INTENT_WRITTEN,
                encrypted.relativePath, "media/v1/${"f".repeat(32)}.vef",
            ),
        )
        database.preparationDraftDao().insert(
            PreparationDraftEntity(
                ACCOUNT, DRAFT, "song", "歌名", "歌手", 60, null,
                "session-create:test:$DRAFT", PreparationDraftStatus.PENDING, "song", 0, 100,
            ),
        )
        database.analysisCheckpointDao().insert(
            AnalysisCheckpointEntity(hash, "server-session", "b".repeat(64), "PROCESSING", 1, 0, 1, 0),
        )
        val opaque = ChunkedAesGcmFileStore.sha256("$hash\u0000$DRAFT")
        val uploadLease = File(context.cacheDir, "upload-lease/$opaque").apply { mkdirs(); File(this, "part").writeText("plain") }
        val recorder = File(context.filesDir, "qiniu-upload-recorder/$opaque").apply { mkdirs(); File(this, "state").writeText("credential") }

        val eraser = AccountDataEraser(context, database, fileStore)
        eraser.delete(ACCOUNT, hash)
        eraser.delete(ACCOUNT, hash)

        assertNull(database.draftDao().find(ACCOUNT, DRAFT))
        assertNull(database.mediaDao().find(ACCOUNT, DRAFT, MediaType.AUDIO))
        assertNull(database.uploadDao().find(ACCOUNT, DRAFT))
        assertNull(database.uploadLocalActionDao().find(ACCOUNT, DRAFT))
        assertNull(database.preparationDraftDao().find(ACCOUNT, DRAFT))
        assertNull(database.analysisCheckpointDao().find(hash, "server-session"))
        assertFalse(fileStore.encryptedMediaExists(ACCOUNT, encrypted.relativePath))
        assertFalse(uploadLease.exists())
        assertFalse(recorder.exists())
        assertEquals("song", database.draftDao().find(OTHER_ACCOUNT, "other-draft")?.songId)
    }

    private fun draft(scope: String, id: String) = DraftEntity(
        scope, id, "song", "session-$id", "create-$id", DraftState.REVIEW_READY, 12, 0, 100, null,
    )
}

private const val ACCOUNT = "11111111-1111-4111-8111-111111111111"
private const val OTHER_ACCOUNT = "22222222-2222-4222-8222-222222222222"
private const val DRAFT = "delete-me"
