from .contracts import AnalysisExecutor


class MockSongExecutor(AnalysisExecutor):
    """仅演示状态协议；绝不生成或伪装真实音频产物。"""

    def execute(self, task):
        return {"protocol_version": "1.0", "is_mock": True, "artifacts": [], "metrics": {}}
