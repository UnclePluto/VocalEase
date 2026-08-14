from __future__ import annotations

from dataclasses import dataclass
import math
import random
from typing import Any
from uuid import UUID

from apps.analysis.contracts import AnalysisProtocolError


SERIES_METRICS = ("volume", "pitch_hz", "snr_db")


def mock_singing_result(task_id: UUID, duration_seconds: int) -> dict[str, Any]:
    randomizer = random.Random(task_id.int)
    duration = max(1, int(duration_seconds))
    events = sorted(
        randomizer.sample(range(1, max(duration, 2)), k=min(3, max(duration - 1, 0)))
    )
    sample_count = min(duration, 600)
    series = {
        "volume": [round(randomizer.uniform(0.25, 0.92), 4) for _ in range(sample_count)],
        "pitch_hz": [round(randomizer.uniform(145.0, 340.0), 2) for _ in range(sample_count)],
        "snr_db": [round(randomizer.uniform(12.0, 32.0), 2) for _ in range(sample_count)],
    }
    return {
        "protocol_version": "1.0",
        "is_mock": True,
        "score": randomizer.randint(68, 96),
        "burp_events": events,
        "sample_interval_ms": max(1, math.ceil(duration * 1000 / sample_count)),
        "series": series,
    }


def mock_face_result(task_id: UUID) -> dict[str, Any]:
    del task_id
    return {"protocol_version": "1.0", "is_mock": True, "landmarks": []}


def _is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


@dataclass(frozen=True)
class SingingAudioPayload:
    protocol_version: str
    is_mock: bool
    score: int
    burp_events: tuple[int, ...]
    sample_interval_ms: int
    series: dict[str, tuple[float, ...]]

    @classmethod
    def parse(cls, value):
        expected = {"protocol_version", "is_mock", "score", "burp_events", "sample_interval_ms", "series"}
        if not isinstance(value, dict) or set(value) != expected:
            raise AnalysisProtocolError("演唱音频模拟结果结构无效")
        if value["protocol_version"] != "1.0" or value["is_mock"] is not True:
            raise AnalysisProtocolError("演唱音频模拟结果版本或标记无效")
        score = value["score"]
        if not isinstance(score, int) or isinstance(score, bool) or not 0 <= score <= 100:
            raise AnalysisProtocolError("演唱评分无效")
        events = value["burp_events"]
        if not isinstance(events, list) or any(not isinstance(item, int) or isinstance(item, bool) or item < 0 for item in events) or events != sorted(set(events)):
            raise AnalysisProtocolError("嗳气事件无效")
        interval = value["sample_interval_ms"]
        if not isinstance(interval, int) or isinstance(interval, bool) or interval <= 0:
            raise AnalysisProtocolError("采样间隔无效")
        series = value["series"]
        if not isinstance(series, dict) or set(series) != set(SERIES_METRICS):
            raise AnalysisProtocolError("演唱时间序列结构无效")
        lengths = set()
        parsed_series = {}
        for metric in SERIES_METRICS:
            values = series[metric]
            if not isinstance(values, list) or not values or len(values) > 600 or any(not _is_number(item) for item in values):
                raise AnalysisProtocolError("演唱时间序列值无效")
            lengths.add(len(values))
            parsed_series[metric] = tuple(float(item) for item in values)
        if len(lengths) != 1:
            raise AnalysisProtocolError("演唱时间序列长度不一致")
        return cls("1.0", True, score, tuple(events), interval, parsed_series)

    def as_dict(self):
        return {
            "protocol_version": self.protocol_version, "is_mock": self.is_mock,
            "score": self.score, "burp_events": list(self.burp_events),
            "sample_interval_ms": self.sample_interval_ms,
            "series": {key: list(values) for key, values in self.series.items()},
        }


@dataclass(frozen=True)
class FaceLandmarksPayload:
    protocol_version: str
    is_mock: bool
    landmarks: tuple

    @classmethod
    def parse(cls, value):
        if not isinstance(value, dict) or set(value) != {"protocol_version", "is_mock", "landmarks"}:
            raise AnalysisProtocolError("面部关键点模拟结果结构无效")
        if value["protocol_version"] != "1.0" or value["is_mock"] is not True or value["landmarks"] != []:
            raise AnalysisProtocolError("面部模拟结果不得生成伪关键点或结论")
        return cls("1.0", True, ())

    def as_dict(self):
        return {"protocol_version": "1.0", "is_mock": True, "landmarks": []}


class MockSingingAudioExecutor:
    def execute(self, task, *, context=None):
        del context
        return mock_singing_result(task.id, int(task.input_snapshot["duration_seconds"]))


class MockFaceLandmarksExecutor:
    def execute(self, task, *, context=None):
        del context
        return mock_face_result(task.id)
