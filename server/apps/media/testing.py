from apps.media.contracts import ObjectMetadata


class FakeQiniuObjectStore:
    """供离线测试注入七牛 stat 行为的极小替身。"""

    def __init__(self, objects: dict[str, ObjectMetadata]):
        self.objects = objects

    def stat(self, object_key: str) -> ObjectMetadata:
        return self.objects[object_key]
