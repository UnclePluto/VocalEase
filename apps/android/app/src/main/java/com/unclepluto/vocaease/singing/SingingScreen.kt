package com.unclepluto.vocaease.singing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.unclepluto.vocaease.catalog.CatalogGateway
import com.unclepluto.vocaease.catalog.CatalogSong
import com.unclepluto.vocaease.catalog.activeLyricIndex
import com.unclepluto.vocaease.catalog.formatDuration
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureInterruptionRegistry {
    @Volatile
    private var handler: ((InterruptionReason) -> Unit)? = null

    fun register(value: ((InterruptionReason) -> Unit)?) {
        handler = value
    }

    fun appBackgrounded() {
        handler?.invoke(InterruptionReason.APP_BACKGROUNDED)
    }
}

private enum class SingingUiState {
    NEED_PERMISSION,
    CHECKING,
    READY,
    FIRST_HEADPHONE_WARNING,
    SECOND_HEADPHONE_WARNING,
    CREATING,
    PRE,
    SINGING,
    POST,
    PROCESSING,
    UPLOAD,
    INTERRUPTED,
}

@Composable
fun SingingSessionScreen(
    activity: ComponentActivity,
    token: String,
    baseUrl: String,
    song: CatalogSong,
    accompanimentVolume: Float,
    catalogGateway: CatalogGateway,
    gateway: SingingGateway,
    interruptionRegistry: CaptureInterruptionRegistry,
    onExit: () -> Unit,
) {
    val context = activity
    val scope = rememberCoroutineScope()
    val store = remember { LocalCaptureStore(context) }
    var state by remember {
        mutableStateOf(
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                SingingUiState.CHECKING
            } else {
                SingingUiState.NEED_PERMISSION
            }
        )
    }
    var preflight by remember { mutableStateOf<PreflightResult?>(null) }
    var error by remember { mutableStateOf("") }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var usedHeadphones by remember { mutableStateOf(true) }
    var riskConfirmed by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var recorder by remember { mutableStateOf<RawVoiceRecorder?>(null) }
    var recordingJob by remember { mutableStateOf<Deferred<RecordingResult>?>(null) }
    var flowJob by remember { mutableStateOf<Job?>(null) }
    var accompanimentStartFrame by remember { mutableLongStateOf(0) }
    var audioStartMonotonicNs by remember { mutableLongStateOf(0) }
    var accompanimentStartMonotonicNs by remember { mutableLongStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0) }
    var routeRisk by remember { mutableStateOf(false) }
    var qualitySummary by remember { mutableStateOf("") }
    var uploadStatus by remember { mutableStateOf<PendingCapture?>(null) }
    var playbackMix by remember { mutableStateOf<PlaybackMix?>(null) }
    var playbackMessage by remember { mutableStateOf("") }
    var experiencePlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    val interruptionStarted = remember { AtomicBoolean(false) }
    val phaseMachine = remember { ThreePhaseStateMachine() }

    fun runPreflight() {
        state = SingingUiState.CHECKING
        error = ""
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    SingingPreflight(context, catalogGateway).run(token, song)
                }
            }.onSuccess {
                preflight = it
                usedHeadphones = it.route.hasHeadphones
                state = when (initialHeadphonePrompt(it.route.hasHeadphones)) {
                    HeadphonePrompt.READY -> SingingUiState.READY
                    HeadphonePrompt.FIRST_WARNING -> SingingUiState.FIRST_HEADPHONE_WARNING
                    else -> error("非法耳机预检状态")
                }
            }.onFailure {
                error = it.message ?: "录制前检查失败"
                state = SingingUiState.CHECKING
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) runPreflight() else {
            state = SingingUiState.NEED_PERMISSION
            error = "必须允许麦克风权限才能采集原始人声"
        }
    }

    fun interrupt(reason: InterruptionReason) {
        val id = sessionId ?: return
        if (!interruptionStarted.compareAndSet(false, true)) return
        if (phaseMachine.phase !in setOf(CapturePhase.COMPLETED, CapturePhase.INTERRUPTED)) {
            phaseMachine.transition(CaptureEvent.INTERRUPT)
        }
        routeRisk = routeRisk || reason == InterruptionReason.ROUTE_CHANGED
        flowJob?.cancel()
        recorder?.stop()
        player?.stop()
        player?.release()
        player = null
        store.markInterruptionPending(reason)
        state = SingingUiState.INTERRUPTED
        error = interruptionMessage(reason)
        scope.launch {
            runCatching { recordingJob?.await() }
            store.active()?.let { File(it.filePath).delete() }
            val synced = runCatching {
                withContext(Dispatchers.IO) { gateway.interrupt(token, id, reason) }
            }.isSuccess
            if (synced) store.clearActive(deletePartial = true)
        }
    }

    fun beginCapture() {
        val checked = preflight ?: return
        state = SingingUiState.CREATING
        error = ""
        scope.launch {
            val remote = runCatching {
                withContext(Dispatchers.IO) {
                    gateway.createSession(
                        token = token,
                        songId = song.id,
                        backingTrackId = song.backingTrackId,
                        lyricVersionId = song.lyricVersionId,
                        usedHeadphones = usedHeadphones,
                        headphoneRiskConfirmed = riskConfirmed,
                        snapshot = checked.snapshot,
                    )
                }
            }.getOrElse {
                error = it.message ?: "无法创建演唱会话"
                state = SingingUiState.READY
                return@launch
            }
            sessionId = remote.id
            val target = File(
                File(context.filesDir, "raw-voices").apply { mkdirs() },
                "${UUID.randomUUID()}.wav",
            )
            store.markActive(remote.id, target)
            val rawRecorder = RawVoiceRecorder(target, checked.recordingParameters)
            recorder = rawRecorder
            val localPlayer = localBackingPlayer(
                activity,
                checked.backingTrack,
                accompanimentVolume,
            )
            player = localPlayer
            val playbackEnded = kotlinx.coroutines.CompletableDeferred<Unit>()
            localPlayer.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED && !playbackEnded.isCompleted) {
                            playbackEnded.complete(Unit)
                        }
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (
                            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS &&
                            state == SingingUiState.SINGING
                        ) {
                            interrupt(InterruptionReason.AUDIO_FOCUS_LOST)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        interrupt(InterruptionReason.CAPTURE_ERROR)
                    }
                }
            )
            recordingJob = scope.async(Dispatchers.IO) { rawRecorder.record() }
            flowJob = scope.launch {
                try {
                    val recordingStart = rawRecorder.started.await()
                    audioStartMonotonicNs = recordingStart.monotonicNs
                    if (recordingStart.actualInputType != checked.route.inputType) {
                        interrupt(InterruptionReason.ROUTE_CHANGED)
                        return@launch
                    }
                    phaseMachine.transition(CaptureEvent.RECORDING_STARTED)
                    state = SingingUiState.PRE
                    delay(remote.preDurationMs)
                    accompanimentStartFrame = rawRecorder.currentFrame()
                    accompanimentStartMonotonicNs = SystemClock.elapsedRealtimeNanos()
                    phaseMachine.transition(CaptureEvent.PRE_FINISHED)
                    state = SingingUiState.SINGING
                    localPlayer.play()
                    playbackEnded.await()
                    phaseMachine.transition(CaptureEvent.ACCOMPANIMENT_FINISHED)
                    state = SingingUiState.POST
                    delay(remote.postDurationMs)
                    rawRecorder.stop()
                    state = SingingUiState.PROCESSING
                    val result = recordingJob?.await() ?: error("录音任务不存在")
                    phaseMachine.transition(CaptureEvent.POST_FINISHED)
                    localPlayer.release()
                    player = null
                    val report = withContext(Dispatchers.IO) {
                        analyzeWav(
                            file = result.file,
                            expectedDurationMs =
                                remote.preDurationMs + remote.songDurationMs + remote.postDurationMs,
                            usedHeadphones = usedHeadphones,
                            routeRisk = routeRisk,
                        )
                    }
                    qualitySummary = report.participantSummary
                    store.saveCompleted(
                        remote.id,
                        result.file,
                        accompanimentStartFrame,
                        audioStartMonotonicNs,
                        accompanimentStartMonotonicNs,
                        result.frames,
                        report,
                    )
                    enqueueVoiceUpload(context, remote.id, baseUrl)
                    state = SingingUiState.UPLOAD
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    interrupt(InterruptionReason.CAPTURE_ERROR)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (state == SingingUiState.CHECKING) runPreflight()
    }
    LaunchedEffect(player, state) {
        while (player != null && state == SingingUiState.SINGING) {
            positionMs = player?.currentPosition?.coerceAtLeast(0) ?: 0
            delay(100)
        }
    }
    LaunchedEffect(state, sessionId) {
        while (state == SingingUiState.UPLOAD && sessionId != null) {
            uploadStatus = store.get(sessionId.orEmpty())
            delay(500)
        }
    }
    LaunchedEffect(uploadStatus?.status, sessionId, playbackMix?.status) {
        val id = sessionId ?: return@LaunchedEffect
        if (uploadStatus?.status != LocalUploadStatus.SUBMITTED) return@LaunchedEffect
        val mixClient = PlaybackMixClient(baseUrl)
        while (state == SingingUiState.UPLOAD) {
            runCatching {
                withContext(Dispatchers.IO) { mixClient.status(token, id) }
            }.onSuccess {
                playbackMix = it
                playbackMessage = ""
            }.onFailure {
                playbackMessage = "回放混音正在创建，请稍候…"
            }
            if (playbackMix?.status in setOf("succeeded", "failed")) break
            delay(2_000)
        }
    }

    DisposableEffect(sessionId, state, preflight) {
        val isCapturing = state in setOf(
            SingingUiState.PRE,
            SingingUiState.SINGING,
            SingingUiState.POST,
        )
        interruptionRegistry.register(if (isCapturing) ::interrupt else null)
        val audioManager = context.getSystemService(AudioManager::class.java)
        val expectedRoute = preflight?.route?.stableKey
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                checkRoute()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                checkRoute()
            }

            private fun checkRoute() {
                if (expectedRoute != null && currentAudioRoute(context).stableKey != expectedRoute) {
                    interrupt(InterruptionReason.ROUTE_CHANGED)
                }
            }
        }
        val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(
                configs: MutableList<AudioPlaybackConfiguration>
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
                val actual = configs.firstOrNull {
                    it.audioAttributes.usage == android.media.AudioAttributes.USAGE_MEDIA
                }?.audioDeviceInfo ?: return
                val actualLabel = audioDeviceTypeLabel(actual.type, output = true)
                val expectedOutput = preflight?.route?.outputRoute ?: return
                if (
                    actualLabel != expectedOutput ||
                    (usedHeadphones && !isHeadphoneDeviceType(actual.type))
                ) {
                    interrupt(InterruptionReason.ROUTE_CHANGED)
                }
            }
        }
        if (isCapturing) {
            audioManager.registerAudioDeviceCallback(callback, null)
            audioManager.registerAudioPlaybackCallback(playbackCallback, null)
        }
        onDispose {
            interruptionRegistry.register(null)
            if (isCapturing) {
                runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
                runCatching { audioManager.unregisterAudioPlaybackCallback(playbackCallback) }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { experiencePlayer?.release() }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(song.title, style = MaterialTheme.typography.headlineMedium)
        Text("${song.artist} · 原始人声采集")
        when (state) {
            SingingUiState.NEED_PERMISSION -> PermissionPanel(error) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            SingingUiState.CHECKING -> CheckingPanel(error, ::runPreflight, onExit)
            SingingUiState.READY -> ReadyPanel(preflight, ::beginCapture, onExit)
            SingingUiState.FIRST_HEADPHONE_WARNING -> FirstHeadphoneWarning(
                onRecheck = ::runPreflight,
                onContinue = {
                    check(
                        continueWithoutHeadphones(HeadphonePrompt.FIRST_WARNING) ==
                            HeadphonePrompt.SECOND_WARNING
                    )
                    state = SingingUiState.SECOND_HEADPHONE_WARNING
                },
                onExit = onExit,
            )
            SingingUiState.SECOND_HEADPHONE_WARNING -> SecondHeadphoneWarning(
                onConfirm = {
                    check(
                        continueWithoutHeadphones(HeadphonePrompt.SECOND_WARNING) ==
                            HeadphonePrompt.CONFIRMED_WITH_RISK
                    )
                    usedHeadphones = false
                    riskConfirmed = true
                    beginCapture()
                },
                onBack = { state = SingingUiState.FIRST_HEADPHONE_WARNING },
            )
            SingingUiState.CREATING -> CenteredMessage("正在创建演唱会话…")
            SingingUiState.PRE -> CapturePanel(
                phase = "唱前准备",
                instruction = "录音已经开始，伴奏将在 3 秒后自动播放",
                song = song,
                positionMs = 0,
                onCancel = { interrupt(InterruptionReason.USER_CANCELLED) },
            )
            SingingUiState.SINGING -> CapturePanel(
                phase = "唱中",
                instruction = "请跟随歌词连续演唱，本阶段不能暂停或拖动",
                song = song,
                positionMs = positionMs,
                onCancel = { interrupt(InterruptionReason.USER_CANCELLED) },
            )
            SingingUiState.POST -> CapturePanel(
                phase = "唱后收尾",
                instruction = "伴奏已结束，请保持安静，3 秒后自动完成",
                song = song,
                positionMs = song.durationMs,
                onCancel = { interrupt(InterruptionReason.USER_CANCELLED) },
            )
            SingingUiState.PROCESSING -> CenteredMessage("正在封装原始 WAV 并进行技术质检…")
            SingingUiState.UPLOAD -> UploadPanel(
                summary = qualitySummary,
                capture = uploadStatus,
                mix = playbackMix,
                playbackMessage = playbackMessage,
                onPlayMix = {
                    val id = sessionId ?: return@UploadPanel
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                PlaybackMixClient(baseUrl).access(token, id)
                            }
                        }.onSuccess { access ->
                            experiencePlayer?.release()
                            experiencePlayer = remoteExperiencePlayer(activity, access.url)
                                .also { it.play() }
                            playbackMessage =
                                "正在回听体验混音，授权 ${access.expiresInSeconds} 秒内有效"
                        }.onFailure {
                            playbackMessage = it.message ?: "无法获取回听授权"
                        }
                    }
                },
                onRetryMix = {
                    val id = sessionId ?: return@UploadPanel
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                PlaybackMixClient(baseUrl).retry(token, id)
                            }
                        }.onSuccess {
                            playbackMix = it
                            playbackMessage = "已重新提交回放混音"
                        }.onFailure {
                            playbackMessage = it.message ?: "无法重试回放混音"
                        }
                    }
                },
                onExit = onExit,
            )
            SingingUiState.INTERRUPTED -> InterruptedPanel(error, onExit)
        }
    }
}

@Composable
private fun PermissionPanel(error: String, onRequest: () -> Unit) {
    Text("开始前需要麦克风权限，用于录制未经混音的原始人声。")
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("允许麦克风权限") }
}

@Composable
private fun CheckingPanel(error: String, onRetry: () -> Unit, onExit: () -> Unit) {
    if (error.isEmpty()) {
        CenteredMessage("正在检查存储空间、伴奏缓存和音频路由…")
    } else {
        Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("重新检查") }
        OutlinedButton(onClick = onExit) { Text("返回歌曲") }
    }
}

@Composable
private fun ReadyPanel(
    result: PreflightResult?,
    onStart: () -> Unit,
    onExit: () -> Unit,
) {
    Text("录制前检查通过", color = MaterialTheme.colorScheme.primary)
    Text("✓ 麦克风：48 kHz / 16-bit / 单声道")
    Text("✓ 伴奏：已缓存到 App 私有目录")
    Text("✓ 输出：${result?.route?.outputRoute.orEmpty()}")
    Text("录制只采集麦克风输入，不会把伴奏数字混入原始人声轨。")
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("开始演唱") }
    OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("返回歌曲") }
}

@Composable
private fun FirstHeadphoneWarning(
    onRecheck: () -> Unit,
    onContinue: () -> Unit,
    onExit: () -> Unit,
) {
    Text("建议佩戴耳机", style = MaterialTheme.typography.titleLarge)
    Text("当前未检测到耳机。外放伴奏会被麦克风同时录入，建议连接有线或蓝牙耳机后重新检查。")
    Button(onClick = onRecheck, modifier = Modifier.fillMaxWidth()) { Text("已连接耳机，重新检查") }
    OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text("仍然不戴耳机")
    }
    OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("取消") }
}

@Composable
private fun SecondHeadphoneWarning(onConfirm: () -> Unit, onBack: () -> Unit) {
    Text("严重数据偏差警告", style = MaterialTheme.typography.titleLarge)
    Text(
        "不佩戴耳机会让伴奏串入原始人声，可能严重影响采集质量和后续数据分析。" +
            "系统会永久记录本次无耳机风险，但仍允许继续。"
    )
    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
        Text("我已了解风险，确认不戴耳机")
    }
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
}

@Composable
private fun CapturePanel(
    phase: String,
    instruction: String,
    song: CatalogSong,
    positionMs: Long,
    onCancel: () -> Unit,
) {
    val currentLine = activeLyricIndex(song.lines, positionMs)
    val listState = rememberLazyListState()
    LaunchedEffect(currentLine) {
        if (currentLine >= 0) listState.animateScrollToItem(currentLine)
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(phase, style = MaterialTheme.typography.titleLarge)
        Text(instruction)
        Text("${formatDuration(positionMs)} / ${formatDuration(song.durationMs)}")
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(song.lines.size) { index ->
                val active = index == currentLine
                Text(
                    song.lines[index].text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (active) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            }
                        )
                        .padding(12.dp),
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("取消本次录制")
        }
    }
}

@Composable
private fun UploadPanel(
    summary: String,
    capture: PendingCapture?,
    mix: PlaybackMix?,
    playbackMessage: String,
    onPlayMix: () -> Unit,
    onRetryMix: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(summary, style = MaterialTheme.typography.titleLarge)
        Text(uploadStatusLabel(capture))
        capture?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (capture?.status == LocalUploadStatus.SUBMITTED) {
            when (mix?.status) {
                "succeeded" -> {
                    Text("回放混音已生成，可申请短时授权回听。")
                    Button(onClick = onPlayMix, modifier = Modifier.fillMaxWidth()) {
                        Text("回听体验混音")
                    }
                }
                "failed" -> {
                    Text(
                        mix.failureMessage ?: "回放混音生成失败",
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onRetryMix, modifier = Modifier.fillMaxWidth()) {
                        Text("重新生成回放混音")
                    }
                }
                else -> Text("回放混音正在处理中，请稍候…")
            }
        } else {
            Text("原始录音提交成功后，将自动生成回放混音。")
        }
        if (playbackMessage.isNotEmpty()) Text(playbackMessage)
        Text("回放混音仅用于体验，不是研究原始数据。")
        Text("这是录音技术质量检查，不是演唱评分、疾病判断或自动诊断。")
        Text("服务端确认前，原始录音会保留在本机；确认 7 天后才会自动清理本地副本。")
        Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("返回曲库") }
    }
}

@Composable
private fun InterruptedPanel(message: String, onExit: () -> Unit) {
    Text("本次录制已作废", style = MaterialTheme.typography.titleLarge)
    Text(message)
    Text("中断后不能接唱，请返回曲库并重新开始。")
    Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("返回曲库") }
}

@Composable
private fun CenteredMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(message, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

private fun localBackingPlayer(
    activity: ComponentActivity,
    file: File,
    volume: Float,
): ExoPlayer =
    ExoPlayer.Builder(activity)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            this.volume = volume.coerceIn(0f, 1f)
            prepare()
        }

private fun remoteExperiencePlayer(activity: ComponentActivity, url: String): ExoPlayer =
    ExoPlayer.Builder(activity)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }

private fun interruptionMessage(reason: InterruptionReason): String = when (reason) {
    InterruptionReason.USER_CANCELLED -> "你主动取消了本次录制。"
    InterruptionReason.APP_BACKGROUNDED -> "录制期间应用离开前台。"
    InterruptionReason.AUDIO_FOCUS_LOST -> "录制期间发生来电或音频焦点被其他应用占用。"
    InterruptionReason.ROUTE_CHANGED -> "录制期间耳机或音频输入输出发生变化。"
    InterruptionReason.PROCESS_RECOVERED -> "应用进程在录制期间终止。"
    InterruptionReason.CAPTURE_ERROR -> "麦克风或伴奏播放发生错误。"
}
