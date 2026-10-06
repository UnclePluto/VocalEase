package com.vocaease.patient.core.media

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.vocaease.patient.feature.training.RecordingInterruption

data class EncodedRecording(val video:File,val audio:File,val sampleRate:Int,val captureStartNanos:Long,val effectiveStartOffsetMillis:Long,val durationMillis:Long)

class PatientRecordingCapture(
    private val camera:RecordingCapture,
    private val microphone:PatientMicrophone=MicrophonePcmCapture(),
    private val muxer:RecordingMuxer=RecordedAvMuxer(),
    dispatcher:CoroutineDispatcher=Dispatchers.Default,
) : RecordingCapture {
    override var listener:suspend(CaptureEvent)->Unit={}
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private val released=AtomicBoolean();private val stopped=AtomicBoolean();private val finalizing=AtomicBoolean()
    private val samples=Channel<PcmBlock>(Channel.CONFLATED)
    private val pitchState=MutableStateFlow(PitchSample(0,null,0f))
    override val pitch=pitchState.asStateFlow()
    override var captureStartNanos:Long?=null;private set
    private val firstPcm=CompletableDeferred<Long>()
    private var output:File?=null;private var patientAudio:File?=null;private var videoStart=0L
    init {
        microphone.onFailure = { failure -> firstPcm.completeExceptionally(failure);scope.launch { if (!released.get()) { camera.stop(); listener(CaptureEvent.Failure(RecordingInterruption.AUDIO)) } } }
        scope.launch { val detector=PitchDetector();for(block in samples) pitchState.value=detector.detect(block.samples,block.sampleRate,((block.firstSampleNanos-(captureStartNanos ?: block.firstSampleNanos))/1_000_000).coerceAtLeast(0)) }
        camera.listener={event ->
            if(!released.get()) when(event) {
                CaptureEvent.Started -> {
                    try {
                        videoStart=checkNotNull(camera.captureStartNanos) { "缺少录像首帧时间戳" }
                        captureStartNanos=videoStart
                        val audioStart=withTimeout(5000) { firstPcm.await() }
                        val timing=File(checkNotNull(output).parentFile,checkNotNull(output).name+".timing")
                        java.io.DataOutputStream(timing.outputStream()).use { data -> data.writeLong(videoStart);data.writeLong(audioStart) }
                        timing.setReadable(false,false);timing.setWritable(false,false);timing.setReadable(true,true);timing.setWritable(true,true)
                        if(!released.get()) listener(CaptureEvent.Started)
                    } catch (_:Exception) { microphone.release();camera.stop();listener(CaptureEvent.Failure(RecordingInterruption.AUDIO)) }
                }
                is CaptureEvent.Failure -> { microphone.release();listener(event) }
                is CaptureEvent.Finalized -> if(finalizing.compareAndSet(false,true)) {
                    val video=checkNotNull(output);val audio=checkNotNull(patientAudio);val merged=File(video.parentFile,video.name+".merged")
                    try {
                        val recorded=microphone.stop()
                        if(!released.get()) {
                            val result=muxer.merge(video,audio,merged,videoStart,recorded.captureStartNanos)
                            if(!released.get()) {
                                check(merged.renameTo(video)) { "合并录像替换失败" }
                                listener(CaptureEvent.Finalized(result.durationMillis,event.interruption,EncodedRecording(video,audio,recorded.sampleRate,videoStart,result.effectiveStartOffsetMillis,result.durationMillis)))
                            }
                        }
                    } catch(_ :Exception) { if(!released.get()) listener(CaptureEvent.Failure(RecordingInterruption.FINALIZE)) }
                    finally { audio.delete();merged.delete();File(video.parentFile,video.name+".timing").delete() }
                }
            }
        }
    }
    override suspend fun bindFrontCamera()=camera.bindFrontCamera()
    override fun start(output:File) {
        check(this.output==null && !released.get());this.output=output
        patientAudio=File(output.parentFile,output.name+".patient-audio").also { it.createNewFile();it.setReadable(false,false);it.setWritable(false,false);it.setReadable(true,true);it.setWritable(true,true) }
        // 先启动唯一麦克风；录像首帧就绪后再开始歌曲，避免编码/事件排队延迟丢失人声。
        try {
            microphone.start(checkNotNull(patientAudio)) { firstPcm.complete(it.firstSampleNanos);samples.trySend(it) }
            camera.start(output)
        } catch(failure:Exception) { microphone.release();throw failure }
    }
    override fun stop() { if(stopped.compareAndSet(false,true)) camera.stop() }
    override fun release() {
        if(!released.compareAndSet(false,true)) return
        camera.release();microphone.release();samples.close();scope.cancel()
        patientAudio?.delete();output?.let { File(it.parentFile,it.name+".merged").delete();File(it.parentFile,it.name+".timing").delete() }
    }
}
