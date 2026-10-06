import {AudioAnalyzer,Visualizer,type IOptions} from 'waviz'
import type {PatientAudioFrame} from './PatientAudioAnalyser'

export interface VoiceRenderer {draw(frame:PatientAudioFrame,elapsedSeconds:number):void;freeze():void;reset():void;destroy():void}

// Waviz 1.0.0 Wave4 / Mixed4 的默认图层；由现有患者采样器供数，避免重复绑定或混入伴奏。
const wave4:IOptions[] = [
  {domain:['time',450],color:['#eb1b00ff']},
  {domain:['time',400],color:['#eb4300ff']},
  {domain:['time',350],color:['#ff6715ff']},
  {domain:['time',300],color:['#ff9320ff']},
  {domain:['time',250],color:['#ffb836ff']},
  {domain:['time',200],color:['#ffca68ff']},
  {domain:['time',150],color:['#ffdd9dff']},
  {domain:['time',100],color:['#ffeeceff']},
]
const mixed4:IOptions[] = [
  {domain:['time',400],coord:['polar',100],viz:['bars',64],color:['radialGradient','#70044aff','#f84791ff',100,120],stroke:[6]},
  {domain:['time',300],coord:['polar',100],viz:['line'],color:['radialGradient','#003bdcff','#1893B8',100,150],stroke:[4]},
  {domain:['time',250],coord:['polar',0,0,.1],viz:['particles',[3,3],0,35,5,50],color:['radialGradient','#002f41ff','#02bff8ff',10,50],stroke:[1]},
]

class PatientFrameData extends AudioAnalyzer {
  private time=new Uint8Array(1024).fill(128)
  private frequency=new Uint8Array(1024)
  update(frame:PatientAudioFrame){
    this.time.fill(128)
    const start=Math.max(0,frame.timeDomain.length-1024)
    for(let i=0;i<Math.min(1024,frame.timeDomain.length);i++){
      this.time[i]=Math.round((Math.max(-1,Math.min(1,frame.timeDomain[start+i]))+1)*127.5)
    }
    this.frequency=new Uint8Array(frame.frequency)
  }
  override getTimeDomainData(){return this.time}
  override getFrequencyData(){return this.frequency}
}

export class VoiceBoardRenderer implements VoiceRenderer {
  private data=new PatientFrameData()
  private wave:Visualizer
  private polar:Visualizer|null
  private lastTime=-1
  private destroyed=false
  constructor(wave:HTMLCanvasElement,polar?:HTMLCanvasElement|null){
    this.wave=new Visualizer(wave,this.data)
    this.polar=polar?new Visualizer(polar,this.data):null
  }
  draw(frame:PatientAudioFrame,elapsedSeconds:number){
    if(this.destroyed)return
    if(elapsedSeconds<this.lastTime)this.reset()
    this.lastTime=elapsedSeconds
    this.data.update(frame)
    this.paint(this.wave,wave4)
    if(this.polar)this.paint(this.polar,mixed4)
  }
  private paint(engine:Visualizer,layers:IOptions[]){
    const {ctx,canvas}=engine
    ctx.clearRect(0,0,canvas.width,canvas.height)
    ctx.fillStyle='#0b1725';ctx.fillRect(0,0,canvas.width,canvas.height)
    for(const layer of layers)engine.layer({domain:['time'],coord:['rect'],viz:['line'],color:['#E34AB0'],stroke:[2],...layer})
    engine.frame++
  }
  freeze(){}
  reset(){
    this.lastTime=-1
    for(const engine of [this.wave,this.polar]){
      if(!engine)continue
      engine.frame=0;engine.particleSystem=[]
      engine.ctx.clearRect(0,0,engine.canvas.width,engine.canvas.height)
    }
  }
  destroy(){this.destroyed=true;this.reset()}
}
