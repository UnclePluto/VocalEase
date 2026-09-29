package com.vocaease.patient.feature.profile

import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
    ): PasswordChangeAttempt
    suspend fun completePasswordChange(prepared: PreparedPasswordChange): Boolean
    suspend fun rollbackPasswordChange(prepared: PreparedPasswordChange)
}

internal sealed interface PasswordChangeAttempt {
    data object Changed : PasswordChangeAttempt
    data object Superseded : PasswordChangeAttempt
    data class Failed(val userMessage: String) : PasswordChangeAttempt
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
        var prepared: PreparedPasswordChange? = null
        return try {
            prepared = account.preparePasswordChange() ?: return PasswordChangeOutcome.Superseded
            val current = requireNotNull(prepared)
            when (val attempt = account.changePassword(current, oldPassword, newPassword)) {
                PasswordChangeAttempt.Changed -> Unit
                PasswordChangeAttempt.Superseded -> return PasswordChangeOutcome.Superseded
                is PasswordChangeAttempt.Failed -> {
                    withContext(NonCancellable) { account.rollbackPasswordChange(current) }
                    prepared = null
                    return PasswordChangeOutcome.Failed(attempt.userMessage)
                }
            }
            if (account.completePasswordChange(current)) PasswordChangeOutcome.Changed
            else PasswordChangeOutcome.Superseded
        } catch (cancelled: CancellationException) {
            prepared?.let { value -> withContext(NonCancellable) { account.rollbackPasswordChange(value) } }
            throw cancelled
        } catch (_: Exception) {
            prepared?.let { value -> withContext(NonCancellable) { runCatching { account.rollbackPasswordChange(value) } } }
            PasswordChangeOutcome.Failed("密码修改未完成，请重试")
        }
    }
}
