import {render,screen} from '@testing-library/react'
import {expect,it} from 'vitest'
import {LiveVoiceBoard} from './LiveVoiceBoard'
it('mockSnrAndBurpsRemainLabeled',()=>{
 render(<LiveVoiceBoard frame={null} result={{task_type:'singing_audio_metrics',status:'completed',is_mock:true,payload:{burp_events:[1]},time_series:{snr_db:{sample_interval_ms:1000,values:[32]}}}} seconds={2}/>)
 expect(screen.getByText('实时声音流看板')).toBeVisible();expect(screen.getByText(/32 dB.*模拟/)).toBeVisible();expect(screen.getByText(/1 次.*模拟/)).toBeVisible();expect(screen.queryByText('Waviz')).not.toBeInTheDocument()
})

it('静音和采样不可用不插入提示行，声音看板布局保持不变',()=>{
 const base={timeDomain:new Float32Array(0),frequency:new Uint8Array(0),rmsDbfs:null,pitchHz:null}
 const view=render(<LiveVoiceBoard frame={{...base,status:'ready'}} seconds={0}/>)
 const board=view.container.querySelector('.live-voice-board')!
 const plots=view.container.querySelector('.voice-board-plots')!
 const count=board.children.length
 for(const status of ['silent','unavailable','ready'] as const){
  view.rerender(<LiveVoiceBoard frame={{...base,status}} seconds={1}/>)
  expect(board.children).toHaveLength(count)
  expect(view.container.querySelector('.voice-board-plots')).toBe(plots)
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
 }
})
