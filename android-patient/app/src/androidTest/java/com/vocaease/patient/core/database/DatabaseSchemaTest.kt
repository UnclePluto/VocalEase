package com.vocaease.patient.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseSchemaTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VocaEaseDatabase::class.java,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUp() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    @Throws(IOException::class)
    fun exportedVersionOneSchema_canBeMigratedValidatedAndReopened() {
        migrationHelper.createDatabase(DATABASE_NAME, 1).close()

        val reopened = VocaEaseDatabase.create(context, DATABASE_NAME, allowMainThreadQueries = true)
        try {
            assertEquals(2, reopened.openHelper.readableDatabase.version)
            val sqlite = reopened.openHelper.writableDatabase
            val triggers = sqlite.query("SELECT name FROM sqlite_master WHERE type='trigger' ORDER BY name").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertEquals(DatabaseConstraintInstaller.triggerNames.sorted(), triggers)
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                sqlite.execSQL(
                    "INSERT INTO drafts(account_scope,draft_id,song_id,session_id,creation_key,state,duration_ms,created_at,expires_at,interruption_reason) " +
                        "VALUES(' ','d','s','session','key','RECORDING',0,0,1,NULL)",
                )
            }
        } finally {
            reopened.close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun productionReopen_replacesLegacyNamedTriggersWithCanonicalConstraints() {
        migrationHelper.createDatabase(DATABASE_NAME, 1).use { legacy ->
            installLegacyTriggers(legacy)
        }

        val reopened = VocaEaseDatabase.create(context, DATABASE_NAME, allowMainThreadQueries = true)
        try {
            val sqlite = reopened.openHelper.writableDatabase
            val installed = sqlite.query(
                "SELECT name, sql FROM sqlite_master WHERE type='trigger' ORDER BY name",
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
                }
            }
            assertEquals(DatabaseConstraintInstaller.triggerNames.sorted(), installed.keys.sorted())
            assertEquals(
                canonicalTriggerSqlByName(),
                installed.mapValues { (_, sql) -> normalizeSql(sql) },
            )

            insertDraft(sqlite, "account-a", "draft-1")
            insertDraft(sqlite, "account-a", "draft-2")
            insertDraft(sqlite, "account-b", "draft-1")
            insertPendingUpload(sqlite, "account-a", "draft-1", "audio-1", "video-1", "submit-1")

            listOf(
                "UPDATE upload_jobs SET audio_confirmed_at=-1 WHERE account_scope='account-a' AND draft_id='draft-1'",
                "UPDATE upload_jobs SET audio_confirmed_at=0 WHERE account_scope='account-a' AND draft_id='draft-1'",
                "UPDATE upload_jobs SET audio_confirm_state='CONFIRMED' WHERE account_scope='account-a' AND draft_id='draft-1'",
                "UPDATE upload_jobs SET audio_asset_key='asset-only' WHERE account_scope='account-a' AND draft_id='draft-1'",
                "UPDATE upload_jobs SET audio_asset_key='asset',audio_object_key='object',audio_receipt='receipt' " +
                    "WHERE account_scope='account-a' AND draft_id='draft-1'",
            ).forEach { sql ->
                assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                    sqlite.execSQL(sql)
                }
            }
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                insertPendingUpload(sqlite, "account-a", "draft-2", "audio-2", "submit-1", "submit-2")
            }
            insertPendingUpload(sqlite, "account-b", "draft-1", "audio-1", "video-1", "submit-1")
        } finally {
            reopened.close()
        }
    }

    private fun installLegacyTriggers(database: SupportSQLiteDatabase) {
        val definitions = mapOf(
            "drafts_guard_insert_v1" to ("INSERT" to "drafts"),
            "drafts_guard_update_v1" to ("UPDATE" to "drafts"),
            "media_guard_insert_v1" to ("INSERT" to "media"),
            "media_guard_update_v1" to ("UPDATE" to "media"),
            "upload_jobs_guard_insert_v1" to ("INSERT" to "upload_jobs"),
            "upload_jobs_guard_update_v1" to ("UPDATE" to "upload_jobs"),
        )
        definitions.forEach { (name, operationAndTable) ->
            database.execSQL("DROP TRIGGER IF EXISTS $name")
            database.execSQL(
                "CREATE TRIGGER $name BEFORE ${operationAndTable.first} ON ${operationAndTable.second} " +
                    "BEGIN SELECT 'legacy-$name'; END",
            )
        }
    }

    private fun canonicalTriggerSqlByName(): Map<String, String> = context.assets
        .open("database/v1_constraints.sql")
        .bufferedReader()
        .use { it.readText() }
        .split("-- VOCAEASE-STATEMENT")
        .map(String::trim)
        .filter(String::isNotEmpty)
        .associate { statement ->
            val name = requireNotNull(
                Regex("CREATE TRIGGER(?: IF NOT EXISTS)? ([^ ]+)").find(statement),
            ).groupValues[1]
            name to normalizeSql(statement)
        }

    private fun normalizeSql(sql: String): String = sql
        .trim()
        .removeSuffix(";")
        .replace(Regex("\\s+"), " ")
        .replace("CREATE TRIGGER IF NOT EXISTS ", "CREATE TRIGGER ")

    private fun insertDraft(database: SupportSQLiteDatabase, accountScope: String, draftId: String) {
        database.execSQL(
            "INSERT INTO drafts(account_scope,draft_id,song_id,session_id,creation_key,state,duration_ms,created_at,expires_at,interruption_reason) " +
                "VALUES(?,?,?, ?,?,'RECORDING',0,0,604800000,NULL)",
            arrayOf(accountScope, draftId, "song-$draftId", "session-$accountScope-$draftId", "creation-$accountScope-$draftId"),
        )
    }

    private fun insertPendingUpload(
        database: SupportSQLiteDatabase,
        accountScope: String,
        draftId: String,
        audioGrantKey: String,
        videoGrantKey: String,
        submitKey: String,
    ) {
        database.execSQL(
            """
            INSERT INTO upload_jobs(
              account_scope,draft_id,overall_state,audio_grant_state,video_grant_state,
              audio_upload_state,video_upload_state,audio_receipt_state,video_receipt_state,
              audio_confirm_state,video_confirm_state,submit_state,audio_grant_key,video_grant_key,
              submit_key,audio_asset_key,video_asset_key,audio_object_key,video_object_key,
              audio_receipt,video_receipt,audio_confirmed_at,video_confirmed_at,attempt_count,
              next_retry_at,last_safe_error
            ) VALUES(
              ?,?,'PAUSED','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING',
              'PENDING','PENDING','PENDING',?,?,?,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,NULL,NULL
            )
            """.trimIndent(),
            arrayOf(accountScope, draftId, audioGrantKey, videoGrantKey, submitKey),
        )
    }

    private companion object {
        const val DATABASE_NAME = "schema-v1-test.db"
    }
}
