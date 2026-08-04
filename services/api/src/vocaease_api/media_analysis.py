import math
import struct
import subprocess
import wave
from pathlib import Path

from fastapi import HTTPException, status


def waveform_envelope(path: Path, max_points: int = 1_200) -> list[dict[str, float | int]]:
    try:
        with wave.open(str(path), "rb") as audio:
            channels = audio.getnchannels()
            sample_width = audio.getsampwidth()
            sample_rate = audio.getframerate()
            frame_count = audio.getnframes()
            if sample_width != 2:
                raise ValueError("unsupported sample width")
            frames_per_point = max(1, math.ceil(frame_count / max_points))
            points: list[dict[str, float | int]] = []
            frame_offset = 0
            while frame_offset < frame_count:
                raw = audio.readframes(min(frames_per_point, frame_count - frame_offset))
                values = struct.unpack(f"<{len(raw) // 2}h", raw)
                samples = values[::channels]
                if not samples:
                    break
                points.append(
                    {
                        "start_ms": round(frame_offset * 1000 / sample_rate),
                        "min": min(samples) / 32_768,
                        "max": max(samples) / 32_768,
                        "rms": math.sqrt(sum(sample * sample for sample in samples) / len(samples))
                        / 32_768,
                    }
                )
                frame_offset += len(samples)
            return points
    except (EOFError, ValueError, wave.Error) as error:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "无法从原始人声生成波形",
        ) from error


def generate_spectrogram(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    process = subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-v",
            "error",
            "-i",
            str(source),
            "-lavfi",
            "showspectrumpic=s=1200x500:legend=disabled:color=intensity:scale=log",
            "-frames:v",
            "1",
            str(target),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if process.returncode != 0 or not target.is_file():
        target.unlink(missing_ok=True)
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "无法从原始人声生成频谱",
        )
