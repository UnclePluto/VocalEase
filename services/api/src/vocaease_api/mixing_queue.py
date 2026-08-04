import json
from dataclasses import asdict, dataclass
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

    def enqueue(self, task: PlaybackMixTask) -> None:
        payload = asdict(task)
        payload["job_id"] = str(task.job_id)
        self._client.rpush(self.queue_name, json.dumps(payload))
