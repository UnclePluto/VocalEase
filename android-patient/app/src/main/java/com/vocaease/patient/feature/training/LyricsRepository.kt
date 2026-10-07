package com.vocaease.patient.feature.training

import com.vocaease.patient.core.network.dto.LyricLineDto
import com.vocaease.patient.core.network.dto.SongLyricsDto
import kotlinx.coroutines.CancellationException

sealed interface LyricsState {
    data object Loading : LyricsState
    data object Unavailable : LyricsState
    data object Failed : LyricsState
    data class Ready(val lines: List<LyricLineDto>) : LyricsState {
        fun activeIndex(positionMillis: Long): Int {
            var low = 0
            var high = lines.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (lines[mid].timeMs <= positionMillis) low = mid + 1 else high = mid
            }
            return low - 1
        }
    }
}

class LyricsRepository(private val fetch: suspend (String) -> SongLyricsDto) {
    suspend fun load(songId: String): LyricsState = try {
        val lines = fetch(songId).lines
        require(lines.size <= 100000)
        require(lines.all { it.timeMs >= 0 && it.text.length <= 10000 })
        require(lines.zipWithNext().all { (a,b) -> a.timeMs <= b.timeMs })
        if (lines.none { it.text.isNotBlank() }) LyricsState.Unavailable
        else LyricsState.Ready(lines.groupBy { it.timeMs }.map { (time, group) ->
            LyricLineDto(time, group.map { it.text }.distinct().joinToString("\n"))
        })
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { LyricsState.Failed }
}
