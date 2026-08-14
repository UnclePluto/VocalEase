from typing import Any, Protocol


class TransientAnalysisError(RuntimeError):
    """可安全重试的执行器短暂故障。"""


class PermanentAnalysisError(RuntimeError):
    """不可重试的执行器故障；对 API 仅暴露稳定错误码。"""


class AnalysisExecutor(Protocol):
    def execute(self, task: "AnalysisTask") -> dict[str, Any]: ...
