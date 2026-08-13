from rest_framework.test import APIClient


def test_validation_error_preserves_field_details_and_stable_envelope():
    response = APIClient().post(
        "/api/v1/auth/login/",
        {"client_kind": "android"},
        format="json",
        HTTP_X_REQUEST_ID="validation-request-1",
    )

    assert response.status_code == 400
    assert response.json() == {
        "code": "validation_error",
        "message": "请求参数校验失败",
        "data": {
            "login_id": ["该字段是必填项。"],
            "password": ["该字段是必填项。"],
        },
        "request_id": "validation-request-1",
    }
