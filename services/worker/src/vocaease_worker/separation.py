import hashlib
import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol


class SeparationError(Exception):
    failure_code = "SEPARATION_FAILED"


class ModelUnavailableError(SeparationError):
    failure_code = "MODEL_UNAVAILABLE"


class OutputMissingError(SeparationError):
    failure_code = "OUTPUT_MISSING"


class OutputInvalidError(SeparationError):
    failure_code = "OUTPUT_INVALID"


@dataclass(frozen=True)
class StemPaths:
    vocals: Path
    no_vocals: Path


@dataclass(frozen=True)
class StoredOutput:
    storage_key: str
    content_type: str
    size_bytes: int
    sha256: str


class TwoTrackSeparator(Protocol):
    def separate(self, source: Path, output_directory: Path) -> StemPaths: ...


class AudioSeparatorTwoTrack:
    """使用本地 Audio Separator 模型生成 vocals 与 instrumental 两轨。"""

    def __init__(self, model_name: str, model_directory: Path) -> None:
        self.model_name = model_name
        self.model_directory = model_directory

    def separate(self, source: Path, output_directory: Path) -> StemPaths:
        try:
            from audio_separator.separator import Separator
        except ImportError as error:
            raise ModelUnavailableError("audio-separator 未安装") from error
        output_directory.mkdir(parents=True, exist_ok=True)
        try:
            separator = Separator(
                output_dir=str(output_directory),
                output_format="WAV",
                sample_rate=48000,
                model_file_dir=str(self.model_directory),
            )
            separator.load_model(model_filename=self.model_name)
            outputs = separator.separate(
                str(source),
                {
                    "Vocals": "vocals",
                    "Instrumental": "no_vocals",
                },
            )
        except Exception as error:
            raise SeparationError("本地模型执行失败") from error
        resolved = [Path(item) for item in outputs]
        resolved = [
            item if item.is_absolute() else output_directory / item.name for item in resolved
        ]
        vocals = next((item for item in resolved if item.stem == "vocals"), None)
        no_vocals = next((item for item in resolved if item.stem == "no_vocals"), None)
        if vocals is None or no_vocals is None:
            raise OutputMissingError("模型没有返回完整的两轨结果")
        return StemPaths(vocals=vocals, no_vocals=no_vocals)


class DeterministicTwoTrackSeparator:
    """测试替身：复制固定输入，避免依赖模型下载、速度或随机结果。"""

    def separate(self, source: Path, output_directory: Path) -> StemPaths:
        output_directory.mkdir(parents=True, exist_ok=True)
        vocals = output_directory / "vocals.wav"
        no_vocals = output_directory / "no_vocals.wav"
        shutil.copyfile(source, vocals)
        shutil.copyfile(source, no_vocals)
        return StemPaths(vocals=vocals, no_vocals=no_vocals)


def probe_audio(path: Path) -> dict[str, int]:
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
        raise OutputInvalidError("无法读取音频输出")
    try:
        payload = json.loads(process.stdout)
        stream = payload["streams"][0]
        return {
            "duration_ms": round(float(payload["format"]["duration"]) * 1000),
            "sample_rate": int(stream["sample_rate"]),
            "channels": int(stream["channels"]),
        }
    except (KeyError, IndexError, TypeError, ValueError) as error:
        raise OutputInvalidError("音频输出参数不完整") from error


def normalize_stem(source: Path, target: Path) -> StoredOutput:
    if not source.is_file():
        raise OutputMissingError("输出文件不存在")
    target.parent.mkdir(parents=True, exist_ok=True)
    process = subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-v",
            "error",
            "-i",
            str(source),
            "-ar",
            "48000",
            "-ac",
            "2",
            "-c:a",
            "aac",
            "-b:a",
            "192k",
            str(target),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if process.returncode != 0:
        target.unlink(missing_ok=True)
        raise OutputInvalidError("输出音频转码失败")
    metadata = probe_audio(target)
    if (
        metadata["duration_ms"] <= 0
        or metadata["sample_rate"] != 48000
        or metadata["channels"] != 2
    ):
        target.unlink(missing_ok=True)
        raise OutputInvalidError("输出音频规格异常")
    content = target.read_bytes()
    return StoredOutput(
        storage_key="",
        content_type="audio/mp4",
        size_bytes=len(content),
        sha256=hashlib.sha256(content).hexdigest(),
    )
