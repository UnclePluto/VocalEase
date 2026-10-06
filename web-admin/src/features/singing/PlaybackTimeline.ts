import type { PlaybackMetadata, SongPlaybackMode } from './types'

/** 不将歌曲轴外推到尚未记录的有效区间。跨 seek 的 segment 使用旧段自身的速度。 */
export function songTimeAt(recordingSeconds: number, metadata: PlaybackMetadata): {seconds:number;playing:boolean;track:SongPlaybackMode}|null {
  const anchors=metadata.anchors, ms=recordingSeconds*1000
  if (!Number.isFinite(ms) || !anchors.length || ms<anchors[0].recording_ms || ms>anchors[anchors.length-1].recording_ms) return null
  let lo=0,hi=anchors.length-1
  while(lo<hi){const mid=Math.ceil((lo+hi)/2);if(anchors[mid].recording_ms<=ms)lo=mid;else hi=mid-1}
  const anchor=anchors[lo],next=anchors[lo+1]
  let song=anchor.song_ms
  if(anchor.playing && next){
    const rate=next.segment===anchor.segment ? Math.max(0,(next.song_ms-anchor.song_ms)/(next.recording_ms-anchor.recording_ms)) : 1
    song+=(ms-anchor.recording_ms)*rate
  }
  return {seconds:song/1000,playing:anchor.playing,track:anchor.track}
}
