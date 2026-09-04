package com.vocaease.patient.feature.profile

import java.util.concurrent.CancellationException

internal data class PreparedPasswordChange(
    val intent: LogoutIntent,
    val accountScope: String,
)

internal interface PasswordChangeAccountBoundary {
    suspend fun preparePasswordChange(): PreparedPasswordChange?
    suspend fun changePassword(
        prepared: PreparedPasswordChange,
        oldPassword: String,
        newPassword: String,
    ): Boolean
    suspend fun completePasswordChange(prepared: PreparedPasswordChange): Boolean
}

internal sealed interface PasswordChangeOutcome {
    data object Changed : PasswordChangeOutcome
    data object Superseded : PasswordChangeOutcome
    data class Failed(val message: String) : PasswordChangeOutcome
}

internal class PasswordChangeCoordinator(
    private val account: PasswordChangeAccountBoundary,
) {
    suspend fun change(
        oldPassword: String,
        newPassword: String,
        confirmation: String,
    ): PasswordChangeOutcome {
        if (oldPassword.isBlank() || newPassword.isBlank() || confirmation.isBlank()) {
            return PasswordChangeOutcome.Failed("请完整填写三项密码")
        }
        if (newPassword != confirmation) {
            return PasswordChangeOutcome.Failed("两次输入的新密码不一致")
        }
        return try {
            val prepared = account.preparePasswordChange() ?: return PasswordChangeOutcome.Superseded
            if (!account.changePassword(prepared, oldPassword, newPassword)) {
                return PasswordChangeOutcome.Failed("密码修改未完成，请重试")
            }
            if (account.completePasswordChange(prepared)) PasswordChangeOutcome.Changed
            else PasswordChangeOutcome.Superseded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PasswordChangeOutcome.Failed("密码修改未完成，请重试")
        }
    }
}
