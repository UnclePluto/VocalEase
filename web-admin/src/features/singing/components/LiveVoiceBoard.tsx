import type {RefObject} from 'react'
import type {AnalysisResult} from '../types'
import type {PatientAudioFrame} from './PatientAudioAnalyser'
import {nearestMetricSample} from './MetricPanel'
export function LiveVoiceBoard({frame,result,seconds,playing=false,waveRef,polarRef}:{frame:PatientAudioFrame|null;result?:AnalysisResult;seconds:number;playing?:boolean;waveRef?:RefObject<HTMLCanvasElement|null>;polarRef?:RefObject<HTMLCanvasElement|null>}){
 const snr=result?.time_series.snr_db
 const snrValue=snr?nearestMetricSample(snr.values,snr.sample_interval_ms,seconds):undefined
 const burps=result?.payload?.burp_events?.filter(time=>time<=seconds).length
 return <section className="live-voice-board" aria-label="患者声音看板">
   <div className="voice-board-heading"><h2>实时声音流看板</h2><span className={playing?'voice-live':'voice-paused'}>{playing?'● LIVE':'已暂停'}</span></div>
   <div className="voice-board-metrics">
     <div><span>实时音量</span><strong className="voice-volume">{frame?.rmsDbfs==null?'—':`${frame.rmsDbfs.toFixed(1)} dBFS`}</strong></div>
     <div><span>基频 F0</span><strong className="voice-pitch">{frame?.pitchHz==null?'—':`${Math.round(frame.pitchHz)} Hz`}</strong></div>
     <div><span>信噪比</span><strong className="voice-snr">{result?.is_mock&&snrValue!==undefined?`${snrValue} dB（模拟）`:'暂无真实数据'}</strong></div>
     <div><span>嗳气事件</span><strong className="voice-burps">{result?.is_mock&&burps!==undefined?`${burps} 次（模拟）`:'暂无真实数据'}</strong></div>
   </div>
   {frame?.status==='unavailable'?<p className="voice-sampling-error" role="status">患者声音采样不可用，请检查媒体授权与跨域访问。</p>:null}
   {frame?.status==='silent'?<p className="voice-sampling-status" role="status">当前无稳定患者人声音高</p>:null}
   <div className="voice-board-plots"><div><div className="voice-plot-title"><h3>时序波形流</h3><span>Layered waveform</span></div><canvas ref={waveRef} width="640" height="300" aria-label="患者时序波形"/></div><div><div className="voice-plot-title"><h3>极坐标声场</h3><span>Polar waveform</span></div><canvas ref={polarRef} width="360" height="300" aria-label="患者极坐标频谱"/></div></div>
 </section>
}
