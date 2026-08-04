package com.unclepluto.vocaease.catalog

import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class LyricLine(
    val timeMs: Long,
    val text: String,
)

data class CatalogSong(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val durationMs: Long,
    val backingTrackUrl: String,
    val lyricVersionId: String,
    val lines: List<LyricLine>,
)

data class AuthenticatedMediaSource(
    val url: String,
    val headers: Map<String, String>,
)

interface CatalogGateway {
    fun listSongs(token: String): List<CatalogSong>
    fun songDetail(token: String, songId: String): CatalogSong
    fun loadMedia(token: String, path: String): ByteArray
    fun playbackSource(token: String, path: String): AuthenticatedMediaSource
}

class HttpCatalogGateway(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) : CatalogGateway {
    override fun listSongs(token: String): List<CatalogSong> {
        val body = get("/api/v1/catalog/songs", token)
        val songs = JSONArray(body)
        return List(songs.length()) { index -> parseSong(songs.getJSONObject(index)) }
    }

    override fun songDetail(token: String, songId: String): CatalogSong =
        parseSong(JSONObject(get("/api/v1/catalog/songs/$songId", token)))

    override fun loadMedia(token: String, path: String): ByteArray {
        val request = authenticatedRequest(resolveUrl(baseUrl, path), token)
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { responseError(response.code, response.body.string()) }
            return response.body.bytes()
        }
    }

    override fun playbackSource(token: String, path: String): AuthenticatedMediaSource =
        AuthenticatedMediaSource(
            url = resolveUrl(baseUrl, path),
            headers = authorizationHeaders(token),
        )

    private fun get(path: String, token: String): String {
        val request = authenticatedRequest(resolveUrl(baseUrl, path), token)
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            check(response.isSuccessful) { responseError(response.code, body) }
            return body
        }
    }
}

fun parseSong(json: JSONObject): CatalogSong {
    val rawLines = json.getJSONArray("lines")
    val lines = List(rawLines.length()) { index ->
        rawLines.getJSONObject(index).let {
            LyricLine(timeMs = it.getLong("time_ms"), text = it.getString("text"))
        }
    }
    return CatalogSong(
        id = json.getString("id"),
        title = json.getString("title"),
        artist = json.getString("artist"),
        coverUrl = json.optString("cover_url").takeIf { it.isNotBlank() && it != "null" },
        durationMs = json.getLong("duration_ms"),
        backingTrackUrl = json.getString("backing_track_url"),
        lyricVersionId = json.getString("lyric_version_id"),
        lines = lines.sortedBy(LyricLine::timeMs),
    )
}

fun activeLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty() || positionMs < lines.first().timeMs) return -1
    var low = 0
    var high = lines.lastIndex
    while (low <= high) {
        val middle = (low + high).ushr(1)
        if (lines[middle].timeMs <= positionMs) {
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return high
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0) / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

fun resolveUrl(baseUrl: String, path: String): String =
    URI(baseUrl.trimEnd('/') + "/").resolve(path).toString()

fun authorizationHeaders(token: String): Map<String, String> =
    mapOf("Authorization" to "Bearer $token")

private fun authenticatedRequest(url: String, token: String): Request =
    Request.Builder()
        .url(url)
        .headers(okhttp3.Headers.headersOf("Authorization", "Bearer $token"))
        .get()
        .build()

private fun responseError(code: Int, body: String): String {
    val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
    return detail?.takeIf(String::isNotBlank) ?: "请求失败（$code）"
}
