import json
from dataclasses import asdict, dataclass
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

    def enqueue(self, task: SeparationTask) -> None:
        payload = asdict(task)
        payload["job_id"] = str(task.job_id)
        self._client.rpush(self.queue_name, json.dumps(payload))
