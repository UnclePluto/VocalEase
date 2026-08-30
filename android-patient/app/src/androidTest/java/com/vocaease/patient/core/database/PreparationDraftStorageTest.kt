package com.vocaease.patient.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.feature.training.AccountScopedPreparationDraftStore
import com.vocaease.patient.feature.training.CreatedTrainingSession
import com.vocaease.patient.feature.training.PreparationDraft
import com.vocaease.patient.feature.training.PreparationSong
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreparationDraftStorageTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VocaEaseDatabase::class.java,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: VocaEaseDatabase
    private lateinit var root: File
    private lateinit var session: MutableAuthenticatedAccountSession
    private lateinit var fileStore: ChunkedAesGcmFileStore

    @Before
    fun setUp() {
        database = VocaEaseDatabase.inMemory(context, allowMainThreadQueries = true)
        root = File(context.filesDir, "preparation-${System.nanoTime()}")
        session = MutableAuthenticatedAccountSession()
        fileStore = ChunkedAesGcmFileStore(context, root)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(MIGRATION_DATABASE)
        context.deleteDatabase(V2_MIGRATION_DATABASE)
        root.deleteRecursively()
    }

    @Test
    fun pendingDraftPersistsStableKeyAndSnapshotBeforeBindingServerSession() = runBlocking {
        session.authenticate(PATIENT_A)
        val storage = AccountScopedDraftStorageProvider(database, fileStore, session).current()
        val store = AccountScopedPreparationDraftStore(storage)
        val pending = draft(store.accountScopeHash)

        store.create(pending)

        val restored = store.find(DRAFT_ID)
        assertEquals(pending, restored)
        assertNull(restored?.serverSessionId)
        assertTrue(restored?.creationKey?.startsWith("session-create:${store.accountScopeHash}:") == true)

        store.bindServerSession(DRAFT_ID, createdSession(PATIENT_A))
        assertEquals(SESSION_ID, store.find(DRAFT_ID)?.serverSessionId)
    }

    @Test
    fun oldLeaseAndMismatchedPatientOrSongCannotBindSession() = runBlocking {
        session.authenticate(PATIENT_A)
        val storeA = AccountScopedPreparationDraftStore(
            AccountScopedDraftStorageProvider(database, fileStore, session).current(),
        )
        storeA.create(draft(storeA.accountScopeHash))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { storeA.bindServerSession(DRAFT_ID, createdSession(PATIENT_B)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                storeA.bindServerSession(
                    DRAFT_ID,
                    createdSession(PATIENT_A).copy(song = song().copy(id = UUID.fromString(OTHER_SONG_ID))),
                )
            }
        }

        session.authenticate(PATIENT_B)
        assertThrows(StaleAccountScopeException::class.java) {
            runBlocking { storeA.bindServerSession(DRAFT_ID, createdSession(PATIENT_A)) }
        }
        assertNull(
            AccountScopedPreparationDraftStore(
                AccountScopedDraftStorageProvider(database, fileStore, session).current(),
            ).find(DRAFT_ID),
        )
    }

    @Test
    fun eachAccountAndSongHasAtMostOneRecoverablePreparation() = runBlocking {
        session.authenticate(PATIENT_A)
        val storeA = AccountScopedPreparationDraftStore(
            AccountScopedDraftStorageProvider(database, fileStore, session).current(),
        )
        storeA.create(draft(storeA.accountScopeHash, DRAFT_ID))

        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            runBlocking { storeA.create(draft(storeA.accountScopeHash, OTHER_DRAFT_ID)) }
        }

        session.authenticate(PATIENT_B)
        val storeB = AccountScopedPreparationDraftStore(
            AccountScopedDraftStorageProvider(database, fileStore, session).current(),
        )
        storeB.create(draft(storeB.accountScopeHash, OTHER_DRAFT_ID))
        assertEquals(OTHER_DRAFT_ID, storeB.find(OTHER_DRAFT_ID)?.draftId)
    }

    @Test
    fun handoffCommitSurvivesNavigationCrashUntilRecordingAcknowledgesIt() = runBlocking {
        session.authenticate(PATIENT_A)
        val store = AccountScopedPreparationDraftStore(
            AccountScopedDraftStorageProvider(database, fileStore, session).current(),
        )
        store.create(draft(store.accountScopeHash))
        store.bindServerSession(DRAFT_ID, createdSession(PATIENT_A))

        assertThrows(SimulatedNavigationCrash::class.java) {
            runBlocking {
                store.markHandoffPending(DRAFT_ID, SESSION_ID) { throw SimulatedNavigationCrash() }
            }
        }

        val recovered = store.findActive(SONG_ID)
        assertEquals(DRAFT_ID, recovered?.draftId)
        assertEquals(SESSION_ID, recovered?.serverSessionId)
        assertEquals(PreparationDraftStatus.HANDOFF_PENDING, recovered?.status)
        assertTrue(store.acknowledgeHandoff(DRAFT_ID))
        assertNull(store.findActive(SONG_ID))

        store.create(draft(store.accountScopeHash, OTHER_DRAFT_ID))
        assertEquals(OTHER_DRAFT_ID, store.findActive(SONG_ID)?.draftId)
    }

    @Test
    fun versionOneMigrationPreservesExistingDraftAndAddsRecoverablePreparationWithoutPrivateUrl() {
        migrationHelper.createDatabase(MIGRATION_DATABASE, 1).use { old ->
            old.execSQL(
                "INSERT INTO drafts(account_scope,draft_id,song_id,session_id,creation_key,state,duration_ms,created_at,expires_at,interruption_reason) " +
                    "VALUES(?,?,?,?,?,'RECORDING',0,0,604800000,NULL)",
                arrayOf(PATIENT_A, "legacy-draft", SONG_ID, SESSION_ID, "legacy-key"),
            )
        }

        migrationHelper.runMigrationsAndValidate(
            MIGRATION_DATABASE,
            3,
            true,
            VocaEaseDatabase.MIGRATION_1_2,
            VocaEaseDatabase.MIGRATION_2_3,
        ).use { migrated ->
            val values = migrated.query(
                "SELECT session_id FROM drafts WHERE draft_id='legacy-draft'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                listOf(cursor.getString(0))
            }
            assertEquals(listOf(SESSION_ID), values)

            migrated.execSQL(
                "INSERT INTO preparation_drafts(account_scope,draft_id,song_id,song_title,song_artist,song_duration_seconds,server_session_id,creation_key,created_at,expires_at) " +
                    "VALUES(?,?,?,?,?,?,NULL,?,?,?)",
                arrayOf<Any?>(PATIENT_A, DRAFT_ID, SONG_ID, "小幸运", "田馥甄", 265, "pending-key", 0, 604800000),
            )
            val columns = migrated.query("PRAGMA table_info(preparation_drafts)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
            assertFalse(columns.any { it.contains("url", ignoreCase = true) || it.contains("expiry", ignoreCase = true) })
        }
    }

    @Test
    fun versionTwoMigrationKeepsOneDeterministicActivePreparationPerAccountSong() {
        migrationHelper.createDatabase(V2_MIGRATION_DATABASE, 2).use { old ->
            listOf("a-draft" to 10L, "b-draft" to 20L).forEach { (draftId, createdAt) ->
                old.execSQL(
                    "INSERT INTO preparation_drafts(account_scope,draft_id,song_id,song_title,song_artist," +
                        "song_duration_seconds,server_session_id,creation_key,created_at,expires_at) " +
                        "VALUES(?,?,?,?,?,?,NULL,?,?,?)",
                    arrayOf<Any?>(
                        PATIENT_A, draftId, SONG_ID, "小幸运", "田馥甄", 265,
                        "session-create:${"a".repeat(64)}:$draftId", createdAt, createdAt + 604800000,
                    ),
                )
            }
        }

        migrationHelper.runMigrationsAndValidate(
            V2_MIGRATION_DATABASE,
            3,
            true,
            VocaEaseDatabase.MIGRATION_2_3,
        ).use { migrated ->
            val rows = migrated.query(
                "SELECT draft_id,status,active_song_id FROM preparation_drafts ORDER BY draft_id",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(
                        Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)),
                    )
                }
            }
            assertEquals(
                listOf(
                    Triple("a-draft", "PENDING", SONG_ID),
                    Triple("b-draft", "ABANDONED", null),
                ),
                rows,
            )
        }
    }

    private fun draft(scopeHash: String, draftId: String = DRAFT_ID) = PreparationDraft(
        draftId = draftId,
        songId = SONG_ID,
        songTitle = "小幸运",
        songArtist = "田馥甄",
        songDurationSeconds = 265,
        serverSessionId = null,
        creationKey = "session-create:$scopeHash:$draftId",
        status = PreparationDraftStatus.PENDING,
        createdAt = 0,
        expiresAt = DraftEntity.MAX_RETENTION_MILLIS,
    )

    private fun song() = PreparationSong(UUID.fromString(SONG_ID), "小幸运", "田馥甄", 265)

    private fun createdSession(patientId: String) = CreatedTrainingSession(
        sessionId = UUID.fromString(SESSION_ID),
        patientId = UUID.fromString(patientId),
        song = song(),
    )

    private companion object {
        const val MIGRATION_DATABASE = "preparation-v1-v2.db"
        const val V2_MIGRATION_DATABASE = "preparation-v2-v3.db"
        const val PATIENT_A = "11111111-1111-4111-8111-111111111111"
        const val PATIENT_B = "22222222-2222-4222-8222-222222222222"
        const val SONG_ID = "33333333-3333-4333-8333-333333333333"
        const val OTHER_SONG_ID = "44444444-4444-4444-8444-444444444444"
        const val SESSION_ID = "55555555-5555-4555-8555-555555555555"
        const val DRAFT_ID = "66666666-6666-4666-8666-666666666666"
        const val OTHER_DRAFT_ID = "77777777-7777-4777-8777-777777777777"
    }
}

private class SimulatedNavigationCrash : RuntimeException()
