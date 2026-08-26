package com.vocaease.patient.core.security

data class SessionSnapshot(
    val accessToken: String?,
    val epoch: Long,
    /** 最近一次成功替换的调用方标识；用于取消竞态确认所有权，不包含凭据。 */
    val replacementId: String? = null,
)

data class RefreshTokenLease(
    val value: String,
    val epoch: Long,
)

data class SessionMutation(
    val applied: Boolean,
    val snapshot: SessionSnapshot,
)

data class SessionInvalidation(
    val fromEpoch: Long,
    val toEpoch: Long,
) {
    init {
        require(toEpoch == fromEpoch + 1) { "会话失效必须且只能推进一个 epoch" }
    }
}

sealed interface RefreshTokenRead {
    data class Available(val lease: RefreshTokenLease) : RefreshTokenRead
    data class Missing(val observedEpoch: Long) : RefreshTokenRead
    data class Invalidated(
        val invalidation: SessionInvalidation,
        val cause: Throwable,
    ) : RefreshTokenRead
}

class VaultInvalidatedException(
    val invalidation: SessionInvalidation,
    cause: Throwable,
) : Exception("安全会话存储已失效", cause)

/** refresh 明文只作为短生命周期 lease 返回，永不进入 SessionSnapshot。 */
interface TokenVault {
    fun sessionSnapshot(): SessionSnapshot

    suspend fun readRefreshToken(expectedEpoch: Long): RefreshTokenRead

    suspend fun replaceTokens(
        expectedEpoch: Long,
        accessToken: String,
        refreshToken: String,
        replacementId: String,
    ): SessionMutation

    /** expectedEpoch=null 表示无条件使当前 epoch 失效。 */
    suspend fun clear(expectedEpoch: Long? = null): SessionMutation
}
