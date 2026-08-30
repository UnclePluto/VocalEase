package com.vocaease.patient.feature.upload

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.vocaease.patient.VocaEaseApplication
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

interface UploadWorkerGateway {
    suspend fun run(contract: UploadWorkContract, onProgress: (Int) -> Unit): UploadRunResult
    suspend fun pause(contract: UploadWorkContract)
    fun stop(contract: UploadWorkContract)
}

object UploadNotificationPermission {
    fun shouldRequest(sdk: Int, triggeredByLocalReviewConfirm: Boolean, granted: Boolean): Boolean =
        sdk >= 33 && triggeredByLocalReviewConfirm && !granted
}

class UploadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    @Volatile private var activeContract: UploadWorkContract? = null
    @Volatile private var gateway: UploadWorkerGateway? = null

    override suspend fun doWork(): Result {
        val contract = parseContract() ?: return Result.failure()
        val currentGateway = applicationGateway() ?: return Result.failure()
        activeContract = contract
        gateway = currentGateway
        setForeground(UploadForegroundNotification.info(applicationContext, contract, 0))
        return try {
            when (
                withTimeout(contract.maxRunMillis) {
                    currentGateway.run(contract) { percent ->
                        val safePercent = percent.coerceIn(0, 100)
                        setProgressAsync(androidx.work.workDataOf(PROGRESS_KEY to safePercent))
                        setForegroundAsync(UploadForegroundNotification.info(applicationContext, contract, safePercent))
                    }
                }
            ) {
                UploadRunResult.Analyzing, UploadRunResult.Paused, is UploadRunResult.TerminalFailure -> Result.success()
                UploadRunResult.Retry -> Result.retry()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        } finally {
            currentGateway.stop(contract)
            activeContract = null
            gateway = null
        }
    }

    private fun parseContract(): UploadWorkContract? = runCatching {
        UploadWorkContract(
            requireNotNull(inputData.getString(UploadWorkContract.ACCOUNT_SCOPE_HASH_KEY)),
            requireNotNull(inputData.getString(UploadWorkContract.DRAFT_ID_KEY)),
        )
    }.getOrNull()

    private fun applicationGateway(): UploadWorkerGateway? =
        ((applicationContext as? VocaEaseApplication)?.container?.uploadFactory?.create() as? UploadWorkerGateway)

    internal companion object {
        const val PROGRESS_KEY = "progress_percent"
    }
}

class UploadPauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE || intent.component?.className != javaClass.name) return
        val contract = runCatching {
            UploadWorkContract(
                requireNotNull(intent.getStringExtra(UploadWorkContract.ACCOUNT_SCOPE_HASH_KEY)),
                requireNotNull(intent.getStringExtra(UploadWorkContract.DRAFT_ID_KEY)),
            )
        }.getOrNull() ?: return
        val application = context.applicationContext as? VocaEaseApplication ?: return
        val gateway = application.container.uploadFactory.create() as? UploadWorkerGateway ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                gateway.pause(contract)
            } finally {
                pending.finish()
            }
        }
    }

    internal companion object {
        const val ACTION_PAUSE = "com.vocaease.patient.action.PAUSE_UPLOAD"
    }
}

internal object UploadForegroundNotification {
    private const val CHANNEL_ID = "singing_upload"

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "演唱上传", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示患者主动提交后的上传进度"
                setShowBadge(false)
            },
        )
    }

    fun info(context: Context, contract: UploadWorkContract, progress: Int): ForegroundInfo {
        createChannelWithoutPermissionRequirement(context)
        val pauseIntent = Intent(context, UploadPauseReceiver::class.java).apply {
            action = UploadPauseReceiver.ACTION_PAUSE
            putExtra(UploadWorkContract.ACCOUNT_SCOPE_HASH_KEY, contract.accountScopeHash)
            putExtra(UploadWorkContract.DRAFT_ID_KEY, contract.draftId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId(contract),
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("演唱上传")
            .setContentText(if (progress <= 0) "正在准备上传" else "正在上传 $progress%")
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "暂停", pendingIntent)
            .build()
        val id = notificationId(contract)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun createChannelWithoutPermissionRequirement(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "演唱上传", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示患者主动提交后的上传进度"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun notificationId(contract: UploadWorkContract): Int = MessageDigest.getInstance("SHA-256")
        .digest(contract.uniqueWorkName.toByteArray())
        .take(4)
        .fold(0) { result, byte -> (result shl 8) or (byte.toInt() and 0xff) }
        .and(0x7fffffff)
}
