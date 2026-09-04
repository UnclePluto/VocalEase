package com.vocaease.patient

import java.util.concurrent.CancellationException

/** 保证退出意图在恢复、续传和定时清理之前先收敛。 */
internal class AccountStartupRecovery(
    private val recoverExit: suspend () -> Boolean,
    private val startNormalWork: suspend () -> Unit,
) {
    /** 返回 true 表示普通账户工作必须保持停用。 */
    suspend fun run(): Boolean {
        val recovered = try {
            recoverExit()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return true
        }
        if (recovered) return true
        startNormalWork()
        return false
    }
}
