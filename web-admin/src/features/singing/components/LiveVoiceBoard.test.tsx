import {render,screen} from '@testing-library/react'
import {expect,it} from 'vitest'
import {LiveVoiceBoard} from './LiveVoiceBoard'
it('mockSnrAndBurpsRemainLabeled',()=>{
 render(<LiveVoiceBoard frame={null} result={{task_type:'singing_audio_metrics',status:'completed',is_mock:true,payload:{burp_events:[1]},time_series:{snr_db:{sample_interval_ms:1000,values:[32]}}}} seconds={2}/>)
 expect(screen.getByText('实时声音流看板')).toBeVisible();expect(screen.getByText(/32 dB.*模拟/)).toBeVisible();expect(screen.getByText(/1 次.*模拟/)).toBeVisible();expect(screen.queryByText('Waviz')).not.toBeInTheDocument()
})
