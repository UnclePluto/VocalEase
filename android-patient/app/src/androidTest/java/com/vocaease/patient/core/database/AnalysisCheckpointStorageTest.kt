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
    fun 同患者新incarnation使旧storage写入和读取都failClosed() = runBlocking {
        val old = provider.current()
        session.authenticate("patient-a")

        assertThrows(StaleAccountScopeException::class.java) {
            runBlocking { old.persistAnalysisCheckpoint(null, checkpoint(old, version = 0)) }
        }
        assertThrows(StaleAccountScopeException::class.java) {
            runBlocking { old.loadAnalysisCheckpoint("session-1") }
        }
        Unit
    }

    @Test
    fun rawSql拒绝伪造hash未知状态越界步进和非CAS更新() {
        val sqlite = database.openHelper.writableDatabase
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            sqlite.execSQL(
                "INSERT INTO analysis_checkpoints(account_scope,session_id,account_scope_hash,incarnation_proof,status,analysis_generation,poll_step,next_deadline_at,operation_version) " +
                    "VALUES('patient-a','session-raw','bad','${"b".repeat(64)}','PROCESSING',0,0,1,0)",
            )
        }
        sqlite.execSQL(
            "INSERT INTO analysis_checkpoints(account_scope,session_id,account_scope_hash,incarnation_proof,status,analysis_generation,poll_step,next_deadline_at,operation_version) " +
                "VALUES('patient-a','session-raw','${"a".repeat(64)}','${"b".repeat(64)}','PROCESSING',0,0,1,0)",
        )
        listOf(
            "UPDATE analysis_checkpoints SET status='UNKNOWN',operation_version=1 WHERE account_scope='patient-a' AND session_id='session-raw'",
            "UPDATE analysis_checkpoints SET poll_step=4,operation_version=1 WHERE account_scope='patient-a' AND session_id='session-raw'",
            "UPDATE analysis_checkpoints SET next_deadline_at=2 WHERE account_scope='patient-a' AND session_id='session-raw'",
        ).forEach { sql ->
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) { sqlite.execSQL(sql) }
        }
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
