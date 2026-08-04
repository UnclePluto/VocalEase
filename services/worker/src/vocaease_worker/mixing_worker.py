from dataclasses import replace
from pathlib import Path
from typing import Protocol

from vocaease_worker.callback import StaleTaskError
from vocaease_worker.mixing import FfmpegPlaybackMixer, MixingError, MixOutput, file_sha256
from vocaease_worker.mixing_queue import PlaybackMixQueue, PlaybackMixTask


class MixCallbackClient(Protocol):
    def started(self, job_id: object, attempt: int) -> None: ...

    def completed(self, job_id: object, attempt: int, output: MixOutput) -> None: ...

    def failed(self, job_id: object, attempt: int, failure_code: str) -> None: ...


class PlaybackMixWorker:
    def __init__(
        self,
        queue: PlaybackMixQueue,
        callback: MixCallbackClient,
        mixer: FfmpegPlaybackMixer,
        media_directory: Path,
    ) -> None:
        self.queue = queue
        self.callback = callback
        self.mixer = mixer
        self.media_directory = media_directory.resolve()

    def media_path(self, storage_key: str) -> Path:
        path = (self.media_directory / storage_key).resolve()
        if self.media_directory not in path.parents:
            raise MixingError("媒体存储键不合法")
        return path

    def process(self, task: PlaybackMixTask) -> None:
        try:
            self.callback.started(task.job_id, task.attempt)
        except StaleTaskError:
            return
        if task.algorithm_version != self.mixer.algorithm_version:
            self.callback.failed(task.job_id, task.attempt, "MIX_FAILED")
            return
        try:
            raw_voice = self.media_path(task.raw_voice_storage_key)
            backing = self.media_path(task.backing_storage_key)
        except MixingError:
            self.callback.failed(task.job_id, task.attempt, "MIX_FAILED")
            return
        if not raw_voice.is_file():
            self.callback.failed(task.job_id, task.attempt, "SOURCE_NOT_FOUND")
            return
        if not backing.is_file():
            self.callback.failed(task.job_id, task.attempt, "BACKING_NOT_FOUND")
            return
        raw_hash_before = file_sha256(raw_voice)
        output_key = f"playback-mixes/{task.job_id}/mix.m4a"
        try:
            output = self.mixer.mix(
                raw_voice,
                backing,
                task.accompaniment_start_frame,
                self.media_path(output_key),
            )
            if file_sha256(raw_voice) != raw_hash_before:
                raise MixingError("原始人声文件发生变化")
            self.callback.completed(
                task.job_id,
                task.attempt,
                replace(output, storage_key=output_key),
            )
        except MixingError as error:
            self.callback.failed(task.job_id, task.attempt, error.failure_code)
        except Exception:
            self.callback.failed(task.job_id, task.attempt, "MIX_FAILED")

    def run_once(self, timeout_seconds: int) -> bool:
        task = self.queue.take(timeout_seconds)
        if task is None:
            return False
        self.process(task)
        return True
