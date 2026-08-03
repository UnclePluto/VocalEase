import os

from vocaease_worker.queue import DeterministicTaskQueue
from vocaease_worker.worker import Worker


def main() -> None:
    redis_url = os.getenv("VOCAEASE_REDIS_URL", "redis://127.0.0.1:63799/1")
    worker = Worker(DeterministicTaskQueue(redis_url))
    while True:
        worker.run_once(timeout_seconds=5)


if __name__ == "__main__":
    main()
