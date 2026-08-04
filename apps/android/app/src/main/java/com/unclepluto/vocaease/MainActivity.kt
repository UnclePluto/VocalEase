package com.unclepluto.vocaease

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionStore = ParticipantSessionStore(this)
        val gateway = HttpParticipantAuthGateway("http://10.0.2.2:8000")
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ParticipantApp(gateway, sessionStore)
                }
            }
        }
    }
}

@Composable
private fun ParticipantApp(gateway: ParticipantAuthGateway, sessionStore: ParticipantSessionStore) {
    var destination by remember {
        mutableStateOf(if (sessionStore.token() == null) AuthDestination.LOGIN else AuthDestination.HOME)
    }
    var token by remember { mutableStateOf(sessionStore.token()) }
    var currentPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
            AuthDestination.HOME -> HomeScreen {
                sessionStore.clear()
                token = null
                destination = AuthDestination.LOGIN
            }
        }
    }
}

@Composable
private fun AppFrame(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("VocaEase", style = MaterialTheme.typography.headlineLarge)
        Text("一期内部测试 · 请勿录入真实参与者数据")
        content()
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

@Composable
private fun HomeScreen(onLogout: () -> Unit) {
    Text("账户已激活", style = MaterialTheme.typography.headlineMedium)
    Text("下一批次将接入研究曲库与自由选歌。")
    Button(onClick = onLogout) { Text("退出登录") }
}
