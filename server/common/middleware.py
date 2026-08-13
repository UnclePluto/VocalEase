import logging
import uuid
from contextvars import ContextVar

_request_id = ContextVar("request_id", default="")


class RequestIdMiddleware:
    header_name = "HTTP_X_REQUEST_ID"

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        request.request_id = request.META.get(self.header_name) or uuid.uuid4().hex
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
