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
        database.uploadDao().insert(UploadJobEntity.newPending("a", "d", "audio", "video", "submit"))
        val db = database.openHelper.writableDatabase

        listOf(
            "UPDATE upload_jobs SET overall_state='UNKNOWN' WHERE account_scope='a' AND draft_id='d'",
            "UPDATE upload_jobs SET audio_grant_key=' ' WHERE account_scope='a' AND draft_id='d'",
            "UPDATE upload_jobs SET audio_grant_key='audio-2' WHERE account_scope='a' AND draft_id='d'",
            "UPDATE upload_jobs SET last_safe_error='${"x".repeat(257)}' WHERE account_scope='a' AND draft_id='d'",
            "UPDATE upload_jobs SET audio_confirm_state='CONFIRMED' WHERE account_scope='a' AND draft_id='d'",
            "UPDATE media SET encrypted_relative_path='media/v1/${"b".repeat(32)}.vef' WHERE account_scope='a' AND draft_id='d' AND type='AUDIO'",
        ).forEach { sql -> assertThrows(SQLiteConstraintException::class.java) { db.execSQL(sql) } }
        Unit
    }

    private fun validDraft() = DraftEntity(
        accountScope = "a",
        draftId = "d",
        songId = "song",
        sessionId = "session",
        creationKey = "creation",
        state = DraftState.RECORDING,
        durationMs = 0,
        createdAt = 0,
        expiresAt = DraftEntity.MAX_RETENTION_MILLIS,
        interruptionReason = null,
    )
}
