import json
import logging

import pytest
from django.http import JsonResponse
from django.test import RequestFactory

from common.middleware import RequestIdLogFilter, RequestIdMiddleware


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
