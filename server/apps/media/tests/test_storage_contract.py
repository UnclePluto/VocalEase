import hashlib
from datetime import timedelta
from pathlib import Path
from uuid import uuid4

import pytest
from django.test import override_settings
from django.utils import timezone
from rest_framework.test import APIClient
from rest_framework.throttling import ScopedRateThrottle

from apps.accounts.models import Role, User
from apps.doctors.services import create_doctor
from apps.media.backends.local import LocalStorageBackend
from apps.media.contracts import MEDIA_TYPES, StorageValidationError
from apps.media.models import MediaAsset
from apps.media.services import get_storage_backend
from apps.patients.services import create_patient


@pytest.fixture
def api_client():
    return APIClient()


@pytest.fixture
def patient_pair(db):
    doctor = create_doctor(
        name="媒体医生", gender="male", phone="13600000901", department="康复科", title="医师"
    )
    patient = create_patient(
        name="媒体患者", gender="female", enrollment_age=40, phone="13500000901",
        doctor=doctor, start_date="2026-01-01", cycle_weeks=4,
    )
    patient.user.must_change_password = False
    patient.user.save(update_fields=["must_change_password"])
    other = create_patient(
        name="另一患者", gender="male", enrollment_age=41, phone="13500000902",
        doctor=doctor, start_date="2026-01-01", cycle_weeks=4,
    )
    other.user.must_change_password = False
    other.user.save(update_fields=["must_change_password"])
    return patient, other


@pytest.fixture
def admin_user(db):
    return User.objects.create_user(
        login_id="media-admin", password="888888", role=Role.SYSTEM_ADMIN, must_change_password=False
    )


def test_local_storage_contract_writes_and_reads_only_controlled_object(tmp_path):
    backend = LocalStorageBackend(root=tmp_path, signing_secret="contract-secret", environment="test")
    owner_id = uuid4()
    content = b"local media contents"
    grant = backend.create_upload_grant(
        owner_id=owner_id, media_type="singing_audio", mime="audio/mpeg", size=len(content)
    )

    assert str(owner_id) not in grant.object_key
    assert grant.object_key.startswith("test/singing_audio/")
    backend.write_upload(grant=grant, content=content, mime="audio/mpeg")
    receipt = backend.verify_completion(
        grant.object_key, {"sha256": hashlib.sha256(content).hexdigest(), "size": len(content), "mime": "audio/mpeg"}
    )

    assert receipt.object_key == grant.object_key
    assert receipt.size == len(content)
    private_url = backend.create_private_url(grant.object_key, ttl_seconds=600)
    assert private_url.expires_at > timezone.now()
    assert backend.read_private(private_url.token) == content


@pytest.mark.parametrize(
    ("media_type", "mime", "size"),
    [
        ("singing_audio", "video/mp4", 10),
        ("singing_audio", "audio/mpeg", 50 * 1024 * 1024 + 1),
        ("singing_video", "video/mp4", 500 * 1024 * 1024 + 1),
        ("unknown", "audio/mpeg", 10),
    ],
)
def test_storage_contract_rejects_unknown_type_mime_and_oversized_grant(tmp_path, media_type, mime, size):
    backend = LocalStorageBackend(root=tmp_path, signing_secret="contract-secret", environment="test")

    with pytest.raises(StorageValidationError):
        backend.create_upload_grant(owner_id=uuid4(), media_type=media_type, mime=mime, size=size)


def test_media_type_contract_includes_all_planned_asset_categories():
    assert {"song_source", "song_accompaniment", "song_vocal", "lyrics", "singing_audio", "singing_video", "waveform", "export"} <= set(MEDIA_TYPES)


@pytest.mark.django_db
def test_patient_cannot_request_upload_for_other_patient(api_client, patient_pair):
    patient, other_patient = patient_pair
    api_client.force_authenticate(patient.user)

    response = api_client.post(
        "/api/v1/patient/media/upload-grants/",
        {"owner_id": str(other_patient.id), "media_type": "singing_audio", "mime": "audio/mpeg", "size": 1024},
        format="json",
    )

    assert response.status_code == 403
    assert response.json()["request_id"]


@pytest.mark.django_db
def test_patient_required_to_change_password_cannot_request_media_upload_grant(api_client, patient_pair):
    patient, _ = patient_pair
    patient.user.must_change_password = True
    patient.user.save(update_fields=["must_change_password"])
    api_client.force_authenticate(patient.user)

    response = api_client.post(
        "/api/v1/patient/media/upload-grants/",
        {"owner_id": str(patient.id), "media_type": "singing_audio", "mime": "audio/mpeg", "size": 1024},
        format="json",
    )

    assert response.status_code == 403
    assert response.json()["code"] == "password_change_required"


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_local_upload_complete_and_private_download_are_authorized_and_do_not_expose_media_root(api_client, patient_pair, tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path / "not-public")
    patient, other_patient = patient_pair
    content = b"private audio"
    sha256 = hashlib.sha256(content).hexdigest()
    api_client.force_authenticate(patient.user)

    grant_response = api_client.post(
        "/api/v1/patient/media/upload-grants/",
        {"owner_id": str(patient.id), "media_type": "singing_audio", "mime": "audio/mpeg", "size": len(content)},
        format="json",
        HTTP_X_REQUEST_ID="media-grant-1",
    )
    assert grant_response.status_code == 201
    grant = grant_response.json()["data"]
    assert str(patient.id) not in grant["object_key"]
    assert str(tmp_path) not in grant["upload_url"]

    upload_response = api_client.put(grant["upload_url"], content, content_type="audio/mpeg")
    assert upload_response.status_code == 204
    assert (tmp_path / "not-public" / ".manifests" / f"{grant['object_key']}.json").is_file()

    complete_response = api_client.post(
        f"/api/v1/patient/media/{grant['asset_id']}/complete/",
        {"sha256": sha256, "size": len(content), "mime": "audio/mpeg"},
        format="json",
    )
    assert complete_response.status_code == 200
    assert complete_response.json()["data"]["status"] == MediaAsset.Status.READY

    private_response = api_client.post(f"/api/v1/patient/media/{grant['asset_id']}/private-url/")
    assert private_response.status_code == 200
    private_url = private_response.json()["data"]["url"]
    assert "/api/v1/media/private/" in private_url
    # 已签发的本地资产不得因运行期切到七牛而无法下载。
    settings.MEDIA_BACKEND = "qiniu"
    download_response = api_client.get(private_url)
    assert download_response.status_code == 200
    assert b"".join(download_response.streaming_content) == content

    settings.MEDIA_BACKEND = "local"
    backend = get_storage_backend()
    expired_signature, _ = backend._issue_token({"object_key": grant["object_key"], "kind": "private"}, -1)
    expired_response = api_client.get(f"/api/v1/media/private/{grant['object_key']}?signature={expired_signature}")
    assert expired_response.status_code == 403

    api_client.force_authenticate(other_patient.user)
    assert api_client.post(f"/api/v1/patient/media/{grant['asset_id']}/private-url/").status_code == 403
    assert api_client.post(f"/api/v1/patient/media/{grant['asset_id']}/complete/", {"sha256": sha256, "size": len(content), "mime": "audio/mpeg"}, format="json").status_code == 403


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local", MEDIA_UPLOAD_GRANT_TTL_SECONDS=1)
def test_local_upload_grant_expiry_and_object_key_traversal_are_rejected(api_client, patient_pair, tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path / "private")
    patient, _ = patient_pair
    api_client.force_authenticate(patient.user)
    response = api_client.post(
        "/api/v1/patient/media/upload-grants/",
        {"owner_id": str(patient.id), "media_type": "singing_audio", "mime": "audio/mpeg", "size": 4},
        format="json",
    )
    grant = response.json()["data"]
    expired_url = grant["upload_url"].replace("signature=", "signature=invalid")
    assert api_client.put(expired_url, b"test", content_type="audio/mpeg").status_code == 403

    backend = LocalStorageBackend(root=tmp_path / "private", signing_secret="test-secret", environment="test")
    with pytest.raises(StorageValidationError):
        backend.stat("../outside")


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_completion_rejects_an_expired_upload_grant_even_if_the_file_was_written(api_client, patient_pair, tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path / "private")
    patient, _ = patient_pair
    api_client.force_authenticate(patient.user)
    content = b"late"
    response = api_client.post(
        "/api/v1/patient/media/upload-grants/",
        {"owner_id": str(patient.id), "media_type": "singing_audio", "mime": "audio/mpeg", "size": len(content)},
        format="json",
    )
    grant = response.json()["data"]
    assert api_client.put(grant["upload_url"], content, content_type="audio/mpeg").status_code == 204
    asset = MediaAsset.objects.get(pk=grant["asset_id"])
    asset.upload_expires_at = timezone.now() - timedelta(seconds=1)
    asset.save(update_fields=["upload_expires_at"])

    completion = api_client.post(
        f"/api/v1/patient/media/{asset.id}/complete/",
        {"sha256": hashlib.sha256(content).hexdigest(), "size": len(content), "mime": "audio/mpeg"},
        format="json",
    )

    assert completion.status_code == 409
    asset.refresh_from_db()
    assert asset.status == MediaAsset.Status.RECEIVING


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_upload_grant_uses_credential_upload_throttle_without_login_scope(api_client, patient_pair, monkeypatch):
    monkeypatch.setitem(ScopedRateThrottle.THROTTLE_RATES, "credential_upload", "1/min")
    patient, _ = patient_pair
    api_client.force_authenticate(patient.user)
    payload = {"owner_id": str(patient.id), "media_type": "singing_audio", "mime": "audio/mpeg", "size": 4}

    assert api_client.post("/api/v1/patient/media/upload-grants/", payload, format="json").status_code == 201
    response = api_client.post("/api/v1/patient/media/upload-grants/", payload, format="json")

    assert response.status_code == 429
    assert response.json()["code"] == "throttled"
