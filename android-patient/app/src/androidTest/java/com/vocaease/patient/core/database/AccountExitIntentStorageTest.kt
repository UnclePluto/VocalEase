package com.vocaease.patient.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class AccountExitIntentStorageTest {
    private lateinit var database: VocaEaseDatabase

    @Before
    fun setUp() {
        database = VocaEaseDatabase.inMemory(
            ApplicationProvider.getApplicationContext<Context>(),
            allowMainThreadQueries = true,
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun freshDatabasePersistsAccountExitIntent() {
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "INSERT INTO account_exit_intents(" +
                "account_scope,account_scope_hash,incarnation_proof,session_epoch,operation_id,operation_kind,choice,stage,operation_version" +
                ") VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(
                "11111111-1111-4111-8111-111111111111",
                "a".repeat(64),
                "b".repeat(64),
                7L,
                "logout-operation",
                "LOGOUT",
                "RETAIN",
                "INTENT_WRITTEN",
                0L,
            ),
        )

        val stored = sqlite.query(
            "SELECT operation_kind,choice,stage,operation_version FROM account_exit_intents WHERE account_scope=?",
            arrayOf("11111111-1111-4111-8111-111111111111"),
        ).use { cursor ->
            check(cursor.moveToFirst())
            listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3).toString())
        }
        assertEquals(listOf("LOGOUT", "RETAIN", "INTENT_WRITTEN", "0"), stored)
    }

    @Test
    fun freshDatabasePersistsPasswordChangeIntentAndRejectsUnknownOperationKind() {
        runBlocking {
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL(
                "INSERT INTO account_exit_intents(" +
                    "account_scope,account_scope_hash,incarnation_proof,session_epoch,operation_id,operation_kind,choice,stage,operation_version" +
                    ") VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(
                    "11111111-1111-4111-8111-111111111111", "a".repeat(64), "b".repeat(64), 7L,
                    "password-change", "PASSWORD_CHANGE", "RETAIN", "INTENT_WRITTEN", 0L,
                ),
            )
            assertEquals(
                AccountOperationKind.PASSWORD_CHANGE,
                database.accountExitIntentDao().find("11111111-1111-4111-8111-111111111111")?.operationKind,
            )

            assertThrows(SQLiteConstraintException::class.java) {
                sqlite.execSQL(
                    "INSERT INTO account_exit_intents(" +
                        "account_scope,account_scope_hash,incarnation_proof,session_epoch,operation_id,operation_kind,choice,stage,operation_version" +
                        ") VALUES(?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(
                        "22222222-2222-4222-8222-222222222222", "c".repeat(64), "d".repeat(64), 8L,
                        "bad-kind", "UNKNOWN", "RETAIN", "INTENT_WRITTEN", 0L,
                    ),
                )
            }
        }
    }

    @Test
    fun rawSqlRejectsForgedScopeHashAndIncarnationProof() {
        val sqlite = database.openHelper.writableDatabase
        listOf(
            "not-a-hash" to "b".repeat(64),
            "a".repeat(64) to "not-a-proof",
            "A".repeat(64) to "b".repeat(64),
        ).forEachIndexed { index, (scopeHash, proof) ->
            assertThrows(SQLiteConstraintException::class.java) {
                sqlite.execSQL(
                    "INSERT INTO account_exit_intents(" +
                        "account_scope,account_scope_hash,incarnation_proof,session_epoch,operation_id,operation_kind,choice,stage,operation_version" +
                        ") VALUES(?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(
                        "11111111-1111-4111-8111-${index.toString().padStart(12, '0')}",
                        scopeHash,
                        proof,
                        1L,
                        "logout-$index",
                        "LOGOUT",
                        "DELETE",
                        "INTENT_WRITTEN",
                        0L,
                    ),
                )
            }
        }
    }
}
