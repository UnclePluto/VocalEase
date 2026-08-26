from copy import deepcopy

import pytest
from rest_framework.test import APIClient


def merge_schemas(left, right):
    merged = deepcopy(left)
    for key, value in right.items():
        if key == "required":
            merged[key] = list(dict.fromkeys([*merged.get(key, []), *value]))
        elif key == "properties":
            properties = deepcopy(merged.get(key, {}))
            for name, property_schema in value.items():
                properties[name] = (
                    merge_schemas(properties[name], property_schema)
                    if name in properties
                    else deepcopy(property_schema)
                )
            merged[key] = properties
        elif key in merged and isinstance(merged[key], dict) and isinstance(value, dict):
            merged[key] = merge_schemas(merged[key], value)
        else:
            merged[key] = deepcopy(value)
    return merged


def resolve_schema(document, schema, seen_references=frozenset()):
    schema = deepcopy(schema)
    resolved = {}
    reference = schema.pop("$ref", None)
    if reference:
        if reference in seen_references:
            raise ValueError(f"OpenAPI Schema 存在循环引用：{reference}")
        component = document["components"]["schemas"][reference.rsplit("/", 1)[-1]]
        resolved = resolve_schema(
            document, component, seen_references | {reference},
        )
    for member in schema.pop("allOf", []):
        resolved = merge_schemas(
            resolved, resolve_schema(document, member, seen_references),
        )
    return merge_schemas(resolved, schema)


def response_schema(operation, status_code):
    return operation["responses"][status_code]["content"]["application/json"]["schema"]


def resolve_data_schema(document, envelope_schema):
    envelope = resolve_schema(document, envelope_schema)
    return resolve_schema(document, envelope["properties"]["data"])


def schema_reference_names(schema):
    names = set()
    if "$ref" in schema:
        names.add(schema["$ref"].rsplit("/", 1)[-1])
    for member in schema.get("allOf", []):
        names.update(schema_reference_names(member))
    return names


def test_resolve_schema_merges_references_and_all_of_without_losing_siblings():
    document = {
        "components": {
            "schemas": {
                "Identity": {
                    "type": "object",
                    "properties": {"id": {"type": "string"}},
                    "required": ["id"],
                },
                "Status": {
                    "type": "object",
                    "properties": {"status": {"type": "string"}},
                    "required": ["status"],
                },
            },
        },
    }
    composed = {
        "allOf": [
            {"$ref": "#/components/schemas/Identity"},
            {"$ref": "#/components/schemas/Status"},
        ],
        "nullable": True,
        "description": "可空组合对象",
    }

    resolved = resolve_schema(document, composed)
    nullable_reference = resolve_schema(
        document,
        {"$ref": "#/components/schemas/Identity", "nullable": True},
    )

    assert set(resolved["properties"]) == {"id", "status"}
    assert set(resolved["required"]) == {"id", "status"}
    assert resolved["nullable"] is True
    assert resolved["description"] == "可空组合对象"
    assert nullable_reference["nullable"] is True
    assert "nullable" not in document["components"]["schemas"]["Identity"]


@pytest.mark.django_db
def test_openapi_schema_and_docs_are_publicly_available():
    client = APIClient()

    schema = client.get("/api/schema/", HTTP_ACCEPT="application/vnd.oai.openapi+json")
    docs = client.get("/api/docs/")

    assert schema.status_code == 200
    assert schema.json()["openapi"].startswith("3.0")
    document = schema.json()
    paths = document["paths"]
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

    android_named_responses = [
        ("/api/v1/auth/login/", "post", "200", {"access", "refresh_expires_at", "user"}),
        ("/api/v1/auth/refresh/", "post", "200", {"access", "refresh_expires_at", "user"}),
        ("/api/v1/patient/me/", "get", "200", {"treatment_progress", "singing_summary"}),
        ("/api/v1/patient/songs/", "get", "200", {"count", "page", "page_size", "results"}),
        ("/api/v1/patient/songs/{song_id}/", "get", "200", {"id", "title", "artist"}),
        ("/api/v1/patient/songs/{song_id}/preview/", "post", "200", {"url", "expires_at"}),
        ("/api/v1/patient/singing-sessions/", "get", "200", {"count", "page", "page_size", "results"}),
        ("/api/v1/patient/singing-sessions/", "post", "200", {"id", "status", "media", "analysis_results"}),
        ("/api/v1/patient/singing-sessions/", "post", "201", {"id", "status", "media", "analysis_results"}),
        ("/api/v1/patient/singing-sessions/{session_id}/", "get", "200", {"id", "status", "media", "analysis_results"}),
        ("/api/v1/patient/singing-sessions/{session_id}/upload-grants/", "post", "200", {"session_id", "asset_id", "upload_url"}),
        ("/api/v1/patient/singing-sessions/{session_id}/upload-grants/", "post", "201", {"session_id", "asset_id", "upload_url"}),
        ("/api/v1/patient/singing-sessions/{session_id}/confirm-upload/", "post", "200", {"id", "status", "media"}),
        ("/api/v1/patient/singing-sessions/{session_id}/submit/", "post", "200", {"session_id", "status", "analysis_task_ids"}),
        ("/api/v1/patient/singing-sessions/{session_id}/submit/", "post", "202", {"session_id", "status", "analysis_task_ids"}),
        ("/api/v1/patient/singing-sessions/{session_id}/cancel/", "post", "200", {"id", "status"}),
        ("/api/v1/patient/singing-sessions/{session_id}/retry/", "post", "200", {"session_id", "status", "analysis_task_ids"}),
        ("/api/v1/patient/singing-sessions/{session_id}/retry/", "post", "202", {"session_id", "status", "analysis_task_ids"}),
        ("/api/v1/patient/media/upload-grants/", "post", "201", {"asset_id", "object_key", "upload_url"}),
        ("/api/v1/patient/media/{asset_id}/private-url/", "post", "200", {"url", "expires_at"}),
    ]
    for path, method, response_status, required_data_fields in android_named_responses:
        raw_envelope = response_schema(paths[path][method], response_status)
        envelope_references = schema_reference_names(raw_envelope)
        assert envelope_references, f"{method.upper()} {path} {response_status} 必须引用具名信封"
        assert "ApiEnvelope" not in envelope_references, (
            f"{method.upper()} {path} {response_status} 不能退回泛型 ApiEnvelope"
        )
        envelope = resolve_schema(document, raw_envelope)
        assert {"code", "message", "data", "request_id"} <= set(
            envelope.get("required", []),
        )
        raw_data = envelope["properties"]["data"]
        assert schema_reference_names(raw_data), (
            f"{method.upper()} {path} {response_status} data 必须引用具名 Schema"
        )
        data = resolve_schema(document, raw_data)
        assert data.get("type") == "object" and data.get("properties"), (
            f"{method.upper()} {path} {response_status} data 必须是具名 object"
        )
        assert required_data_fields <= set(data.get("required", [])), (
            f"{method.upper()} {path} {response_status} data 缺少 required 字段"
        )

    login_data = resolve_data_schema(
        document,
        response_schema(paths["/api/v1/auth/login/"]["post"], "200"),
    )
    assert {"access", "refresh", "user"} <= set(login_data["properties"])
    assert {"access", "refresh_expires_at", "user"} <= set(login_data["required"])
    assert "refresh" not in login_data["required"]
    assert schema_reference_names(login_data["properties"]["user"])
    login_user = resolve_schema(document, login_data["properties"]["user"])
    assert {"login_id", "role", "must_change_password"} <= set(login_user["properties"])
    assert {"login_id", "role", "must_change_password"} <= set(login_user["required"])

    refresh_data = resolve_data_schema(
        document,
        response_schema(paths["/api/v1/auth/refresh/"]["post"], "200"),
    )
    assert {"access", "refresh", "user"} <= set(refresh_data["properties"])
    assert {"access", "refresh_expires_at", "user"} <= set(refresh_data["required"])
    assert "refresh" not in refresh_data["required"]
    assert schema_reference_names(refresh_data["properties"]["user"])

    me_schema = response_schema(paths["/api/v1/patient/me/"]["get"], "200")
    me_data = resolve_data_schema(document, me_schema)
    assert {"treatment_progress", "singing_summary"} <= set(me_data["properties"])
    assert {"treatment_progress", "singing_summary"} <= set(me_data["required"])
    progress = resolve_schema(document, me_data["properties"]["treatment_progress"])
    assert progress["nullable"] is True
    assert {
        "completed_session_count", "target_session_count", "progress_percent", "current_week",
    } <= set(progress["properties"])
    summary = resolve_schema(document, me_data["properties"]["singing_summary"])
    assert {"completed_session_count", "total_duration_seconds"} <= set(summary["properties"])

    song_list_data = resolve_data_schema(
        document,
        response_schema(paths["/api/v1/patient/songs/"]["get"], "200"),
    )
    assert set(song_list_data["properties"]) == {"count", "page", "page_size", "results"}
    assert schema_reference_names(song_list_data["properties"]["results"]["items"])
    song = resolve_schema(document, song_list_data["properties"]["results"]["items"])
    assert {"id", "title", "artist", "duration_seconds"} <= set(song["properties"])
    preview_data = resolve_data_schema(
        document,
        response_schema(paths["/api/v1/patient/songs/{song_id}/preview/"]["post"], "200"),
    )
    assert set(preview_data["properties"]) == {"url", "expires_at"}

    create = paths["/api/v1/patient/singing-sessions/"]["post"]
    create_header = next(p for p in create["parameters"] if p["name"] == "Idempotency-Key")
    assert create_header["in"] == "header" and create_header["required"] is True
    create_data = resolve_data_schema(document, response_schema(create, "201"))
    assert {"id", "status", "media", "analysis_results"} <= set(create_data["properties"])

    session_list_data = resolve_data_schema(
        document,
        response_schema(paths["/api/v1/patient/singing-sessions/"]["get"], "200"),
    )
    assert set(session_list_data["properties"]) == {"count", "page", "page_size", "results"}
    assert schema_reference_names(
        session_list_data["properties"]["results"]["items"],
    )
    listed_session = resolve_schema(
        document, session_list_data["properties"]["results"]["items"],
    )
    assert {"id", "status"} <= set(listed_session["properties"])

    expected_session_statuses = {
        "created", "awaiting_upload", "uploaded", "processing",
        "completed", "failed", "cancelled",
    }
    session_read_data_schemas = [
        listed_session,
        *[
            resolve_data_schema(
                document,
                response_schema(paths[path][method], response_status),
            )
            for path, method, response_status in (
                ("/api/v1/patient/singing-sessions/", "post", "200"),
                ("/api/v1/patient/singing-sessions/", "post", "201"),
                ("/api/v1/patient/singing-sessions/{session_id}/", "get", "200"),
                ("/api/v1/patient/singing-sessions/{session_id}/confirm-upload/", "post", "200"),
                ("/api/v1/patient/singing-sessions/{session_id}/cancel/", "post", "200"),
            )
        ],
    ]
    for session_data in session_read_data_schemas:
        raw_status = session_data["properties"]["status"]
        assert schema_reference_names(raw_status) == {"SingingSessionStatus"}
        assert set(resolve_schema(document, raw_status)["enum"]) == expected_session_statuses

    detail_data = resolve_data_schema(
        document,
        response_schema(
            paths["/api/v1/patient/singing-sessions/{session_id}/"]["get"], "200",
        ),
    )
    assert {"status", "media", "analysis_results"} <= set(detail_data["properties"])
    analysis_result = resolve_schema(
        document, detail_data["properties"]["analysis_results"]["items"],
    )
    assert "is_mock" in analysis_result["properties"]

    upload_grant = paths[
        "/api/v1/patient/singing-sessions/{session_id}/upload-grants/"
    ]["post"]
    grant_header = next(
        p for p in upload_grant["parameters"] if p["name"] == "Idempotency-Key"
    )
    assert grant_header["in"] == "header" and grant_header.get("required", False) is False
    grant_data = resolve_data_schema(document, response_schema(upload_grant, "201"))
    assert {
        "session_id", "asset_id", "object_key", "expires_at", "upload_url",
        "upload_token", "fields",
    } == set(grant_data["properties"])

    confirm_data = resolve_data_schema(
        document,
        response_schema(
            paths["/api/v1/patient/singing-sessions/{session_id}/confirm-upload/"]["post"],
            "200",
        ),
    )
    assert {"id", "status", "media"} <= set(confirm_data["properties"])

    for action in ("submit", "retry"):
        operation = paths[
            f"/api/v1/patient/singing-sessions/{{session_id}}/{action}/"
        ]["post"]
        header = next(p for p in operation["parameters"] if p["name"] == "Idempotency-Key")
        assert header["in"] == "header" and header["required"] is True
        for response_status in ("200", "202"):
            mutation_data = resolve_data_schema(
                document, response_schema(operation, response_status),
            )
            assert set(mutation_data["properties"]) == {
                "session_id", "status", "analysis_task_ids",
            }
            mutation_status = resolve_schema(
                document, mutation_data["properties"]["status"],
            )
            assert schema_reference_names(
                mutation_data["properties"]["status"],
            ) == {"SingingSessionStatus"}
            assert set(mutation_status["enum"]) == expected_session_statuses

    cancelled_data = resolve_data_schema(
        document,
        response_schema(
            paths["/api/v1/patient/singing-sessions/{session_id}/cancel/"]["post"], "200",
        ),
    )
    assert {"id", "status"} <= set(cancelled_data["properties"])

    patient_grant_operation = paths[
        "/api/v1/patient/media/upload-grants/"
    ]["post"]
    patient_grant_request = resolve_schema(
        document,
        patient_grant_operation["requestBody"]["content"]["application/json"][
            "schema"
        ],
    )
    assert set(patient_grant_request["properties"]) == {
        "owner_id",
        "media_type",
        "mime",
        "size",
    }
    assert set(patient_grant_request["required"]) == {
        "owner_id",
        "media_type",
        "mime",
        "size",
    }
    patient_grant_data = resolve_data_schema(
        document,
        response_schema(patient_grant_operation, "201"),
    )
    assert {"asset_id", "object_key", "expires_at", "upload_url", "upload_token", "fields"} == set(
        patient_grant_data["properties"]
    )
    private_url_data = resolve_data_schema(
        document,
        response_schema(
            paths["/api/v1/patient/media/{asset_id}/private-url/"]["post"], "200",
        ),
    )
    assert set(private_url_data["properties"]) == {"url", "expires_at"}
    assert docs.status_code == 200
    assert b"VocaEase API" in docs.content
