package com.vocaease.patient.core.media

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocaease.patient.core.database.AccountScopedDraftStorageProvider
import com.vocaease.patient.core.database.DraftEntity
import com.vocaease.patient.core.database.DraftState
import com.vocaease.patient.core.database.MutableAuthenticatedAccountSession
import com.vocaease.patient.core.database.VocaEaseDatabase
import com.vocaease.patient.core.security.ChunkedAesGcmFileStore
import com.vocaease.patient.feature.training.RecordingInterruption
import com.vocaease.patient.feature.training.RecordingState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingErrorFinalizeRecoveryTest {
    private lateinit var context: Context
    private lateinit var database: VocaEaseDatabase
    private lateinit var encryptedRoot: File
    private lateinit var plaintextRoot: File
    private lateinit var fileStore: ChunkedAesGcmFileStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = VocaEaseDatabase.inMemory(context, allowMainThreadQueries = true)
        encryptedRoot = File(context.filesDir, "error-finalize-encrypted-${System.nanoTime()}")
        plaintextRoot = File(context.filesDir, "recordings/plaintext").also { it.deleteRecursively() }
        fileStore = ChunkedAesGcmFileStore(context, encryptedRoot)
    }

    @After
    fun tearDown() {
        database.close()
        runCatching { fileStore.destroyAccountEncryption(PATIENT) }
        encryptedRoot.deleteRecursively()
        plaintextRoot.deleteRecursively()
    }

    @Test
    fun `ERROR_SOURCE_INACTIVE但AVC_AAC可解析时形成双密文INTERRUPTED而不销毁`() = runBlocking {
        val sessions = MutableAuthenticatedAccountSession().apply { authenticate(PATIENT) }
        val storage = AccountScopedDraftStorageProvider(
            database,
            fileStore,
            sessions,
        ).current()
        storage.insertDraft(
            DRAFT,
            "song",
            "session-$DRAFT",
            "create-$DRAFT",
            DraftState.RECORDING,
            0,
            0,
            DraftEntity.MAX_RETENTION_MILLIS,
            null,
        )
        val backend = RecoverableErrorBackend()
        val capture = CameraXRecordingCapture(backend, Dispatchers.Unconfined)
        val coordinator = DefaultRecordingCoordinator(
            capture = capture,
            playback = NoOpRecordingPlayback,
            clockNanos = { 1_000_000_000L },
            tempFiles = PrivateRecordingTempFiles(context),
            publisher = AccountScopedRecordingArtifactPublisher(storage),
        )
        coordinator.takeOver(
            DRAFT,
            RecordingStagingIdentity(storage.accountScopeHash, DRAFT, "session-$DRAFT", "create-$DRAFT"),
        )
        coordinator.onCountdownFinished()
        backend.emit(CameraXBackendEvent.Started)

        backend.emit(CameraXBackendEvent.Finalized(0, RecordingInterruption.CAMERA))

        withTimeout(5_000) { coordinator.state.first { it is RecordingState.Reviewable } }
        assertEquals(DraftState.INTERRUPTED, storage.findDraft(DRAFT)?.state)
        val recovered = storage.loadReviewDraft(DRAFT)
        assertTrue(recovered.durationMs > 0)
        assertEquals(2, recovered.media.size)
        assertTrue(plaintextRoot.listFiles().orEmpty().isEmpty())
        assertFalse(backend.output.exists())
        coordinator.close()
    }

    private inner class RecoverableErrorBackend : CameraXBackend {
        lateinit var output: File
        private var callback: (CameraXBackendEvent) -> Unit = {}
        override suspend fun bind(selector: CameraSelector) {
            assertEquals(CameraSelector.DEFAULT_FRONT_CAMERA, selector)
        }
        override fun start(output: File, audioEnabled: Boolean, callback: (CameraXBackendEvent) -> Unit) {
            assertTrue(audioEnabled)
            this.output = output
            InstrumentationRegistry.getInstrumentation().context.assets.open("sample_avc_aac.mp4").use { input ->
                output.outputStream().use(input::copyTo)
            }
            this.callback = callback
        }
        override fun stop() = Unit
        override fun release() = Unit
        fun emit(event: CameraXBackendEvent) = callback(event)
    }

    private data object NoOpRecordingPlayback : RecordingPlayback {
        override val currentPositionMillis: Long = 0
        override fun play() = Unit
        override fun stop() = Unit
    }

    private companion object {
        const val PATIENT = "123e4567-e89b-12d3-a456-426614174000"
        const val DRAFT = "recoverable-error"
    }
}
