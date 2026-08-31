package com.vocaease.patient.feature.history

import java.net.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PrivateVideoAsset(
    val assetId: String,
    val mediaType: String,
    val status: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class PrivateVideoSession(
    val sessionId: String,
    val media: List<PrivateVideoAsset>,
)

data class PrivateVideoGrant(
    val privateUrl: String,
    val expiresAtEpochMillis: Long,
)

interface PrivateVideoRemote {
    suspend fun session(sessionId: String): PrivateVideoSession
    suspend fun privateUrl(assetId: String): PrivateVideoGrant
}

interface PrivateVideoEngine {
    fun setHttpErrorListener(listener: (sourceId: String, statusCode: Int) -> Unit) = Unit
    suspend fun load(sourceId: String, privateUrl: String, positionMillis: Long)
    suspend fun currentPositionMillis(): Long
    suspend fun stopAndClear()
    suspend fun releaseAndAwait()
}

sealed interface PrivateVideoState {
    data object Unavailable : PrivateVideoState
    data object Loading : PrivateVideoState
    data class Ready(val sourceId: String) : PrivateVideoState
    data class Failed(val message: String) : PrivateVideoState
}

/** Compose 路由退出后仍存活到真实播放器释放完成；关闭后拒绝所有迟到回调。 */
class PrivateVideoRouteLifecycle(
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val closed = AtomicBoolean()
    private val lock = Any()
    private var releaseJob: Job? = null
    val isClosed: Boolean get() = closed.get()

    fun launch(operation: suspend () -> Unit): Job? {
        if (closed.get()) return null
        return scope.launch { if (!closed.get()) operation() }
    }

    fun release(operation: suspend () -> Unit): Job = synchronized(lock) {
        releaseJob ?: run {
            closed.set(true)
            scope.launch {
                try {
                    operation()
                } finally {
                    scope.cancel()
                }
            }.also { releaseJob = it }
        }
    }

    /** 资源尚未创建时不消耗只能使用一次的退出作用域。 */
    fun releaseWhenReady(operation: (suspend () -> Unit)?): Job? = operation?.let(::release)
}

/**
 * 私有视频控制器。签名 URL 只存在于本实例的内存缓存和调用栈中，绝不进入公开状态。
 */
class PrivateSessionVideoPlayer(
    private val remote: PrivateVideoRemote,
    private val engine: PrivateVideoEngine,
    private val nowEpochMillis: () -> Long,
    private val leaseActive: () -> Boolean,
    launchPlaybackError: ((suspend () -> Unit) -> Unit) = {},
) {
    private val stateLock = Any()
    private val engineLock = Mutex()
    private val grants = mutableMapOf<String, PrivateVideoGrant>()
    private var operationGeneration = 0L
    private var currentSessionId: String? = null
    private var currentAssetId: String? = null
    private var currentSourceId: String? = null
    private var authenticationRefreshUsed = false
    private var released = false

    private val mutableState = MutableStateFlow<PrivateVideoState>(PrivateVideoState.Unavailable)
    val stateFlow: StateFlow<PrivateVideoState> = mutableState.asStateFlow()
    val state: PrivateVideoState get() = mutableState.value

    /** 公开层永远拿不到签名地址。 */
    val publicPlaybackUrl: String? get() = null

    init {
        engine.setHttpErrorListener { sourceId, statusCode ->
            launchPlaybackError { onPlaybackHttpError(sourceId, statusCode) }
        }
    }

    suspend fun open(sessionId: String) {
        if (!validIdentifier(sessionId)) {
            failUnavailable()
            return
        }
        val generation = synchronized(stateLock) {
            if (released || !leaseActive()) return
            operationGeneration += 1
            currentSessionId = sessionId
            currentAssetId = null
            currentSourceId = null
            authenticationRefreshUsed = false
            mutableState.value = PrivateVideoState.Loading
            operationGeneration
        }
        val detail = runCatching { remote.session(sessionId) }.getOrNull()
        val asset = detail?.validatedVideoFor(sessionId)
        if (asset == null) {
            failIfCurrent(generation, "视频暂不可用")
            return
        }
        val grant = cachedGrant(asset.assetId) ?: runCatching { remote.privateUrl(asset.assetId) }.getOrNull()
        if (grant == null || !grant.isUsable(nowEpochMillis())) {
            failIfCurrent(generation, "视频暂不可用")
            return
        }
        synchronized(stateLock) {
            if (isCurrent(generation, sessionId)) grants[asset.assetId] = grant
        }
        loadIfCurrent(generation, sessionId, asset.assetId, grant, 0L, refreshIndex = 0)
    }

    suspend fun onPlaybackHttpError(sourceId: String, statusCode: Int) {
        val pending = synchronized(stateLock) {
            if (released || !leaseActive() || sourceId != currentSourceId) return
            if (statusCode !in AUTHENTICATION_FAILURES || authenticationRefreshUsed) {
                mutableState.value = PrivateVideoState.Failed(PLAYBACK_FAILURE)
                return
            }
            val sessionId = currentSessionId ?: return
            val assetId = currentAssetId ?: return
            authenticationRefreshUsed = true
            grants.remove(assetId)
            mutableState.value = PrivateVideoState.Loading
            RefreshRequest(operationGeneration, sessionId, assetId, 0L)
        }
        val refresh = pending.copy(positionMillis = engine.currentPositionMillis().coerceAtLeast(0))
        val grant = runCatching { remote.privateUrl(refresh.assetId) }.getOrNull()
        if (grant == null || !grant.isUsable(nowEpochMillis())) {
            failIfCurrent(refresh.generation, PLAYBACK_FAILURE)
            return
        }
        synchronized(stateLock) {
            if (isCurrent(refresh.generation, refresh.sessionId)) grants[refresh.assetId] = grant
        }
        loadIfCurrent(
            refresh.generation,
            refresh.sessionId,
            refresh.assetId,
            grant,
            refresh.positionMillis,
            refreshIndex = 1,
        )
    }

    suspend fun invalidateLease() {
        synchronized(stateLock) {
            operationGeneration += 1
            currentSessionId = null
            currentAssetId = null
            currentSourceId = null
            grants.clear()
            mutableState.value = PrivateVideoState.Unavailable
        }
        engineLock.withLock { engine.stopAndClear() }
    }

    suspend fun releaseAndAwait() {
        val shouldRelease = synchronized(stateLock) {
            if (released) false else {
                released = true
                operationGeneration += 1
                currentSessionId = null
                currentAssetId = null
                currentSourceId = null
                grants.clear()
                mutableState.value = PrivateVideoState.Unavailable
                true
            }
        }
        if (shouldRelease) engineLock.withLock { engine.releaseAndAwait() }
    }

    private suspend fun loadIfCurrent(
        generation: Long,
        sessionId: String,
        assetId: String,
        grant: PrivateVideoGrant,
        positionMillis: Long,
        refreshIndex: Int,
    ) = engineLock.withLock {
        val sourceId = "$assetId#private-$generation-$refreshIndex"
        val canLoad = synchronized(stateLock) {
            if (!isCurrent(generation, sessionId)) false else {
                currentAssetId = assetId
                currentSourceId = sourceId
                true
            }
        }
        if (!canLoad) return@withLock
        val loaded = runCatching { engine.load(sourceId, grant.privateUrl, positionMillis) }.isSuccess
        synchronized(stateLock) {
            if (isCurrent(generation, sessionId) && currentSourceId == sourceId) {
                mutableState.value = if (loaded) PrivateVideoState.Ready(sourceId) else PrivateVideoState.Failed(PLAYBACK_FAILURE)
            }
        }
    }

    private fun cachedGrant(assetId: String): PrivateVideoGrant? = synchronized(stateLock) {
        grants[assetId]?.takeIf { it.isUsable(nowEpochMillis()) }
    }

    private fun failUnavailable() = synchronized(stateLock) {
        if (!released) mutableState.value = PrivateVideoState.Failed("视频暂不可用")
    }

    private fun failIfCurrent(generation: Long, message: String) = synchronized(stateLock) {
        if (generation == operationGeneration && !released) mutableState.value = PrivateVideoState.Failed(message)
    }

    private fun isCurrent(generation: Long, sessionId: String): Boolean =
        !released && leaseActive() && generation == operationGeneration && sessionId == currentSessionId

    private data class RefreshRequest(
        val generation: Long,
        val sessionId: String,
        val assetId: String,
        val positionMillis: Long,
    )

    private companion object {
        const val URL_SAFETY_MARGIN_MILLIS = 30_000L
        const val MAX_VIDEO_SIZE_BYTES = 4L * 1_024 * 1_024 * 1_024
        const val PLAYBACK_FAILURE = "视频加载失败，请重试"
        val AUTHENTICATION_FAILURES = setOf(401, 403)

        fun validIdentifier(value: String): Boolean = value.isNotBlank() && value.length <= 128

        fun PrivateVideoSession.validatedVideoFor(requestedSessionId: String): PrivateVideoAsset? {
            if (sessionId != requestedSessionId || !validIdentifier(sessionId)) return null
            val videos = media.filter { it.mediaType == "singing_video" }
            if (videos.size != 1) return null
            return videos.single().takeIf {
                validIdentifier(it.assetId) && it.status == "ready" && it.mimeType == "video/mp4" &&
                    it.sizeBytes in 1..MAX_VIDEO_SIZE_BYTES
            }
        }

        fun PrivateVideoGrant.isUsable(now: Long): Boolean {
            if (expiresAtEpochMillis <= now + URL_SAFETY_MARGIN_MILLIS) return false
            val uri = runCatching { URI(privateUrl) }.getOrNull() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
        }
    }
}
