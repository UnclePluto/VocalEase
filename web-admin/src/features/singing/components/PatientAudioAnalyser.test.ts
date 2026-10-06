import {expect,it,vi} from 'vitest'
import {PatientAudioAnalyser} from './PatientAudioAnalyser'
function fixture(silent=false){
 const samples=new Float32Array(4096);if(!silent)for(let i=0;i<samples.length;i++)samples[i]=.3*Math.sin(2*Math.PI*220*i/48000)
 const analyser={fftSize:4096,frequencyBinCount:2048,connect:vi.fn(),disconnect:vi.fn(),getFloatTimeDomainData:(out:Float32Array)=>out.set(samples),getByteFrequencyData:(out:Uint8Array)=>out.fill(silent?0:100)}
 const context={state:'running',sampleRate:48000,destination:{},createMediaElementSource:vi.fn(()=>({connect:vi.fn(),disconnect:vi.fn()})),createAnalyser:()=>analyser,resume:vi.fn().mockResolvedValue(undefined),close:vi.fn()}
 return {context:context as unknown as AudioContext,createSource:context.createMediaElementSource}
}
it('reusesPatientSourceAcrossPauseAndReattachment',async()=>{
 const {context,createSource}=fixture(),media=document.createElement('audio');document.body.append(media)
 const first=new PatientAudioAnalyser(()=>context);await first.attach(media);await first.resume();expect(first.sample().pitchHz).toBeCloseTo(220,0)
 first.close();const next=new PatientAudioAnalyser(()=>context);await next.attach(media);expect(createSource).toHaveBeenCalledTimes(1);next.close();media.remove()
})
it('silenceIsNotCorsErrorAndNoFallbackInventsSamples',async()=>{
 const {context}=fixture(true),media=document.createElement('audio'),patient=new PatientAudioAnalyser(()=>context)
 await patient.attach(media);expect(patient.sample()).toMatchObject({status:'silent',pitchHz:null,rmsDbfs:null})
 Object.defineProperty(media,'error',{value:{code:4}});expect(patient.sample().status).toBe('unavailable');patient.close()
})
