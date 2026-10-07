package com.vocaease.patient.feature.training
import com.vocaease.patient.core.network.dto.*
import kotlinx.coroutines.CancellationException

sealed interface ReferencePitchState {
    data object Loading:ReferencePitchState
    data class Ready(val version:String,val notes:List<ReferenceNoteDto>):ReferencePitchState
    data object Unaligned:ReferencePitchState
    data object Unavailable:ReferencePitchState
    data object Failed:ReferencePitchState
}
class ReferencePitchRepository(private val fetch:suspend(String,String?)->ReferencePitchDto) {
    private val cache=java.util.concurrent.ConcurrentHashMap<String,ReferencePitchState.Ready>()
    suspend fun load(songId:String,version:String?):ReferencePitchState {
        if(version!=null) cache["$songId:$version"]?.let { return it }
        return try {
            val response=fetch(songId,version)
            if(response.status!="ready") ReferencePitchState.Unavailable
            else {
                require(response.schemaVersion==1 && response.version!=null && (version==null || response.version==version))
                require(response.notes.size<=100000)
                var end=0L
                response.notes.forEach { require(it.startMs>=end && it.endMs>it.startMs && it.midiNote.isFinite() && it.midiNote in 0f..127f && it.confidence.isFinite() && it.confidence in 0f..1f);end=it.endMs }
                ReferencePitchState.Ready(response.version,response.notes).also { cache["$songId:${it.version}"]=it }
            }
        } catch(error:CancellationException) { throw error }
        catch(_:Exception) { ReferencePitchState.Failed }
    }
}
