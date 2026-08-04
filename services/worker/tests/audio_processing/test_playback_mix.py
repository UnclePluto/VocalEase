import math
import struct
import subprocess
import wave
from array import array
from dataclasses import dataclass, field
from pathlib import Path
from uuid import UUID, uuid4

import pytest
from vocaease_worker.mixing import (
    FfmpegPlaybackMixer,
    MixOutput,
    file_sha256,
    probe_audio,
)
from vocaease_worker.mixing_queue import PlaybackMixQueue, PlaybackMixTask
from vocaease_worker.mixing_worker import PlaybackMixWorker

REDIS_URL = "redis://127.0.0.1:63799/15"


def write_tone(path: Path, seconds: float, frequency: float | None) -> None:
    sample_rate = 48_000
    frame_count = round(sample_rate * seconds)
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(sample_rate)
        frames = bytearray()
        for index in range(frame_count):
            value = (
                0
                if frequency is None
                else round(10_000 * math.sin(2 * math.pi * frequency * index / sample_rate))
            )
            frames.extend(struct.pack("<h", value))
        audio.writeframes(frames)


def decode_mono(path: Path) -> array:
    process = subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-i",
            str(path),
            "-ar",
            "48000",
            "-ac",
            "1",
            "-f",
            "f32le",
            "-",
        ],
        capture_output=True,
        check=False,
    )
    assert process.returncode == 0
    samples = array("f")
    samples.frombytes(process.stdout)
    return samples


def rms(samples: array, start_seconds: float, end_seconds: float) -> float:
    start = round(start_seconds * 48_000)
    end = round(end_seconds * 48_000)
    window = samples[start:end]
    return math.sqrt(sum(value * value for value in window) / len(window))


@dataclass
class RecordedCallback:
    events: list[tuple[str, UUID, int, object | None]] = field(default_factory=list)

    def started(self, job_id: UUID, attempt: int) -> None:
        self.events.append(("started", job_id, attempt, None))

    def completed(self, job_id: UUID, attempt: int, output: MixOutput) -> None:
        self.events.append(("completed", job_id, attempt, output))

    def failed(self, job_id: UUID, attempt: int, failure_code: str) -> None:
        self.events.append(("failed", job_id, attempt, failure_code))


def make_task(media: Path, offset_frames: int = 36_000) -> PlaybackMixTask:
    write_tone(media / "raw/session.wav", 2.0, None)
    write_tone(media / "backing/song.wav", 1.0, 440.0)
    return PlaybackMixTask(
        job_id=uuid4(),
        attempt=1,
        raw_voice_storage_key="raw/session.wav",
        backing_storage_key="backing/song.wav",
        accompaniment_start_frame=offset_frames,
        algorithm_version="ffmpeg-amix-v1",
    )


@pytest.mark.audio_processing
def test_mix_uses_48k_frame_offset_and_preserves_raw_voice(tmp_path):
    media = tmp_path / "media"
    task = make_task(media)
    raw_path = media / task.raw_voice_storage_key
    raw_hash = file_sha256(raw_path)
    callback = RecordedCallback()
    queue = PlaybackMixQueue(REDIS_URL)
    queue.clear()
    queue.enqueue(task)
    worker = PlaybackMixWorker(queue, callback, FfmpegPlaybackMixer(), media)

    assert worker.run_once(timeout_seconds=1) is True

    assert [event[0] for event in callback.events] == ["started", "completed"]
    output = callback.events[-1][3]
    output_path = media / output.storage_key
    sample_rate, channels, duration_ms = probe_audio(output_path)
    samples = decode_mono(output_path)
    assert sample_rate == 48_000
    assert channels == 2
    assert 1_950 <= duration_ms <= 2_100
    assert rms(samples, 0.10, 0.60) < 0.001
    assert rms(samples, 0.85, 1.20) > 0.03
    assert file_sha256(raw_path) == raw_hash


@pytest.mark.audio_processing
def test_repeated_mix_processing_reuses_one_bounded_output_path(tmp_path):
    media = tmp_path / "media"
    task = make_task(media)
    callback = RecordedCallback()
    worker = PlaybackMixWorker(
        PlaybackMixQueue(REDIS_URL),
        callback,
        FfmpegPlaybackMixer(),
        media,
    )

    worker.process(task)
    first_output = callback.events[-1][3]
    worker.process(task)
    second_output = callback.events[-1][3]

    assert first_output.storage_key == second_output.storage_key
    assert list((media / f"playback-mixes/{task.job_id}").glob("*.m4a")) == [
        media / first_output.storage_key
    ]


@pytest.mark.audio_processing
def test_mix_worker_reports_missing_sources_and_algorithm_mismatch(tmp_path):
    media = tmp_path / "media"
    callback = RecordedCallback()
    worker = PlaybackMixWorker(
        PlaybackMixQueue(REDIS_URL),
        callback,
        FfmpegPlaybackMixer(),
        media,
    )
    missing_source = PlaybackMixTask(
        job_id=uuid4(),
        attempt=1,
        raw_voice_storage_key="raw/missing.wav",
        backing_storage_key="backing/missing.wav",
        accompaniment_start_frame=0,
        algorithm_version="ffmpeg-amix-v1",
    )

    worker.process(missing_source)
    assert callback.events[-1][3] == "SOURCE_NOT_FOUND"

    task = make_task(media)
    mismatched = PlaybackMixTask(
        job_id=uuid4(),
        attempt=1,
        raw_voice_storage_key=task.raw_voice_storage_key,
        backing_storage_key=task.backing_storage_key,
        accompaniment_start_frame=0,
        algorithm_version="unknown-version",
    )
    worker.process(mismatched)
    assert callback.events[-1][3] == "MIX_FAILED"


def test_playback_mix_startup_recovers_unacknowledged_task(tmp_path):
    media = tmp_path / "media"
    queue = PlaybackMixQueue(REDIS_URL)
    queue.clear()
    task = make_task(media)
    queue.enqueue(task)
    unacknowledged = queue.take(timeout_seconds=1)
    assert unacknowledged is not None

    restarted_queue = PlaybackMixQueue(REDIS_URL)
    assert restarted_queue.recover_processing() == 1
    recovered = restarted_queue.take(timeout_seconds=1)

    assert recovered is not None
    assert recovered.job_id == task.job_id
    restarted_queue.ack(recovered)
