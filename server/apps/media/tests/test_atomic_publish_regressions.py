import hashlib
import io
import json
from datetime import timedelta
from uuid import uuid4

import pytest
from django.db import IntegrityError, transaction
from django.test import override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from apps.media.backends.local import LocalStorageBackend
from apps.media.contracts import StorageValidationError
from apps.media.models import MediaAsset
from apps.media.services import backend_for_asset, claim_local_upload, complete_local_asset, create_upload_grant, publish_local_upload
from apps.doctors.models import SequenceCounter
from apps.doctors.services import create_doctor
from apps.patients.services import create_patient


@pytest.fixture
def qiniu_patient(db):
    SequenceCounter.objects.bulk_create([SequenceCounter(prefix="D"), SequenceCounter(prefix="P")], ignore_conflicts=True)
    doctor = create_doctor(name="原子发布医生", gender="male", phone="13600000661", department="康复科", title="医师")
    patient = create_patient(name="原子发布患者", gender="female", enrollment_age=30, phone="13500000661", doctor=doctor, start_date="2026-01-01", cycle_weeks=1)
    patient.user.must_change_password = False
    patient.user.save(update_fields=["must_change_password"])
    return patient


def test_local_manifest_rejects_path_escape_and_invalid_schema(tmp_path):
    backend = LocalStorageBackend(root=tmp_path, signing_secret="secret", environment="test")
    key = "test/singing_audio/2026/08/14/" + uuid4().hex
    manifest_path = tmp_path / ".manifests" / f"{key}.json"
    manifest_path.parent.mkdir(parents=True)
    manifest_path.write_text(json.dumps({
        "version": 1, "generation": uuid4().hex, "blob": "../../outside",
        "mime": "audio/mpeg", "size": 3, "sha256": hashlib.sha256(b"bad").hexdigest(),
    }))

    with pytest.raises(StorageValidationError, match="清单"):
        backend.stat(key)
    with pytest.raises(StorageValidationError, match="清单"):
        backend.create_private_url(key, ttl_seconds=600, asset_id=uuid4())


def test_private_token_is_bound_to_manifest_generation_and_never_reads_replacement(tmp_path):
    backend = LocalStorageBackend(root=tmp_path, signing_secret="secret", environment="test")
    grant = backend.create_upload_grant(owner_id=uuid4(), media_type="singing_audio", mime="audio/mpeg", size=3)
    asset_id = uuid4()
    backend.write_upload(grant=grant, content=b"old", mime="audio/mpeg", asset_id=asset_id)
    old_url = backend.create_private_url(grant.object_key, ttl_seconds=600, asset_id=asset_id)
    already_open = backend.open_authorized_private(old_url.token, grant.object_key, asset_id=asset_id)

    backend.write_upload(grant=grant, content=b"new", mime="audio/mpeg", asset_id=asset_id)

    with already_open:
        assert already_open.read() == b"old"
    with pytest.raises(StorageValidationError, match="版本"):
        backend.read_private(old_url.token)
    current = backend.create_private_url(grant.object_key, ttl_seconds=600, asset_id=asset_id)
    assert backend.read_private(current.token) == b"new"
    assert len(list((tmp_path / ".blobs").iterdir())) == 1


def test_manifest_recovery_restores_previous_generation(tmp_path):
    backend = LocalStorageBackend(root=tmp_path, signing_secret="secret", environment="test")
    asset_id = uuid4()
    grant = backend.create_upload_grant(owner_id=asset_id, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend.write_upload(grant=grant, content=b"old", mime="audio/mpeg", asset_id=asset_id)
    previous = backend.stat(grant.object_key)
    prepared = backend.prepare_authorized_stream(object_key=grant.object_key, token=grant.upload_token, stream=io.BytesIO(b"new"), mime="audio/mpeg", asset_id=asset_id)
    backend.publish_manifest(prepared, expected_generation=previous.generation)

    backend.recover_pending(grant.object_key, asset_id=asset_id, expected_generation=previous.generation)

    assert backend.stat(grant.object_key).sha256 == hashlib.sha256(b"old").hexdigest()
    assert len(list((tmp_path / ".blobs").iterdir())) == 1
    assert not list((tmp_path / ".pending").iterdir())


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_db_failure_after_manifest_replace_compensates_manifest_blob_and_marker(qiniu_patient, tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = tmp_path
    asset, grant = create_upload_grant(owner=qiniu_patient, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend = backend_for_asset(asset)
    nonce = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(b"one"), mime="audio/mpeg", asset_id=asset.id)
    original_save = MediaAsset.save

    def fail_staged_save(self, *args, **kwargs):
        if self.status == MediaAsset.Status.STAGED:
            raise RuntimeError("injected DB failure")
        return original_save(self, *args, **kwargs)

    monkeypatch.setattr(MediaAsset, "save", fail_staged_save)
    with pytest.raises(RuntimeError, match="injected DB failure"):
        publish_local_upload(asset_id=asset.id, nonce=nonce, prepared=prepared, backend=backend)
    with pytest.raises(StorageValidationError, match="不存在"):
        backend.stat(asset.object_key)
    assert not list((tmp_path / ".blobs").iterdir())
    assert not list((tmp_path / ".pending").iterdir())


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_expired_receiving_lease_recovers_blob_completed_before_publish(qiniu_patient, tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = tmp_path
    asset, grant = create_upload_grant(owner=qiniu_patient, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend = backend_for_asset(asset)
    nonce_a = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(b"old"), mime="audio/mpeg", asset_id=asset.id)
    assert prepared.pending_marker.exists()
    MediaAsset.objects.filter(pk=asset.pk).update(upload_lease_expires_at=timezone.now() - timedelta(seconds=1))

    nonce_b = claim_local_upload(asset=asset)

    assert nonce_a != nonce_b
    assert not prepared.pending_marker.exists()
    assert not prepared.manifest_temp.exists()
    assert not (tmp_path / ".blobs" / prepared.blob).exists()


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_expired_lease_recovers_manifest_visible_before_db_commit(qiniu_patient, tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = tmp_path
    asset, grant = create_upload_grant(owner=qiniu_patient, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend = backend_for_asset(asset)
    nonce_a = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(b"old"), mime="audio/mpeg", asset_id=asset.id)
    published = backend.publish_manifest(prepared, expected_generation="")
    assert backend.stat(asset.object_key).generation == prepared.generation
    MediaAsset.objects.filter(pk=asset.pk).update(upload_lease_expires_at=timezone.now() - timedelta(seconds=1))

    nonce_b = claim_local_upload(asset=asset)

    assert nonce_a != nonce_b
    with pytest.raises(StorageValidationError, match="不存在"):
        backend.stat(asset.object_key)
    assert not published.prepared.pending_marker.exists()
    assert not list((tmp_path / ".blobs").iterdir())


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_complete_recovers_cleanup_marker_after_db_commit_before_finalize(qiniu_patient, tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = tmp_path
    asset, grant = create_upload_grant(owner=qiniu_patient, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend = backend_for_asset(asset)
    nonce = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(b"one"), mime="audio/mpeg", asset_id=asset.id)
    published = backend.publish_manifest(prepared, expected_generation="")
    MediaAsset.objects.filter(pk=asset.pk).update(
        status=MediaAsset.Status.STAGED, sha256=prepared.sha256, manifest_generation=prepared.generation,
        upload_nonce=None, upload_lease_expires_at=None,
    )
    asset.refresh_from_db()

    completed = complete_local_asset(asset=asset)

    assert completed.status == MediaAsset.Status.READY
    assert not published.prepared.pending_marker.exists()


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_local_api_closes_receiving_staged_ready_state_machine(qiniu_patient, tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = tmp_path
    client = APIClient(); client.force_authenticate(qiniu_patient.user)
    response = client.post("/api/v1/patient/media/upload-grants/", {
        "owner_id": str(qiniu_patient.id), "media_type": "singing_audio", "mime": "audio/mpeg", "size": 3,
    }, format="json")
    grant = response.data["data"]
    assert client.put(grant["upload_url"], b"one", content_type="audio/mpeg").status_code == 204
    asset = MediaAsset.objects.get(pk=grant["asset_id"])
    assert asset.status == MediaAsset.Status.STAGED
    assert asset.upload_nonce is None and asset.upload_lease_expires_at is None
    assert len(asset.manifest_generation) == 32
    assert asset.sha256 == hashlib.sha256(b"one").hexdigest()

    assert client.post(f"/api/v1/patient/media/{asset.id}/complete/", {}, format="json").status_code == 200
    asset.refresh_from_db()
    assert asset.status == MediaAsset.Status.READY


@pytest.mark.django_db(transaction=True)
@pytest.mark.parametrize(
    ("owner_type", "media_type"),
    [("patient", "song_source"), ("song", "singing_audio"), ("export", "waveform"), ("system", "export")],
)
def test_database_rejects_owner_media_matrix_direct_writes(qiniu_patient, owner_type, media_type):
    patient_owner = qiniu_patient if owner_type == "patient" else None
    with pytest.raises(IntegrityError), transaction.atomic():
        MediaAsset.objects.create(
            patient_owner=patient_owner, owner_type=owner_type,
            owner_id=qiniu_patient.id, media_type=media_type, backend="local",
            object_key=f"test/{media_type}/2026/08/14/{uuid4().hex}", mime="audio/mpeg", size=3,
            status=MediaAsset.Status.UPLOADING, upload_expires_at="2026-08-15T00:00:00Z",
        )


@pytest.mark.django_db(transaction=True)
@override_settings(MEDIA_BACKEND="local")
def test_database_rejects_owner_media_matrix_direct_update(qiniu_patient):
    asset, _ = create_upload_grant(owner=qiniu_patient, media_type="singing_audio", mime="audio/mpeg", size=3)
    with pytest.raises(IntegrityError), transaction.atomic():
        MediaAsset.objects.filter(pk=asset.pk).update(media_type="song_source")


@pytest.mark.django_db(transaction=True)
def test_database_rejects_invalid_upload_lease_and_ready_receipt_invariants(qiniu_patient):
    base = dict(
        patient_owner=qiniu_patient, owner_type="patient", owner_id=qiniu_patient.id,
        media_type="singing_audio", backend="local", mime="audio/mpeg", size=3,
        upload_expires_at="2026-08-15T00:00:00Z",
    )
    invalid = [
        {"status": "receiving", "upload_nonce": None, "upload_lease_expires_at": None},
        {"status": "uploading", "upload_nonce": uuid4(), "upload_lease_expires_at": "2026-08-15T00:00:00Z"},
        {"status": "ready", "sha256": "a" * 64, "manifest_generation": ""},
        {"status": "ready", "sha256": "a" * 64, "manifest_generation": "not-a-generation"},
    ]
    for index, fields in enumerate(invalid):
        with pytest.raises(IntegrityError), transaction.atomic():
            MediaAsset.objects.create(object_key=f"test/singing_audio/2026/08/14/{index:032x}", **base, **fields)


@pytest.mark.django_db
@override_settings(
    MEDIA_BACKEND="qiniu", QINIU_ACCESS_KEY="ak", QINIU_SECRET_KEY="sk", QINIU_BUCKET="bucket",
    QINIU_DOMAIN="https://cdn.example.test", QINIU_CALLBACK_URL="http://testserver/api/v1/media/qiniu/callback/?source=qiniu",
)
def test_qiniu_callback_verifies_signature_before_parsing_or_database_lookup(monkeypatch):
    monkeypatch.setattr("apps.media.views.get_object_or_404", lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("DB must not be queried")))
    response = APIClient().post(
        "/api/v1/media/qiniu/callback/?source=qiniu", b"not-even-a-form",
        content_type="application/x-www-form-urlencoded", HTTP_AUTHORIZATION="QBox invalid",
    )
    assert response.status_code == 403
