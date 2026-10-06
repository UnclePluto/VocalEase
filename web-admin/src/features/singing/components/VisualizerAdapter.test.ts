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
