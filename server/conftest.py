import pytest
from django.core.cache import cache


@pytest.fixture(autouse=True)
def isolate_throttle_cache():
    cache.clear()
    yield
    cache.clear()
