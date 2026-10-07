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
        require(durationMs>=0 && effectiveStartOffsetMs>=0 && anchors.isNotEmpty())
        val end=effectiveStartOffsetMs+durationMs
        fun boundary(ms:Long):PlaybackAnchor {
            val index=anchors.indexOfLast { it.recordingMs<=ms }.coerceAtLeast(0)
            val prior=anchors[index];val next=anchors.getOrNull(index+1)
            if(prior.recordingMs==ms) return prior
            val rate=if(!prior.playing) 0.0 else if(next!=null && next.segment==prior.segment)
                (next.songMs-prior.songMs).toDouble()/(next.recordingMs-prior.recordingMs) else 1.0
            return prior.copy(recordingMs=ms,songMs=(prior.songMs+(ms-prior.recordingMs)*rate).toLong().coerceAtLeast(0))
        }
        // 边界也属于有效时间轴，不能因最后一个停止锚点晚于成片几毫秒而丢掉整段尾音。
        val clipped=(listOf(boundary(effectiveStartOffsetMs))+anchors.filter { it.recordingMs>effectiveStartOffsetMs && it.recordingMs<end }+listOf(boundary(end)))
            .distinctBy { it.recordingMs }.map { it.copy(recordingMs=it.recordingMs-effectiveStartOffsetMs) }
        return snapshot().copy(anchors=clipped,modeChanges=changes.filter { it.recordingMs in effectiveStartOffsetMs..end }.map { it.copy(recordingMs=it.recordingMs-effectiveStartOffsetMs) })
    }
}
