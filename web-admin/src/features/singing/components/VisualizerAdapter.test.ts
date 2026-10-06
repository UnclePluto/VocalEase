import {expect,it,vi} from 'vitest'
import {createVisualizerAdapter} from './VisualizerAdapter'
it('pauseThenResumeRestartsRenderingWithoutSecondSource',async()=>{
 const frame={timeDomain:new Float32Array(32),frequency:new Uint8Array(16),rmsDbfs:null,pitchHz:null,status:'silent' as const}
 const analyser={attach:vi.fn().mockResolvedValue(undefined),resume:vi.fn().mockResolvedValue(undefined),sample:()=>frame,close:vi.fn()}
 const requestFrame=vi.fn().mockReturnValue(7),cancelFrame=vi.fn(),draw=vi.fn()
 const adapter=createVisualizerAdapter({analyserFactory:()=>analyser,rendererFactory:()=>({draw,freeze:vi.fn(),reset:vi.fn(),destroy:vi.fn()}),requestFrame,cancelFrame})
 const media=document.createElement('audio'),canvas=document.createElement('canvas');await adapter.start(media,canvas);adapter.stop();await adapter.start(media,canvas)
 expect(analyser.attach).toHaveBeenCalledTimes(1);expect(draw).toHaveBeenCalledTimes(2);expect(requestFrame).toHaveBeenCalledTimes(2);adapter.destroy();expect(analyser.close).toHaveBeenCalledTimes(1)
})

it('预设逐动画帧绘制而指标更新维持限频',async()=>{
 vi.spyOn(performance,'now').mockReturnValue(0)
 let next:FrameRequestCallback=()=>{}
 const frame={timeDomain:new Float32Array(1024),frequency:new Uint8Array(512),rmsDbfs:null,pitchHz:null,status:'silent' as const}
 const draw=vi.fn(),onFrame=vi.fn()
 const adapter=createVisualizerAdapter({analyserFactory:()=>({attach:async()=>{},resume:async()=>{},sample:()=>frame,close:()=>{}}),rendererFactory:()=>({draw,freeze:()=>{},reset:()=>{},destroy:()=>{}}),requestFrame:callback=>{next=callback;return 1},cancelFrame:()=>{}})
 await adapter.start(document.createElement('audio'),document.createElement('canvas'),null,onFrame)
 next(16);next(32)
 expect(draw).toHaveBeenCalledTimes(3)
 expect(onFrame).toHaveBeenCalledTimes(1)
 adapter.destroy()
})
