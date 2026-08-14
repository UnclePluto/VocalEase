import hashlib
import io
import json
import os
from datetime import timedelta
from pathlib import Path
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


def test_manifest_temp_is_bound_to_object_asset_and_generation(tmp_path):
    backend = LocalStorageBackend(root=tmp_path, signing_secret="secret", environment="test")
    asset_a, asset_b = uuid4(), uuid4()
    grant_a = backend.create_upload_grant(owner_id=asset_a, media_type="singing_audio", mime="audio/mpeg", size=3)
    grant_b = backend.create_upload_grant(owner_id=asset_b, media_type="singing_audio", mime="audio/mpeg", size=3)
    prepared_a = backend.prepare_authorized_stream(object_key=grant_a.object_key, token=grant_a.upload_token, stream=io.BytesIO(b"aaa"), mime="audio/mpeg", asset_id=asset_a)
    prepared_b = backend.prepare_authorized_stream(object_key=grant_b.object_key, token=grant_b.upload_token, stream=io.BytesIO(b"bbb"), mime="audio/mpeg", asset_id=asset_b)
    marker_a = json.loads(prepared_a.pending_marker.read_text())
    marker_a["manifest_temp"] = prepared_b.manifest_temp.name
    prepared_a.pending_marker.write_text(json.dumps(marker_a))

    with pytest.raises(StorageValidationError, match="临时文件"):
        backend.recover_pending(grant_a.object_key, asset_id=asset_a, expected_generation="")
    assert prepared_b.manifest_temp.exists()


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
@override_settings(MEDIA_BACKEND="local", QINIU_ACCESS_KEY="", QINIU_SECRET_KEY="", QINIU_BUCKET="", QINIU_DOMAIN="", QINIU_CALLBACK_URL="")
def test_qiniu_verifier_failures_log_only_safe_diagnostics(caplog):
    secret = "QBox super-secret-authorization"
    body = b"key=private%2Fpatient-key&token=body-secret"
    with caplog.at_level("WARNING", logger="apps.media.views"):
        response = APIClient().post(
            "/api/v1/media/qiniu/callback/", body,
            content_type="application/x-www-form-urlencoded", HTTP_AUTHORIZATION=secret,
            HTTP_X_REQUEST_ID="req-safe-42",
        )
    assert response.status_code == 403
    rendered = " ".join(record.getMessage() for record in caplog.records)
    assert "qiniu_callback_verifier_unavailable" in rendered
    assert "req-safe-42" in rendered
    assert "StorageValidationError" in rendered
    assert secret not in rendered and "patient-key" not in rendered and "body-secret" not in rendered


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


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_scanner_rejects_marker_claiming_real_asset_with_another_object_key(patient, tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = tmp_path
    asset, grant = media_services.create_upload_grant(owner=patient, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend = media_services.backend_for_asset(asset)
    forged_key = asset.object_key[:-32] + uuid4().hex
    # marker 自身完全合法，但其 asset_id 故意指向另一个 object_key 的真实 DB 资产。
    prepared = backend._prepare_stream(
        object_key=forged_key, stream=io.BytesIO(b"one"), mime="audio/mpeg", asset_id=asset.id,
        expected_size=3,
    )

    stats = media_services.recover_stale_local_uploads(now=timezone.now())

    asset.refresh_from_db()
    assert stats["unknown_markers"] == 1
    assert stats["ready_finalized"] == stats["staged_finalized"] == 0
    assert asset.object_key != forged_key and prepared.pending_marker.exists()


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_scanner_completes_prepared_manifest_when_database_already_has_generation(patient, tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = tmp_path
    asset, grant = media_services.create_upload_grant(owner=patient, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend = media_services.backend_for_asset(asset)
    prepared = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(b"one"), mime="audio/mpeg", asset_id=asset.id)
    # 模拟：数据库事务已提交 generation，但进程在 manifest replace 前硬崩溃。
    MediaAsset.objects.filter(pk=asset.pk).update(
        status=MediaAsset.Status.READY, sha256=prepared.sha256,
        manifest_generation=prepared.generation, upload_nonce=None, upload_lease_expires_at=None,
    )
    assert not backend._manifest_path(asset.object_key).exists()

    stats = media_services.recover_stale_local_uploads(now=timezone.now())

    assert stats["ready_finalized"] == 1
    assert backend.read_private(
        backend.create_private_url(asset.object_key, ttl_seconds=600, asset_id=asset.id, expected_generation=prepared.generation).token
    ) == b"one"
    assert not prepared.pending_marker.exists() and not prepared.manifest_temp.exists()


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
@pytest.mark.parametrize("legacy_layout", ["v0_sidecar", "v1_manifest"])
def test_scanner_recovers_legacy_conversion_crash_between_marker_and_manifest_replace(patient, tmp_path, settings, monkeypatch, legacy_layout):
    settings.MEDIA_LOCAL_ROOT = tmp_path
    asset, _ = media_services.create_upload_grant(owner=patient, media_type="singing_audio", mime="audio/mpeg", size=3)
    generation = asset.id.hex
    digest = hashlib.sha256(b"old").hexdigest()
    MediaAsset.objects.filter(pk=asset.pk).update(
        status=MediaAsset.Status.READY, sha256=digest, manifest_generation=generation,
        metadata={"migration_pending": True, "local_layout": "legacy_pending_v0_or_v1"},
    )
    asset.refresh_from_db()
    if legacy_layout == "v0_sidecar":
        legacy = tmp_path / asset.object_key; legacy.parent.mkdir(parents=True)
        legacy.write_bytes(b"old")
        legacy.with_name(f".{legacy.name}.metadata.json").write_text(json.dumps({"mime": "audio/mpeg", "size": 3, "sha256": digest}))
    else:
        old_blob = uuid4().hex
        (tmp_path / ".blobs").mkdir(parents=True)
        (tmp_path / ".blobs" / old_blob).write_bytes(b"old")
        old_manifest = tmp_path / ".manifests" / f"{asset.object_key}.json"
        old_manifest.parent.mkdir(parents=True)
        old_manifest.write_text(json.dumps({"version": 1, "generation": uuid4().hex, "blob": old_blob, "mime": "audio/mpeg", "size": 3, "sha256": digest}))
    backend = media_services.backend_for_asset(asset)
    original_replace = backend._safe_replace

    class HardCrash(BaseException):
        pass

    def crash_before_manifest(source, destination):
        if Path(destination) == backend._manifest_path(asset.object_key):
            raise HardCrash
        return original_replace(source, destination)

    monkeypatch.setattr(backend, "_safe_replace", crash_before_manifest)
    with pytest.raises(HardCrash):
        backend.migrate_legacy_layout(
            object_key=asset.object_key, asset_id=asset.id, generation=generation,
            expected_size=3, expected_mime="audio/mpeg", expected_sha256=digest,
        )
    monkeypatch.setattr(backend, "_safe_replace", original_replace)

    stats = media_services.recover_stale_local_uploads(now=timezone.now())

    assert stats["legacy_converted"] == 1
    asset.refresh_from_db()
    assert asset.metadata["migration_pending"] is False
    private = backend.create_private_url(asset.object_key, ttl_seconds=600, asset_id=asset.id, expected_generation=generation)
    assert backend.read_private(private.token) == b"old"


def test_legacy_object_parent_symlink_is_rejected_without_external_write(tmp_path):
    root = tmp_path / "media"; root.mkdir()
    outside = tmp_path / "outside"; outside.mkdir()
    (root / "test").symlink_to(outside, target_is_directory=True)
    backend = LocalStorageBackend(root=root, signing_secret="secret", environment="test")
    with pytest.raises(StorageValidationError):
        backend.migrate_legacy_layout(
            object_key=f"test/singing_audio/2026/08/14/{uuid4().hex}", asset_id=uuid4(), generation=uuid4().hex,
            expected_size=3, expected_mime="audio/mpeg", expected_sha256=hashlib.sha256(b"old").hexdigest(),
        )
    assert list(outside.iterdir()) == []


@pytest.mark.parametrize("special", [".manifests", ".blobs", ".locks", ".pending"])
def test_local_backend_rejects_symlinked_internal_directories(tmp_path, special):
    outside = tmp_path / "outside"
    root = tmp_path / "media"
    outside.mkdir(); root.mkdir()
    (root / special).symlink_to(outside, target_is_directory=True)
    backend = LocalStorageBackend(root=root, signing_secret="secret", environment="test")
    asset_id = uuid4()
    grant = backend.create_upload_grant(owner_id=asset_id, media_type="singing_audio", mime="audio/mpeg", size=3)
    with pytest.raises(StorageValidationError):
        backend.prepare_authorized_stream(object_key=grant.object_key, token=grant.upload_token, stream=io.BytesIO(b"one"), mime="audio/mpeg", asset_id=asset_id)
    with pytest.raises(StorageValidationError):
        backend.stat(grant.object_key)
    with pytest.raises(StorageValidationError):
        backend.migrate_legacy_layout(
            object_key=grant.object_key, asset_id=asset_id, generation=asset_id.hex,
            expected_size=3, expected_mime="audio/mpeg", expected_sha256=hashlib.sha256(b"one").hexdigest(),
        )
    with pytest.raises(StorageValidationError):
        backend.pending_marker_claims()
    assert list(outside.iterdir()) == []


def test_local_backend_rejects_symlinked_storage_root(tmp_path):
    outside = tmp_path / "outside"; outside.mkdir()
    linked = tmp_path / "media"; linked.symlink_to(outside, target_is_directory=True)
    with pytest.raises(StorageValidationError):
        LocalStorageBackend(root=linked, signing_secret="secret", environment="test")
    assert list(outside.iterdir()) == []


def test_local_backend_fails_closed_when_secure_dirfd_capabilities_are_missing(tmp_path, monkeypatch):
    monkeypatch.delattr("apps.media.backends.local.os.O_NOFOLLOW")
    with pytest.raises(StorageValidationError, match="安全文件能力"):
        LocalStorageBackend(root=tmp_path, signing_secret="secret", environment="test")


def test_safe_open_rejects_file_swapped_to_symlink_between_check_and_open(tmp_path):
    backend = LocalStorageBackend(root=tmp_path, signing_secret="secret", environment="test")
    asset_id = uuid4()
    grant = backend.create_upload_grant(owner_id=asset_id, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend.write_upload(grant=grant, content=b"old", mime="audio/mpeg", asset_id=asset_id)
    manifest_path = backend._manifest_path(grant.object_key)
    saved_manifest = manifest_path.with_suffix(".saved")
    outside = tmp_path.parent / f"outside-{uuid4().hex}"
    outside.write_text('{"private":"must-not-read"}')
    invoked = False

    def swap(kind, path):
        nonlocal invoked
        if not invoked and kind == "manifest":
            invoked = True
            manifest_path.replace(saved_manifest)
            manifest_path.symlink_to(outside)

    backend._safe_open_hook = swap
    try:
        with pytest.raises(StorageValidationError):
            backend.stat(grant.object_key)
        assert outside.read_text() == '{"private":"must-not-read"}'
    finally:
        manifest_path.unlink(missing_ok=True)
        if saved_manifest.exists():
            saved_manifest.replace(manifest_path)
        outside.unlink(missing_ok=True)


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_scanner_rejects_same_size_marker_whose_content_hash_disagrees_with_locked_db_receipt(patient, tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = tmp_path
    asset, grant = media_services.create_upload_grant(owner=patient, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend = media_services.backend_for_asset(asset)
    nonce = media_services.claim_local_upload(asset=asset)
    original = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(b"old"), mime="audio/mpeg", asset_id=asset.id)
    media_services.publish_local_upload(asset_id=asset.id, nonce=nonce, prepared=original, backend=backend)
    media_services.complete_local_asset(asset=asset)
    original_generation = original.generation
    original_manifest = backend._manifest_path(asset.object_key)
    saved_manifest = original_manifest.with_suffix(".trusted-original")
    original_manifest.replace(saved_manifest)
    malicious = backend._prepare_stream(object_key=asset.object_key, stream=io.BytesIO(b"bad"), mime="audio/mpeg", asset_id=asset.id, expected_size=3)
    trusted_sha = hashlib.sha256(b"old").hexdigest()
    forged_marker = json.loads(malicious.pending_marker.read_text())
    forged_marker["new"]["sha256"] = trusted_sha
    malicious.pending_marker.write_text(json.dumps(forged_marker))
    forged_temp = json.loads(malicious.manifest_temp.read_text())
    forged_temp["sha256"] = trusted_sha
    malicious.manifest_temp.write_text(json.dumps(forged_temp))
    MediaAsset.objects.filter(pk=asset.pk).update(
        status=MediaAsset.Status.READY, manifest_generation=malicious.generation,
        sha256=trusted_sha, upload_nonce=None, upload_lease_expires_at=None,
    )

    stats = media_services.recover_stale_local_uploads(now=timezone.now())

    assert stats["errors"] == 1
    assert malicious.pending_marker.exists()
    assert saved_manifest.exists() and not original_manifest.exists()
    saved_manifest.replace(original_manifest)
    assert backend.stat(asset.object_key, expected_generation=original_generation).sha256 == hashlib.sha256(b"old").hexdigest()
    MediaAsset.objects.filter(pk=asset.pk).update(manifest_generation=original_generation)
    private = backend.create_private_url(asset.object_key, ttl_seconds=600, asset_id=asset.id, expected_generation=original_generation)
    assert backend.read_private(private.token) == b"old"


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local", MEDIA_SCANNER_MAX_MARKERS=10, MEDIA_SCANNER_MAX_MARKER_BYTES=1024)
def test_scanner_bounds_marker_count_and_size_without_reading_oversized_file(patient, tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = tmp_path
    asset, _ = media_services.create_upload_grant(owner=patient, media_type="singing_audio", mime="audio/mpeg", size=3)
    backend = media_services.backend_for_asset(asset)
    prepared = backend._prepare_stream(object_key=asset.object_key, stream=io.BytesIO(b"old"), mime="audio/mpeg", asset_id=asset.id, expected_size=3)
    oversized = backend.pending_root / f"{uuid4().hex}.json"
    oversized.write_bytes(b"x" * 2048)
    (backend.pending_root / f"{uuid4().hex}.json").write_text("{}")
    (backend.pending_root / f"{uuid4().hex}.json").write_text("{}")
    (backend.pending_root / f"{uuid4().hex}.json").write_text("{}")
    original_read_text = Path.read_text

    def guarded_read_text(path, *args, **kwargs):
        if path == oversized:
            raise AssertionError("oversized marker must not be read")
        return original_read_text(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", guarded_read_text)
    monkeypatch.setitem(media_services.STORAGE_BACKEND_FACTORIES, "local", lambda: backend)

    stats = media_services.recover_stale_local_uploads(now=timezone.now())

    assert stats["oversized_markers"] == 1
    assert stats["unknown_markers"] >= 2
    assert not prepared.pending_marker.exists(), "预算内合法 marker 仍必须被隔离恢复"
    for _ in range(20):
        (backend.pending_root / f"{uuid4().hex}.json").write_text("{}")
    with override_settings(MEDIA_SCANNER_MAX_MARKERS=3):
        bounded = media_services.recover_stale_local_uploads(now=timezone.now())
    assert bounded["truncated_markers"] >= 1


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_bad_private_download_token_is_rejected_before_database_or_legacy_conversion(monkeypatch, tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = tmp_path
    monkeypatch.setattr("apps.media.views.get_object_or_404", lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("DB must not be queried")))
    monkeypatch.setattr("apps.media.views.ensure_local_asset_layout", lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("layout must not change")))
    response = APIClient().get("/api/v1/media/private/test/singing_audio/2026/08/14/00000000000000000000000000000000?signature=bad")
    assert (response.status_code, response.json()["code"]) == (403, "media_private_url_invalid")
