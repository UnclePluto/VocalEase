import logging
import os
from pathlib import Path

from vocaease_worker.callback import PlaybackMixCallbackClient, SeparationCallbackClient
from vocaease_worker.mixing import FfmpegPlaybackMixer
from vocaease_worker.mixing_queue import PlaybackMixQueue
from vocaease_worker.mixing_worker import PlaybackMixWorker
from vocaease_worker.separation import AudioSeparatorTwoTrack
from vocaease_worker.separation_queue import SeparationQueue
from vocaease_worker.separation_worker import SeparationWorker

SINGLE_WORKER_MVP = True


def main() -> None:
    redis_url = os.getenv("VOCAEASE_REDIS_URL", "redis://127.0.0.1:63799/1")
    media_directory = Path(os.getenv("VOCAEASE_MEDIA_DIRECTORY", ".runtime/media"))
    model_directory = Path(
        os.getenv("VOCAEASE_SEPARATION_MODEL_DIRECTORY", ".runtime/separation-models")
    )
    api_base_url = os.getenv("VOCAEASE_API_BASE_URL", "http://127.0.0.1:8000")
    internal_token = os.getenv("VOCAEASE_WORKER_INTERNAL_TOKEN", "development-worker-token")
    model_name = os.getenv("VOCAEASE_SEPARATION_MODEL_NAME", "UVR-MDX-NET-Inst_HQ_3.onnx")
    worker = SeparationWorker(
        queue=SeparationQueue(redis_url),
        callback=SeparationCallbackClient(api_base_url, internal_token),
        separator=AudioSeparatorTwoTrack(model_name, model_directory),
        media_directory=media_directory,
    )
    mix_worker = PlaybackMixWorker(
        queue=PlaybackMixQueue(redis_url),
        callback=PlaybackMixCallbackClient(api_base_url, internal_token),
        mixer=FfmpegPlaybackMixer(),
        media_directory=media_directory,
    )
    # 一期部署约束为单 Worker；启动时可安全地把上次崩溃遗留的未确认任务全部恢复。
    if SINGLE_WORKER_MVP:
        worker.queue.recover_processing()
        mix_worker.queue.recover_processing()
    while True:
        for active_worker in (worker, mix_worker):
            try:
                active_worker.run_once(timeout_seconds=1)
            except Exception:
                logging.exception("异步任务循环发生异常，下一轮将继续消费")


if __name__ == "__main__":
    main()
