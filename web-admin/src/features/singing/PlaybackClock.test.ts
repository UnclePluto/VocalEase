import { describe, expect, it, vi } from 'vitest'

import { createPlaybackClock } from './PlaybackClock'

describe('PlaybackClock', () => {
  it('uses audio as the single clock and corrects a drifting video', async () => {
    const audio = document.createElement('audio')
    const video = document.createElement('video')
    Object.defineProperty(audio, 'currentTime', { value: 12, writable: true })
    Object.defineProperty(video, 'currentTime', { value: 10, writable: true })
    Object.defineProperty(audio, 'play', { value: vi.fn().mockResolvedValue(undefined) })
    Object.defineProperty(video, 'play', { value: vi.fn().mockResolvedValue(undefined) })
    const clock = createPlaybackClock(audio, video)

    await clock.play()
    audio.dispatchEvent(new Event('timeupdate'))

    expect(video.currentTime).toBe(12)
    expect(video.play).toHaveBeenCalledOnce()
    clock.destroy()
  })

  it('does not loop autoplay rejections', async () => {
    const audio = document.createElement('audio')
    Object.defineProperty(audio, 'play', { value: vi.fn().mockRejectedValue(new DOMException('blocked', 'NotAllowedError')) })
    const clock = createPlaybackClock(audio)
    await expect(clock.play()).rejects.toMatchObject({ name: 'NotAllowedError' })
    expect(audio.play).toHaveBeenCalledOnce()
    clock.destroy()
  })
})

 it('combinedModePlaysBoundAccompanimentOnlyAndKeepsPatientPosition',async()=>{
 const audio=document.createElement('audio'),video=document.createElement('video'),backing=document.createElement('audio')
 for(const media of [audio,video,backing]){Object.defineProperty(media,'play',{value:vi.fn().mockResolvedValue(undefined)});Object.defineProperty(media,'pause',{value:vi.fn()})}
 const metadata={schema_version:1 as const,sample_rate:48000,source_asset_id:'s',accompaniment_asset_id:'a',reference_version:null,mode_changes:[],anchors:[{recording_ms:0,song_ms:56000,track:'accompaniment' as const,playing:true,segment:0},{recording_ms:1000,song_ms:57000,track:'accompaniment' as const,playing:false,segment:0},{recording_ms:3000,song_ms:57000,track:'accompaniment' as const,playing:false,segment:0}]}
 const clock=createPlaybackClock(audio,video,backing,metadata)
 clock.setMode('combined');await clock.play()
 expect(video.muted).toBe(true);expect(backing.currentTime).toBe(56);expect(backing.play).toHaveBeenCalled()
 audio.currentTime=2;audio.dispatchEvent(new Event('timeupdate'))
 expect(backing.currentTime).toBe(57);expect(backing.pause).toHaveBeenCalled()
 clock.setMode('patient');expect(audio.currentTime).toBe(2);clock.destroy()
 })
