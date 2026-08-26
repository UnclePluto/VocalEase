package com.vocaease.patient.core.security

data class SessionSnapshot(
    val accessToken: String?,
    val epoch: Long,
)

data class RefreshTokenLease(
    val value: String,
    val epoch: Long,
)

data class SessionMutation(
    val applied: Boolean,
    val snapshot: SessionSnapshot,
)

/** refresh 明文只作为短生命周期 lease 返回，永不进入 SessionSnapshot。 */
interface TokenVault {
    fun sessionSnapshot(): SessionSnapshot

    suspend fun readRefreshToken(expectedEpoch: Long): RefreshTokenLease?

    suspend fun replaceTokens(
        expectedEpoch: Long,
        accessToken: String,
        refreshToken: String,
    ): SessionMutation

    /** expectedEpoch=null 表示无条件使当前 epoch 失效。 */
    suspend fun clear(expectedEpoch: Long? = null): SessionMutation
}
