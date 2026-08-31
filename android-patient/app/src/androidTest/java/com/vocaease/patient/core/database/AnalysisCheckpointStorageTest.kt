package com.vocaease.patient.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.feature.history.AnalysisCheckpoint
import com.vocaease.patient.feature.history.AnalysisStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisCheckpointStorageTest {
    private lateinit var database: VocaEaseDatabase
    private lateinit var session: MutableAuthenticatedAccountSession
    private lateinit var provider: AccountScopedDraftStorageProvider

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = VocaEaseDatabase.inMemory(context, allowMainThreadQueries = true)
        session = MutableAuthenticatedAccountSession()
        provider = AccountScopedDraftStorageProvider(
            database,
            ChunkedAesGcmFileStore(context, File(context.filesDir, "analysis-checkpoint-test")),
            session,
        )
        session.authenticate("patient-a")
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun checkpoint按账户隔离并以version进行CAS且不含结果或URL() = runBlocking {
        val storage = provider.current()
        val initial = checkpoint(storage, version = 0)
        assertEquals(true, storage.persistAnalysisCheckpoint(null, initial))
        assertEquals(initial, storage.loadAnalysisCheckpoint("session-1"))
        assertFalse(storage.persistAnalysisCheckpoint(9, initial.copy(version = 10)))
        assertEquals(true, storage.persistAnalysisCheckpoint(0, initial.copy(version = 1, nextDeadlineEpochMillis = 31_000)))

        session.authenticate("patient-b")
        assertNull(provider.current().loadAnalysisCheckpoint("session-1"))
    }

    @Test
    fun 同患者新incarnation原子接管checkpoint且旧storage继续failClosed() = runBlocking {
        val old = provider.current()
        val initial = checkpoint(old, version = 0)
        assertEquals(true, old.persistAnalysisCheckpoint(null, initial))
        session.authenticate("patient-a")
        val current = provider.current()

        assertEquals(
            initial.copy(incarnationProof = current.cleanupScopeToken, version = 1),
            current.loadAnalysisCheckpoint("session-1"),
        )

        assertThrows(StaleAccountScopeException::class.java) {
            runBlocking { old.persistAnalysisCheckpoint(0, initial.copy(version = 1)) }
        }
        assertThrows(StaleAccountScopeException::class.java) {
            runBlocking { old.loadAnalysisCheckpoint("session-1") }
        }
        Unit
    }

    @Test
    fun rawSql拒绝伪造hash未知状态越界步进和非CAS更新() {
        val sqlite = database.openHelper.writableDatabase
        val columns = sqlite.query("PRAGMA table_info(analysis_checkpoints)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        assertFalse(columns.contains("account_scope"))
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            sqlite.execSQL(
                "INSERT INTO analysis_checkpoints(account_scope_hash,session_id,incarnation_proof,status,analysis_generation,poll_step,next_deadline_at,operation_version) " +
                    "VALUES('bad','session-raw','${"b".repeat(64)}','PROCESSING',0,0,1,0)",
            )
        }
        sqlite.execSQL(
            "INSERT INTO analysis_checkpoints(account_scope_hash,session_id,incarnation_proof,status,analysis_generation,poll_step,next_deadline_at,operation_version) " +
                "VALUES('${"a".repeat(64)}','session-raw','${"b".repeat(64)}','PROCESSING',0,0,1,0)",
        )
        listOf(
            "UPDATE analysis_checkpoints SET status='UNKNOWN',operation_version=1 WHERE account_scope_hash='${"a".repeat(64)}' AND session_id='session-raw'",
            "UPDATE analysis_checkpoints SET poll_step=4,operation_version=1 WHERE account_scope_hash='${"a".repeat(64)}' AND session_id='session-raw'",
            "UPDATE analysis_checkpoints SET next_deadline_at=2 WHERE account_scope_hash='${"a".repeat(64)}' AND session_id='session-raw'",
        ).forEach { sql ->
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) { sqlite.execSQL(sql) }
        }
    }

    @Test
    fun failed只允许以更高generation重建retrying检查点() = runBlocking {
        val storage = provider.current()
        val failed = checkpoint(storage, version = 0).copy(
            status = AnalysisStatus.FAILED,
            analysisGeneration = 2,
            pollStep = 3,
        )
        assertEquals(true, storage.persistAnalysisCheckpoint(null, failed))
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            runBlocking {
                storage.persistAnalysisCheckpoint(
                    0,
                    failed.copy(status = AnalysisStatus.RETRYING, pollStep = 0, version = 1),
                )
            }
        }
        assertEquals(
            true,
            storage.persistAnalysisCheckpoint(
                0,
                failed.copy(
                    status = AnalysisStatus.RETRYING,
                    analysisGeneration = 3,
                    pollStep = 0,
                    nextDeadlineEpochMillis = 21_000,
                    version = 1,
                ),
            ),
        )
        Unit
    }

    @Test
    fun 历史会话可直接创建retrying检查点且不需要raw账户UUID() = runBlocking {
        val storage = provider.current()
        val restarted = checkpoint(storage, version = 0).copy(
            sessionId = "legacy-session",
            status = AnalysisStatus.RETRYING,
            analysisGeneration = 4,
        )

        assertEquals(true, storage.persistAnalysisCheckpoint(null, restarted))
        assertEquals(restarted, storage.loadAnalysisCheckpoint("legacy-session"))
        assertFalse(database.openHelper.writableDatabase.query("PRAGMA table_info(analysis_checkpoints)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }.contains("account_scope")
        })
    }

    private fun checkpoint(storage: AccountScopedDraftStorage, version: Long) = AnalysisCheckpoint(
        accountScopeHash = storage.accountScopeHash,
        sessionId = "session-1",
        incarnationProof = storage.cleanupScopeToken,
        status = AnalysisStatus.PROCESSING,
        analysisGeneration = 1,
        pollStep = 0,
        nextDeadlineEpochMillis = 11_000,
        version = version,
    )
}
