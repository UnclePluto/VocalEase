import base64
import hashlib
import hmac
import json
from datetime import timedelta
from urllib.parse import urlencode, urlsplit
from uuid import uuid4

import pytest
from django.test import override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts.models import Role, User
from apps.doctors.services import create_doctor
from apps.media.backends.qiniu import QiniuStorageBackend
from apps.media.contracts import ObjectMetadata
from qiniu import Auth
from apps.media.models import MediaAsset
from apps.media.services import STORAGE_BACKEND_FACTORIES, create_upload_grant
from apps.patients.services import create_patient


def qiniu_callback_authorization(*, access_key, secret_key, callback_url, body):
    return f"QBox {Auth(access_key, secret_key).token_of_request(callback_url, body.decode('utf-8'), 'application/x-www-form-urlencoded')}"


def test_qiniu_policy_locks_key_type_size_and_callback_without_network():
    backend = QiniuStorageBackend(
        access_key="access-key", secret_key="secret-key", bucket="private-bucket", domain="https://cdn.example.test",
        callback_url="https://api.example.test/api/v1/media/qiniu/callback/?source=qiniu", environment="production",
    )
    grant = backend.create_upload_grant(owner_id=uuid4(), media_type="singing_audio", mime="audio/mpeg", size=1024)
    _, _, encoded_policy = grant.upload_token.split(":")
    policy = json.loads(base64.urlsafe_b64decode(encoded_policy + "=="))

    assert policy["scope"] == f"private-bucket:{grant.object_key}"
    assert policy["fsizeLimit"] == 1024
    assert policy["mimeLimit"] == "audio/mpeg"
    assert policy["callbackUrl"].endswith("?source=qiniu")
    assert policy["callbackBodyType"] == "application/x-www-form-urlencoded"
    assert "key=$(key)" in policy["callbackBody"]
    assert str(uuid4()) not in grant.object_key


@pytest.mark.parametrize("reissue", [False, True])
@pytest.mark.parametrize("media_type,mime", [("lyrics", "text/plain"), ("lyrics", "application/json"), ("singing_audio", "audio/mpeg")])
def test_qiniu_lrc_policy_accepts_detected_binary_and_preserves_declared_mime(reissue, media_type, mime):
    backend = QiniuStorageBackend(
        access_key="access-key", secret_key="secret-key", bucket="private-bucket", domain="https://cdn.example.test",
        callback_url="https://api.example.test/api/v1/media/qiniu/callback/", environment="production",
    )
    kwargs = dict(owner_id=uuid4(), media_type=media_type, mime=mime, size=64)
    if reissue:
        grant = backend.reissue_upload_grant(object_key=f"production/{media_type}/object", expires_at=timezone.now() + timedelta(minutes=5), **kwargs)
    else:
        grant = backend.create_upload_grant(**kwargs)
    policy = json.loads(base64.urlsafe_b64decode(grant.upload_token.split(":")[-1] + "=="))
    if media_type == "lyrics" and mime == "text/plain":
        # 线上七牛将 LRC 正文识别为 octet-stream；mimeLimit 会独立于 detectMime 检查正文。
        assert "application/octet-stream" in policy["mimeLimit"].split(";")
        assert "text/plain" in policy["mimeLimit"].split(";")
        assert policy["detectMime"] == 0
    else:
        assert policy["mimeLimit"] == mime
        assert policy["detectMime"] == 1
    assert policy["scope"] == f"private-bucket:{grant.object_key}"
    assert policy["fsizeLimit"] == 64
    assert policy["insertOnly"] == 1
    assert policy["callbackBodyType"] == "application/x-www-form-urlencoded"


def test_qiniu_callback_signature_covers_path_query_and_raw_body():
    backend = QiniuStorageBackend(
        access_key="access-key", secret_key="secret-key", bucket="private-bucket", domain="https://cdn.example.test",
        callback_url="https://api.example.test/api/v1/media/qiniu/callback/?source=qiniu", environment="production",
    )
    body = b"key=production%2Fsinging_audio%2F2026%2F08%2F13%2Fobject&hash=etag&fsize=3&mime=audio%2Fmpeg"
    authorization = qiniu_callback_authorization(
        access_key="access-key", secret_key="secret-key", callback_url=backend.callback_url, body=body
    )

    assert backend.verify_callback_signature(
        authorization=authorization, content_type="application/x-www-form-urlencoded", callback_url=backend.callback_url, body=body
    )
    assert not backend.verify_callback_signature(
        authorization=authorization, content_type="application/x-www-form-urlencoded", callback_url=backend.callback_url + "&tampered=1", body=body
    )
    assert not backend.verify_callback_signature(
        authorization=authorization, content_type="application/x-www-form-urlencoded", callback_url=backend.callback_url, body=body + b"!"
    )
    assert not backend.verify_callback_signature(
        authorization="QBox access-key:wrong", content_type="application/x-www-form-urlencoded", callback_url=backend.callback_url, body=body
    )
    assert not backend.verify_callback_signature(
        authorization=authorization, content_type="text/plain", callback_url=backend.callback_url, body=body
    )


@pytest.fixture
def qiniu_patient(db):
    doctor = create_doctor(name="回调医生", gender="male", phone="13600000911", department="康复科", title="医师")
    patient = create_patient(
        name="回调患者", gender="female", enrollment_age=42, phone="13500000911",
        doctor=doctor, start_date="2026-01-01", cycle_weeks=4,
    )
    patient.user.must_change_password = False
    patient.user.save(update_fields=["must_change_password"])
    return patient


@pytest.mark.django_db
@override_settings(
    MEDIA_BACKEND="qiniu", QINIU_ACCESS_KEY="access-key", QINIU_SECRET_KEY="secret-key", QINIU_BUCKET="private-bucket",
    QINIU_DOMAIN="https://cdn.example.test", QINIU_CALLBACK_URL="http://testserver/api/v1/media/qiniu/callback/?source=qiniu",
)
def test_qiniu_callback_is_idempotent_and_cannot_confirm_wrong_or_failed_asset(qiniu_patient, monkeypatch):
    backend = QiniuStorageBackend.from_settings(stat_transport=lambda _: ObjectMetadata("", 3, "audio/mpeg", "", "etag-value"))
    # 回调从已定位资产的 backend 分派，即使全局默认已改为 local 仍应走七牛验签与 stat。
    monkeypatch.setitem(STORAGE_BACKEND_FACTORIES, "qiniu", lambda: backend)
    asset, grant = create_upload_grant(owner=qiniu_patient, media_type="singing_audio", mime="audio/mpeg", size=3, backend=backend)
    body = urlencode({"key": grant.object_key, "hash": "etag-value", "fsize": 3, "mime": "audio/mpeg"}).encode()
    authorization = qiniu_callback_authorization(
        access_key="access-key", secret_key="secret-key", callback_url=backend.callback_url, body=body
    )
    client = APIClient()
    from django.conf import settings
    settings.MEDIA_BACKEND = "local"
    response = client.post(
        "/api/v1/media/qiniu/callback/?source=qiniu", body, content_type="application/x-www-form-urlencoded", HTTP_AUTHORIZATION=authorization
    )
    assert response.status_code == 200
    asset.refresh_from_db()
    assert asset.status == MediaAsset.Status.READY
    assert asset.sha256 == ""
    assert asset.etag == "etag-value"

    repeat = client.post(
        "/api/v1/media/qiniu/callback/?source=qiniu", body, content_type="application/x-www-form-urlencoded", HTTP_AUTHORIZATION=authorization
    )
    assert repeat.status_code == 200
    assert MediaAsset.objects.filter(object_key=grant.object_key).count() == 1

    changed_hash_body = urlencode({"key": grant.object_key, "hash": "other-etag", "fsize": 3, "mime": "audio/mpeg"}).encode()
    changed_hash_auth = qiniu_callback_authorization(access_key="access-key", secret_key="secret-key", callback_url=backend.callback_url, body=changed_hash_body)
    assert client.post("/api/v1/media/qiniu/callback/?source=qiniu", changed_hash_body, content_type="application/x-www-form-urlencoded", HTTP_AUTHORIZATION=changed_hash_auth).status_code == 409

    failed = MediaAsset.objects.create(
        patient_owner=qiniu_patient, owner_type="patient", owner_id=qiniu_patient.id, media_type="singing_audio", backend="qiniu", object_key="production/singing_audio/2026/08/13/failed", mime="audio/mpeg", size=3,
        status=MediaAsset.Status.FAILED, upload_expires_at=timezone.now(),
    )
    failed_body = urlencode({"key": failed.object_key, "hash": "etag-value", "fsize": 3, "mime": "audio/mpeg"}).encode()
    failed_auth = qiniu_callback_authorization(access_key="access-key", secret_key="secret-key", callback_url=backend.callback_url, body=failed_body)
    rejected = client.post("/api/v1/media/qiniu/callback/?source=qiniu", failed_body, content_type="application/x-www-form-urlencoded", HTTP_AUTHORIZATION=failed_auth)
    assert rejected.status_code == 409
    failed.refresh_from_db()
    assert failed.status == MediaAsset.Status.FAILED
