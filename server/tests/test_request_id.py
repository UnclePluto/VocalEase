import json
import logging
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager

import pytest
from django.http import JsonResponse
from django.test import Client, RequestFactory, override_settings
from django.urls import path

from common.middleware import RequestIdLogFilter, RequestIdMiddleware


def api_422(request):
    return JsonResponse(
        {
            "code": "test_unprocessable",
            "message": "",
            "data": None,
            "request_id": request.request_id,
        },
        status=422,
    )


def uncaught_500(_request):
    raise RuntimeError("test-only uncaught view")


def context_echo(request):
    log_record = logging.LogRecord("request-id-echo", logging.INFO, __file__, 1, "ok", (), None)
    RequestIdLogFilter().filter(log_record)
    return JsonResponse(
        {"request_id": request.request_id, "context_request_id": log_record.request_id}
    )


urlpatterns = [
    path("test/api-422/", api_422),
    path("test/uncaught-500/", uncaught_500),
    path("test/context-echo/", context_echo),
]


class _CaptureHandler(logging.Handler):
    def __init__(self):
        super().__init__()
        self.records = []
        self.addFilter(RequestIdLogFilter())

    def emit(self, record):
        self.records.append(record)


@contextmanager
def capture_django_request_logs():
    handler = _CaptureHandler()
    logger_states = []
    for name in ("django.request", "django.security.DisallowedHost"):
        logger = logging.getLogger(name)
        logger_states.append((logger, logger.handlers, logger.propagate, logger.level))
        logger.handlers = [handler]
        logger.propagate = False
        logger.setLevel(logging.WARNING)
    try:
        yield handler.records
    finally:
        for logger, handlers, propagate, level in logger_states:
            logger.handlers = handlers
            logger.propagate = propagate
            logger.setLevel(level)


@pytest.mark.parametrize(
    "unsafe_request_id",
    [
        "13800000003",
        "+86.138.0000.0003",
        "+86\u200b138/0000/0003",
        "＋８６．１３８．００００．０００３",
        "bad\r\nrequest-id",
        "不接受Unicode请求号",
    ],
)
def test_request_id_middleware_uses_one_safe_id_for_request_response_and_log_context(
    unsafe_request_id,
):
    captured = {}

    def get_response(request):
        log_record = logging.LogRecord("request-id-test", logging.INFO, __file__, 1, "ok", (), None)
        RequestIdLogFilter().filter(log_record)
        captured["request_id"] = log_record.request_id
        return JsonResponse({"request_id": request.request_id})

    request = RequestFactory().get("/health/live/", HTTP_X_REQUEST_ID=unsafe_request_id)
    response = RequestIdMiddleware(get_response)(request)
    body = json.loads(response.content)

    assert len(request.request_id) == 32
    assert all(character in "0123456789abcdef" for character in request.request_id)
    assert body["request_id"] == request.request_id
    assert response["X-Request-ID"] == request.request_id
    assert captured["request_id"] == request.request_id
    assert unsafe_request_id not in response.content.decode("utf-8")


@override_settings(ROOT_URLCONF=__name__, DEBUG=False, ALLOWED_HOSTS=["testserver"])
@pytest.mark.parametrize(
    ("path_value", "host", "unsafe_request_id", "expected_status", "has_envelope"),
    [
        ("/test/api-422/", "testserver", "trace:138_0000_0003", 422, True),
        ("/test/missing/", "testserver", "86:138:0000:0003", 404, False),
        ("/test/api-422/", "untrusted.example", "+86_138:0000_0003", 400, False),
        ("/test/uncaught-500/", "testserver", "＋８６：１３８＿００００：０００３", 500, False),
    ],
)
def test_real_handler_correlates_safe_request_id_across_error_response_and_structured_logs(
    path_value, host, unsafe_request_id, expected_status, has_envelope
):
    client = Client(raise_request_exception=False)
    with capture_django_request_logs() as records:
        response = client.get(
            path_value,
            HTTP_HOST=host,
            HTTP_X_REQUEST_ID=unsafe_request_id,
        )

    assert response.status_code == expected_status
    safe_request_id = response["X-Request-ID"]
    assert len(safe_request_id) == 32
    assert all(character in "0123456789abcdef" for character in safe_request_id)
    assert records
    assert all(record.request_id == safe_request_id for record in records)
    assert all(unsafe_request_id not in record.getMessage() for record in records)
    if has_envelope:
        assert response.json()["request_id"] == safe_request_id


@override_settings(ROOT_URLCONF=__name__, DEBUG=False, ALLOWED_HOSTS=["testserver"])
def test_real_handler_preserves_safe_request_id_in_response_envelope_and_post_response_log():
    with capture_django_request_logs() as records:
        response = Client().get(
            "/test/api-422/",
            HTTP_X_REQUEST_ID="normal:api-422",
        )

    assert response["X-Request-ID"] == "normal:api-422"
    assert response.json()["request_id"] == "normal:api-422"
    assert records
    assert all(record.request_id == "normal:api-422" for record in records)


@override_settings(ROOT_URLCONF=__name__, DEBUG=False, ALLOWED_HOSTS=["testserver"])
def test_real_handler_keeps_request_id_context_isolated_across_sixteen_threads():
    request_ids = [f"parallel:{index}" for index in range(16)]

    def call(request_id):
        response = Client().get(
            "/test/context-echo/",
            HTTP_X_REQUEST_ID=request_id,
        )
        return response.json()

    with ThreadPoolExecutor(max_workers=16) as executor:
        responses = list(executor.map(call, request_ids))

    assert responses == [
        {"request_id": request_id, "context_request_id": request_id}
        for request_id in request_ids
    ]
