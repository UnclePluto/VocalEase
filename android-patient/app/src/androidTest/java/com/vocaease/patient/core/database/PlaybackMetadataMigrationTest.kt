package com.vocaease.patient.core.database
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.core.media.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackMetadataMigrationTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),VocaEaseDatabase::class.java)
    @Test fun migrationTenToElevenPreservesOldDrafts() {
        helper.createDatabase("metadata-migration",10).apply {
            execSQL("INSERT INTO drafts(account_scope,draft_id,song_id,session_id,creation_key,state,duration_ms,created_at,expires_at,interruption_reason) VALUES('p','d','s','session','key','RECORDING',0,0,1000,NULL)")
            close()
        }
        val db=helper.runMigrationsAndValidate("metadata-migration",11,true,VocaEaseDatabase.MIGRATION_10_11)
        try { db.query("SELECT draft_id, playback_metadata_path FROM drafts").use { assertTrue(it.moveToFirst());assertEquals("d",it.getString(0));assertTrue(it.isNull(1)) } } finally { db.close() }
    }
    @Test fun encryptedRoundTripAndAccountExitRevokesMetadataRead() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=VocaEaseDatabase.inMemory(context,true)
        val root=java.io.File(context.filesDir,"metadata-${System.nanoTime()}")
        val files=ChunkedAesGcmFileStore(context,root)
        val sessions=MutableAuthenticatedAccountSession();sessions.authenticate("00000000-0000-0000-0000-000000000003")
        val storage=AccountScopedDraftStorageProvider(db,files,sessions).current()
        try {
            storage.insertDraft("d","s","session","key",DraftState.RECORDING,0,0,1000,null)
            val recorder=PlaybackMetadataRecorder(48000,"00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002",null)
            recorder.record(0,0,SongPlaybackMode.ACCOMPANIMENT,true)
            storage.savePlaybackMetadata("d",recorder.snapshot())
            assertEquals(recorder.snapshot(),storage.loadPlaybackMetadata("d"))
            assertFalse(root.walkTopDown().filter { it.isFile }.any { it.readBytes().toString(Charsets.UTF_8).contains("source_asset_id") })
            sessions.clear()
            assertThrows(StaleAccountScopeException::class.java) { runBlocking { storage.loadPlaybackMetadata("d") } }
        } finally { db.close();files.destroyAccountEncryption("00000000-0000-0000-0000-000000000003");root.deleteRecursively() }
        Unit
    }
}
