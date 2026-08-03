from vocaease_worker.queue import DeterministicTaskQueue


class Worker:
    def __init__(self, queue: DeterministicTaskQueue) -> None:
        self._queue = queue

    def run_once(self, timeout_seconds: int) -> bool:
        task = self._queue.take(timeout_seconds)
        if task is None:
            return False
        self._queue.complete(task, f"processed:{task.value}")
        return True

