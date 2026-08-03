import json
from dataclasses import dataclass
from uuid import uuid4

from redis import Redis


@dataclass(frozen=True)
class DeterministicTask:
    id: str
    value: str


class DeterministicTaskQueue:
    _queue_name = "vocaease:worker:deterministic"
    _result_prefix = "vocaease:worker:result:"

    def __init__(self, redis_url: str) -> None:
        self._client = Redis.from_url(redis_url, decode_responses=True)

    def clear(self) -> None:
        self._client.flushdb()

    def enqueue(self, value: str) -> DeterministicTask:
        task = DeterministicTask(id=str(uuid4()), value=value)
        self._client.rpush(
            self._queue_name,
            json.dumps({"task_id": task.id, "value": task.value}),
        )
        return task

    def take(self, timeout_seconds: int) -> DeterministicTask | None:
        item = self._client.blpop(self._queue_name, timeout=timeout_seconds)
        if item is None:
            return None
        _, payload = item
        decoded = json.loads(payload)
        return DeterministicTask(id=decoded["task_id"], value=decoded["value"])

    def complete(self, task: DeterministicTask, output: str) -> None:
        result = {
            "task_id": task.id,
            "input": task.value,
            "output": output,
            "status": "completed",
        }
        self._client.set(f"{self._result_prefix}{task.id}", json.dumps(result))

    def result(self, task_id: str) -> dict[str, str] | None:
        payload = self._client.get(f"{self._result_prefix}{task_id}")
        return json.loads(payload) if payload is not None else None
