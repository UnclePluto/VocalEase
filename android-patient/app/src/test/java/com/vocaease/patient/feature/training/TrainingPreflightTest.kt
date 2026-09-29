package com.vocaease.patient.feature.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingPreflightTest {
    @Test
    fun `所有硬门禁同时通过才允许开始且耳机缺失只警告`() {
        val ready = readiness()

        assertTrue(TrainingPreflight.evaluate(ready).canStart)
        assertTrue(TrainingPreflight.evaluate(ready.copy(headphonesConnected = false)).canStart)
        assertEquals(
            "未检测到耳机，使用扬声器可能产生串音",
            TrainingPreflight.evaluate(ready.copy(headphonesConnected = false)).warning,
        )

        val blocked = listOf(
            ready.copy(cameraPermission = PermissionReadiness.DENIED),
            ready.copy(audioPermission = PermissionReadiness.DENIED),
            ready.copy(availableBytes = TrainingPreflight.requiredStorageBytes(265) - 1),
            ready.copy(previewBuffered = false),
            ready.copy(online = false),
            ready.copy(frontCameraAvailable = false),
        )
        blocked.forEach { assertFalse(TrainingPreflight.evaluate(it).canStart) }
    }

    @Test
    fun `存储阈值取512MiB与歌曲估算较大值并防止乘法溢出`() {
        val fiveMinutes = 300L
        val expected = fiveMinutes * 8L * 1024L * 1024L

        assertEquals(expected, TrainingPreflight.requiredStorageBytes(fiveMinutes))
        assertEquals(512L * 1024L * 1024L, TrainingPreflight.requiredStorageBytes(1))
        assertEquals(Long.MAX_VALUE, TrainingPreflight.requiredStorageBytes(Long.MAX_VALUE))
        assertTrue(
            TrainingPreflight.evaluate(
                readiness(
                    durationSeconds = fiveMinutes,
                    availableBytes = expected,
                ),
            ).canStart,
        )
    }

    @Test
    fun `任一权限永久拒绝时提供系统设置入口`() {
        val result = TrainingPreflight.evaluate(
            readiness(cameraPermission = PermissionReadiness.PERMANENTLY_DENIED),
        )

        assertFalse(result.canStart)
        assertTrue(result.openSettingsRequired)
        assertTrue(PreflightBlocker.CAMERA_PERMISSION in result.blockers)
    }

    private fun readiness(
        cameraPermission: PermissionReadiness = PermissionReadiness.GRANTED,
        audioPermission: PermissionReadiness = PermissionReadiness.GRANTED,
        durationSeconds: Long = 265,
        availableBytes: Long = Long.MAX_VALUE,
    ) = DeviceReadiness(
        cameraPermission = cameraPermission,
        audioPermission = audioPermission,
        availableBytes = availableBytes,
        durationSeconds = durationSeconds,
        previewBuffered = true,
        online = true,
        frontCameraAvailable = true,
        headphonesConnected = true,
    )
}
