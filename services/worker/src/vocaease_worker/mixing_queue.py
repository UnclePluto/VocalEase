import json
from dataclasses import dataclass, field
from uuid import UUID

from redis import Redis


@dataclass(frozen=True)
class PlaybackMixTask:
    job_id: UUID
    attempt: int
    raw_voice_storage_key: str
    backing_storage_key: str
    accompaniment_start_frame: int
    algorithm_version: str
    receipt: str = field(default="", repr=False, compare=False)


class PlaybackMixQueue:
    queue_name = "vocaease:playback-mix:pending"
    processing_name = "vocaease:playback-mix:processing"

    def __init__(self, redis_url: str) -> None:
        self._client = Redis.from_url(redis_url, decode_responses=True)

    def clear(self) -> None:
        self._client.delete(self.queue_name, self.processing_name)

    def take(self, timeout_seconds: int) -> PlaybackMixTask | None:
        raw_payload = self._client.brpoplpush(
            self.queue_name,
            self.processing_name,
            timeout=timeout_seconds,
        )
        if raw_payload is None:
            return None
        payload = json.loads(raw_payload)
        return PlaybackMixTask(
            job_id=UUID(payload["job_id"]),
            attempt=int(payload["attempt"]),
            raw_voice_storage_key=payload["raw_voice_storage_key"],
            backing_storage_key=payload["backing_storage_key"],
            accompaniment_start_frame=int(payload["accompaniment_start_frame"]),
            algorithm_version=payload["algorithm_version"],
            receipt=raw_payload,
        )

    def ack(self, task: PlaybackMixTask) -> None:
        if task.receipt:
            self._client.lrem(self.processing_name, 1, task.receipt)

    def release(self, task: PlaybackMixTask) -> None:
        if not task.receipt:
            return
        with self._client.pipeline(transaction=True) as pipeline:
            pipeline.lrem(self.processing_name, 1, task.receipt)
            pipeline.lpush(self.queue_name, task.receipt)
            pipeline.execute()

    def recover_processing(self) -> int:
        recovered = 0
        while self._client.rpoplpush(self.processing_name, self.queue_name) is not None:
            recovered += 1
        return recovered

    def enqueue(self, task: PlaybackMixTask) -> None:
        self._client.rpush(
            self.queue_name,
            json.dumps(
                {
                    "job_id": str(task.job_id),
                    "attempt": task.attempt,
                    "raw_voice_storage_key": task.raw_voice_storage_key,
                    "backing_storage_key": task.backing_storage_key,
                    "accompaniment_start_frame": task.accompaniment_start_frame,
                    "algorithm_version": task.algorithm_version,
                }
            ),
        )
