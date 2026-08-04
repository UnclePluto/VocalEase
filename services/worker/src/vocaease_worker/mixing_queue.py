import json
from dataclasses import dataclass
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


class PlaybackMixQueue:
    queue_name = "vocaease:playback-mix:pending"

    def __init__(self, redis_url: str) -> None:
        self._client = Redis.from_url(redis_url, decode_responses=True)

    def clear(self) -> None:
        self._client.delete(self.queue_name)

    def take(self, timeout_seconds: int) -> PlaybackMixTask | None:
        item = self._client.blpop(self.queue_name, timeout=timeout_seconds)
        if item is None:
            return None
        _, raw_payload = item
        payload = json.loads(raw_payload)
        return PlaybackMixTask(
            job_id=UUID(payload["job_id"]),
            attempt=int(payload["attempt"]),
            raw_voice_storage_key=payload["raw_voice_storage_key"],
            backing_storage_key=payload["backing_storage_key"],
            accompaniment_start_frame=int(payload["accompaniment_start_frame"]),
            algorithm_version=payload["algorithm_version"],
        )

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
