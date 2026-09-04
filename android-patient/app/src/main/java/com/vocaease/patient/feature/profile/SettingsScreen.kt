package com.vocaease.patient.feature.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vocaease.patient.ui.theme.AppError
import com.vocaease.patient.ui.theme.MinimumTouchTargetSize

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOldPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onToggleOldPassword: () -> Unit,
    onToggleNewPassword: () -> Unit,
    onToggleConfirmation: () -> Unit,
    onSubmitPassword: () -> Unit,
    onRequestLogout: () -> Unit,
    onConfirmLogout: () -> Unit,
    onChooseRetain: () -> Unit,
    onChooseDelete: () -> Unit,
    onDismissLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = state.loading) { }

    Column(
        modifier = modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !state.loading, modifier = Modifier.testTag("settings-back")) {
                Text("‹", style = MaterialTheme.typography.headlineMedium)
            }
            Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(28.dp))
        Text("修改密码", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("修改成功后需要使用新密码重新登录", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(18.dp))
        SettingsPasswordField(
            state.oldPassword, onOldPasswordChange, "原密码", "settings-old-password",
            state.oldPasswordVisible, "原密码", onToggleOldPassword, !state.loading,
        )
        Spacer(Modifier.height(12.dp))
        SettingsPasswordField(
            state.newPassword, onNewPasswordChange, "新密码", "settings-new-password",
            state.newPasswordVisible, "新密码", onToggleNewPassword, !state.loading,
        )
        Spacer(Modifier.height(12.dp))
        SettingsPasswordField(
            state.confirmation, onConfirmationChange, "确认新密码", "settings-confirm-password",
            state.confirmationVisible, "确认密码", onToggleConfirmation, !state.loading,
        )
        state.errorMessage?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("settings-error"))
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onSubmitPassword,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().height(MinimumTouchTargetSize).testTag("settings-change-submit"),
        ) {
            if (state.loading) CircularProgressIndicator(modifier = Modifier.height(24.dp), strokeWidth = 2.dp)
            else Text("修改密码")
        }
        Spacer(Modifier.height(36.dp))
        Text("账户", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onRequestLogout,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().height(MinimumTouchTargetSize).testTag("settings-logout"),
        ) { Text("退出登录", color = AppError) }
        Spacer(Modifier.height(24.dp))
    }

    if (state.showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissLogout,
            title = { Text("确认退出") },
            text = { Text("退出前将安全停止当前账户的上传、分析与媒体访问。") },
            confirmButton = { TextButton(onClick = onConfirmLogout) { Text("继续退出") } },
            dismissButton = { TextButton(onClick = onDismissLogout) { Text("取消") } },
        )
    }
    state.pendingDraftDecisionCount?.let { count ->
        AlertDialog(
            onDismissRequest = onDismissLogout,
            title = { Text("有 $count 条待上传草稿") },
            text = { Text("请选择保留加密草稿，或删除当前账户的全部草稿后退出。") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onChooseRetain) { Text("保留草稿并退出") }
                    TextButton(onClick = onChooseDelete) { Text("删除草稿并退出", color = AppError) }
                }
            },
            dismissButton = { TextButton(onClick = onDismissLogout) { Text("取消") } },
        )
    }
}

@Composable
private fun SettingsPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    visible: Boolean,
    semanticName: String,
    onToggle: () -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(
                onClick = onToggle,
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = if (visible) "隐藏$semanticName" else "显示$semanticName"
                },
            ) { Text(if (visible) "隐" else "显") }
        },
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
