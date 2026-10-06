import {expect,it,vi} from 'vitest'
import {VoiceBoardRenderer} from './VoiceBoardRenderer'

it('患者采样驱动Wave4分层和Mixed4的极坐标渐变且销毁后停止绘制',()=>{
 const make=()=>{
  const c=document.createElement('canvas')
  const gradient={addColorStop:vi.fn()}
  const ctx={clearRect:vi.fn(),fillRect:vi.fn(),beginPath:vi.fn(),moveTo:vi.fn(),lineTo:vi.fn(),closePath:vi.fn(),stroke:vi.fn(),fill:vi.fn(),arc:vi.fn(),roundRect:vi.fn(),createRadialGradient:vi.fn(()=>gradient),fillStyle:'',strokeStyle:'',lineWidth:1}
  vi.spyOn(c,'getContext').mockReturnValue(ctx as unknown as CanvasRenderingContext2D)
  return {c,ctx,gradient}
 }
 const wave=make(),polar=make(),renderer=new VoiceBoardRenderer(wave.c,polar.c)
 const timeDomain=Float32Array.from({length:1024},(_,i)=>Math.sin(i*.1)*.5)
 renderer.draw({timeDomain,frequency:new Uint8Array(512).fill(100),rmsDbfs:-6,pitchHz:220,status:'ready'},1)
 expect(wave.ctx.strokeStyle).toBe('#ffeeceff')
 expect(polar.gradient.addColorStop).toHaveBeenCalledWith(0,'#70044aff')
 expect(polar.gradient.addColorStop).toHaveBeenCalledWith(1,'#02bff8ff')
 expect(polar.ctx.lineTo).toHaveBeenCalled()
 expect(wave.ctx.lineTo.mock.calls.every(args=>args.every(Number.isFinite))).toBe(true)
 renderer.destroy()
 const count=wave.ctx.stroke.mock.calls.length
 renderer.draw({timeDomain,frequency:new Uint8Array(512),rmsDbfs:null,pitchHz:null,status:'silent'},2)
 expect(wave.ctx.stroke.mock.calls.length).toBe(count)
})
