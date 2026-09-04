package com.vocaease.patient.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val oldPassword: String = "",
    val newPassword: String = "",
    val confirmation: String = "",
    val oldPasswordVisible: Boolean = false,
    val newPasswordVisible: Boolean = false,
    val confirmationVisible: Boolean = false,
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val showLogoutConfirmation: Boolean = false,
    val pendingDraftDecisionCount: Int? = null,
)

sealed interface SettingsLogoutResult {
    data class NeedsDraftDecision(val count: Int) : SettingsLogoutResult
    data object LoggedOut : SettingsLogoutResult
    data object Superseded : SettingsLogoutResult
    data class Failed(val message: String) : SettingsLogoutResult
}

sealed interface SettingsPasswordResult {
    data object Changed : SettingsPasswordResult
    data object Superseded : SettingsPasswordResult
    data class Failed(val message: String) : SettingsPasswordResult
}

interface SettingsAccountActions {
    suspend fun beginLogout(): SettingsLogoutResult
    suspend fun confirmLogout(choice: LogoutChoice): SettingsLogoutResult
    suspend fun changePassword(oldPassword: String, newPassword: String, confirmation: String): SettingsPasswordResult
}

internal class ProductionSettingsAccountActions(
    boundary: ProductionAccountExitManager,
) : SettingsAccountActions {
    private val logout = LogoutCoordinator(boundary)
    private val password = PasswordChangeCoordinator(boundary)

    override suspend fun beginLogout(): SettingsLogoutResult = logout.beginLogout().toSettingsResult()

    override suspend fun confirmLogout(choice: LogoutChoice): SettingsLogoutResult =
        logout.confirmLogout(choice).toSettingsResult()

    override suspend fun changePassword(
        oldPassword: String,
        newPassword: String,
        confirmation: String,
    ): SettingsPasswordResult = when (val outcome = password.change(oldPassword, newPassword, confirmation)) {
        PasswordChangeOutcome.Changed -> SettingsPasswordResult.Changed
        PasswordChangeOutcome.Superseded -> SettingsPasswordResult.Superseded
        is PasswordChangeOutcome.Failed -> SettingsPasswordResult.Failed(outcome.message)
    }

    private fun LogoutOutcome.toSettingsResult(): SettingsLogoutResult = when (this) {
        is LogoutOutcome.NeedsDraftDecision -> SettingsLogoutResult.NeedsDraftDecision(pendingDraftCount)
        LogoutOutcome.LoggedOut -> SettingsLogoutResult.LoggedOut
        LogoutOutcome.Superseded -> SettingsLogoutResult.Superseded
        is LogoutOutcome.Failed -> SettingsLogoutResult.Failed(message)
    }
}

class SettingsViewModel(
    private val actions: SettingsAccountActions,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    fun updateOldPassword(value: String) = updateSecrets { copy(oldPassword = value, errorMessage = null) }
    fun updateNewPassword(value: String) = updateSecrets { copy(newPassword = value, errorMessage = null) }
    fun updateConfirmation(value: String) = updateSecrets { copy(confirmation = value, errorMessage = null) }
    fun toggleOldPassword() = mutableState.update { it.copy(oldPasswordVisible = !it.oldPasswordVisible) }
    fun toggleNewPassword() = mutableState.update { it.copy(newPasswordVisible = !it.newPasswordVisible) }
    fun toggleConfirmation() = mutableState.update { it.copy(confirmationVisible = !it.confirmationVisible) }

    fun submitPasswordChange() = launchOnce {
        val current = mutableState.value
        when (val result = actions.changePassword(current.oldPassword, current.newPassword, current.confirmation)) {
            SettingsPasswordResult.Changed -> mutableState.update {
                it.copy(oldPassword = "", newPassword = "", confirmation = "", errorMessage = null)
            }
            SettingsPasswordResult.Superseded -> Unit
            is SettingsPasswordResult.Failed -> mutableState.update { it.copy(errorMessage = result.message) }
        }
    }

    fun requestLogout() {
        if (!mutableState.value.loading) mutableState.update { it.copy(showLogoutConfirmation = true, errorMessage = null) }
    }

    fun confirmLogout() {
        mutableState.update { it.copy(showLogoutConfirmation = false) }
        launchLogout { actions.beginLogout() }
    }

    fun chooseRetain() = chooseLogout(LogoutChoice.RETAIN)
    fun chooseDelete() = chooseLogout(LogoutChoice.DELETE)

    fun dismissLogout() = mutableState.update {
        it.copy(showLogoutConfirmation = false, pendingDraftDecisionCount = null)
    }

    private fun chooseLogout(choice: LogoutChoice) {
        mutableState.update { it.copy(pendingDraftDecisionCount = null) }
        launchLogout { actions.confirmLogout(choice) }
    }

    private fun launchLogout(block: suspend () -> SettingsLogoutResult) = launchOnce {
        when (val result = block()) {
            is SettingsLogoutResult.NeedsDraftDecision -> mutableState.update {
                it.copy(pendingDraftDecisionCount = result.count)
            }
            SettingsLogoutResult.LoggedOut,
            SettingsLogoutResult.Superseded,
            -> Unit
            is SettingsLogoutResult.Failed -> mutableState.update { it.copy(errorMessage = result.message) }
        }
    }

    private fun launchOnce(block: suspend () -> Unit) {
        if (mutableState.value.loading) return
        mutableState.update { it.copy(loading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(errorMessage = "操作未完成，请重试") }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    private fun updateSecrets(block: SettingsUiState.() -> SettingsUiState) {
        if (!mutableState.value.loading) mutableState.update(block)
    }

    companion object {
        fun factory(actions: SettingsAccountActions): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
                    return SettingsViewModel(actions) as T
                }
            }
    }
}
