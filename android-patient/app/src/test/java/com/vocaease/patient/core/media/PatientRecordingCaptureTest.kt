package com.vocaease.patient.core.media
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PatientRecordingCaptureTest {
    @Test fun onlyOneMicAndBothFinalizeBeforeReview() = runBlocking {
        val camera=FakeCamera();val microphone=FakeMic()
        val muxEntered=CompletableDeferred<Unit>();val finishMux=CompletableDeferred<Unit>()
        val events=mutableListOf<CaptureEvent>()
        val capture=PatientRecordingCapture(camera,microphone,RecordingMuxer { video,audio,out,v,a ->
            muxEntered.complete(Unit);finishMux.await();out.writeBytes(byteArrayOf(1));MuxedRecording(1000,0)
        }, Dispatchers.Unconfined)
        capture.listener={events+=it}
        val out=File.createTempFile("patient-capture-",".mp4")
        capture.start(out);camera.listener(CaptureEvent.Started)
        assertEquals(1,microphone.starts)
        capture.stop();capture.stop();assertEquals(1,camera.stops)
        val finalize=async { camera.listener(CaptureEvent.Finalized(1000)) }
        assertFalse(events.any { it is CaptureEvent.Finalized })
        microphone.done.complete(AudioCaptureResult(48000,1000,1000))
        muxEntered.await();assertFalse(events.any { it is CaptureEvent.Finalized })
        finishMux.complete(Unit);finalize.await()
        assertEquals(1,events.filterIsInstance<CaptureEvent.Finalized>().size)
        capture.release();out.delete();Unit
    }
    @Test fun stopIsIdempotentAndAccountExitCancelsPublish() = runBlocking {
        val camera=FakeCamera();val mic=FakeMic();var muxes=0
        val capture=PatientRecordingCapture(camera,mic,RecordingMuxer { _,_,_,_,_ -> muxes++;MuxedRecording(1,0) },Dispatchers.Unconfined)
        val events=mutableListOf<CaptureEvent>();capture.listener={events+=it}
        val out=File.createTempFile("patient-release-",".mp4")
        capture.start(out);camera.listener(CaptureEvent.Started);capture.release()
        camera.listener(CaptureEvent.Finalized(1000))
        assertEquals(0,muxes);assertFalse(events.any { it is CaptureEvent.Finalized });out.delete();Unit
    }
    private class FakeCamera:RecordingCapture {
        override var listener:suspend(CaptureEvent)->Unit={}
        var stops=0
        override suspend fun bindFrontCamera(){}
        override fun start(output:File){}
        override fun stop(){stops++}
    }
    private class FakeMic:PatientMicrophone {
        val done=CompletableDeferred<AudioCaptureResult>();var starts=0
        override fun start(output:File,onPcm:(PcmBlock)->Unit){starts++;onPcm(PcmBlock(ShortArray(2208),48000,System.nanoTime()))}
        override suspend fun stop():AudioCaptureResult=done.await()
        override fun release(){}
    }
}
