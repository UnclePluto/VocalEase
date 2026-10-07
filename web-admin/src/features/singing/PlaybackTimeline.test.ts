import { expect, it } from 'vitest'
import { songTimeAt } from './PlaybackTimeline'
import type { PlaybackMetadata } from './types'
export const metadata: PlaybackMetadata = {schema_version:1,sample_rate:48000,source_asset_id:'source',accompaniment_asset_id:'backing',reference_version:null,mode_changes:[],anchors:[
  {recording_ms:0,song_ms:56000,track:'accompaniment',playing:true,segment:0},
  {recording_ms:1000,song_ms:57000,track:'source',playing:false,segment:0},
  {recording_ms:3000,song_ms:57000,track:'source',playing:true,segment:0},
  {recording_ms:5000,song_ms:59000,track:'source',playing:false,segment:0},
]}
it('bufferAnchorPausesBackingAndDoesNotExtrapolate',()=>{
 expect(songTimeAt(.5,metadata)).toEqual({seconds:56.5,playing:true,track:'accompaniment'})
 expect(songTimeAt(2,metadata)).toEqual({seconds:57,playing:false,track:'source'})
 expect(songTimeAt(-1,metadata)).toBeNull()
 expect(songTimeAt(6,metadata)).toBeNull()
})
it('doesNotInterpolateAcrossASeekSegment',()=>{
 const jumped={...metadata,anchors:[metadata.anchors[0],{...metadata.anchors[1],song_ms:1000,segment:1}]}
 expect(songTimeAt(.5,jumped)?.seconds).toBe(56.5)
 expect(songTimeAt(1,jumped)?.seconds).toBe(1)
})
