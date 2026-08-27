package com.vocaease.patient.core.network

import com.vocaease.patient.core.security.TokenVault
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Interceptor
import okhttp3.Response

data class AuthRequestGeneration(val value: Long)

/**
 * Retrofit 请求级认证上下文。初次请求在发送时读取当前 vault；只有 401 刷新后的真正重试
 * 才携带刷新结果捕获的 token/epoch，避免重试在换号后读取到新患者凭据。
 */
internal sealed interface AuthRequestContext {
    data object Current : AuthRequestContext

    class Retry(
        internal val accessToken: String,
        internal val expectedEpoch: Long,
    ) : AuthRequestContext {
        private val networkAttemptClaimed = AtomicBoolean(false)

        internal fun claimNetworkAttempt(): Boolean = networkAttemptClaimed.compareAndSet(false, true)

        override fun toString(): String = "Retry(expectedEpoch=$expectedEpoch, accessToken=<redacted>)"
    }
}

/**
 * retry override 只允许一个物理网络尝试。OkHttp 的自动重定向或 follow-up 会复用请求 tag，
 * 第二次到达网络边界即 fail closed，因而捕获的 Authorization 不会进入重定向或内部重试。
 */
internal class RetryRequestSingleAttemptInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val context = chain.request().tag(AuthRequestContext::class.java)
        if (context is AuthRequestContext.Retry && !context.claimNetworkAttempt()) {
            throw SessionChangedException()
        }
        return chain.proceed(chain.request())
    }
}

class AuthInterceptor(
    private val tokenVault: TokenVault,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath in UNAUTHENTICATED_PATHS) {
            return chain.proceed(
                request.newBuilder()
                    .removeHeader("Authorization")
                    .tag(AuthRequestContext::class.java, null)
                    .build(),
            )
        }
        val snapshot = tokenVault.sessionSnapshot()
        val requestContext = request.tag(AuthRequestContext::class.java)
        val token = when (requestContext) {
            is AuthRequestContext.Retry -> {
                if (
                    snapshot.epoch != requestContext.expectedEpoch ||
                    snapshot.accessToken != requestContext.accessToken
                ) {
                    throw SessionChangedException()
                }
                requestContext.accessToken
            }
            AuthRequestContext.Current, null -> snapshot.accessToken
        }
        val requestEpoch = (requestContext as? AuthRequestContext.Retry)?.expectedEpoch ?: snapshot.epoch
        val builder = request.newBuilder()
            .removeHeader("Authorization")
            .tag(AuthRequestGeneration::class.java, AuthRequestGeneration(requestEpoch))
        token?.let {
            builder.header("Authorization", "Bearer $token")
        }
        return chain.proceed(builder.build())
    }

    private companion object {
        val UNAUTHENTICATED_PATHS = setOf(
            "/api/v1/auth/login/",
            "/api/v1/auth/refresh/",
        )
    }
}
