"""可信单声部的有界 FFmpeg 解码与 YIN；绝不从原曲猜主旋律。"""
import array
import math
import os
import subprocess
import tempfile
from collections.abc import Iterable

SAMPLE_RATE = 8000
MAX_PCM_BYTES = 64 * 1024 * 1024


def decode_vocal(content: bytes, *, duration_ms: int) -> array.array:
    if duration_ms * SAMPLE_RATE * 4 // 1000 > MAX_PCM_BYTES:
        raise ValueError('音轨解码超过安全容量')
    with tempfile.TemporaryDirectory(prefix='vocaease-pitch-') as root:
        source = os.path.join(root, 'input'); target = os.path.join(root, 'pcm')
        with open(source, 'wb') as file:
            file.write(content)
        subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-y', '-i', source, '-vn', '-ac', '1', '-ar', str(SAMPLE_RATE), '-t', str(duration_ms / 1000), '-f', 'f32le', '-fs', str(MAX_PCM_BYTES + 4), target], timeout=120, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        if os.path.getsize(target) > MAX_PCM_BYTES:
            raise ValueError('解码超过安全容量')
        pcm = array.array('f')
        with open(target, 'rb') as file:
            pcm.frombytes(file.read())
        import sys
        if sys.byteorder != 'little':
            pcm.byteswap()
        return pcm


def extract_pitch_notes(pcm: Iterable[float], *, sample_rate: int, duration_ms: int) -> list[dict]:
    frame_size, hop = round(sample_rate * .046), round(sample_rate * .02)
    values = iter(pcm); frame = []
    notes = []; position = 0
    while position * 1000 < duration_ms * sample_rate:
        while len(frame) < frame_size:
            try:
                frame.append(next(values))
            except StopIteration:
                return notes
        rms = math.sqrt(sum(x*x for x in frame)/len(frame))
        note = None
        if math.isfinite(rms) and rms > .008:
            maximum = min(sample_rate // 65, frame_size // 2)
            length = frame_size - maximum
            cumulative = 0.; normalized = [1.]
            for lag in range(1, maximum + 1):
                difference = sum((frame[i]-frame[i+lag])**2 for i in range(0, length, 2))
                cumulative += difference
                normalized.append(difference * lag / cumulative if cumulative > 0 else 1.)
            lag = max(2, sample_rate // 1000)
            while lag < maximum:
                if normalized[lag] < .15:
                    while lag + 1 <= maximum and normalized[lag+1] < normalized[lag]:
                        lag += 1
                    left, center, right = normalized[lag-1], normalized[lag], normalized[min(lag+1, maximum)]
                    denominator = left - 2*center + right
                    refined = lag + (.5*(left-right)/denominator if abs(denominator) > 1e-12 else 0)
                    frequency = sample_rate/refined
                    midi = 69 + 12*math.log2(frequency/440)
                    if 65 <= frequency <= 1000 and 0 <= midi <= 127:
                        note = (round(midi, 2), round(1-center, 3))
                    break
                lag += 1
        start = round(position*1000/sample_rate); end = min(duration_ms, round((position+hop)*1000/sample_rate))
        if note and end > start:
            if notes and notes[-1]['end_ms'] == start and abs(notes[-1]['midi_note']-note[0]) < .5:
                notes[-1]['end_ms'] = end
                notes[-1]['confidence'] = min(notes[-1]['confidence'], note[1])
            else:
                notes.append(dict(start_ms=start, end_ms=end, midi_note=note[0], confidence=note[1]))
                if len(notes) > 100000:
                    raise ValueError('音高区段超过上限')
        frame = frame[hop:]; position += hop
    return notes
