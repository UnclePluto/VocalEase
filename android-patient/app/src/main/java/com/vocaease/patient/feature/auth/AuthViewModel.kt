package com.vocaease.patient.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AuthViewModel(
    private val repository: AuthRepository,
) : ViewModel() {
    val state: StateFlow<AuthState> = repository.state
    val operation: StateFlow<AuthOperationState> = repository.operation
    val events = repository.events

    init {
        viewModelScope.launch { repository.restoreSession() }
    }

    fun login(loginId: String, password: String) {
        launchOperation { repository.login(loginId, password) }
    }

    fun changePassword(oldPassword: String, newPassword: String) {
        launchOperation { repository.changePassword(oldPassword, newPassword) }
    }

    fun dismissError() = repository.dismissError()

    private fun launchOperation(operation: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                operation()
            } finally {
                repository.finishOperation()
            }
        }
    }

    companion object {
        fun factory(repository: AuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AuthViewModel::class.java))
                    return AuthViewModel(repository) as T
                }
            }
    }
}

@Composable
fun AuthFlow(
    repository: AuthRepository,
    modifier: Modifier = Modifier,
    authenticatedContent: @Composable () -> Unit = { Text("去唱歌") },
) {
    val authViewModel: AuthViewModel = viewModel(
        factory = AuthViewModel.factory(repository),
    )
    val state by authViewModel.state.collectAsState()
    val operation by authViewModel.operation.collectAsState()
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(authViewModel) {
        authViewModel.events.collect { event ->
            notice = when (event) {
                AuthEvent.PasswordChanged -> "密码已修改，请重新登录"
                AuthEvent.SessionExpired -> "登录状态已失效，请重新登录"
            }
        }
    }

    when (state) {
        AuthState.Restoring -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        AuthState.LoggedOut -> LoginScreen(
            operation = operation,
            notice = notice,
            onLogin = authViewModel::login,
            modifier = modifier,
        )
        AuthState.MustChangePassword -> ChangePasswordScreen(
            operation = operation,
            onChangePassword = authViewModel::changePassword,
            modifier = modifier,
        )
        AuthState.Authenticated -> authenticatedContent()
    }
}
