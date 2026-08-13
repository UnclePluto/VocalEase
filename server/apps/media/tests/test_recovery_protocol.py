import hashlib
import io
import json
from datetime import timedelta
from threading import Event, Thread
from uuid import uuid4

import pytest
from django.core.management import call_command
from django.db import connection, connections
from django.test import override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from apps.doctors.models import SequenceCounter
from apps.doctors.services import create_doctor
from apps.media.backends.local import LocalStorageBackend
from apps.media.contracts import StorageValidationError
from apps.media.models import MediaAsset
from apps.media import services as media_services
from apps.patients.services import create_patient


@pytest.fixture
def patient(db):
    SequenceCounter.objects.bulk_create([SequenceCounter(prefix="D"), SequenceCounter(prefix="P")], ignore_conflicts=True)
    doctor = create_doctor(name="恢复医生", gender="male", phone="13600000551", department="康复科", title="医师")
    patient = create_patient(name="恢复患者", gender="female", enrollment_age=30, phone="13500000551", doctor=doctor, start_date="2026-01-01", cycle_weeks=1)
    patient.user.must_change_password = False
    patient.user.save(update_fields=["must_change_password"])
    return patient


@pytest.mark.postgresql
@pytest.mark.django_db(transaction=True)
@override_settings(MEDIA_BACKEND="local")
def test_postgresql_failed_publish_recovery_holds_row_lock_before_new_writer(patient, tmp_path, settings, monkeypatch):
    if connection.vendor != "postgresql":
        pytest.skip("补偿与新 writer 交错由 PostgreSQL 行锁证明")
    settings.MEDIA_LOCAL_ROOT = tmp_path
    asset, grant = media_services.create_upload_grant(owner=patient, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend = media_services.backend_for_asset(asset)
    nonce_a = media_services.claim_local_upload(asset=asset)
    prepared_a = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(b"aaa"), mime="audio/mpeg", asset_id=asset.id)
    original_save = MediaAsset.save
    original_recover = backend.recover_pending
    recovery_entered, release_recovery, second_finished = Event(), Event(), Event()

    def fail_staged(self, *args, **kwargs):
        if self.pk == asset.pk and self.status == MediaAsset.Status.STAGED:
            raise RuntimeError("commit failed")
        return original_save(self, *args, **kwargs)

    def pause(operation):
        def wrapped(*args, **kwargs):
            recovery_entered.set()
            assert release_recovery.wait(10)
            return operation(*args, **kwargs)
        return wrapped

    monkeypatch.setattr(MediaAsset, "save", fail_staged)
    monkeypatch.setattr(backend, "recover_pending", pause(original_recover))
    outcome = {}

    def writer_a():
        try:
            media_services.publish_local_upload(asset_id=asset.id, nonce=nonce_a, prepared=prepared_a, backend=backend)
        except RuntimeError:
            outcome["a"] = "failed"
        finally:
            connections.close_all()

    def writer_b_attempt():
        try:
            media_services.claim_local_upload(asset=asset)
        except Exception as exc:
            outcome["b_first"] = getattr(exc, "status_code", None)
        finally:
            second_finished.set(); connections.close_all()

    first = Thread(target=writer_a)
    first.start(); assert recovery_entered.wait(10)
    second = Thread(target=writer_b_attempt)
    second.start()
    assert not second_finished.wait(0.25), "新 writer 必须阻塞在恢复事务持有的 DB 行锁"
    release_recovery.set(); first.join(10); second.join(10)
    assert outcome == {"a": "failed", "b_first": 409}

    monkeypatch.setattr(MediaAsset, "save", original_save)
    monkeypatch.setattr(backend, "recover_pending", original_recover)
    MediaAsset.objects.filter(pk=asset.pk).update(upload_lease_expires_at=timezone.now() - timedelta(seconds=1))
    nonce_b = media_services.claim_local_upload(asset=asset)
    prepared_b = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(b"bbb"), mime="audio/mpeg", asset_id=asset.id)
    media_services.publish_local_upload(asset_id=asset.id, nonce=nonce_b, prepared=prepared_b, backend=backend)
    asset.refresh_from_db()
    assert backend.stat(asset.object_key, expected_generation=asset.manifest_generation).sha256 == hashlib.sha256(b"bbb").hexdigest()


def test_marker_schema_and_object_binding_cannot_delete_other_asset_blob(tmp_path):
    backend = LocalStorageBackend(root=tmp_path, signing_secret="secret", environment="test")
    asset_a, asset_b = uuid4(), uuid4()
    grant_a = backend.create_upload_grant(owner_id=asset_a, media_type="singing_audio", mime="audio/mpeg", size=3)
    grant_b = backend.create_upload_grant(owner_id=asset_b, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend.write_upload(grant=grant_b, content=b"bbb", mime="audio/mpeg", asset_id=asset_b)
    b_metadata = backend.stat(grant_b.object_key)
    prepared_a = backend.prepare_authorized_stream(object_key=grant_a.object_key, token=grant_a.upload_token, stream=io.BytesIO(b"aaa"), mime="audio/mpeg", asset_id=asset_a)
    marker = json.loads(prepared_a.pending_marker.read_text())
    marker["new"]["blob"] = b_metadata.blob
    marker["unexpected"] = True
    prepared_a.pending_marker.write_text(json.dumps(marker))

    with pytest.raises(StorageValidationError, match="标记"):
        backend.recover_pending(grant_a.object_key, asset_id=asset_a, expected_generation="")
    assert backend.stat(grant_b.object_key).sha256 == hashlib.sha256(b"bbb").hexdigest()


def test_verify_and_open_private_returns_open_fd_that_survives_unlink(tmp_path):
    backend = LocalStorageBackend(root=tmp_path, signing_secret="secret", environment="test")
    asset_id = uuid4()
    grant = backend.create_upload_grant(owner_id=asset_id, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend.write_upload(grant=grant, content=b"old", mime="audio/mpeg", asset_id=asset_id)
    metadata = backend.stat(grant.object_key)
    private = backend.create_private_url(grant.object_key, ttl_seconds=600, asset_id=asset_id, expected_generation=metadata.generation)

    source = backend.verify_and_open_private(private.token, grant.object_key, asset_id=asset_id, expected_generation=metadata.generation)
    (tmp_path / ".blobs" / metadata.blob).unlink()

    with source:
        assert source.read() == b"old"


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local", QINIU_ACCESS_KEY="", QINIU_SECRET_KEY="", QINIU_BUCKET="", QINIU_DOMAIN="", QINIU_CALLBACK_URL="")
def test_bad_qiniu_signature_is_same_403_without_configuration_or_database_lookup(monkeypatch):
    monkeypatch.setattr("apps.media.views.get_object_or_404", lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("DB must not be queried")))
    client = APIClient()
    known = client.post("/api/v1/media/qiniu/callback/", b"key=test%2Fknown&fsize=3&mime=audio%2Fmpeg&hash=e", content_type="application/x-www-form-urlencoded", HTTP_AUTHORIZATION="QBox malformed")
    unknown = client.post("/api/v1/media/qiniu/callback/", b"key=test%2Funknown&fsize=3&mime=audio%2Fmpeg&hash=e", content_type="application/x-www-form-urlencoded", HTTP_AUTHORIZATION="broken")
    assert (known.status_code, known.json()["code"]) == (403, "qiniu_callback_invalid")
    assert (unknown.status_code, unknown.json()["code"]) == (403, "qiniu_callback_invalid")
    monkeypatch.setitem(media_services.STORAGE_BACKEND_FACTORIES, "qiniu", lambda: (_ for _ in ()).throw(RuntimeError("SDK init failed")))
    sdk_failure = client.post("/api/v1/media/qiniu/callback/", b"not-a-form", content_type="application/x-www-form-urlencoded", HTTP_AUTHORIZATION="QBox malformed")
    assert (sdk_failure.status_code, sdk_failure.json()["code"]) == (403, "qiniu_callback_invalid")


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_stale_scanner_recovers_unattended_prepared_upload(patient, tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = tmp_path
    asset, grant = media_services.create_upload_grant(owner=patient, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend = media_services.backend_for_asset(asset)
    media_services.claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(b"old"), mime="audio/mpeg", asset_id=asset.id)
    MediaAsset.objects.filter(pk=asset.pk).update(upload_lease_expires_at=timezone.now() - timedelta(seconds=1))

    stats = media_services.recover_stale_local_uploads(now=timezone.now())

    asset.refresh_from_db()
    assert stats["receiving_recovered"] == 1
    assert asset.status == MediaAsset.Status.UPLOADING
    assert asset.upload_nonce is None and asset.upload_lease_expires_at is None
    assert not prepared.pending_marker.exists() and not prepared.manifest_temp.exists()
    assert not (tmp_path / ".blobs" / prepared.blob).exists()
    call_command("recover_stale_local_uploads")


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_stale_scanner_finalizes_staged_marker_and_ignores_unknown_temp(patient, tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = tmp_path
    asset, grant = media_services.create_upload_grant(owner=patient, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend = media_services.backend_for_asset(asset)
    nonce = media_services.claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(b"one"), mime="audio/mpeg", asset_id=asset.id)
    backend.publish_manifest(prepared, expected_generation="")
    MediaAsset.objects.filter(pk=asset.pk).update(
        status=MediaAsset.Status.STAGED, sha256=prepared.sha256, manifest_generation=prepared.generation,
        upload_nonce=None, upload_lease_expires_at=None,
    )
    unknown_temp = prepared.manifest_temp.parent / ".manifest-unknown-active"
    unknown_temp.write_bytes(b"do not delete")

    stats = media_services.recover_stale_local_uploads(now=timezone.now())

    assert stats["staged_finalized"] == 1
    assert not prepared.pending_marker.exists()
    assert unknown_temp.read_bytes() == b"do not delete"
