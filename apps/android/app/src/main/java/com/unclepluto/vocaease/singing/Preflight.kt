package com.unclepluto.vocaease.singing

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.unclepluto.vocaease.catalog.CatalogGateway
import com.unclepluto.vocaease.catalog.CatalogSong
import java.io.File
import java.security.MessageDigest

data class AudioRouteSnapshot(
    val inputType: String,
    val outputRoute: String,
    val bluetoothMode: String?,
    val hasHeadphones: Boolean,
) {
    val stableKey: String = "$inputType|$outputRoute|${bluetoothMode.orEmpty()}"
}

data class PreflightResult(
    val route: AudioRouteSnapshot,
    val snapshot: DeviceSnapshot,
    val recordingParameters: RecordingParameters,
    val backingTrack: File,
    val availableBytes: Long,
)

class SingingPreflight(
    private val context: Context,
    private val catalogGateway: CatalogGateway,
) {
    fun run(token: String, song: CatalogSong): PreflightResult {
        val route = currentAudioRoute(context)
        val available = context.filesDir.usableSpace
        val required = requiredRecordingBytes(song.durationMs)
        check(available >= required) { "设备可用空间不足，请至少释放 ${required / 1024 / 1024} MB" }
        val parameters = probeRecordingParameters(context)
        val track = BackingTrackCache(context, catalogGateway).get(token, song)
        check(track.isFile && track.length() > 0) { "伴奏缓存失败，请检查网络后重试" }
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return PreflightResult(
            route = route,
            snapshot = DeviceSnapshot(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                appVersion = packageInfo.versionName ?: "unknown",
                inputType = route.inputType,
                outputRoute = route.outputRoute,
                bluetoothMode = route.bluetoothMode,
                sampleRate = parameters.sampleRate,
                channels = parameters.channels,
                bitDepth = parameters.bitDepth,
            ),
            recordingParameters = parameters,
            backingTrack = track,
            availableBytes = available,
        )
    }
}

data class RecordingParameters(
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val audioSource: Int,
    val bufferBytes: Int,
)

@SuppressLint("MissingPermission")
fun probeRecordingParameters(context: Context): RecordingParameters {
    val source = preferredAudioSource(context)
    val minimum = AudioRecord.getMinBufferSize(
        RAW_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    check(minimum > 0) { "设备不支持 48 kHz 单声道录音" }
    val bufferBytes = maxOf(minimum * 2, RAW_SAMPLE_RATE / 5 * RAW_BYTES_PER_FRAME)
    val record = AudioRecord.Builder()
        .setAudioSource(source)
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(RAW_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
        )
        .setBufferSizeInBytes(bufferBytes)
        .build()
    return try {
        check(record.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
        RecordingParameters(
            sampleRate = record.sampleRate,
            channels = record.channelCount,
            bitDepth = 16,
            audioSource = source,
            bufferBytes = bufferBytes,
        )
    } finally {
        record.release()
    }
}

fun preferredAudioSource(context: Context): Int {
    val audioManager = context.getSystemService(AudioManager::class.java)
    val supportsUnprocessed =
        audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    return if (supportsUnprocessed) {
        MediaRecorder.AudioSource.UNPROCESSED
    } else {
        MediaRecorder.AudioSource.MIC
    }
}

fun requiredRecordingBytes(songDurationMs: Long): Long {
    val rawBytes = (songDurationMs + PRE_DURATION_MS + POST_DURATION_MS) *
        RAW_SAMPLE_RATE * RAW_BYTES_PER_FRAME / 1_000
    return rawBytes * 2 + 20L * 1024 * 1024
}

fun currentAudioRoute(context: Context): AudioRouteSnapshot {
    val manager = context.getSystemService(AudioManager::class.java)
    val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    val output = outputs.firstOrNull { it.type in HEADPHONE_TYPES }
        ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        ?: outputs.firstOrNull()
    val input = inputs.firstOrNull { it.type in EXTERNAL_INPUT_TYPES }
        ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        ?: inputs.firstOrNull()
    val outputRoute = audioDeviceTypeLabel(output?.type, output = true)
    return AudioRouteSnapshot(
        inputType = audioDeviceTypeLabel(input?.type, output = false),
        outputRoute = outputRoute,
        bluetoothMode = when (output?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "a2dp"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "sco"
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            -> "ble"
            else -> null
        },
        hasHeadphones = output?.type?.let { it in HEADPHONE_TYPES } == true,
    )
}

fun audioDeviceTypeLabel(type: Int?, output: Boolean): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built_in_mic"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> if (output) "wired_headset" else "headset_mic"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> if (output) "bluetooth_sco" else "bluetooth_mic"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "bluetooth_ble_headset"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth_ble_speaker"
    AudioDeviceInfo.TYPE_USB_HEADSET -> if (output) "usb_headset" else "usb_mic"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_audio"
    null -> "unknown"
    else -> "other"
}

fun isHeadphoneDeviceType(type: Int): Boolean = type in HEADPHONE_TYPES

class BackingTrackCache(
    context: Context,
    private val gateway: CatalogGateway,
) {
    private val directory = File(context.filesDir, "backing-cache").apply { mkdirs() }

    fun get(token: String, song: CatalogSong): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest("${song.id}|${song.lyricVersionId}|${song.backingTrackUrl}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(directory, "$key.m4a")
        if (target.isFile && target.length() > 0) return target
        val temporary = File(directory, "$key.download")
        temporary.outputStream().use { it.write(gateway.loadMedia(token, song.backingTrackUrl)) }
        check(temporary.length() > 0) { "伴奏文件为空" }
        check(temporary.renameTo(target)) { "伴奏缓存落盘失败" }
        return target
    }
}

const val RAW_SAMPLE_RATE = 48_000
const val RAW_BYTES_PER_FRAME = 2
const val PRE_DURATION_MS = 3_000L
const val POST_DURATION_MS = 3_000L

private val HEADPHONE_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
)

private val EXTERNAL_INPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
)
