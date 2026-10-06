package com.vocaease.patient.core.media

/** 仅记录播放时钟与状态；不保存 URL 或音高帧。 */
class PlaybackMetadataRecorder(private var sampleRate:Int,private val sourceAssetId:String,private val accompanimentAssetId:String,private val referenceVersion:String?) {
    private val anchors=mutableListOf<PlaybackAnchor>();private val changes=mutableListOf<ModeChange>();private var segment=0
    @Synchronized fun record(recordingMs:Long,songMs:Long,track:SongPlaybackMode,playing:Boolean) {
        val previous=anchors.lastOrNull()
        if(previous!=null && recordingMs<=previous.recordingMs) return
        if(previous!=null && songMs<previous.songMs) segment++
        if(previous!=null && previous.track!=track) { check(changes.size<1000);changes+=ModeChange(recordingMs,track) }
        check(anchors.size<10000)
        anchors+=PlaybackAnchor(recordingMs.coerceAtLeast(0),songMs.coerceAtLeast(0),track,playing,segment)
    }
    @Synchronized fun snapshot():PlaybackMetadata=PlaybackMetadata(sampleRate=sampleRate,sourceAssetId=sourceAssetId,accompanimentAssetId=accompanimentAssetId,referenceVersion=referenceVersion,anchors=anchors.toList(),modeChanges=changes.toList())
    @Synchronized fun finish(durationMs:Long,actualSampleRate:Int,effectiveStartOffsetMs:Long=0):PlaybackMetadata {
        sampleRate=actualSampleRate
        val effective=anchors.filter { it.recordingMs in effectiveStartOffsetMs..(effectiveStartOffsetMs+durationMs) }.map { it.copy(recordingMs=it.recordingMs-effectiveStartOffsetMs) }
        require(effective.isNotEmpty())
        return snapshot().copy(anchors=effective,modeChanges=changes.filter { it.recordingMs in effectiveStartOffsetMs..(effectiveStartOffsetMs+durationMs) }.map { it.copy(recordingMs=it.recordingMs-effectiveStartOffsetMs) })
    }
}
