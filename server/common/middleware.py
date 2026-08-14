import logging
import uuid
from contextvars import ContextVar

from .privacy import sanitize_request_id

_request_id = ContextVar("request_id", default="")


class RequestIdMiddleware:
    header_name = "HTTP_X_REQUEST_ID"

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        supplied_request_id = request.META.get(self.header_name, "")
        safe_request_id = sanitize_request_id(supplied_request_id, invalid_value="")
        request.request_id = safe_request_id or uuid.uuid4().hex
        token = _request_id.set(request.request_id)
        try:
            response = self.get_response(request)
        finally:
            _request_id.reset(token)
        response["X-Request-ID"] = request.request_id
        return response


class RequestIdLogFilter(logging.Filter):
    def filter(self, record):
        record.request_id = _request_id.get()
        return True
