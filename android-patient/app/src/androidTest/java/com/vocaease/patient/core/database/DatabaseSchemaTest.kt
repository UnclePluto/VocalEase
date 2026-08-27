package com.vocaease.patient.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
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
    fun exportedVersionOneSchema_canBeCreatedValidatedAndReopened() {
        migrationHelper.createDatabase(DATABASE_NAME, 1).close()

        val reopened = VocaEaseDatabase.create(context, DATABASE_NAME, allowMainThreadQueries = true)
        try {
            assertEquals(1, reopened.openHelper.readableDatabase.version)
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

    private companion object {
        const val DATABASE_NAME = "schema-v1-test.db"
    }
}
