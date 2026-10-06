import {PatientAudioAnalyser,type PatientAudioFrame,type PatientSampler} from './PatientAudioAnalyser'
import {VoiceBoardRenderer,type VoiceRenderer} from './VoiceBoardRenderer'
export interface VisualizerAdapter {start(media:HTMLMediaElement,canvas:HTMLCanvasElement,polar?:HTMLCanvasElement|null,onFrame?:(frame:PatientAudioFrame)=>void):Promise<void>;stop():void;reset?():void;destroy():void}
export type VisualizerDependencies={analyserFactory?:()=>PatientSampler;rendererFactory?:(canvas:HTMLCanvasElement,polar?:HTMLCanvasElement|null)=>VoiceRenderer;requestFrame?:(callback:FrameRequestCallback)=>number;cancelFrame?:(id:number)=>void}
export function createVisualizerAdapter(dependencies:VisualizerDependencies={}):VisualizerAdapter {
  const requestFrame=dependencies.requestFrame??requestAnimationFrame,cancelFrame=dependencies.cancelFrame??cancelAnimationFrame
  let analyser:PatientSampler|null=null,renderer:VoiceRenderer|null=null,mediaNode:HTMLMediaElement|null=null,raf=0,destroyed=false,running=false,generation=0,lastDraw=-Infinity
  const stop=()=>{running=false;generation++;if(raf)cancelFrame(raf);raf=0;renderer?.freeze()}
  return {
    async start(media,canvas,polar,onFrame){
      if(destroyed)return
      const token=++generation
      analyser??=dependencies.analyserFactory?.()??new PatientAudioAnalyser()
      if(mediaNode!==media){await analyser.attach(media);mediaNode=media}
      else await analyser.resume()
      if(destroyed||token!==generation)return
      renderer??=dependencies.rendererFactory?.(canvas,polar)??new VoiceBoardRenderer(canvas,polar)
      if(running)return
      running=true;lastDraw=-Infinity
      const draw=(time:number)=>{
        raf=0;if(!running||destroyed)return
        if(time-lastDraw>=50){const frame=analyser!.sample();renderer!.draw(frame,media.currentTime);onFrame?.(frame);lastDraw=time}
        raf=requestFrame(draw)
      }
      draw(performance.now())
    },
    stop,
    reset(){renderer?.reset()},
    destroy(){destroyed=true;stop();renderer?.destroy();renderer=null;analyser?.close();analyser=null;mediaNode=null},
  }
}
