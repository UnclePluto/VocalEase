import { songTimeAt } from './PlaybackTimeline'
import type { PlaybackMetadata } from './types'
export interface PlaybackClock {
  play(): Promise<void>
  pause(): void
  seek(seconds: number): void
  setFollowers(video:HTMLMediaElement|null,accompaniment:HTMLMediaElement|null,metadata?:PlaybackMetadata|null,offsetMillis?:number,manualOffsetMillis?:number):void
  setMode(mode:'combined'|'patient'):void
  currentTime(): number
  subscribe(listener: (seconds: number) => void): () => void
  destroy(): void
}
const DRIFT_SECONDS=.1
export function createPlaybackClock(audio: HTMLMediaElement, video?: HTMLMediaElement|null, accompaniment?: HTMLMediaElement|null, metadata?: PlaybackMetadata|null, offsetMillis=0, manualOffsetMillis?:number): PlaybackClock {
  const listeners=new Set<(seconds:number)=>void>()
  let mode:'combined'|'patient'='patient', wanted=false, buffering=false, destroyed=false, raf=0
  let videoRunning=false,backingRunning=false,backingFailed=false,videoFailed=false
  if(video) video.muted=true
  const stopFollowers=()=>{video?.pause();accompaniment?.pause();videoRunning=false;backingRunning=false}
  const align=(media:HTMLMediaElement,seconds:number)=>{if(Math.abs(media.currentTime-seconds)>DRIFT_SECONDS) media.currentTime=seconds;media.playbackRate=audio.playbackRate}
  const sync=()=>{
    if(destroyed)return
    const seconds=audio.currentTime||0
    if(video){align(video,seconds);if(wanted&&!buffering&&!videoRunning&&!videoFailed){videoRunning=true;void video.play().catch(()=>{videoFailed=true;videoRunning=false})}}
    const song=metadata? songTimeAt(seconds,metadata):manualOffsetMillis!==undefined ? {seconds:seconds+manualOffsetMillis/1000,playing:true}:null
    if(accompaniment){
      const follower=accompaniment
      const backingSeconds=song ? song.seconds+offsetMillis/1000 : null
      if(backingSeconds!==null && backingSeconds>=0) align(accompaniment,backingSeconds)
      if(mode==='combined'&&wanted&&!buffering&&song?.playing&&backingSeconds!==null&&backingSeconds>=0&&!backingFailed){
        if(!backingRunning){backingRunning=true;void accompaniment.play().catch(()=>{backingRunning=false;backingFailed=true;follower.dispatchEvent(new Event('error'))})}
      }else if(backingRunning){accompaniment.pause();backingRunning=false}
    }
    listeners.forEach(listener=>listener(seconds))
  }
  const tick=()=>{raf=0;if(!wanted||destroyed)return;sync();raf=requestAnimationFrame(tick)}
  const startLoop=()=>{if(!raf&&!destroyed&&wanted)raf=requestAnimationFrame(tick)}
  const stopLoop=()=>{if(raf)cancelAnimationFrame(raf);raf=0}
  const onPause=()=>{wanted=false;stopLoop();stopFollowers();sync()}
  const onPlaying=()=>{wanted=true;buffering=false;sync();startLoop()}
  const onWaiting=()=>{buffering=true;stopFollowers()}
  const onVisibility=()=>{if(document.hidden){audio.pause();onPause()}else sync()}
  const handlers:Record<string,()=>void>={timeupdate:sync,seeked:sync,ratechange:sync,playing:onPlaying,waiting:onWaiting,stalled:onWaiting,pause:onPause,ended:onPause,error:onPause}
  Object.entries(handlers).forEach(([event,handler])=>audio.addEventListener(event,handler))
  document.addEventListener('visibilitychange',onVisibility)
  return {
    async play(){backingFailed=false;videoFailed=false;await audio.play();if(destroyed)return;wanted=true;buffering=false;sync();startLoop()},
    pause(){audio.pause();onPause()},
    seek(seconds){audio.currentTime=Math.max(0,seconds);sync()},
    setFollowers(nextVideo,nextBacking,nextMetadata,nextOffset=0,nextManualOffset){
      stopFollowers();video=nextVideo;accompaniment=nextBacking;metadata=nextMetadata;offsetMillis=nextOffset;manualOffsetMillis=nextManualOffset
      videoFailed=false;backingFailed=false
      if(video) video.muted=true
      sync()
    },
    setMode(next){mode=accompaniment&&(metadata||manualOffsetMillis!==undefined)?next:'patient';backingFailed=false;sync()},
    currentTime:()=>audio.currentTime||0,
    subscribe(listener){listeners.add(listener);return()=>listeners.delete(listener)},
    destroy(){destroyed=true;wanted=false;stopLoop();Object.entries(handlers).forEach(([event,handler])=>audio.removeEventListener(event,handler));document.removeEventListener('visibilitychange',onVisibility);audio.pause();stopFollowers();listeners.clear()},
  }
}
