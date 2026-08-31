package com.vocaease.patient.feature.upload

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.vocaease.patient.core.database.AccountScopedDraftStorageProvider
import com.vocaease.patient.core.database.StaleAccountScopeException
import com.vocaease.patient.core.database.UploadPipelineStage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UploadCoordinator(
    context: Context,
    private val storageProvider: AccountScopedDraftStorageProvider,
    private val remoteFactory: (String) -> UploadRemote,
    private val scheduler: UploadWorkScheduling = AndroidUploadWorkScheduler(context),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val uploaderFactory: (File) -> QiniuUploader = ::QiniuV2Uploader,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : UploadWorkerGateway {
    private val applicationContext = context.applicationContext
    private val plaintextRoot = File(applicationContext.cacheDir, "upload-lease")
    private val recorderRoot = File(applicationContext.filesDir, "qiniu-upload-recorder")
    private val active = ConcurrentHashMap<String, ActiveUpload>()

    init {
        PlaintextUploadLeaseManager.cleanupOrphans(plaintextRoot, nowEpochMillis())
    }

    suspend fun schedule(draftId: String) {
        val storage = storageProvider.current()
        val contract = UploadWorkContract(storage.accountScopeHash, draftId)
        val durableJob = storage.findUploadJob(draftId) ?: return
        if (durableJob.pipelineStage == UploadPipelineStage.ANALYZING) {
            scheduler.enqueue(contract, replace = false)
            return
        }
        val store = RoomUploadStore(storage, draftId, nowEpochMillis)
        val current = store.load()
        when (current.stage) {
            UploadStage.PAUSED if current.safeError == RoomUploadStore.USER_PAUSED_MARKER -> return
            UploadStage.PAUSED -> store.resumeFromPause()
            UploadStage.FAILED -> return
            UploadStage.ANALYZING -> Unit
            else -> Unit
        }
        scheduler.enqueue(contract, replace = false)
    }

    suspend fun retry(contract: UploadWorkContract) {
        val storage = requireCurrentStorage(contract)
        if (storage.findUploadJob(contract.draftId)?.pipelineStage == UploadPipelineStage.ANALYZING) return
        val store = RoomUploadStore(storage, contract.draftId, nowEpochMillis)
        val current = store.load()
        if (current.stage in setOf(UploadStage.ANALYZING, UploadStage.SUBMITTING)) return
        when (current.stage) {
            UploadStage.WAITING_NETWORK, UploadStage.FAILED -> if (!store.manualRetry()) return
            UploadStage.PAUSED -> store.resumeFromPause()
            else -> Unit
        }
        scheduler.enqueue(contract, replace = true)
    }

    override suspend fun run(contract: UploadWorkContract, onProgress: (Int) -> Unit): UploadRunResult {
        val lock = executionLocks.computeIfAbsent(contract.uniqueWorkName) { Mutex() }
        return lock.withLock {
            val storage = requireCurrentStorage(contract)
            val durableJob = storage.findUploadJob(contract.draftId) ?: return@withLock UploadRunResult.Paused
            if (durableJob.pipelineStage == UploadPipelineStage.ANALYZING) {
                storage.deleteSubmittedUploadMedia(contract.draftId)
                return@withLock UploadRunResult.Analyzing
            }
            val store = RoomUploadStore(storage, contract.draftId, nowEpochMillis)
            val initial = store.load()
            if (initial.stage == UploadStage.PAUSED) return@withLock UploadRunResult.Paused
            val uploader = uploaderFactory(QiniuRecorderDirectory.create(recorderRoot, contract.accountScopeHash, contract.draftId))
            val revocable = RevocablePlaintextLeaseProvider(store.plaintextLeaseProvider(plaintextRoot))
            val activeUpload = ActiveUpload(uploader, revocable)
            active[contract.uniqueWorkName] = activeUpload
            val registration = storage.onLeaseInvalidated {
                activeUpload.stop()
                scheduler.cancel(contract)
            }
            try {
                UploadOrchestrator(
                    store = store,
                    remote = remoteFactory(contract.accountScopeHash),
                    uploader = uploader,
                    leases = revocable,
                    wait = wait,
                    nowEpochMillis = nowEpochMillis,
                    onProgress = onProgress,
                ).run()
            } finally {
                registration.unregister()
                active.remove(contract.uniqueWorkName, activeUpload)
                activeUpload.stop()
            }
        }
    }

    override suspend fun pause(contract: UploadWorkContract) {
        val storage = requireCurrentStorage(contract)
        if (storage.findUploadJob(contract.draftId)?.pipelineStage == UploadPipelineStage.ANALYZING) return
        val store = RoomUploadStore(storage, contract.draftId, nowEpochMillis)
        val current = store.load()
        if (current.stage != UploadStage.ANALYZING && current.stage != UploadStage.PAUSED) {
            store.pause()
        }
        stop(contract)
        scheduler.cancel(contract)
    }

    override fun stop(contract: UploadWorkContract) {
        active[contract.uniqueWorkName]?.stop()
    }

    suspend fun delete(contract: UploadWorkContract) {
        val storage = requireCurrentStorage(contract)
        storage.requestQueuedUploadDelete(contract.draftId)
        stop(contract)
        scheduler.cancel(contract)
        executionLocks.computeIfAbsent(contract.uniqueWorkName) { Mutex() }.withLock {
            storage.resumeUploadLocalAction(contract.draftId)
        }
    }

    fun cancelAccount(accountScopeHash: String) {
        if (!accountScopeHash.matches(UploadWorkContract.SHA256)) return
        active.filterKeys { it.startsWith("upload:$accountScopeHash:") }.values.forEach(ActiveUpload::stop)
        scheduler.cancelAccount(accountScopeHash)
    }

    private fun requireCurrentStorage(contract: UploadWorkContract) = storageProvider.current().also { storage ->
        if (storage.accountScopeHash != contract.accountScopeHash || !storage.isLeaseActive()) {
            throw StaleAccountScopeException()
        }
    }

    private class ActiveUpload(
        private val uploader: QiniuUploader,
        private val leases: RevocablePlaintextLeaseProvider,
    ) {
        fun stop() {
            uploader.cancel()
            leases.revokeAll()
        }
    }

    private companion object {
        val executionLocks = ConcurrentHashMap<String, Mutex>()
    }
}

interface UploadWorkScheduling {
    fun enqueue(contract: UploadWorkContract, replace: Boolean)
    fun cancel(contract: UploadWorkContract)
    fun cancelAccount(accountScopeHash: String)
}

class AndroidUploadWorkScheduler(context: Context) : UploadWorkScheduling {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun enqueue(contract: UploadWorkContract, replace: Boolean) {
        workManager.enqueueUniqueWork(
            contract.uniqueWorkName,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            contract.request(),
        )
    }

    override fun cancel(contract: UploadWorkContract) {
        workManager.cancelUniqueWork(contract.uniqueWorkName)
    }

    override fun cancelAccount(accountScopeHash: String) {
        workManager.cancelAllWorkByTag(UploadWorkContract.ACCOUNT_TAG_PREFIX + accountScopeHash)
    }
}
