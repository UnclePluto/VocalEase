package com.unclepluto.vocaease.auth

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class LoginResult(
    val accessToken: String,
    val mustChangePassword: Boolean,
)

enum class AuthDestination {
    LOGIN,
    CHANGE_PASSWORD,
    HOME,
}

fun destinationAfterLogin(result: LoginResult): AuthDestination =
    if (result.mustChangePassword) AuthDestination.CHANGE_PASSWORD else AuthDestination.HOME

interface ParticipantAuthGateway {
    fun login(phone: String, password: String): LoginResult
    fun changePassword(token: String, currentPassword: String, newPassword: String): LoginResult
}

class HttpParticipantAuthGateway(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) : ParticipantAuthGateway {
    override fun login(phone: String, password: String): LoginResult = post(
        path = "/api/v1/auth/participant/login",
        payload = JSONObject().put("phone", phone).put("password", password),
    )

    override fun changePassword(
        token: String,
        currentPassword: String,
        newPassword: String,
    ): LoginResult = post(
        path = "/api/v1/auth/participant/change-password",
        payload = JSONObject()
            .put("current_password", currentPassword)
            .put("new_password", newPassword),
        token = token,
    )

    private fun post(path: String, payload: JSONObject, token: String? = null): LoginResult {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (token != null) builder.header("Authorization", "Bearer $token")
        client.newCall(builder.build()).execute().use { response ->
            val body = JSONObject(response.body.string())
            check(response.isSuccessful) { body.optString("detail", "请求失败") }
            return LoginResult(
                accessToken = body.getString("access_token"),
                mustChangePassword = body.getBoolean("must_change_password"),
            )
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class ParticipantSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("participant_session", Context.MODE_PRIVATE)

    fun token(): String? = preferences.getString("access_token", null)

    fun save(token: String) {
        preferences.edit().putString("access_token", token).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }
}
