package com.unclepluto.vocaease.catalog

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.unclepluto.vocaease.singing.LocalCaptureStore
import com.unclepluto.vocaease.singing.LocalUploadStatus
import com.unclepluto.vocaease.singing.PendingCapture
import com.unclepluto.vocaease.singing.PlaybackMix
import com.unclepluto.vocaease.singing.PlaybackMixClient
import com.unclepluto.vocaease.singing.enqueueVoiceUpload
import com.unclepluto.vocaease.singing.uploadStatusLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CatalogScreen(
    token: String,
    baseUrl: String,
    gateway: CatalogGateway,
    playerFactory: (AuthenticatedMediaSource) -> ExoPlayer,
    onStartSinging: (CatalogSong, Float) -> Unit,
    onLogout: () -> Unit,
) {
    var selectedSongId by remember { mutableStateOf<String?>(null) }
    if (selectedSongId == null) {
        CatalogList(
            token = token,
            baseUrl = baseUrl,
            gateway = gateway,
            playerFactory = playerFactory,
            onSongSelected = { selectedSongId = it },
            onLogout = onLogout,
        )
    } else {
        CatalogDetail(
            token = token,
            songId = selectedSongId.orEmpty(),
            gateway = gateway,
            playerFactory = playerFactory,
            onStartSinging = onStartSinging,
            onBack = { selectedSongId = null },
        )
    }
}

@Composable
private fun CatalogList(
    token: String,
    baseUrl: String,
    gateway: CatalogGateway,
    playerFactory: (AuthenticatedMediaSource) -> ExoPlayer,
    onSongSelected: (String) -> Unit,
    onLogout: () -> Unit,
) {
    var songs by remember { mutableStateOf<List<CatalogSong>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reloadKey by remember { mutableLongStateOf(0) }

    LaunchedEffect(token, reloadKey) {
        loading = true
        error = ""
        runCatching { withContext(Dispatchers.IO) { gateway.listSongs(token) } }
            .onSuccess { songs = it }
            .onFailure { error = it.message ?: "曲库加载失败" }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("研究曲库", style = MaterialTheme.typography.headlineMedium)
                Text("请选择本次演唱歌曲")
            }
            OutlinedButton(onClick = onLogout) { Text("退出") }
        }
        PendingUploads(token, baseUrl, playerFactory)
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error, color = MaterialTheme.colorScheme.error)
                Button(onClick = { reloadKey++ }) { Text("重新加载") }
            }
            songs.isEmpty() -> Text("当前暂无已发布歌曲")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(songs, key = CatalogSong::id) { song ->
                    SongCard(song, token, gateway) { onSongSelected(song.id) }
                }
            }
        }
    }
}

@Composable
private fun PendingUploads(
    token: String,
    baseUrl: String,
    playerFactory: (AuthenticatedMediaSource) -> ExoPlayer,
) {
    val context = LocalContext.current
    val store = remember { LocalCaptureStore(context) }
    var captures by remember { mutableStateOf<List<PendingCapture>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            captures = store.all()
            delay(500)
        }
    }
    captures.take(3).forEach { capture ->
        CaptureStatusCard(capture, token, baseUrl, playerFactory)
    }
}

@Composable
private fun CaptureStatusCard(
    capture: PendingCapture,
    token: String,
    baseUrl: String,
    playerFactory: (AuthenticatedMediaSource) -> ExoPlayer,
) {
    val context = LocalContext.current
    var mix by remember(capture.sessionId) { mutableStateOf<PlaybackMix?>(null) }
    var message by remember(capture.sessionId) { mutableStateOf("") }
    var playback by remember(capture.sessionId) { mutableStateOf<ExoPlayer?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(capture.status, mix?.status) {
        if (capture.status != LocalUploadStatus.SUBMITTED) return@LaunchedEffect
        while (mix?.status !in setOf("succeeded", "failed")) {
            runCatching {
                withContext(Dispatchers.IO) {
                    PlaybackMixClient(baseUrl).status(token, capture.sessionId)
                }
            }.onSuccess {
                mix = it
                message = ""
            }.onFailure {
                message = "回放混音正在创建…"
            }
            delay(2_000)
        }
    }
    DisposableEffect(capture.sessionId) {
        onDispose { playback?.release() }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("录音提交状态", style = MaterialTheme.typography.titleMedium)
            Text(uploadStatusLabel(capture))
            Text(capture.qualitySummary)
            capture.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (capture.status == LocalUploadStatus.FAILED) {
                OutlinedButton(
                    onClick = {
                        enqueueVoiceUpload(
                            context,
                            capture.sessionId,
                            baseUrl,
                            manualRetry = true,
                        )
                    }
                ) {
                    Text("重新上传")
                }
            }
            if (capture.status == LocalUploadStatus.SUBMITTED) {
                when (mix?.status) {
                    "succeeded" -> OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        PlaybackMixClient(baseUrl).access(
                                            token,
                                            capture.sessionId,
                                        )
                                    }
                                }.onSuccess { access ->
                                    playback?.release()
                                    playback = playerFactory(
                                        AuthenticatedMediaSource(
                                            url = access.url,
                                            headers = authorizationHeaders(token),
                                        )
                                    ).also { it.play() }
                                    message = "正在回听体验混音（短时授权）"
                                }.onFailure {
                                    message = it.message ?: "无法获取回听授权"
                                }
                            }
                        }
                    ) {
                        Text("回听体验混音")
                    }
                    "failed" -> OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        PlaybackMixClient(baseUrl).retry(
                                            token,
                                            capture.sessionId,
                                        )
                                    }
                                }.onSuccess {
                                    mix = it
                                    message = "已重新生成回放混音"
                                }.onFailure {
                                    message = it.message ?: "无法重试回放混音"
                                }
                            }
                        }
                    ) {
                        Text("重新生成回放混音")
                    }
                    else -> Text("回放混音处理中")
                }
            }
            if (message.isNotEmpty()) Text(message)
        }
    }
}

@Composable
private fun SongCard(
    song: CatalogSong,
    token: String,
    gateway: CatalogGateway,
    onOpen: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuthenticatedCover(
                song.coverUrl,
                token,
                gateway,
                "${song.title}封面",
                Modifier.height(76.dp).aspectRatio(1f),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(song.title, style = MaterialTheme.typography.titleLarge)
                Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("时长 ${formatDuration(song.durationMs)}")
            }
            Button(onClick = onOpen) { Text("查看") }
        }
    }
}

@Composable
private fun CatalogDetail(
    token: String,
    songId: String,
    gateway: CatalogGateway,
    playerFactory: (AuthenticatedMediaSource) -> ExoPlayer,
    onStartSinging: (CatalogSong, Float) -> Unit,
    onBack: () -> Unit,
) {
    var song by remember(songId) { mutableStateOf<CatalogSong?>(null) }
    var error by remember(songId) { mutableStateOf("") }
    LaunchedEffect(songId) {
        runCatching { withContext(Dispatchers.IO) { gateway.songDetail(token, songId) } }
            .onSuccess { song = it }
            .onFailure { error = it.message ?: "歌曲详情加载失败" }
    }

    when {
        error.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(error, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onBack) { Text("返回曲库") }
        }
        song == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> SongPlayer(song!!, token, gateway, playerFactory, onStartSinging, onBack)
    }
}

@Composable
private fun SongPlayer(
    song: CatalogSong,
    token: String,
    gateway: CatalogGateway,
    playerFactory: (AuthenticatedMediaSource) -> ExoPlayer,
    onStartSinging: (CatalogSong, Float) -> Unit,
    onBack: () -> Unit,
) {
    val player = remember(song.id, song.backingTrackUrl, token) {
        playerFactory(gateway.playbackSource(token, song.backingTrackUrl))
    }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var positionMs by remember(player) { mutableLongStateOf(0) }
    var volume by remember(player) { mutableFloatStateOf(1f) }
    val activeIndex = activeLyricIndex(song.lines, positionMs)
    val lyricListState = rememberLazyListState()

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            delay(100)
        }
    }
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) lyricListState.animateScrollToItem(activeIndex)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text("返回曲库") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuthenticatedCover(
                song.coverUrl,
                token,
                gateway,
                "${song.title}封面",
                Modifier.height(96.dp).aspectRatio(1f),
            )
            Column {
                Text(song.title, style = MaterialTheme.typography.headlineSmall)
                Text(song.artist)
                Text("${formatDuration(positionMs)} / ${formatDuration(song.durationMs)}")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                },
            ) {
                Text(if (playing) "暂停试听" else "播放伴奏")
            }
            OutlinedButton(
                onClick = {
                    player.seekTo(0)
                    positionMs = 0
                },
            ) {
                Text("回到开头")
            }
        }
        Button(
            onClick = {
                player.stop()
                onStartSinging(song, volume)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("开始演唱")
        }
        Text("伴奏音量 ${(volume * 100).toInt()}%")
        Slider(
            value = volume,
            onValueChange = {
                volume = it
                player.volume = it
            },
            valueRange = 0f..1f,
        )
        HorizontalDivider()
        Text("同步歌词", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            state = lyricListState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(song.lines.size) { index ->
                val active = index == activeIndex
                Text(
                    text = song.lines[index].text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .padding(12.dp),
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    style = if (active) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.bodyLarge,
                )
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun AuthenticatedCover(
    coverUrl: String?,
    token: String,
    gateway: CatalogGateway,
    contentDescription: String,
    modifier: Modifier,
) {
    var imageBytes by remember(coverUrl, token) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(coverUrl, token) {
        imageBytes = coverUrl?.let {
            runCatching { withContext(Dispatchers.IO) { gateway.loadMedia(token, it) } }.getOrNull()
        }
    }
    val bitmap = remember(imageBytes) {
        imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    if (bitmap == null) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("暂无封面")
        }
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
fun createAuthenticatedPlayer(
    activity: androidx.activity.ComponentActivity,
    source: AuthenticatedMediaSource,
): ExoPlayer {
    val dataSource = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(source.headers)
    val mediaSource = ProgressiveMediaSource.Factory(dataSource)
        .createMediaSource(MediaItem.fromUri(source.url))
    return ExoPlayer.Builder(activity)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            setMediaSource(mediaSource)
            prepare()
        }
}
