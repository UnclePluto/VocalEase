import pytest
from rest_framework.test import APIClient


@pytest.mark.django_db
def test_openapi_schema_and_docs_are_publicly_available():
    client = APIClient()

    schema = client.get("/api/schema/", HTTP_ACCEPT="application/vnd.oai.openapi+json")
    docs = client.get("/api/docs/")

    assert schema.status_code == 200
    assert schema.json()["openapi"].startswith("3.0")
    paths = schema.json()["paths"]
    write_contract = {
        ("/api/v1/admin/analytics/exports/", "post"): (True, {"200", "202"}),
        ("/api/v1/admin/analytics/exports/{job_id}/private-url/", "post"): (False, {"200"}),
        ("/api/v1/admin/doctors/", "post"): (True, {"201"}),
        ("/api/v1/admin/doctors/{doctor_id}/", "patch"): (True, {"200"}),
        ("/api/v1/admin/doctors/{doctor_id}/", "delete"): (False, {"204"}),
        ("/api/v1/admin/doctors/{doctor_id}/activate/", "post"): (False, {"200"}),
        ("/api/v1/admin/doctors/{doctor_id}/deactivate/", "post"): (False, {"200"}),
        ("/api/v1/admin/media/{asset_id}/complete/", "post"): (False, {"200"}),
        ("/api/v1/admin/media/{asset_id}/private-url/", "post"): (False, {"200"}),
        ("/api/v1/admin/media/upload-grants/", "post"): (True, {"201"}),
        ("/api/v1/admin/patients/", "post"): (True, {"201"}),
        ("/api/v1/admin/patients/{patient_id}/", "patch"): (True, {"200"}),
        ("/api/v1/admin/patients/{patient_id}/", "delete"): (False, {"204"}),
        ("/api/v1/admin/songs/", "post"): (True, {"201"}),
        ("/api/v1/admin/songs/{song_id}/", "patch"): (True, {"200"}),
        ("/api/v1/admin/songs/{song_id}/", "delete"): (False, {"204"}),
        ("/api/v1/admin/songs/{song_id}/preview/", "post"): (False, {"200"}),
        ("/api/v1/admin/songs/{song_id}/publish/", "post"): (False, {"200"}),
        ("/api/v1/admin/songs/{song_id}/reanalyze/", "post"): (True, {"202", "409"}),
        ("/api/v1/admin/songs/{song_id}/unpublish/", "post"): (False, {"200"}),
        ("/api/v1/admin/songs/upload-grants/", "post"): (True, {"201"}),
        ("/api/v1/admin/users/{user_id}/reset-password/", "post"): (False, {"200"}),
        ("/api/v1/auth/change-password/", "post"): (True, {"200"}),
        ("/api/v1/auth/login/", "post"): (True, {"200"}),
        ("/api/v1/auth/logout/", "post"): (True, {"200"}),
        ("/api/v1/auth/refresh/", "post"): (True, {"200"}),
        ("/api/v1/media/local-upload/{asset_id}/", "put"): (True, {"204"}),
        ("/api/v1/media/qiniu/callback/", "post"): (True, {"200"}),
        ("/api/v1/patient/media/{asset_id}/complete/", "post"): (False, {"200"}),
        ("/api/v1/patient/media/{asset_id}/private-url/", "post"): (False, {"200"}),
        ("/api/v1/patient/media/upload-grants/", "post"): (True, {"201"}),
        ("/api/v1/patient/singing-sessions/", "post"): (True, {"200", "201"}),
        ("/api/v1/patient/singing-sessions/{session_id}/cancel/", "post"): (False, {"200"}),
        ("/api/v1/patient/singing-sessions/{session_id}/confirm-upload/", "post"): (True, {"200"}),
        ("/api/v1/patient/singing-sessions/{session_id}/retry/", "post"): (False, {"200", "202"}),
        ("/api/v1/patient/singing-sessions/{session_id}/submit/", "post"): (False, {"200", "202"}),
        ("/api/v1/patient/singing-sessions/{session_id}/upload-grants/", "post"): (True, {"200", "201"}),
        ("/api/v1/patient/songs/{song_id}/preview/", "post"): (False, {"200"}),
    }
    actual_write_operations = {
        (path, method)
        for path, path_item in paths.items()
        for method in path_item
        if method in {"post", "put", "patch", "delete"}
    }
    assert actual_write_operations == set(write_contract)
    for (path, method), (has_body, statuses) in write_contract.items():
        operation = paths[path][method]
        assert ("requestBody" in operation) is has_body, f"{method.upper()} {path} 请求体契约错误"
        assert set(operation["responses"]) == statuses, f"{method.upper()} {path} 状态码契约错误"
        for response_status, response in operation["responses"].items():
            if response_status != "204":
                assert response.get("content"), f"{method.upper()} {path} {response_status} 缺少响应体契约"
    assert docs.status_code == 200
    assert b"VocaEase API" in docs.content
