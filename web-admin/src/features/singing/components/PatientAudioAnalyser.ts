export type PatientAudioFrame = {timeDomain:Float32Array;frequency:Uint8Array;rmsDbfs:number|null;pitchHz:number|null;status:'ready'|'silent'|'unavailable'}
export interface PatientSampler {attach(media:HTMLMediaElement):Promise<void>;sample():PatientAudioFrame;resume():Promise<void>;close():void}
type SharedSource={context:AudioContext;source:MediaElementAudioSourceNode;analyser:AnalyserNode;owners:number}
const sources=new WeakMap<HTMLMediaElement,SharedSource>()
const empty=():PatientAudioFrame=>({timeDomain:new Float32Array(0),frequency:new Uint8Array(0),rmsDbfs:null,pitchHz:null,status:'unavailable'})

/** YIN：低能量或非周期信号不提供音高，两个图始终取同一个患者节点。 */
function pitch(samples:Float32Array,sampleRate:number):number|null {
  const step=Math.max(1,Math.floor(sampleRate/8000)),rate=sampleRate/step
  const length=Math.min(Math.floor(samples.length/step),Math.round(rate*.046))
  const data=new Float32Array(length)
  for(let i=0;i<length;i++) data[i]=samples[samples.length-length*step+i*step]
  const min=Math.floor(rate/1000),max=Math.min(Math.ceil(rate/65),Math.floor(length/2)),difference=new Float32Array(max+1)
  let cumulative=0
  for(let lag=1;lag<=max;lag++){
    let sum=0;for(let i=0;i<length-max;i++){const delta=data[i]-data[i+lag];sum+=delta*delta}
    difference[lag]=sum;cumulative+=sum
    difference[lag]=cumulative>0?sum*lag/cumulative:1
  }
  for(let lag=min;lag<max;lag++){
    if(difference[lag]>=.15)continue
    while(lag+1<max&&difference[lag+1]<difference[lag])lag++
    const left=difference[lag-1],center=difference[lag],right=difference[lag+1],denominator=2*(2*center-left-right)
    const offset=denominator?(right-left)/denominator:0
    return rate/(lag+Math.max(-1,Math.min(1,offset)))
  }
  return null
}
export class PatientAudioAnalyser implements PatientSampler {
  private shared:SharedSource|null=null
  private media:HTMLMediaElement|null=null
  constructor(private createContext:()=>AudioContext=()=>new AudioContext()){}
  async attach(media:HTMLMediaElement){
    if(this.media===media)return
    this.close()
    let shared=sources.get(media)
    if(!shared){
      const context=this.createContext(),source=context.createMediaElementSource(media),analyser=context.createAnalyser()
      analyser.fftSize=4096;analyser.smoothingTimeConstant=.65
      shared={context,source,analyser,owners:0};sources.set(media,shared)
    }
    if(shared.context.state==='closed')throw new Error('患者声音采样已释放，请重新准备回放')
    if(shared.owners++===0){shared.source.connect(shared.analyser);shared.analyser.connect(shared.context.destination)}
    this.media=media;this.shared=shared
    await this.resume()
  }
  async resume(){await this.shared?.context.resume()}
  sample():PatientAudioFrame{
    const shared=this.shared
    if(!shared||this.media?.error||shared.context.state==='closed')return empty()
    const timeDomain=new Float32Array(shared.analyser.fftSize),frequency=new Uint8Array(shared.analyser.frequencyBinCount)
    try{shared.analyser.getFloatTimeDomainData(timeDomain);shared.analyser.getByteFrequencyData(frequency)}catch{return empty()}
    let sum=0;for(const value of timeDomain)sum+=value*value
    const rms=Math.sqrt(sum/timeDomain.length),silent=rms<.008
    return {timeDomain,frequency,rmsDbfs:rms>0?20*Math.log10(rms):null,pitchHz:silent?null:pitch(timeDomain,shared.context.sampleRate),status:silent?'silent':'ready'}
  }
  close(){
    const shared=this.shared,media=this.media
    this.shared=null;this.media=null
    if(!shared||!media)return
    if(--shared.owners===0){
      shared.source.disconnect();shared.analyser.disconnect()
      // StrictMode/effect 重新挂载期间复用已绑定节点；只有 DOM 移除且无人使用才关闭。
      queueMicrotask(()=>{if(!media.isConnected&&shared.owners===0&&shared.context.state!=='closed')void shared.context.close()})
    }
  }
}
