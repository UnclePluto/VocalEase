package com.vocaease.patient.core.media

import android.media.*
import java.io.File
import java.nio.ByteOrder

/** AAC-LC，只接收患者原始 PCM；不读取歌曲播放数据。 */
class PatientAudioEncoder(output: File, private val sampleRate: Int) {
    private val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
    private val muxer = MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1;private var started = false;private var eos = false
    init {
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm",sampleRate,1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE,128000);format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8192)
        codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
    }
    fun write(samples: ShortArray, count: Int, ptsUs: Long) {
        var offset=0
        while (offset<count) {
            val deadline=System.nanoTime()+2_000_000_000L
            var index=codec.dequeueInputBuffer(10_000)
            while (index<0 && System.nanoTime()<deadline) { drain();index=codec.dequeueInputBuffer(10_000) }
            check(index>=0) { "音频编码背压超时" }
            val input=checkNotNull(codec.getInputBuffer(index));input.clear();input.order(ByteOrder.LITTLE_ENDIAN)
            val size=minOf(count-offset,input.remaining()/2)
            check(size>0)
            for (i in 0 until size) input.putShort(samples[offset+i])
            codec.queueInputBuffer(index,0,size*2,ptsUs+offset*1_000_000L/sampleRate,0)
            offset+=size;drain()
        }
    }
    fun finish(ptsUs: Long) {
        val deadline=System.nanoTime()+5_000_000_000L
        var index=codec.dequeueInputBuffer(10_000)
        while(index<0 && System.nanoTime()<deadline) { drain();index=codec.dequeueInputBuffer(10_000) }
        check(index>=0);codec.queueInputBuffer(index,0,0,ptsUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        while(!eos && System.nanoTime()<deadline) drain()
        check(eos && started) { "AAC结束失败" }
        muxer.stop();started=false
    }
    private fun drain() {
        while(true) {
            val index=codec.dequeueOutputBuffer(info,10_000)
            if(index==MediaCodec.INFO_TRY_AGAIN_LATER) return
            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                check(track<0);track=muxer.addTrack(codec.outputFormat);muxer.start();started=true
            } else if(index>=0) {
                val buffer=checkNotNull(codec.getOutputBuffer(index))
                if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                    check(started);buffer.position(info.offset);buffer.limit(info.offset+info.size);muxer.writeSampleData(track,buffer,info)
                }
                eos=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                codec.releaseOutputBuffer(index,false)
                if(eos) return
            }
        }
    }
    fun release() {
        runCatching { codec.stop() };codec.release()
        if(started) runCatching { muxer.stop() }
        muxer.release()
    }
}
