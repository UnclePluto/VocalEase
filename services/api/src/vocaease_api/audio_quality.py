import io
import math
import struct
import wave
from dataclasses import dataclass


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


def ratio_matching(samples: list[int], predicate) -> float:
    if not samples:
        return 0.0
    return sum(1 for sample in samples if predicate(sample)) / len(samples)


def dbfs(samples: list[int]) -> float:
    if not samples:
        return -120.0
    mean_square = sum(sample * sample for sample in samples) / len(samples)
    if mean_square == 0:
        return -120.0
    return 20 * math.log10(math.sqrt(mean_square) / 32_768)


def quality_markers(
    samples: list[int], sample_rate: int, thresholds: QualityThresholds
) -> list[QualityMarker]:
    window_samples = max(1, round(sample_rate * thresholds.window_ms / 1000))
    markers: list[QualityMarker] = []
    for offset in range(0, len(samples), window_samples):
        window = samples[offset : offset + window_samples]
        start_ms = round(offset * 1000 / sample_rate)
        end_ms = round(min(offset + len(window), len(samples)) * 1000 / sample_rate)
        silent_ratio = ratio_matching(
            window, lambda sample: abs(sample) <= thresholds.silence_amplitude
        )
        clipped_ratio = ratio_matching(
            window, lambda sample: abs(sample) >= thresholds.clipping_amplitude
        )
        if silent_ratio >= 0.95:
            markers.append(QualityMarker("silence", start_ms, end_ms, silent_ratio))
        elif dbfs(window) < thresholds.low_volume_dbfs:
            markers.append(QualityMarker("low_volume", start_ms, end_ms, dbfs(window)))
        if clipped_ratio >= thresholds.clipping_ratio_warning:
            markers.append(QualityMarker("clipping", start_ms, end_ms, clipped_ratio))
    return markers


def analyze_pcm_wav(
    content: bytes, thresholds: QualityThresholds | None = None
) -> AudioQualityReport:
    thresholds = thresholds or QualityThresholds()
    try:
        with wave.open(io.BytesIO(content), "rb") as audio:
            sample_rate = audio.getframerate()
            channels = audio.getnchannels()
            sample_width = audio.getsampwidth()
            frame_count = audio.getnframes()
            frames = audio.readframes(frame_count)
    except (EOFError, wave.Error):
        return warning_report("无法读取 WAV 文件")

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

    values = list(struct.unpack(f"<{len(frames) // 2}h", frames))
    mono_samples = values[::channels] if channels > 1 else values
    silent_ratio = ratio_matching(
        mono_samples, lambda sample: abs(sample) <= thresholds.silence_amplitude
    )
    clipped_ratio = ratio_matching(
        mono_samples, lambda sample: abs(sample) >= thresholds.clipping_amplitude
    )
    rms_dbfs = dbfs(mono_samples)
    if silent_ratio >= thresholds.silent_ratio_warning:
        file_warnings.append("录音大部分为静音")
    if clipped_ratio >= thresholds.clipping_ratio_warning:
        file_warnings.append("录音存在明显削波")
    if rms_dbfs < thresholds.low_volume_dbfs:
        file_warnings.append("录音整体音量过低")

    return AudioQualityReport(
        readable=True,
        sample_rate=sample_rate,
        channels=channels,
        bit_depth=sample_width * 8,
        duration_ms=round(frame_count * 1000 / sample_rate),
        rms_dbfs=rms_dbfs,
        silent_sample_ratio=silent_ratio,
        clipped_sample_ratio=clipped_ratio,
        status="warning" if file_warnings else "ok",
        file_warnings=file_warnings,
        markers=quality_markers(mono_samples, sample_rate, thresholds),
    )
