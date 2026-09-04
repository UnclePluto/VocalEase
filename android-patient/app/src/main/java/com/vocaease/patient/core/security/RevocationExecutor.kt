package com.vocaease.patient.core.security

import java.util.concurrent.CancellationException

data class RevocationHandle(val slotId: String) {
    init {
        require(slotId.matches(SLOT_ID))
    }

    private companion object {
        val SLOT_ID = Regex("[0-9a-f]{64}")
    }
}

/** 认证域只能写入待撤销 refresh family，拿到的句柄不包含凭据。 */
fun interface RevocationTokenSink {
    suspend fun store(accessToken: String, refreshToken: String): RevocationHandle
}

/** 启动恢复使用调用方已持久化的 handle 幂等提交，不复制生成新槽。 */
internal interface RevocationTransferSink : RevocationTokenSink {
    suspend fun store(handle: RevocationHandle, accessToken: String, refreshToken: String)
}

/** 仅供独立撤销执行器使用，不注入认证拦截器、刷新协调器或普通仓库。 */
internal interface RevocationTokenSource {
    suspend fun lease(handle: RevocationHandle): RevocationTokenLease?
    suspend fun remove(handle: RevocationHandle)
    suspend fun handles(): List<RevocationHandle>
}

internal data class RevocationTokenLease(
    val handle: RevocationHandle,
    val accessToken: String,
    val refreshToken: String,
) {
    init {
        require(accessToken.isNotBlank() && refreshToken.isNotBlank())
    }
}

enum class RevocationRemoteResult { Success, InvalidOrExpired, Retryable }

fun interface RevocationRemote {
    suspend fun revoke(accessToken: String, refreshToken: String): RevocationRemoteResult
}

enum class RevocationExecution { Finished, Retry }

internal class RevocationExecutor(
    private val source: RevocationTokenSource,
    private val sink: RevocationTokenSource,
    private val remote: RevocationRemote,
) {
    suspend fun revoke(handle: RevocationHandle): RevocationExecution {
        val lease = source.lease(handle) ?: return RevocationExecution.Finished
        val result = try {
            remote.revoke(lease.accessToken, lease.refreshToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RevocationExecution.Retry
        }
        return when (result) {
            RevocationRemoteResult.Success,
            RevocationRemoteResult.InvalidOrExpired,
            -> {
                sink.remove(handle)
                RevocationExecution.Finished
            }
            RevocationRemoteResult.Retryable -> RevocationExecution.Retry
        }
    }
}
