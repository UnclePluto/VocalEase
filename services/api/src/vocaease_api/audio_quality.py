import io
import math
import struct
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO


@dataclass(frozen=True)
class QualityThresholds:
    silence_amplitude: int = 32
    clipping_amplitude: int = 32_734
    silent_ratio_warning: float = 0.8
    clipping_ratio_warning: float = 0.01
    low_volume_dbfs: float = -42.0
    window_ms: int = 500


@dataclass(frozen=True)
class QualityMarker:
    kind: str
    start_ms: int
    end_ms: int
    value: float


@dataclass(frozen=True)
class AudioQualityReport:
    readable: bool
    sample_rate: int
    channels: int
    bit_depth: int
    duration_ms: int
    rms_dbfs: float
    silent_sample_ratio: float
    clipped_sample_ratio: float
    status: str
    file_warnings: list[str]
    markers: list[QualityMarker]
    algorithm_version: str = "wav-qc-v1"


@dataclass
class SampleStats:
    count: int = 0
    silent: int = 0
    clipped: int = 0
    sum_squares: int = 0

    def add(self, sample: int, thresholds: QualityThresholds) -> None:
        absolute = abs(sample)
        self.count += 1
        self.silent += absolute <= thresholds.silence_amplitude
        self.clipped += absolute >= thresholds.clipping_amplitude
        self.sum_squares += sample * sample

    def merge(self, other: "SampleStats") -> None:
        self.count += other.count
        self.silent += other.silent
        self.clipped += other.clipped
        self.sum_squares += other.sum_squares

    @property
    def silent_ratio(self) -> float:
        return self.silent / self.count if self.count else 0.0

    @property
    def clipped_ratio(self) -> float:
        return self.clipped / self.count if self.count else 0.0

    @property
    def rms_dbfs(self) -> float:
        if not self.count or not self.sum_squares:
            return -120.0
        mean_square = self.sum_squares / self.count
        return 20 * math.log10(math.sqrt(mean_square) / 32_768)


def warning_report(message: str) -> AudioQualityReport:
    return AudioQualityReport(
        readable=False,
        sample_rate=0,
        channels=0,
        bit_depth=0,
        duration_ms=0,
        rms_dbfs=-120.0,
        silent_sample_ratio=1.0,
        clipped_sample_ratio=0.0,
        status="warning",
        file_warnings=[message],
        markers=[],
    )


def stats_for_pcm16(
    raw_frames: bytes,
    channels: int,
    thresholds: QualityThresholds,
) -> SampleStats:
    stats = SampleStats()
    for value_index, (sample,) in enumerate(struct.iter_unpack("<h", raw_frames)):
        if value_index % channels == 0:
            stats.add(sample, thresholds)
    return stats


def source_for_wave(content: bytes | Path | BinaryIO) -> io.BytesIO | str | BinaryIO:
    if isinstance(content, bytes):
        return io.BytesIO(content)
    if isinstance(content, Path):
        return str(content)
    return content


def analyze_pcm_wav(
    content: bytes | Path | BinaryIO,
    thresholds: QualityThresholds | None = None,
) -> AudioQualityReport:
    thresholds = thresholds or QualityThresholds()
    try:
        with wave.open(source_for_wave(content), "rb") as audio:
            sample_rate = audio.getframerate()
            channels = audio.getnchannels()
            sample_width = audio.getsampwidth()
            frame_count = audio.getnframes()
            file_warnings: list[str] = []
            if sample_rate != 48_000:
                file_warnings.append("采样率不是 48000 Hz")
            if channels != 1:
                file_warnings.append("声道数不是单声道")
            if sample_width != 2:
                file_warnings.append("位深不是 16-bit PCM")
                return AudioQualityReport(
                    readable=True,
                    sample_rate=sample_rate,
                    channels=channels,
                    bit_depth=sample_width * 8,
                    duration_ms=round(frame_count * 1000 / sample_rate),
                    rms_dbfs=-120.0,
                    silent_sample_ratio=0.0,
                    clipped_sample_ratio=0.0,
                    status="warning",
                    file_warnings=file_warnings,
                    markers=[],
                )

            frames_per_window = max(1, round(sample_rate * thresholds.window_ms / 1000))
            global_stats = SampleStats()
            markers: list[QualityMarker] = []
            frame_offset = 0
            while frame_offset < frame_count:
                requested_frames = min(frames_per_window, frame_count - frame_offset)
                raw_frames = audio.readframes(requested_frames)
                if not raw_frames:
                    break
                window_frame_count = len(raw_frames) // (sample_width * channels)
                window = stats_for_pcm16(raw_frames, channels, thresholds)
                global_stats.merge(window)
                start_ms = round(frame_offset * 1000 / sample_rate)
                end_ms = round((frame_offset + window_frame_count) * 1000 / sample_rate)
                if window.silent_ratio >= 0.95:
                    markers.append(
                        QualityMarker("silence", start_ms, end_ms, window.silent_ratio)
                    )
                elif window.rms_dbfs < thresholds.low_volume_dbfs:
                    markers.append(
                        QualityMarker("low_volume", start_ms, end_ms, window.rms_dbfs)
                    )
                if window.clipped_ratio >= thresholds.clipping_ratio_warning:
                    markers.append(
                        QualityMarker("clipping", start_ms, end_ms, window.clipped_ratio)
                    )
                frame_offset += window_frame_count
    except (EOFError, OSError, wave.Error):
        return warning_report("无法读取 WAV 文件")

    if global_stats.silent_ratio >= thresholds.silent_ratio_warning:
        file_warnings.append("录音大部分为静音")
    if global_stats.clipped_ratio >= thresholds.clipping_ratio_warning:
        file_warnings.append("录音存在明显削波")
    if global_stats.rms_dbfs < thresholds.low_volume_dbfs:
        file_warnings.append("录音整体音量过低")
    return AudioQualityReport(
        readable=True,
        sample_rate=sample_rate,
        channels=channels,
        bit_depth=sample_width * 8,
        duration_ms=round(frame_count * 1000 / sample_rate),
        rms_dbfs=global_stats.rms_dbfs,
        silent_sample_ratio=global_stats.silent_ratio,
        clipped_sample_ratio=global_stats.clipped_ratio,
        status="warning" if file_warnings else "ok",
        file_warnings=file_warnings,
        markers=markers,
    )
