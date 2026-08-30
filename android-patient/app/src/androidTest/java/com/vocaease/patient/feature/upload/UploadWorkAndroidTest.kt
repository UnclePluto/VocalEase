package com.vocaease.patient.feature.upload

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadWorkAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val contract = UploadWorkContract("a".repeat(64), "draft-1")

    @Test
    fun request真实配置为唯一联网30秒指数退避且无敏感输入() {
        val request = contract.request()

        assertEquals(setOf("account_scope_hash", "draft_id"), request.workSpec.input.keyValueMap.keys)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
        assertTrue(request.tags.contains(UploadWorkContract.WORK_TAG))
        assertTrue(request.tags.contains(UploadWorkContract.ACCOUNT_TAG_PREFIX + contract.accountScopeHash))
    }

    @Test
    fun 前台通知使用dataSync安全文案且pause接收器不可导出() {
        val info = UploadForegroundNotification.info(context, contract, 37)
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        }
        val title = info.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = info.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals("演唱上传", title)
        assertEquals("正在上传 37%", text)
        listOf(title, text).forEach { safe ->
            assertFalse(safe.contains("patient", ignoreCase = true))
            assertFalse(safe.contains("token", ignoreCase = true))
            assertFalse(safe.contains(contract.accountScopeHash))
            assertFalse(safe.contains(contract.draftId))
        }
        val receiver = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getReceiverInfo(
                ComponentName(context, UploadPauseReceiver::class.java),
                PackageManager.ComponentInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getReceiverInfo(ComponentName(context, UploadPauseReceiver::class.java), 0)
        }
        assertFalse(receiver.exported)
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel("singing_upload")
        assertEquals("演唱上传", channel.name.toString())
        assertTrue(context.getSystemService(NotificationManager::class.java).notificationChannels.none {
            it.name.toString().contains("提醒")
        })
    }
}
