package com.vocaease.patient.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vocaease.patient.ui.theme.MinimumTouchTargetSize

@Composable
fun LoginScreen(
    operation: AuthOperationState,
    onLogin: (loginId: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
    notice: String? = null,
) {
    var loginId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val loading = operation == AuthOperationState.Loading

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("登录", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        notice?.let {
            Text(it, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
        }
        OutlinedTextField(
            value = loginId,
            onValueChange = { loginId = it },
            label = { Text("手机号") },
            singleLine = true,
            enabled = !loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login-id"),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            enabled = !loading,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login-password"),
        )
        if (operation is AuthOperationState.Error) {
            Spacer(Modifier.height(8.dp))
            Text(
                operation.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("auth-error"),
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onLogin(loginId, password) },
            enabled = !loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(MinimumTouchTargetSize)
                .testTag("login-submit"),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("登录")
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "账号由医生创建，如需帮助请联系医生",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
