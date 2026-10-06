import {expect,it,vi} from 'vitest'
import {VoiceBoardRenderer} from './VoiceBoardRenderer'
it('bothPlotsUsePatientSamplesAndSilenceDoesNotInventSpikes',()=>{
 const make=()=>{const c=document.createElement('canvas'),ctx={clearRect:vi.fn(),fillRect:vi.fn(),beginPath:vi.fn(),moveTo:vi.fn(),lineTo:vi.fn(),closePath:vi.fn(),stroke:vi.fn(),fill:vi.fn(),arc:vi.fn(),fillStyle:'',strokeStyle:'',lineWidth:1};vi.spyOn(c,'getContext').mockReturnValue(ctx as unknown as CanvasRenderingContext2D);return {c,ctx}}
 const wave=make(),polar=make(),renderer=new VoiceBoardRenderer(wave.c,polar.c)
 renderer.draw({timeDomain:new Float32Array(32),frequency:new Uint8Array(16),rmsDbfs:null,pitchHz:null,status:'silent'},0)
 expect(wave.ctx.lineTo).toHaveBeenCalled();expect(polar.ctx.lineTo).not.toHaveBeenCalled()
 renderer.draw({timeDomain:new Float32Array(32).fill(.5),frequency:new Uint8Array(16).fill(100),rmsDbfs:-6,pitchHz:220,status:'ready'},1)
 expect(polar.ctx.lineTo).toHaveBeenCalled();renderer.destroy()
})
