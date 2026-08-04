package com.unclepluto.vocaease

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.unclepluto.vocaease.auth.AuthDestination
import com.unclepluto.vocaease.auth.HttpParticipantAuthGateway
import com.unclepluto.vocaease.auth.ParticipantAuthGateway
import com.unclepluto.vocaease.auth.ParticipantSessionStore
import com.unclepluto.vocaease.auth.destinationAfterLogin
import com.unclepluto.vocaease.catalog.CatalogGateway
import com.unclepluto.vocaease.catalog.CatalogScreen
import com.unclepluto.vocaease.catalog.HttpCatalogGateway
import com.unclepluto.vocaease.catalog.createAuthenticatedPlayer
import com.unclepluto.vocaease.catalog.CatalogSong
import com.unclepluto.vocaease.singing.CaptureInterruptionRegistry
import com.unclepluto.vocaease.singing.HttpSingingGateway
import com.unclepluto.vocaease.singing.InterruptionReason
import com.unclepluto.vocaease.singing.LocalCaptureStore
import com.unclepluto.vocaease.singing.SingingGateway
import com.unclepluto.vocaease.singing.SingingSessionScreen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val interruptionRegistry = CaptureInterruptionRegistry()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionStore = ParticipantSessionStore(this)
        val baseUrl = BuildConfig.API_BASE_URL
        val gateway = HttpParticipantAuthGateway(baseUrl)
        val catalogGateway = HttpCatalogGateway(baseUrl)
        val singingGateway = HttpSingingGateway(baseUrl)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ParticipantApp(
                        gateway = gateway,
                        catalogGateway = catalogGateway,
                        singingGateway = singingGateway,
                        sessionStore = sessionStore,
                        baseUrl = baseUrl,
                        activity = this,
                        interruptionRegistry = interruptionRegistry,
                        playerFactory = { createAuthenticatedPlayer(this, it) },
                    )
                }
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) interruptionRegistry.appBackgrounded()
        super.onStop()
    }
}

@Composable
private fun ParticipantApp(
    gateway: ParticipantAuthGateway,
    catalogGateway: CatalogGateway,
    singingGateway: SingingGateway,
    sessionStore: ParticipantSessionStore,
    baseUrl: String,
    activity: ComponentActivity,
    interruptionRegistry: CaptureInterruptionRegistry,
    playerFactory: (com.unclepluto.vocaease.catalog.AuthenticatedMediaSource) ->
        androidx.media3.exoplayer.ExoPlayer,
) {
    var destination by remember {
        mutableStateOf(if (sessionStore.token() == null) AuthDestination.LOGIN else AuthDestination.HOME)
    }
    var token by remember { mutableStateOf(sessionStore.token()) }
    var currentPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var activeSingingSong by remember { mutableStateOf<CatalogSong?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(token) {
        val accessToken = token ?: return@LaunchedEffect
        val store = LocalCaptureStore(activity)
        val active = store.active() ?: return@LaunchedEffect
        val reason = active.interruptionReason ?: InterruptionReason.PROCESS_RECOVERED
        runCatching {
            withContext(Dispatchers.IO) {
                singingGateway.interrupt(accessToken, active.sessionId, reason)
            }
        }.onSuccess {
            File(active.filePath).delete()
            store.clearActive(deletePartial = true)
        }
    }

    fun runRequest(block: suspend () -> Unit) {
        busy = true
        error = ""
        scope.launch {
            runCatching { block() }
                .onFailure { error = it.message ?: "请求失败" }
            busy = false
        }
    }

    AppFrame {
        when (destination) {
            AuthDestination.LOGIN -> LoginScreen(error, busy) { phone, password ->
                runRequest {
                    val result = withContext(Dispatchers.IO) { gateway.login(phone, password) }
                    token = result.accessToken
                    currentPassword = password
                    if (!result.mustChangePassword) sessionStore.save(result.accessToken)
                    destination = destinationAfterLogin(result)
                }
            }
            AuthDestination.CHANGE_PASSWORD -> ChangePasswordScreen(
                error = error,
                busy = busy,
                onChange = { newPassword ->
                    runRequest {
                        val result = withContext(Dispatchers.IO) {
                            gateway.changePassword(token.orEmpty(), currentPassword, newPassword)
                        }
                        token = result.accessToken
                        currentPassword = ""
                        sessionStore.save(result.accessToken)
                        destination = AuthDestination.HOME
                    }
                },
                onLogout = {
                    token = null
                    currentPassword = ""
                    destination = AuthDestination.LOGIN
                },
            )
            AuthDestination.HOME -> {
                val singingSong = activeSingingSong
                if (singingSong == null) {
                    CatalogScreen(
                        token = token.orEmpty(),
                        baseUrl = baseUrl,
                        gateway = catalogGateway,
                        playerFactory = playerFactory,
                        onStartSinging = { activeSingingSong = it },
                        onLogout = {
                            sessionStore.clear()
                            token = null
                            destination = AuthDestination.LOGIN
                        },
                    )
                } else {
                    SingingSessionScreen(
                        activity = activity,
                        token = token.orEmpty(),
                        baseUrl = baseUrl,
                        song = singingSong,
                        catalogGateway = catalogGateway,
                        gateway = singingGateway,
                        interruptionRegistry = interruptionRegistry,
                        onExit = { activeSingingSong = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppFrame(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("VocaEase", style = MaterialTheme.typography.headlineLarge)
        Text("一期内部测试 · 请勿录入真实参与者数据")
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun LoginScreen(error: String, busy: Boolean, onLogin: (String, String) -> Unit) {
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text("参与者登录", style = MaterialTheme.typography.headlineMedium)
    OutlinedTextField(phone, { phone = it }, label = { Text("手机号") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        password,
        { password = it },
        label = { Text("密码") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    Button(onClick = { onLogin(phone, password) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "登录中…" else "登录")
    }
}

@Composable
private fun ChangePasswordScreen(
    error: String,
    busy: Boolean,
    onChange: (String) -> Unit,
    onLogout: () -> Unit,
) {
    var newPassword by remember { mutableStateOf("") }
    Text("首次登录需要修改密码", style = MaterialTheme.typography.headlineMedium)
    Text("新密码至少 10 位，不能继续使用 88888888。")
    OutlinedTextField(
        newPassword,
        { newPassword = it },
        label = { Text("新密码") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    Button(onClick = { onChange(newPassword) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "提交中…" else "修改密码并进入")
    }
    Button(onClick = onLogout, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("退出登录")
    }
}
