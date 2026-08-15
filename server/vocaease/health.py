import os
import tempfile
from pathlib import Path

from django.conf import settings
from django.core.cache import cache
from django.db import connection
from django.http import JsonResponse


def envelope(data, request_id=""):
    return {"code": "ok", "message": "", "data": data, "request_id": request_id}


def check_dependencies():
    connection.ensure_connection()
    cache.set("health", "ok", 5)
    database = connection.is_usable()
    redis = cache.get("health") == "ok"
    if settings.MEDIA_BACKEND == "local":
        root = Path(settings.MEDIA_LOCAL_ROOT)
        descriptor, probe_path = tempfile.mkstemp(prefix=".health-", dir=root)
        try:
            os.write(descriptor, b"ok")
        finally:
            os.close(descriptor)
            probe_path = Path(probe_path)
            probe_path.unlink(missing_ok=True)
        storage = True
    else:
        storage = all(
            getattr(settings, name, "")
            for name in (
                "QINIU_ACCESS_KEY",
                "QINIU_SECRET_KEY",
                "QINIU_BUCKET",
                "QINIU_DOMAIN",
                "QINIU_CALLBACK_URL",
            )
        )
    if not (database and redis and storage):
        raise RuntimeError("依赖未就绪")
    return {"database": database, "redis": redis, "storage": storage}


def live(request):
    return JsonResponse(envelope({"status": "ok"}, getattr(request, "request_id", "")))


def ready(request):
    request_id = getattr(request, "request_id", "")
    try:
        dependencies = check_dependencies()
    except Exception:
        return JsonResponse(
            {
                "code": "service_unavailable",
                "message": "服务依赖尚未就绪",
                "data": None,
                "request_id": request_id,
            },
            status=503,
        )
    return JsonResponse(envelope(dependencies, request_id))
