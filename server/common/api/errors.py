from rest_framework.response import Response
from rest_framework.views import exception_handler as drf_exception_handler


def exception_handler(exc, context):
    response = drf_exception_handler(exc, context)
    if response is None:
        response = Response(status=500)

    request = context.get("request")
    request_id = getattr(request, "request_id", "")
    detail = response.data.get("detail", "请求处理失败") if isinstance(response.data, dict) else "请求处理失败"
    error_code = "error"
    if hasattr(exc, "get_codes"):
        codes = exc.get_codes()
        if isinstance(codes, str):
            error_code = codes
    response.data = {"code": error_code, "message": str(detail), "data": None, "request_id": request_id}
    return response
