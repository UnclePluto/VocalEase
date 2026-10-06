package com.vocaease.patient.core.media

import android.media.*
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MuxedRecording(val durationMillis: Long, val effectiveStartOffsetMillis: Long)
fun interface RecordingMuxer {
    suspend fun merge(videoOnly:File,patientAudio:File,output:File,videoStartNanos:Long,audioStartNanos:Long):MuxedRecording
}

/** 保留视频压缩样本与旋转信息；只添加患者 AAC 音轨。 */
class RecordedAvMuxer : RecordingMuxer {
    override suspend fun merge(videoOnly:File,patientAudio:File,output:File,videoStartNanos:Long,audioStartNanos:Long):MuxedRecording = withContext(Dispatchers.IO) {
        val video=MediaExtractor();val audio=MediaExtractor();var muxer:MediaMuxer?=null;var started=false
        try {
            video.setDataSource(videoOnly.absolutePath);audio.setDataSource(patientAudio.absolutePath)
            val videos=(0 until video.trackCount).filter { video.getTrackFormat(it).getString(MediaFormat.KEY_MIME)=="video/avc" }
            val audios=(0 until audio.trackCount).filter { audio.getTrackFormat(it).getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm" }
            check(videos.size==1 && video.trackCount==1 && audios.size==1 && audio.trackCount==1)
            val vf=video.getTrackFormat(videos.single());val af=audio.getTrackFormat(audios.single())
            val offsetUs=(audioStartNanos-videoStartNanos)/1000
            // 复制视频必须保留首个关键帧，音频起点迟到超过预算时拒绝发布。
            check(offsetUs in 0..100_000) { "采音起点超出同步预算" }
            val durationUs=minOf(vf.getLong(MediaFormat.KEY_DURATION),offsetUs+af.getLong(MediaFormat.KEY_DURATION))
            check(durationUs>offsetUs)
            muxer=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vt=muxer.addTrack(vf);val at=muxer.addTrack(af)
            val retriever=MediaMetadataRetriever()
            try { retriever.setDataSource(videoOnly.absolutePath);muxer.setOrientationHint(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) } finally { retriever.release() }
            muxer.start();started=true
            copy(video,videos.single(),muxer,vt,0,durationUs)
            copy(audio,audios.single(),muxer,at,offsetUs,durationUs)
            muxer.stop();started=false
            MuxedRecording(durationUs/1000,0)
        } catch(error:Throwable) { output.delete();throw error }
        finally { if(started) runCatching { muxer?.stop() };muxer?.release();video.release();audio.release() }
    }
    private fun copy(source:MediaExtractor,index:Int,target:MediaMuxer,track:Int,offset:Long,end:Long) {
        source.selectTrack(index)
        val format=source.getTrackFormat(index)
        val capacity=if(format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(64*1024,8*1024*1024) else 2*1024*1024
        val buffer=ByteBuffer.allocateDirect(capacity);val info=MediaCodec.BufferInfo();var count=0
        while(source.sampleTime>=0) {
            val time=source.sampleTime+offset
            if(time>=end) break
            buffer.clear();val size=source.readSampleData(buffer,0);check(size in 0..capacity)
            if(size==0) break
            info.set(0,size,time,codecSampleFlags(source.sampleFlags));target.writeSampleData(track,buffer,info);count++
            if(!source.advance()) break
        }
        check(count>0)
    }
}

internal fun codecSampleFlags(flags: Int): Int {
    require(flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "录制文件不能包含加密编码样本" }
    var result = 0
    if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) result = result or MediaCodec.BUFFER_FLAG_KEY_FRAME
    if (flags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) result = result or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
    return result
}
