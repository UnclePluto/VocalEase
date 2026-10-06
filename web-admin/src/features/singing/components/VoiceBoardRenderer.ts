import type {PatientAudioFrame} from './PatientAudioAnalyser'
export interface VoiceRenderer {draw(frame:PatientAudioFrame,elapsedSeconds:number):void;freeze():void;reset():void;destroy():void}
export class VoiceBoardRenderer implements VoiceRenderer {
  private history:{top:number;bottom:number}[]=[]
  private lastTime=-1
  private destroyed=false
  constructor(private wave:HTMLCanvasElement,private polar?:HTMLCanvasElement|null){}
  draw(frame:PatientAudioFrame,elapsedSeconds:number){
    if(this.destroyed)return
    if(elapsedSeconds<this.lastTime)this.reset()
    if(elapsedSeconds!==this.lastTime){
      const samples=frame.timeDomain
      for(let bucket=0;bucket<8&&samples.length;bucket++){
        let top=0,bottom=0
        for(let i=Math.floor(bucket*samples.length/8);i<Math.floor((bucket+1)*samples.length/8);i++){top=Math.max(top,samples[i]);bottom=Math.max(bottom,-samples[i])}
        this.history.push({top,bottom})
      }
      this.history=this.history.slice(-240);this.lastTime=elapsedSeconds
    }
    const ctx=this.wave.getContext('2d'),w=this.wave.width,h=this.wave.height
    if(ctx){
      ctx.clearRect(0,0,w,h);ctx.fillStyle='#0b1725';ctx.fillRect(0,0,w,h)
      const values=this.history.length?this.history:[{top:0,bottom:0}]
      const layer=(side:'top'|'bottom',color:string,scale:number)=>{
        ctx.beginPath();ctx.moveTo(0,h/2)
        for(let i=0;i<values.length;i++)ctx.lineTo(i*w/Math.max(1,values.length-1),h/2+(side==='top'?-1:1)*values[i][side]*h*.46*scale)
        ctx.lineTo(w,h/2);ctx.closePath();ctx.fillStyle=color;ctx.fill()
      }
      layer('top','#ffe9a0',1);layer('top','#ffc34a',.62);layer('bottom','#ff4149',1)
      ctx.beginPath();ctx.moveTo(0,h/2);ctx.lineTo(w,h/2);ctx.strokeStyle='#6d7788';ctx.lineWidth=1;ctx.stroke()
    }
    const polar=this.polar,p=polar?.getContext('2d')
    if(p&&polar){
      const width=polar.width,height=polar.height,cx=width/2,cy=height/2,r=Math.min(width,height)*.27
      p.clearRect(0,0,width,height);p.fillStyle='#0b1725';p.fillRect(0,0,width,height)
      for(let ring=1;ring<=3;ring++){p.beginPath();p.arc(cx,cy,r*ring/3,0,Math.PI*2);p.strokeStyle='#526775';p.lineWidth=1;p.stroke()}
      const bins=Math.min(256,frame.frequency.length)
      for(let i=0;i<bins;i++){
        const energy=frame.frequency[i]/255;if(!energy)continue
        const angle=i/bins*Math.PI*2-Math.PI/2
        const radius=r*(.25+energy*1.9)
        p.beginPath();p.moveTo(cx,cy);p.lineTo(cx+Math.cos(angle)*radius,cy+Math.sin(angle)*radius)
        p.strokeStyle=i<bins/4?'#ff4149':'#ff982d';p.lineWidth=1.4;p.stroke()
      }
    }
  }
  freeze(){}
  reset(){this.history=[];this.lastTime=-1;this.wave.getContext('2d')?.clearRect(0,0,this.wave.width,this.wave.height);if(this.polar)this.polar.getContext('2d')?.clearRect(0,0,this.polar.width,this.polar.height)}
  destroy(){this.destroyed=true;this.reset()}
}
