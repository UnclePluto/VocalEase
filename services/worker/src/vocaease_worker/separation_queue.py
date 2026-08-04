import json
from dataclasses import dataclass
from uuid import UUID

from redis import Redis


@dataclass(frozen=True)
class SeparationTask:
    job_id: UUID
    attempt: int
    source_storage_key: str
    model_name: str


class SeparationQueue:
    queue_name = "vocaease:separation:pending"

    def __init__(self, redis_url: str) -> None:
        self._client = Redis.from_url(redis_url, decode_responses=True)

    def clear(self) -> None:
        self._client.delete(self.queue_name)

    def take(self, timeout_seconds: int) -> SeparationTask | None:
        item = self._client.blpop(self.queue_name, timeout=timeout_seconds)
        if item is None:
            return None
        _, raw_payload = item
        payload = json.loads(raw_payload)
        return SeparationTask(
            job_id=UUID(payload["job_id"]),
            attempt=int(payload["attempt"]),
            source_storage_key=payload["source_storage_key"],
            model_name=payload["model_name"],
        )

    def enqueue(self, task: SeparationTask) -> None:
        self._client.rpush(
            self.queue_name,
            json.dumps(
                {
                    "job_id": str(task.job_id),
                    "attempt": task.attempt,
                    "source_storage_key": task.source_storage_key,
                    "model_name": task.model_name,
                }
            ),
        )
