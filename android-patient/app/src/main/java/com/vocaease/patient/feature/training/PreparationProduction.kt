package com.vocaease.patient.feature.training

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.os.StatFs
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import com.vocaease.patient.core.media.PreviewGrant
import com.vocaease.patient.core.media.PreviewGrantSource
import com.vocaease.patient.core.network.NetworkContractException
import com.vocaease.patient.core.network.PatientApi
import com.vocaease.patient.core.network.dto.CreateSessionRequestDto
import com.vocaease.patient.core.network.dto.asInstant
import com.vocaease.patient.core.network.dto.toDomain
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class VocaEasePreparationSongSource(
    private val api: PatientApi,
) : PreparationSongSource {
    override suspend fun load(songId: String): PreparationSong {
        val song = api.song(songId).data.toDomain()
        if (song.durationSeconds <= 0) throw NetworkContractException("歌曲时长无效")
        return PreparationSong(song.id, song.title, song.artist, song.durationSeconds)
    }
}

class VocaEasePreviewGrantSource(
    private val api: PatientApi,
) : PreviewGrantSource {
    override suspend fun fetch(songId: String): PreviewGrant = fetch(songId, com.vocaease.patient.core.media.SongPlaybackMode.ORIGINAL, null)
    override suspend fun fetch(songId: String, mode: com.vocaease.patient.core.media.SongPlaybackMode, sessionId: String?): PreviewGrant {
        if (sessionId == null) {
            val grant=api.previewSong(songId,mode.wire).data
            val offset=if(mode==com.vocaease.patient.core.media.SongPlaybackMode.ACCOMPANIMENT) {
                if(!grant.alignmentVerified || grant.accompanimentOffsetMs==null) throw java.io.IOException("伴奏起点尚未核验")
                grant.accompanimentOffsetMs
            } else 0L
            val url=grant.toDomain()
            return PreviewGrant(url.url,url.expiresAt,timelineOffsetMillis=offset)
        }
        val binding = api.session(sessionId).data.playback ?: error("会话缺少媒体快照")
        val grant = api.sessionSongPlayback(sessionId, mode.wire).data
        val expected = if (mode == com.vocaease.patient.core.media.SongPlaybackMode.ORIGINAL) binding.sourceAssetId else binding.accompanimentAssetId
        require(grant.assetId.isNotBlank() && grant.assetId == expected) { "会话媒体版本不一致" }
        val offset=if(mode==com.vocaease.patient.core.media.SongPlaybackMode.ACCOMPANIMENT) {
            if(!binding.alignmentVerified || binding.accompanimentOffsetMs==null) throw java.io.IOException("会话伴奏起点尚未核验")
            binding.accompanimentOffsetMs
        } else 0L
        return PreviewGrant(grant.url, grant.expiresAt.asInstant("grant.expires_at"), grant.assetId, binding,offset)
    }
}

class VocaEaseTrainingSessionCreator(
    private val api: PatientApi,
) : TrainingSessionCreator {
    override suspend fun create(songId: java.util.UUID, creationKey: String): CreatedTrainingSession {
        val session = api.createSession(creationKey, CreateSessionRequestDto(songId.toString())).data.toDomain()
        return CreatedTrainingSession(
            sessionId = session.id,
            patientId = session.patient.id,
            song = PreparationSong(
                id = session.song.id,
                title = session.song.title,
                artist = session.song.artist,
                durationSeconds = session.song.durationSeconds,
            ),
        )
    }
}

class SavedStatePreparationState(
    private val handle: SavedStateHandle,
) : PreparationSavedState {
    override var draftId: String?
        get() = handle[DRAFT_ID]
        set(value) { handle[DRAFT_ID] = value }

    override var accountScopeHash: String?
        get() = handle[ACCOUNT_SCOPE_HASH]
        set(value) { handle[ACCOUNT_SCOPE_HASH] = value }

    private companion object {
        const val DRAFT_ID = "preparation_draft_id"
        const val ACCOUNT_SCOPE_HASH = "preparation_account_scope_hash"
    }
}

class AndroidReadinessSource(
    private val activity: Activity,
    private val permissionsRequested: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val isOnline: () -> Boolean = { AndroidAppConnectivity(activity.applicationContext).isOnline() },
) : ReadinessSource {
    override suspend fun inspect(durationSeconds: Long, previewBuffered: Boolean): DeviceReadiness {
        val context = activity.applicationContext
        return DeviceReadiness(
            cameraPermission = permissionState(Manifest.permission.CAMERA),
            audioPermission = permissionState(Manifest.permission.RECORD_AUDIO),
            availableBytes = StatFs(context.filesDir.absolutePath).availableBytes,
            durationSeconds = durationSeconds,
            previewBuffered = previewBuffered,
            online = isOnline(),
            frontCameraAvailable = withContext(ioDispatcher) {
                runCatching {
                    ProcessCameraProvider.getInstance(context).get(
                        CAMERA_PROVIDER_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                    ).hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                }.getOrDefault(false)
            },
            headphonesConnected = context.headphonesConnected(),
        )
    }

    private fun permissionState(permission: String): PermissionReadiness {
        if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) {
            return PermissionReadiness.GRANTED
        }
        return if (
            permissionsRequested() &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        ) {
            PermissionReadiness.PERMANENTLY_DENIED
        } else {
            PermissionReadiness.DENIED
        }
    }

    private companion object {
        const val CAMERA_PROVIDER_TIMEOUT_SECONDS = 5L
    }
}

class AndroidPreparationEnvironmentMonitor(context: Context) : PreparationEnvironmentMonitor {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(onChanged: () -> Unit) {
        val manager = connectivity ?: return
        synchronized(lock) {
            if (callback != null) return
            val registered = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = notifyIfCurrent(this, onChanged)
                override fun onLost(network: Network) = notifyIfCurrent(this, onChanged)
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    notifyIfCurrent(this, onChanged)
            }
            callback = registered
            try {
                manager.registerDefaultNetworkCallback(registered)
            } catch (error: RuntimeException) {
                callback = null
                throw error
            }
        }
    }

    override fun stop() {
        val manager = connectivity ?: return
        val registered = synchronized(lock) { callback.also { callback = null } } ?: return
        runCatching { manager.unregisterNetworkCallback(registered) }
    }

    private fun notifyIfCurrent(
        source: ConnectivityManager.NetworkCallback,
        listener: () -> Unit,
    ) {
        if (synchronized(lock) { callback === source }) listener()
    }
}

interface AppConnectivity {
    fun isOnline(): Boolean
    fun environmentMonitor(): PreparationEnvironmentMonitor
}

class AndroidAppConnectivity(context: Context) : AppConnectivity {
    private val applicationContext = context.applicationContext

    override fun isOnline(): Boolean {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun environmentMonitor(): PreparationEnvironmentMonitor =
        AndroidPreparationEnvironmentMonitor(applicationContext)
}

private fun Context.headphonesConnected(): Boolean {
    val audio = getSystemService(AudioManager::class.java) ?: return false
    return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
        device.type in setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
