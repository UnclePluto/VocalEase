import hashlib
import io
import json
from concurrent.futures import ThreadPoolExecutor

import pytest
from django.test import override_settings
from django.db import connection, connections
from rest_framework.test import APIClient
from urllib.parse import urlencode

from apps.accounts.models import Role, User
from apps.doctors.services import create_doctor
from apps.doctors.models import SequenceCounter
from apps.media.backends.local import LocalStorageBackend
from apps.media.backends.qiniu import QiniuStorageBackend
from apps.media.contracts import ObjectMetadata, StorageValidationError, build_object_key, validate_media_request
from apps.media.models import MediaAsset
from apps.media.services import backend_for_asset, complete_qiniu_callback, create_upload_grant, mark_asset_for_cleanup
from apps.patients.services import create_patient


@pytest.fixture
def api_client():
    return APIClient()


@pytest.fixture
def admin_user(db):
    return User.objects.create_user(login_id="media-security-admin", password="888888", role=Role.SYSTEM_ADMIN, must_change_password=False)


@pytest.fixture
def qiniu_patient(db):
    SequenceCounter.objects.bulk_create([SequenceCounter(prefix="D"), SequenceCounter(prefix="P")], ignore_conflicts=True)
    doctor = create_doctor(name="安全医生", gender="male", phone="13600000881", department="康复科", title="医师")
    patient = create_patient(name="安全患者", gender="female", enrollment_age=40, phone="13500000881", doctor=doctor, start_date="2026-01-01", cycle_weeks=4)
    patient.user.must_change_password = False
    patient.user.save(update_fields=["must_change_password"])
    return patient


@pytest.fixture
def patient_pair(qiniu_patient):
    return qiniu_patient, qiniu_patient


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="qiniu", QINIU_ACCESS_KEY="ak", QINIU_SECRET_KEY="sk", QINIU_BUCKET="bucket", QINIU_DOMAIN="https://cdn.example.test", QINIU_CALLBACK_URL="http://testserver/api/v1/media/qiniu/callback/?source=qiniu")
def test_qiniu_asset_cannot_be_completed_by_patient_or_admin_api(qiniu_patient):
    backend = QiniuStorageBackend.from_settings(stat_transport=lambda _: ObjectMetadata("unused", 3, "audio/mpeg", "", "etag"))
    asset, _ = create_upload_grant(owner=qiniu_patient, media_type="singing_audio", mime="audio/mpeg", size=3, backend=backend)
    client = APIClient()
    client.force_authenticate(qiniu_patient.user)

    response = client.post(f"/api/v1/patient/media/{asset.id}/complete/", {"sha256": "a" * 64, "size": 3, "mime": "audio/mpeg"}, format="json")

    assert response.status_code == 403
    asset.refresh_from_db()
    assert asset.status == MediaAsset.Status.UPLOADING


def test_qiniu_stat_uses_official_rs_stat_protocol_and_never_trusts_callback_sha256():
    captured = {}

    def transport(object_key):
        captured["key"] = object_key
        return {"fsize": 3, "mimeType": "audio/mpeg", "hash": "etag-from-kodo"}

    backend = QiniuStorageBackend(
        access_key="ak", secret_key="sk", bucket="bucket", domain="https://cdn.example.test",
        callback_url="https://api.example.test/callback", environment="prod", stat_transport=transport,
    )

    stat = backend.stat("prod/singing_audio/2026/08/14/abc")

    assert captured["key"] == "prod/singing_audio/2026/08/14/abc"
    assert stat == ObjectMetadata("prod/singing_audio/2026/08/14/abc", 3, "audio/mpeg", "", "etag-from-kodo")
    receipt = backend.verify_completion(stat.object_key, {"key": stat.object_key, "fsize": 3, "mime": "audio/mpeg", "hash": "etag-from-kodo", "sha256": "f" * 64})
    assert receipt.sha256 == ""
    assert receipt.etag == "etag-from-kodo"


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="qiniu", QINIU_ACCESS_KEY="ak", QINIU_SECRET_KEY="sk", QINIU_BUCKET="bucket", QINIU_DOMAIN="https://cdn.example.test", QINIU_CALLBACK_URL="http://testserver/api/v1/media/qiniu/callback/?source=qiniu")
def test_qiniu_callback_rejects_actual_query_tampering(qiniu_patient):
    backend = QiniuStorageBackend.from_settings(stat_transport=lambda _: ObjectMetadata("unused", 3, "audio/mpeg", "", "etag"))
    asset, grant = create_upload_grant(owner=qiniu_patient, media_type="singing_audio", mime="audio/mpeg", size=3, backend=backend)
    body = urlencode({"key": grant.object_key, "fsize": 3, "mime": "audio/mpeg", "hash": "etag"}).encode()
    authorization = backend.sign_callback_for_test("/api/v1/media/qiniu/callback/?source=qiniu", body)
    response = APIClient().post("/api/v1/media/qiniu/callback/?source=tampered", body, content_type="application/x-www-form-urlencoded", HTTP_AUTHORIZATION=authorization)

    assert response.status_code == 403
    asset.refresh_from_db()
    assert asset.status == MediaAsset.Status.UPLOADING


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_patient_route_rejects_admin_and_patient_rejects_song_type(api_client, patient_pair, admin_user):
    patient, _ = patient_pair
    payload = {"owner_id": str(patient.id), "media_type": "singing_audio", "mime": "audio/mpeg", "size": 3}
    api_client.force_authenticate(admin_user)
    assert api_client.post("/api/v1/patient/media/upload-grants/", payload, format="json").status_code == 403
    api_client.force_authenticate(patient.user)
    payload["media_type"] = "song_source"
    assert api_client.post("/api/v1/patient/media/upload-grants/", payload, format="json").status_code == 403


def test_local_stat_is_streaming_and_preserves_trusted_mime(tmp_path, monkeypatch):
    backend = LocalStorageBackend(root=tmp_path, signing_secret="s", environment="test")
    grant = backend.create_upload_grant(owner_id=__import__("uuid").uuid4(), media_type="singing_audio", mime="audio/mpeg", size=1024 * 1024 + 3)
    backend.write_authorized_stream(object_key=grant.object_key, token=grant.upload_token, stream=io.BytesIO(b"x" * (1024 * 1024 + 3)), mime="audio/mpeg")
    monkeypatch.setattr(__import__("pathlib").Path, "read_bytes", lambda *_: (_ for _ in ()).throw(AssertionError("must stream")))

    stat = backend.stat(grant.object_key)

    assert stat.size == 1024 * 1024 + 3
    assert stat.mime == "audio/mpeg"
    assert stat.sha256 == hashlib.sha256(b"x" * (1024 * 1024 + 3)).hexdigest()


@pytest.mark.parametrize("environment", ["prod/evil", "prod?x", "prod#x", "prod%2f", "prod space", "..", ""])
def test_object_key_environment_rejects_ambiguous_or_unsafe_values(environment):
    with pytest.raises(StorageValidationError):
        build_object_key(environment, "singing_audio")


@override_settings(MEDIA_AUDIO_MAX_BYTES=100 * 1024 * 1024)
def test_audio_environment_cannot_relax_absolute_50_mib_limit():
    with pytest.raises(StorageValidationError):
        validate_media_request(media_type="singing_audio", mime="audio/mpeg", size=50 * 1024 * 1024 + 1)


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_owner_contract_supports_future_song_and_historical_asset_backend_dispatch(qiniu_patient, settings):
    song_asset, _ = create_upload_grant(owner_type="song", owner_id=qiniu_patient.id, media_type="song_source", mime="audio/mpeg", size=3)
    assert song_asset.patient_owner is None
    assert song_asset.owner_type == "song"
    settings.MEDIA_BACKEND = "qiniu"
    assert isinstance(backend_for_asset(song_asset), LocalStorageBackend)
    assert mark_asset_for_cleanup(asset=song_asset).status == MediaAsset.Status.PENDING_CLEANUP
    assert mark_asset_for_cleanup(asset=song_asset).status == MediaAsset.Status.PENDING_CLEANUP


@pytest.mark.postgresql
@pytest.mark.django_db(transaction=True)
@override_settings(MEDIA_BACKEND="qiniu")
def test_postgresql_concurrent_qiniu_callbacks_have_one_consistent_ready_result(qiniu_patient, settings):
    if connection.vendor != "postgresql":
        pytest.skip("并发回调由真实 PostgreSQL 行锁测试证明")
    settings.QINIU_ACCESS_KEY = "ak"; settings.QINIU_SECRET_KEY = "sk"; settings.QINIU_BUCKET = "bucket"; settings.QINIU_DOMAIN = "https://cdn.example.test"; settings.QINIU_CALLBACK_URL = "https://api.example.test/callback"
    backend = QiniuStorageBackend.from_settings(stat_transport=lambda key: ObjectMetadata(key, 3, "audio/mpeg", "", "etag-a"))
    asset, grant = create_upload_grant(owner=qiniu_patient, media_type="singing_audio", mime="audio/mpeg", size=3, backend=backend)
    payload = {"key": grant.object_key, "fsize": 3, "mime": "audio/mpeg", "hash": "etag-a"}
    def callback_once(_):
        try:
            return complete_qiniu_callback(payload=payload, backend=backend).status
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(callback_once, range(2)))
    asset.refresh_from_db()
    assert results == [MediaAsset.Status.READY, MediaAsset.Status.READY]
    assert asset.etag == "etag-a"
