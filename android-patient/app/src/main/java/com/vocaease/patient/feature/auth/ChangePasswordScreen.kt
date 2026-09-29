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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vocaease.patient.ui.theme.MinimumTouchTargetSize

@Composable
fun ChangePasswordScreen(
    operation: AuthOperationState,
    onChangePassword: (oldPassword: String, newPassword: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    val loading = operation == AuthOperationState.Loading

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("首次登录，请修改密码", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        PasswordField(
            value = oldPassword,
            onValueChange = { oldPassword = it },
            label = "原密码",
            enabled = !loading,
            tag = "change-old-password",
        )
        Spacer(Modifier.height(12.dp))
        PasswordField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = "新密码",
            enabled = !loading,
            tag = "change-new-password",
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
            onClick = { onChangePassword(oldPassword, newPassword) },
            enabled = !loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(MinimumTouchTargetSize)
                .testTag("change-password-submit"),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("修改密码")
            }
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    tag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}
