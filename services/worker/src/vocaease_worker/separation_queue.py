import json
from dataclasses import dataclass, field
from uuid import UUID

from redis import Redis


@dataclass(frozen=True)
class SeparationTask:
    job_id: UUID
    attempt: int
    source_storage_key: str
    model_name: str
    receipt: str = field(default="", repr=False, compare=False)


class SeparationQueue:
    queue_name = "vocaease:separation:pending"
    processing_name = "vocaease:separation:processing"

    def __init__(self, redis_url: str) -> None:
        self._client = Redis.from_url(redis_url, decode_responses=True)

    def clear(self) -> None:
        self._client.delete(self.queue_name, self.processing_name)

    def take(self, timeout_seconds: int) -> SeparationTask | None:
        raw_payload = self._client.brpoplpush(
            self.queue_name,
            self.processing_name,
            timeout=timeout_seconds,
        )
        if raw_payload is None:
            return None
        payload = json.loads(raw_payload)
        return SeparationTask(
            job_id=UUID(payload["job_id"]),
            attempt=int(payload["attempt"]),
            source_storage_key=payload["source_storage_key"],
            model_name=payload["model_name"],
            receipt=raw_payload,
        )

    def ack(self, task: SeparationTask) -> None:
        if task.receipt:
            self._client.lrem(self.processing_name, 1, task.receipt)

    def release(self, task: SeparationTask) -> None:
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
