package com.vocaease.patient.core.security

data class AccessTokenSnapshot(
    val value: String?,
    val generation: Long,
)

/**
 * Access token 只允许由实现保存在进程内存；持久化边界只包含 refresh token。
 */
interface TokenVault {
    fun accessSnapshot(): AccessTokenSnapshot

    suspend fun readRefreshToken(): String?

    /** 持久化 refresh 成功后才可发布新的 access，避免半更新会话。 */
    suspend fun replaceTokens(accessToken: String, refreshToken: String)

    suspend fun clear()
}
