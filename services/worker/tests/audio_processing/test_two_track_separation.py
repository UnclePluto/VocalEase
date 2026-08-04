import io
import sys
import types
import wave
from dataclasses import dataclass, field
from pathlib import Path
from uuid import UUID, uuid4

import pytest
from vocaease_worker.separation import (
    AudioSeparatorTwoTrack,
    DeterministicTwoTrackSeparator,
    SeparationError,
    StemPaths,
    StoredOutput,
)
from vocaease_worker.separation_queue import SeparationQueue, SeparationTask
from vocaease_worker.separation_worker import SeparationWorker

REDIS_URL = "redis://127.0.0.1:63799/15"


def write_synthetic_sample(path: Path, seconds: int = 1) -> None:
    """生成无版权的固定静音样本，供确定性音频处理测试使用。"""
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(44_100)
        audio.writeframes(b"\0\0" * 44_100 * seconds)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(output.getvalue())


@dataclass
class RecordedCallback:
    events: list[tuple[str, UUID, int, object | None]] = field(default_factory=list)

    def started(self, job_id: UUID, attempt: int) -> None:
        self.events.append(("started", job_id, attempt, None))

    def completed(
        self,
        job_id: UUID,
        attempt: int,
        vocals: StoredOutput,
        no_vocals: StoredOutput,
    ) -> None:
        self.events.append(("completed", job_id, attempt, (vocals, no_vocals)))

    def failed(self, job_id: UUID, attempt: int, failure_code: str) -> None:
        self.events.append(("failed", job_id, attempt, failure_code))


class FailingSeparator:
    def separate(self, source: Path, output_directory: Path) -> StemPaths:
        raise SeparationError("固定失败")


class MissingOutputSeparator:
    def separate(self, source: Path, output_directory: Path) -> StemPaths:
        return StemPaths(
            vocals=output_directory / "missing-vocals.wav",
            no_vocals=output_directory / "missing-no-vocals.wav",
        )


class InvalidOutputSeparator:
    def separate(self, source: Path, output_directory: Path) -> StemPaths:
        output_directory.mkdir(parents=True, exist_ok=True)
        vocals = output_directory / "vocals.wav"
        no_vocals = output_directory / "no-vocals.wav"
        vocals.write_text("not audio")
        no_vocals.write_text("not audio")
        return StemPaths(vocals=vocals, no_vocals=no_vocals)


class NamedSeparator(DeterministicTwoTrackSeparator):
    model_name = "configured-model"


def make_task(media: Path, attempt: int = 1) -> SeparationTask:
    source_key = "original-music/synthetic.wav"
    write_synthetic_sample(media / source_key)
    return SeparationTask(
        job_id=uuid4(),
        attempt=attempt,
        source_storage_key=source_key,
        model_name="deterministic-test",
    )


@pytest.mark.audio_processing
def test_worker_generates_valid_versioned_two_track_outputs(tmp_path):
    media = tmp_path / "media"
    task = make_task(media)
    queue = SeparationQueue(REDIS_URL)
    queue.clear()
    queue.enqueue(task)
    callback = RecordedCallback()
    worker = SeparationWorker(
        queue,
        callback,
        DeterministicTwoTrackSeparator(),
        media,
    )

    assert worker.run_once(timeout_seconds=1) is True

    assert [event[0] for event in callback.events] == ["started", "completed"]
    vocals, no_vocals = callback.events[-1][3]
    assert vocals.storage_key.endswith("/attempt-1/vocals.m4a")
    assert no_vocals.storage_key.endswith("/attempt-1/no-vocals.m4a")
    assert vocals.size_bytes > 0
    assert no_vocals.size_bytes > 0
    assert len(vocals.sha256) == 64
    assert (media / vocals.storage_key).is_file()
    assert (media / no_vocals.storage_key).is_file()


@pytest.mark.audio_processing
def test_retry_uses_new_attempt_path_without_overwriting_previous_result(tmp_path):
    media = tmp_path / "media"
    first = make_task(media, attempt=1)
    second = SeparationTask(
        job_id=first.job_id,
        attempt=2,
        source_storage_key=first.source_storage_key,
        model_name=first.model_name,
    )
    callback = RecordedCallback()
    worker = SeparationWorker(
        SeparationQueue(REDIS_URL),
        callback,
        DeterministicTwoTrackSeparator(),
        media,
    )

    worker.process(first)
    first_vocals, _ = callback.events[-1][3]
    first_content = (media / first_vocals.storage_key).read_bytes()
    worker.process(second)
    second_vocals, _ = callback.events[-1][3]

    assert first_vocals.storage_key != second_vocals.storage_key
    assert (media / first_vocals.storage_key).read_bytes() == first_content
    assert (media / second_vocals.storage_key).is_file()


@pytest.mark.audio_processing
@pytest.mark.parametrize(
    ("separator", "expected_code"),
    [
        (FailingSeparator(), "SEPARATION_FAILED"),
        (MissingOutputSeparator(), "OUTPUT_MISSING"),
        (InvalidOutputSeparator(), "OUTPUT_INVALID"),
    ],
)
def test_worker_reports_safe_failure_codes(tmp_path, separator, expected_code):
    media = tmp_path / "media"
    task = make_task(media)
    callback = RecordedCallback()
    worker = SeparationWorker(SeparationQueue(REDIS_URL), callback, separator, media)

    worker.process(task)

    assert callback.events[-1] == ("failed", task.job_id, 1, expected_code)


@pytest.mark.audio_processing
def test_worker_reports_missing_source(tmp_path):
    task = SeparationTask(
        job_id=uuid4(),
        attempt=1,
        source_storage_key="original-music/not-found.wav",
        model_name="deterministic-test",
    )
    callback = RecordedCallback()
    worker = SeparationWorker(
        SeparationQueue(REDIS_URL),
        callback,
        DeterministicTwoTrackSeparator(),
        tmp_path,
    )

    worker.process(task)

    assert callback.events[-1] == ("failed", task.job_id, 1, "SOURCE_NOT_FOUND")


@pytest.mark.audio_processing
def test_worker_rejects_task_for_a_different_configured_model(tmp_path):
    media = tmp_path / "media"
    task = make_task(media)
    callback = RecordedCallback()
    worker = SeparationWorker(
        SeparationQueue(REDIS_URL),
        callback,
        NamedSeparator(),
        media,
    )

    worker.process(task)

    assert callback.events[-1] == ("failed", task.job_id, 1, "MODEL_UNAVAILABLE")


def test_audio_separator_adapter_requests_named_vocals_and_instrumental(monkeypatch, tmp_path):
    source = tmp_path / "source.wav"
    write_synthetic_sample(source)
    loaded_models: list[str] = []

    class FakeSeparator:
        def __init__(self, **options):
            self.output_directory = Path(options["output_dir"])

        def load_model(self, model_filename: str) -> None:
            loaded_models.append(model_filename)

        def separate(self, source_path: str, output_names: dict[str, str]) -> list[str]:
            assert Path(source_path) == source
            assert output_names == {
                "Vocals": "vocals",
                "Instrumental": "no_vocals",
            }
            vocals = self.output_directory / "vocals.wav"
            no_vocals = self.output_directory / "no_vocals.wav"
            write_synthetic_sample(vocals)
            write_synthetic_sample(no_vocals)
            return [str(vocals), str(no_vocals)]

    package = types.ModuleType("audio_separator")
    separator_module = types.ModuleType("audio_separator.separator")
    separator_module.Separator = FakeSeparator
    monkeypatch.setitem(sys.modules, "audio_separator", package)
    monkeypatch.setitem(sys.modules, "audio_separator.separator", separator_module)
    adapter = AudioSeparatorTwoTrack("approved-model.onnx", tmp_path / "models")

    result = adapter.separate(source, tmp_path / "outputs")

    assert loaded_models == ["approved-model.onnx"]
    assert result.vocals.name == "vocals.wav"
    assert result.no_vocals.name == "no_vocals.wav"
