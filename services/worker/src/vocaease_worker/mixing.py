import hashlib
import json
import os
import subprocess
from dataclasses import dataclass
from pathlib import Path


class MixingError(Exception):
    failure_code = "MIX_FAILED"


class MixingOutputInvalidError(MixingError):
    failure_code = "OUTPUT_INVALID"


@dataclass(frozen=True)
class MixOutput:
    storage_key: str
    content_type: str
    size_bytes: int
    sha256: str


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def probe_audio(path: Path) -> tuple[int, int, int]:
    process = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "a:0",
            "-show_entries",
            "stream=sample_rate,channels:format=duration",
            "-of",
            "json",
            str(path),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if process.returncode != 0:
        raise MixingOutputInvalidError("无法读取混音输出")
    try:
        payload = json.loads(process.stdout)
        stream = payload["streams"][0]
        sample_rate = int(stream["sample_rate"])
        channels = int(stream["channels"])
        duration_ms = round(float(payload["format"]["duration"]) * 1000)
        return sample_rate, channels, duration_ms
    except (IndexError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise MixingOutputInvalidError("混音输出参数不完整") from error


class FfmpegPlaybackMixer:
    algorithm_version = "ffmpeg-amix-v1"

    def mix(
        self,
        raw_voice: Path,
        backing: Path,
        accompaniment_start_frame: int,
        target: Path,
    ) -> MixOutput:
        if accompaniment_start_frame < 0:
            raise MixingOutputInvalidError("伴奏启动帧不能为负数")
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(".tmp.m4a")
        temporary.unlink(missing_ok=True)
        filter_graph = (
            "[0:a]aresample=48000,"
            "aformat=sample_fmts=fltp:channel_layouts=stereo[voice];"
            "[1:a]aresample=48000,"
            "aformat=sample_fmts=fltp:channel_layouts=stereo,"
            f"adelay=delays={accompaniment_start_frame}S:all=1,"
            "volume=0.65[backing];"
            "[voice][backing]amix=inputs=2:duration=longest:"
            "dropout_transition=0:normalize=0,"
            "alimiter=limit=0.95[out]"
        )
        process = subprocess.run(
            [
                "ffmpeg",
                "-y",
                "-v",
                "error",
                "-i",
                str(raw_voice),
                "-i",
                str(backing),
                "-filter_complex",
                filter_graph,
                "-map",
                "[out]",
                "-ar",
                "48000",
                "-ac",
                "2",
                "-c:a",
                "aac",
                "-b:a",
                "192k",
                str(temporary),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        if process.returncode != 0:
            temporary.unlink(missing_ok=True)
            raise MixingError("FFmpeg 混音失败")
        sample_rate, channels, duration_ms = probe_audio(temporary)
        if sample_rate != 48000 or channels != 2 or duration_ms <= 0:
            temporary.unlink(missing_ok=True)
            raise MixingOutputInvalidError("混音输出规格不正确")
        os.replace(temporary, target)
        return MixOutput(
            storage_key="",
            content_type="audio/mp4",
            size_bytes=target.stat().st_size,
            sha256=file_sha256(target),
        )
