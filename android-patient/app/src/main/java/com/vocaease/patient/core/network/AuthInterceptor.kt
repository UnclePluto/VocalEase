package com.vocaease.patient.core.network

import com.vocaease.patient.core.security.TokenVault
import okhttp3.Interceptor
import okhttp3.Response

data class AuthRequestGeneration(val value: Long)

class AuthInterceptor(
    private val tokenVault: TokenVault,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath in UNAUTHENTICATED_PATHS) {
            return chain.proceed(request.newBuilder().removeHeader("Authorization").build())
        }
        val snapshot = tokenVault.accessSnapshot()
        val builder = request.newBuilder()
            .tag(AuthRequestGeneration::class.java, AuthRequestGeneration(snapshot.generation))
        snapshot.value?.let { token ->
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
