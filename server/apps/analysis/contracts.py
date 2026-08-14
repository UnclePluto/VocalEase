from dataclasses import dataclass
from typing import Any, Mapping, Protocol


class TransientAnalysisError(RuntimeError):
    """可安全重试的执行器短暂故障。"""


class PermanentAnalysisError(RuntimeError):
    """不可重试的执行器故障；对 API 仅暴露稳定错误码。"""


class AnalysisProtocolError(PermanentAnalysisError):
    """执行器组合或结果不符合版本化协议。"""


@dataclass(frozen=True)
class AnalysisPayload:
    protocol_version: str
    is_mock: bool
    artifacts: tuple[Mapping[str, Any], ...]
    metrics: Mapping[str, Any]

    @classmethod
    def parse_mock(cls, value: Any) -> "AnalysisPayload":
        if not isinstance(value, dict) or set(value) != {"protocol_version", "is_mock", "artifacts", "metrics"}:
            raise AnalysisProtocolError("模拟分析结果结构无效")
        if value["protocol_version"] != "1.0" or value["is_mock"] is not True:
            raise AnalysisProtocolError("模拟分析结果版本或标记无效")
        if not isinstance(value["artifacts"], list) or value["artifacts"]:
            raise AnalysisProtocolError("模拟分析不得声明真实产物")
        if not isinstance(value["metrics"], dict):
            raise AnalysisProtocolError("模拟分析指标结构无效")
        return cls("1.0", True, (), dict(value["metrics"]))

    def as_dict(self) -> dict[str, Any]:
        return {
            "protocol_version": self.protocol_version,
            "is_mock": self.is_mock,
            "artifacts": [dict(item) for item in self.artifacts],
            "metrics": dict(self.metrics),
        }


class AnalysisExecutor(Protocol):
    def execute(self, task: "AnalysisTask") -> Mapping[str, Any]: ...
