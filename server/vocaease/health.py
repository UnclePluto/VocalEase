from django.core.cache import cache
from django.db import connection
from django.http import JsonResponse


def envelope(data, request_id=""):
    return {"code": "ok", "message": "", "data": data, "request_id": request_id}


def check_dependencies():
    connection.ensure_connection()
    cache.set("health", "ok", 5)
    return {"database": connection.is_usable(), "redis": cache.get("health") == "ok"}


def live(request):
    return JsonResponse(envelope({"status": "ok"}, getattr(request, "request_id", "")))


def ready(request):
    return JsonResponse(envelope(check_dependencies(), getattr(request, "request_id", "")))
