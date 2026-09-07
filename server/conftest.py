import pytest
from django.core.cache import cache


@pytest.fixture(autouse=True)
def isolated_media_root(settings, tmp_path):
    # 本地后端要求可信根目录已存在；每个测试独立创建，避免依赖开发机残留。
    root = tmp_path / "private-media"
    root.mkdir(mode=0o700)
    settings.MEDIA_LOCAL_ROOT = str(root)


@pytest.fixture(autouse=True)
def isolate_throttle_cache():
    cache.clear()
    yield
    cache.clear()
