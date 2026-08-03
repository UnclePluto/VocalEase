from vocaease_worker.queue import DeterministicTaskQueue
from vocaease_worker.worker import Worker


def test_worker_completes_a_deterministic_task():
    queue = DeterministicTaskQueue("redis://127.0.0.1:63799/1")
    queue.clear()
    task = queue.enqueue("health-check")

    processed = Worker(queue).run_once(timeout_seconds=1)

    assert processed is True
    assert queue.result(task.id) == {
        "task_id": task.id,
        "input": "health-check",
        "output": "processed:health-check",
        "status": "completed",
    }
