package com.unclepluto.vocaease

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class HttpHealthGateway(
    private val endpoint: String,
    private val client: OkHttpClient = OkHttpClient(),
) : HealthGateway {
    override fun fetch(): HealthReport {
        val request = Request.Builder().url(endpoint).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "健康检查请求失败：HTTP ${response.code}" }
            val payload = JSONObject(response.body.string())
            val dependenciesObject = payload.getJSONObject("dependencies")
            val dependencies = dependenciesObject.keys().asSequence().associateWith { key ->
                dependenciesObject.getString(key)
            }
            return HealthReport(
                status = payload.getString("status"),
                dependencies = dependencies,
            )
        }
    }
}
