from dataclasses import dataclass
from typing import Callable

from .contracts import (AnalysisExecutor, AnalysisPayload,
                        AnalysisProtocolError)


class MockSongExecutor(AnalysisExecutor):
    """仅演示状态协议；绝不生成或伪装真实音频产物。"""

    def execute(self, task):
        return {"protocol_version": "1.0", "is_mock": True, "artifacts": [], "metrics": {}}


@dataclass(frozen=True)
class ExecutorRegistration:
    factory: Callable[[], AnalysisExecutor]
    result_parser: Callable[[object], AnalysisPayload]


EXECUTOR_REGISTRY = {
    (task_type, "mock_song", "1.0"): ExecutorRegistration(MockSongExecutor, AnalysisPayload.parse_mock)
    for task_type in ("vocal_separation", "accompaniment_generation", "lyrics_recognition")
}


def resolve_executor(task_type: str, executor: str, protocol_version: str) -> ExecutorRegistration:
    try:
        return EXECUTOR_REGISTRY[(task_type, executor, protocol_version)]
    except KeyError as exc:
        raise AnalysisProtocolError("不支持的分析执行器或协议组合") from exc
