package com.vocaease.patient.feature.training

enum class PermissionReadiness {
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED,
}

enum class PreflightBlocker {
    CAMERA_PERMISSION,
    AUDIO_PERMISSION,
    STORAGE,
    PREVIEW_BUFFER,
    OFFLINE,
    FRONT_CAMERA,
}

data class DeviceReadiness(
    val cameraPermission: PermissionReadiness,
    val audioPermission: PermissionReadiness,
    val availableBytes: Long,
    val durationSeconds: Long,
    val previewBuffered: Boolean,
    val online: Boolean,
    val frontCameraAvailable: Boolean,
    val headphonesConnected: Boolean,
)

data class PreflightResult(
    val blockers: Set<PreflightBlocker>,
    val warning: String?,
    val openSettingsRequired: Boolean,
) {
    val canStart: Boolean = blockers.isEmpty()
}

object TrainingPreflight {
    private const val MEBIBYTE = 1024L * 1024L
    private const val MIN_STORAGE_BYTES = 512L * MEBIBYTE
    private const val ESTIMATED_BYTES_PER_SECOND = 8L * MEBIBYTE

    fun evaluate(readiness: DeviceReadiness): PreflightResult {
        val blockers = buildSet {
            if (readiness.cameraPermission != PermissionReadiness.GRANTED) add(PreflightBlocker.CAMERA_PERMISSION)
            if (readiness.audioPermission != PermissionReadiness.GRANTED) add(PreflightBlocker.AUDIO_PERMISSION)
            if (readiness.availableBytes < requiredStorageBytes(readiness.durationSeconds)) add(PreflightBlocker.STORAGE)
            if (!readiness.previewBuffered) add(PreflightBlocker.PREVIEW_BUFFER)
            if (!readiness.online) add(PreflightBlocker.OFFLINE)
            if (!readiness.frontCameraAvailable) add(PreflightBlocker.FRONT_CAMERA)
        }
        return PreflightResult(
            blockers = blockers,
            warning = if (readiness.headphonesConnected) null else "未检测到耳机，使用扬声器可能产生串音",
            openSettingsRequired = readiness.cameraPermission == PermissionReadiness.PERMANENTLY_DENIED ||
                readiness.audioPermission == PermissionReadiness.PERMANENTLY_DENIED,
        )
    }

    fun requiredStorageBytes(durationSeconds: Long): Long {
        val estimated = if (durationSeconds <= 0) {
            0L
        } else if (durationSeconds > Long.MAX_VALUE / ESTIMATED_BYTES_PER_SECOND) {
            Long.MAX_VALUE
        } else {
            durationSeconds * ESTIMATED_BYTES_PER_SECOND
        }
        return maxOf(MIN_STORAGE_BYTES, estimated)
    }
}
