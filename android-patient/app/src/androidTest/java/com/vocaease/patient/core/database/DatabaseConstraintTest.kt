package com.vocaease.patient.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseConstraintTest {
    private lateinit var database: VocaEaseDatabase

    @Before
    fun setUp() {
        database = VocaEaseDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>(), allowMainThreadQueries = true)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun versionedTriggersRejectInvalidRawInsertAndUpdate() {
        val db = database.openHelper.writableDatabase
        val triggerNames = db.query("SELECT name FROM sqlite_master WHERE type='trigger' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertEquals(DatabaseConstraintInstaller.triggerNames.sorted(), triggerNames)

        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL(
                "INSERT INTO drafts(account_scope,draft_id,song_id,session_id,creation_key,state,duration_ms,created_at,expires_at,interruption_reason) " +
                    "VALUES('   ','d','s','session','key','RECORDING',0,0,1,NULL)",
            )
        }
        Unit
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL(
                "INSERT INTO drafts(account_scope,draft_id,song_id,session_id,creation_key,state,duration_ms,created_at,expires_at,interruption_reason) " +
                    "VALUES('a','d','s','session','key','UNKNOWN',0,0,1,NULL)",
            )
        }
        db.execSQL(
            "INSERT INTO drafts(account_scope,draft_id,song_id,session_id,creation_key,state,duration_ms,created_at,expires_at,interruption_reason) " +
                "VALUES('a','d','s','session','key','RECORDING',0,0,604800000,NULL)",
        )
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL("UPDATE drafts SET expires_at=604800001 WHERE account_scope='a' AND draft_id='d'")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            db.execSQL(
                "INSERT INTO media(account_scope,draft_id,type,encrypted_relative_path,mime_type,size_bytes,sha256,validation_state) " +
                    "VALUES('a','d','AUDIO','../outside.vef','audio/mp4',1,'${"0".repeat(64)}','VALID')",
            )
        }
    }

    @Test
    fun daoUpdateIsAlsoProtectedBySqliteConstraint() = runBlocking {
        database.draftDao().insert(validDraft())
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                database.draftDao().updateState("a", "d", DraftState.RECORDING, -1, null)
            }
        }
        Unit
    }

    @Test
    fun rawUploadUpdatesRejectUnknownStateUnsafeKeyAndOversizedSafeError() = runBlocking {
        database.draftDao().insert(validDraft())
        database.mediaDao().insert(
            MediaEntity(
                "a", "d", MediaType.AUDIO, "media/v1/${"a".repeat(32)}.vef", "audio/mp4", 1,
                "0".repeat(64), MediaValidationState.VALID,
            ),
        )
        database.uploadDao().insert(UploadJobEntity.newPending("a", "d", "grant:d:audio", "grant:d:video", "submit:d"))
        val db = database.openHelper.writableDatabase
        db.execSQL("UPDATE upload_jobs SET progress_percent=10,attempt_count=1 WHERE account_scope='a' AND draft_id='d'")

        listOf(
            "UPDATE upload_jobs SET overall_state='UNKNOWN' WHERE account_scope='a' AND draft_id='d'",
            "UPDATE upload_jobs SET audio_grant_key=' ' WHERE account_scope='a' AND draft_id='d'",
            "UPDATE upload_jobs SET audio_grant_key='audio-2' WHERE account_scope='a' AND draft_id='d'",
            "UPDATE upload_jobs SET last_safe_error='${"x".repeat(257)}' WHERE account_scope='a' AND draft_id='d'",
            "UPDATE upload_jobs SET pipeline_stage='UPLOADING_AUDIO',overall_state='UPLOADING',audio_asset_key='asset',audio_object_key='object',audio_grant_state='GRANT_READY',audio_upload_state='UPLOADING' WHERE account_scope='a' AND draft_id='d'",
            "UPDATE upload_jobs SET audio_grant_state='SUBMITTED' WHERE account_scope='a' AND draft_id='d'",
            "UPDATE upload_jobs SET overall_state='ANALYZING' WHERE account_scope='a' AND draft_id='d'",
            "UPDATE upload_jobs SET audio_confirm_state='CONFIRMED' WHERE account_scope='a' AND draft_id='d'",
            "UPDATE upload_jobs SET progress_percent=9 WHERE account_scope='a' AND draft_id='d'",
            "UPDATE upload_jobs SET attempt_count=0 WHERE account_scope='a' AND draft_id='d'",
            "UPDATE media SET encrypted_relative_path='media/v1/${"b".repeat(32)}.vef' WHERE account_scope='a' AND draft_id='d' AND type='AUDIO'",
        ).forEach { sql -> assertThrows(SQLiteConstraintException::class.java) { db.execSQL(sql) } }
        Unit
    }

    @Test
    fun rawUploadInsertAndUpdateEnforceConfirmationPayloadAndGlobalAccountIdempotency() = runBlocking {
        database.draftDao().insert(validDraft("a", "d1"))
        database.draftDao().insert(validDraft("a", "d2"))
        database.draftDao().insert(validDraft("b", "d1"))
        database.uploadDao().insert(UploadJobEntity.newPending("a", "d1", "grant:d1:audio", "grant:d1:video", "submit:d1"))
        val db = database.openHelper.writableDatabase

        listOf(
            "UPDATE upload_jobs SET audio_confirmed_at=-1 WHERE account_scope='a' AND draft_id='d1'",
            "UPDATE upload_jobs SET audio_confirmed_at=0 WHERE account_scope='a' AND draft_id='d1'",
            "UPDATE upload_jobs SET audio_asset_key='asset-only' WHERE account_scope='a' AND draft_id='d1'",
            "UPDATE upload_jobs SET audio_asset_key='asset',audio_object_key='object',audio_receipt='receipt' WHERE account_scope='a' AND draft_id='d1'",
        ).forEach { sql -> assertThrows(SQLiteConstraintException::class.java) { db.execSQL(sql) } }

        assertThrows(SQLiteConstraintException::class.java) {
            cloneUploadJobRaw("a", "d1", "d2", "audio-key", "video-key-2", "submit-key-2")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            cloneUploadJobRaw("a", "d1", "d2", "audio-key-2", "submit-key", "submit-key-2")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                database.uploadDao().insert(UploadJobEntity.newPending("a", "d2", "same-key", "same-key", "submit-key-2"))
            }
        }

        database.uploadDao().insert(UploadJobEntity.newPending("b", "d1", "grant:d1:audio", "grant:d1:video", "submit:d1"))
        assertEquals("grant:d1:audio", database.uploadDao().find("b", "d1")?.audioGrantKey)
    }

    private fun cloneUploadJobRaw(
        accountScope: String,
        sourceDraftId: String,
        targetDraftId: String,
        audioGrantKey: String,
        videoGrantKey: String,
        submitKey: String,
    ) {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO upload_jobs(
              account_scope,draft_id,overall_state,audio_grant_state,video_grant_state,
              audio_upload_state,video_upload_state,audio_receipt_state,video_receipt_state,
              audio_confirm_state,video_confirm_state,submit_state,audio_grant_key,video_grant_key,
              submit_key,audio_asset_key,video_asset_key,audio_object_key,video_object_key,
              audio_receipt,video_receipt,audio_confirmed_at,video_confirmed_at,attempt_count,
              next_retry_at,last_safe_error
            )
            SELECT account_scope,?,overall_state,audio_grant_state,video_grant_state,
              audio_upload_state,video_upload_state,audio_receipt_state,video_receipt_state,
              audio_confirm_state,video_confirm_state,submit_state,?,?,?,audio_asset_key,
              video_asset_key,audio_object_key,video_object_key,audio_receipt,video_receipt,
              audio_confirmed_at,video_confirmed_at,attempt_count,next_retry_at,last_safe_error
            FROM upload_jobs WHERE account_scope=? AND draft_id=?
            """.trimIndent(),
            arrayOf(targetDraftId, audioGrantKey, videoGrantKey, submitKey, accountScope, sourceDraftId),
        )
    }

    private fun validDraft(accountScope: String = "a", draftId: String = "d") = DraftEntity(
        accountScope = accountScope,
        draftId = draftId,
        songId = "song",
        sessionId = "session-$draftId",
        creationKey = "creation-$draftId",
        state = DraftState.RECORDING,
        durationMs = 0,
        createdAt = 0,
        expiresAt = DraftEntity.MAX_RETENTION_MILLIS,
        interruptionReason = null,
    )
}
