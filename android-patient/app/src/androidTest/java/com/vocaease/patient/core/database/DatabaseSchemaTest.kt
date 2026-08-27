package com.vocaease.patient.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
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

        val reopened = Room.databaseBuilder(context, VocaEaseDatabase::class.java, DATABASE_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(1, reopened.openHelper.readableDatabase.version)
        } finally {
            reopened.close()
        }
    }

    private companion object {
        const val DATABASE_NAME = "schema-v1-test.db"
    }
}
