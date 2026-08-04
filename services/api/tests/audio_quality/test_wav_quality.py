import io
import math
import struct
import wave
from pathlib import Path

import pytest
from vocaease_api.audio_quality import QualityThresholds, analyze_pcm_wav


def wav_with_samples(
    samples: list[int],
    *,
    sample_rate: int = 48_000,
    channels: int = 1,
    sample_width: int = 2,
) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(channels)
        audio.setsampwidth(sample_width)
        audio.setframerate(sample_rate)
        audio.writeframes(b"".join(struct.pack("<h", sample) for sample in samples))
    return output.getvalue()


def sine_samples(seconds: float, amplitude: int = 8_000) -> list[int]:
    count = round(48_000 * seconds)
    return [
        round(amplitude * math.sin(2 * math.pi * 440 * index / 48_000)) for index in range(count)
    ]


def test_quality_report_uses_technical_metrics_without_scoring():
    report = analyze_pcm_wav(wav_with_samples(sine_samples(1)))

    assert report.readable is True
    assert report.sample_rate == 48_000
    assert report.channels == 1
    assert report.bit_depth == 16
    assert report.duration_ms == 1_000
    assert -20 < report.rms_dbfs < -5
    assert report.clipped_sample_ratio == 0
    assert report.silent_sample_ratio < 0.01
    assert report.status == "ok"
    assert report.algorithm_version == "wav-qc-v1"
    assert report.markers == []


def test_silence_and_clipping_produce_traceable_markers():
    samples = [0] * 24_000 + [32_767] * 24_000
    thresholds = QualityThresholds(window_ms=250, silent_ratio_warning=0.2)

    report = analyze_pcm_wav(wav_with_samples(samples), thresholds)

    assert report.status == "warning"
    assert report.silent_sample_ratio == pytest.approx(0.5)
    assert report.clipped_sample_ratio == pytest.approx(0.5)
    assert {marker.kind for marker in report.markers} == {"silence", "clipping"}
    assert all(marker.end_ms > marker.start_ms for marker in report.markers)


@pytest.mark.parametrize(
    ("payload", "message"),
    [
        (b"not-a-wave", "无法读取 WAV 文件"),
        (wav_with_samples(sine_samples(0.1), sample_rate=44_100), "采样率不是 48000 Hz"),
        (
            wav_with_samples(sine_samples(0.1) * 2, channels=2),
            "声道数不是单声道",
        ),
    ],
)
def test_invalid_or_nonconforming_wav_is_reported(payload: bytes, message: str):
    report = analyze_pcm_wav(payload)

    assert report.status == "warning"
    assert message in report.file_warnings


class TrackingReader:
    def __init__(self, path: Path) -> None:
        self.file = path.open("rb")
        self.max_read_size = 0

    def read(self, size: int = -1) -> bytes:
        self.max_read_size = max(self.max_read_size, size)
        return self.file.read(size)

    def seek(self, offset: int, whence: int = 0) -> int:
        return self.file.seek(offset, whence)

    def tell(self) -> int:
        return self.file.tell()

    def close(self) -> None:
        self.file.close()


def test_large_wav_is_analyzed_in_bounded_streaming_windows(tmp_path):
    path = tmp_path / "large-recording.wav"
    one_second = b"\0\0" * 48_000
    with wave.open(str(path), "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(48_000)
        for _ in range(120):
            audio.writeframesraw(one_second)

    reader = TrackingReader(path)
    try:
        report = analyze_pcm_wav(reader)
    finally:
        reader.close()

    assert path.stat().st_size > 10_000_000
    assert report.duration_ms == 120_000
    assert report.readable is True
    assert reader.max_read_size <= 48_000
