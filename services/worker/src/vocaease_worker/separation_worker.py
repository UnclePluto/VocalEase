import tempfile
from dataclasses import replace
from pathlib import Path
from typing import Protocol

from vocaease_worker.callback import CallbackDeliveryError, StaleTaskError
from vocaease_worker.separation import (
    OutputMissingError,
    SeparationError,
    StoredOutput,
    TwoTrackSeparator,
    normalize_stem,
)
from vocaease_worker.separation_queue import SeparationQueue, SeparationTask


class CallbackClient(Protocol):
    def started(self, job_id: object, attempt: int) -> None: ...

    def completed(
        self,
        job_id: object,
        attempt: int,
        vocals: StoredOutput,
        no_vocals: StoredOutput,
    ) -> None: ...

    def failed(self, job_id: object, attempt: int, failure_code: str) -> None: ...


class SeparationWorker:
    def __init__(
        self,
        queue: SeparationQueue,
        callback: CallbackClient,
        separator: TwoTrackSeparator,
        media_directory: Path,
    ) -> None:
        self.queue = queue
        self.callback = callback
        self.separator = separator
        self.media_directory = media_directory.resolve()

    def media_path(self, storage_key: str) -> Path:
        path = (self.media_directory / storage_key).resolve()
        if self.media_directory not in path.parents:
            raise OutputMissingError("源文件存储键不合法")
        return path

    def stored_output(
        self, task: SeparationTask, stem_name: str, temporary_output: Path
    ) -> StoredOutput:
        storage_key = f"separations/{task.job_id}/attempt-{task.attempt}/{stem_name}.m4a"
        output = normalize_stem(temporary_output, self.media_path(storage_key))
        return replace(output, storage_key=storage_key)

    def process(self, task: SeparationTask) -> None:
        source = self.media_path(task.source_storage_key)
        try:
            self.callback.started(task.job_id, task.attempt)
        except StaleTaskError:
            return
        configured_model = getattr(self.separator, "model_name", task.model_name)
        if configured_model != task.model_name:
            self.callback.failed(task.job_id, task.attempt, "MODEL_UNAVAILABLE")
            return
        if not source.is_file():
            self.callback.failed(task.job_id, task.attempt, "SOURCE_NOT_FOUND")
            return
        try:
            with tempfile.TemporaryDirectory(prefix="vocaease-separation-") as directory:
                raw_outputs = self.separator.separate(source, Path(directory))
                vocals = self.stored_output(task, "vocals", raw_outputs.vocals)
                no_vocals = self.stored_output(task, "no-vocals", raw_outputs.no_vocals)
            self.callback.completed(task.job_id, task.attempt, vocals, no_vocals)
        except StaleTaskError:
            return
        except CallbackDeliveryError:
            raise
        except SeparationError as error:
            self.callback.failed(task.job_id, task.attempt, error.failure_code)
        except Exception:
            self.callback.failed(task.job_id, task.attempt, "SEPARATION_FAILED")

    def run_once(self, timeout_seconds: int) -> bool:
        task = self.queue.take(timeout_seconds)
        if task is None:
            return False
        try:
            self.process(task)
        except Exception:
            self.queue.release(task)
            return True
        self.queue.ack(task)
        return True
