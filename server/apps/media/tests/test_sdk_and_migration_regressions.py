import json
from io import BytesIO
from uuid import uuid4

import pytest
from django.db import IntegrityError, connection
from django.db.migrations.exceptions import IrreversibleError
from django.db.migrations.executor import MigrationExecutor
from django.db.migrations.recorder import MigrationRecorder
from django.test import override_settings
from django.conf import settings
from qiniu import Auth
from apps.doctors.models import SequenceCounter
from apps.doctors.services import create_doctor
from apps.patients.services import create_patient

from apps.media.backends.qiniu import QiniuStorageBackend


def test_qiniu_upload_token_is_produced_by_official_sdk_not_reimplemented(monkeypatch):
    monkeypatch.setattr("qiniu.auth.time.time", lambda: 1_786_648_000)
    backend = QiniuStorageBackend(access_key="ak", secret_key="sk", bucket="bucket", domain="https://cdn.example.test", callback_url="https://api.example.test/callback", environment="prod")
    grant = backend.create_upload_grant(owner_id=uuid4(), media_type="singing_audio", mime="audio/mpeg", size=3)
    expected = Auth("ak", "sk").upload_token("bucket", grant.object_key, expires=settings.MEDIA_UPLOAD_GRANT_TTL_SECONDS, policy=backend.last_policy, strict_policy=True)
    assert grant.upload_token == expected
    private_url = backend.create_private_url(grant.object_key, ttl_seconds=600)
    expected_private_url = Auth("ak", "sk").private_download_url(f"https://cdn.example.test/{grant.object_key}", expires=600)
    assert private_url.url == expected_private_url


@override_settings(QINIU_ACCESS_KEY="ak", QINIU_SECRET_KEY="sk", QINIU_BUCKET="bucket", QINIU_DOMAIN="https://cdn.example.test", QINIU_CALLBACK_URL="https://api.example.test/callback", MEDIA_ENVIRONMENT="prod")
def test_qiniu_production_factory_initializes_official_sdk_clients():
    backend = QiniuStorageBackend.from_settings()
    assert isinstance(backend.auth, Auth)
    assert backend.bucket_manager.auth is backend.auth


def test_qiniu_stat_uses_injected_official_bucket_manager_boundary():
    calls = []

    class FakeBucketManager:
        def stat(self, bucket, key):
            calls.append((bucket, key))
            return {"fsize": 3, "mimeType": "audio/mpeg", "hash": "etag"}, None

    backend = QiniuStorageBackend(access_key="ak", secret_key="sk", bucket="bucket", domain="https://cdn.example.test", callback_url="https://api.example.test/callback", environment="prod", bucket_manager=FakeBucketManager())
    metadata = backend.stat("prod/singing_audio/2026/08/14/object")
    assert calls == [("bucket", "prod/singing_audio/2026/08/14/object")]
    assert metadata.etag == "etag"


@pytest.mark.django_db(transaction=True)
def test_media_migration_upgrades_legacy_qiniu_and_local_ready_assets_at_every_boundary(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = tmp_path / "legacy-media"
    SequenceCounter.objects.bulk_create([SequenceCounter(prefix="D"), SequenceCounter(prefix="P")], ignore_conflicts=True)
    doctor = create_doctor(name="迁移医生", gender="male", phone="13600000771", department="康复科", title="医师")
    current_patient = create_patient(name="迁移患者", gender="female", enrollment_age=30, phone="13500000771", doctor=doctor, start_date="2026-01-01", cycle_weeks=1)
    MigrationRecorder(connection).record_unapplied("media", "0009_irreversible_schema_barrier")
    executor = MigrationExecutor(connection)
    executor.migrate([("media", "0001_initial")])
    old_apps = executor.loader.project_state([("media", "0001_initial")]).apps
    Patient = old_apps.get_model("patients", "PatientProfile")
    Media = old_apps.get_model("media", "MediaAsset")
    patient = Patient.objects.get(pk=current_patient.id)
    legacy = Media.objects.create(owner=patient, owner_type="patient", media_type="singing_audio", backend="qiniu", object_key="test/singing_audio/2026/08/14/legacy", mime="audio/mpeg", size=3, sha256="", status="ready", upload_expires_at="2026-08-14T00:00:00Z", metadata={"qiniu_etag": "legacy-etag"})
    local_key = "test/singing_audio/2026/08/14/legacy-local"
    local_content = b"old-local"
    local_sha = __import__("hashlib").sha256(local_content).hexdigest()
    local_asset = Media.objects.create(owner=patient, owner_type="patient", media_type="singing_audio", backend="local", object_key=local_key, mime="audio/mpeg", size=len(local_content), sha256=local_sha, status="ready", upload_expires_at="2026-08-14T00:00:00Z", metadata={})
    legacy_path = settings.MEDIA_LOCAL_ROOT / local_key
    legacy_path.parent.mkdir(parents=True)
    legacy_path.write_bytes(local_content)
    legacy_path.with_name(f".{legacy_path.name}.metadata.json").write_text(json.dumps({"mime": "audio/mpeg", "size": len(local_content), "sha256": local_sha}))
    missing_local = Media.objects.create(owner=patient, owner_type="patient", media_type="singing_audio", backend="local", object_key="test/singing_audio/2026/08/14/missing-local", mime="audio/mpeg", size=3, sha256="a" * 64, status="ready", upload_expires_at="2026-08-14T00:00:00Z", metadata={})
    escaped_key = "evil/singing_audio/2026/08/14/symlink-local"
    escaped_content = b"outside-must-not-change"
    escaped_sha = __import__("hashlib").sha256(escaped_content).hexdigest()
    escaped_local = Media.objects.create(owner=patient, owner_type="patient", media_type="singing_audio", backend="local", object_key=escaped_key, mime="audio/mpeg", size=len(escaped_content), sha256=escaped_sha, status="ready", upload_expires_at="2026-08-14T00:00:00Z", metadata={})
    outside = tmp_path / "outside"; outside_data = outside / "singing_audio/2026/08/14/symlink-local"
    outside_data.parent.mkdir(parents=True); outside_data.write_bytes(escaped_content)
    outside_data.with_name(f".{outside_data.name}.metadata.json").write_text(json.dumps({"mime": "audio/mpeg", "size": len(escaped_content), "sha256": escaped_sha}))
    (settings.MEDIA_LOCAL_ROOT / "evil").symlink_to(outside, target_is_directory=True)
    uploading = Media.objects.create(owner=patient, owner_type="patient", media_type="singing_audio", backend="local", object_key="test/singing_audio/2026/08/14/uploading", mime="audio/mpeg", size=3, sha256="", status="uploading", upload_expires_at="2026-08-14T00:00:00Z", metadata={})
    connection.commit()
    executor.loader.build_graph()
    media_nodes = sorted(node for node in executor.loader.graph.node_map if node[0] == "media" and node[1] != "0001_initial")
    for node in media_nodes:
        executor.migrate([node])
        executor = MigrationExecutor(connection)
    executor.migrate(executor.loader.graph.leaf_nodes())
    from apps.media.models import MediaAsset
    upgraded = MediaAsset.objects.get(pk=legacy.pk)
    assert upgraded.etag == "legacy-etag"
    assert upgraded.status == "ready"
    upgraded_local = MediaAsset.objects.get(pk=local_asset.pk)
    assert upgraded_local.status == "ready"
    assert upgraded_local.manifest_generation == local_asset.id.hex
    assert MediaAsset.objects.get(pk=missing_local.pk).status == "ready"
    assert MediaAsset.objects.get(pk=escaped_local.pk).status == "ready"
    assert outside_data.read_bytes() == escaped_content
    assert MediaAsset.objects.get(pk=uploading.pk).status == "uploading"
    __import__("apps.media.services", fromlist=["ensure_local_asset_layout"]).ensure_local_asset_layout(asset=upgraded_local)
    local_backend = __import__("apps.media.backends.local", fromlist=["LocalStorageBackend"]).LocalStorageBackend(root=settings.MEDIA_LOCAL_ROOT, signing_secret=settings.SECRET_KEY, environment="test")
    local_url = local_backend.create_private_url(local_key, ttl_seconds=600, asset_id=local_asset.id, expected_generation=local_asset.id.hex)
    assert local_backend.read_private(local_url.token) == local_content
    assert not legacy_path.exists()
    recovery = __import__("apps.media.services", fromlist=["recover_stale_local_uploads"]).recover_stale_local_uploads()
    assert recovery["errors"] == 2
    assert MediaAsset.objects.get(pk=missing_local.pk).status == "failed"
    assert MediaAsset.objects.get(pk=escaped_local.pk).status == "failed"
    assert outside_data.read_bytes() == escaped_content


@pytest.mark.django_db(transaction=True)
def test_media_migration_upgrades_real_0007_immutable_manifest_without_losing_ready_content(tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = tmp_path / "media-0007"
    SequenceCounter.objects.bulk_create([SequenceCounter(prefix="D"), SequenceCounter(prefix="P")], ignore_conflicts=True)
    doctor = create_doctor(name="七版医生", gender="male", phone="13600000772", department="康复科", title="医师")
    patient_now = create_patient(name="七版患者", gender="female", enrollment_age=30, phone="13500000772", doctor=doctor, start_date="2026-01-01", cycle_weeks=1)
    MigrationRecorder(connection).record_unapplied("media", "0009_irreversible_schema_barrier")
    executor = MigrationExecutor(connection)
    executor.migrate([("media", "0007_media_type_constraint")])
    old_apps = executor.loader.project_state([("media", "0007_media_type_constraint")]).apps
    Patient = old_apps.get_model("patients", "PatientProfile")
    Media = old_apps.get_model("media", "MediaAsset")
    content = b"immutable-v1"
    digest = __import__("hashlib").sha256(content).hexdigest()
    generation = uuid4().hex
    key = f"test/singing_audio/2026/08/14/{uuid4().hex}"
    asset = Media.objects.create(
        patient_owner=Patient.objects.get(pk=patient_now.id), owner_type="patient", owner_id=patient_now.id,
        media_type="singing_audio", backend="local", object_key=key, mime="audio/mpeg", size=len(content),
        sha256=digest, status="ready", upload_expires_at="2026-08-14T00:00:00Z", metadata={},
    )
    blob_root = settings.MEDIA_LOCAL_ROOT / ".blobs"; blob_root.mkdir(parents=True)
    (blob_root / generation).write_bytes(content)
    manifest = settings.MEDIA_LOCAL_ROOT / ".manifests" / f"{key}.json"; manifest.parent.mkdir(parents=True)
    manifest.write_text(json.dumps({"version": 1, "generation": generation, "blob": generation, "mime": "audio/mpeg", "size": len(content), "sha256": digest}))

    before_manifest = manifest.read_bytes()
    before_blob = (blob_root / generation).read_bytes()
    with monkeypatch.context() as migration_guard:
        migration_guard.setattr("os.replace", lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("migration must not replace files")))
        migration_guard.setattr("os.unlink", lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("migration must not unlink files")))
        executor = MigrationExecutor(connection)
        executor.migrate(executor.loader.graph.leaf_nodes())

    from apps.media.models import MediaAsset
    upgraded = MediaAsset.objects.get(pk=asset.pk)
    assert upgraded.status == "ready" and upgraded.manifest_generation == upgraded.id.hex
    assert upgraded.metadata["local_layout"] == "legacy_pending_v0_or_v1"
    assert upgraded.metadata["migration_pending"] is True
    assert manifest.read_bytes() == before_manifest
    assert (blob_root / generation).read_bytes() == before_blob
    local_backend = __import__("apps.media.backends.local", fromlist=["LocalStorageBackend"]).LocalStorageBackend(root=settings.MEDIA_LOCAL_ROOT, signing_secret=settings.SECRET_KEY, environment="test")
    __import__("apps.media.services", fromlist=["ensure_local_asset_layout"]).ensure_local_asset_layout(asset=upgraded)
    private = local_backend.create_private_url(key, ttl_seconds=600, asset_id=asset.pk, expected_generation=upgraded.id.hex)
    assert local_backend.read_private(private.token) == content
    stored = json.loads(manifest.read_text())
    assert stored["object_key"] == key and stored["asset_id"] == str(asset.pk)
    assert stored["blob"] != generation and not (blob_root / generation).exists()


@pytest.mark.django_db(transaction=True)
def test_media_data_migration_downgrade_is_declared_irreversible_and_preserves_latest_data():
    from apps.media.models import MediaAsset
    assets = []
    for owner_type, media_type, mime in [
        ("song", "song_source", "audio/mpeg"),
        ("system", "waveform", "application/json"),
        ("export", "export", "application/zip"),
    ]:
        assets.append(MediaAsset.objects.create(
            patient_owner=None, owner_type=owner_type, owner_id=uuid4(), media_type=media_type, backend="local",
            object_key=f"test/{media_type}/2026/08/14/{uuid4().hex}", mime=mime, size=3,
            status="uploading", upload_expires_at="2026-08-15T00:00:00Z",
        ))
    executor = MigrationExecutor(connection)
    with pytest.raises(IrreversibleError):
        executor.migrate([("media", "0001_initial")])
    assert {MediaAsset.objects.get(pk=asset.pk).owner_type for asset in assets} == {"song", "system", "export"}
    assert ("media", "0008_atomic_manifest_state") in MigrationExecutor(connection).loader.applied_migrations


@pytest.mark.parametrize("contents", ["empty", "patient", "generic"])
@pytest.mark.django_db(transaction=True)
def test_latest_media_schema_reverse_barrier_stops_before_any_change(contents):
    from apps.media.models import MediaAsset
    if contents == "patient":
        SequenceCounter.objects.bulk_create([SequenceCounter(prefix="D"), SequenceCounter(prefix="P")], ignore_conflicts=True)
        doctor = create_doctor(name="屏障医生", gender="male", phone="13600000779", department="康复科", title="医师")
        patient = create_patient(name="屏障患者", gender="female", enrollment_age=30, phone="13500000779", doctor=doctor, start_date="2026-01-01", cycle_weeks=1)
        MediaAsset.objects.create(patient_owner=patient, owner_type="patient", owner_id=patient.id, media_type="singing_audio", backend="local", object_key=f"test/singing_audio/2026/08/14/{uuid4().hex}", mime="audio/mpeg", size=3, status="uploading", upload_expires_at="2026-08-15T00:00:00Z")
    elif contents == "generic":
        MediaAsset.objects.create(patient_owner=None, owner_type="song", owner_id=uuid4(), media_type="song_source", backend="local", object_key=f"test/song_source/2026/08/14/{uuid4().hex}", mime="audio/mpeg", size=3, status="uploading", upload_expires_at="2026-08-15T00:00:00Z")
    recorder_before = set(MigrationRecorder(connection).applied_migrations())
    columns_before = [column.name for column in connection.introspection.get_table_description(connection.cursor(), MediaAsset._meta.db_table)]
    rows_before = list(MediaAsset.objects.order_by("id").values())
    try:
        with pytest.raises(IrreversibleError):
            MigrationExecutor(connection).migrate([("media", "0008_atomic_manifest_state")])
    finally:
        # RED 阶段旧实现可能真的回退；保证测试库恢复 latest，不污染后续测试。
        MigrationExecutor(connection).migrate(MigrationExecutor(connection).loader.graph.leaf_nodes())
    assert set(MigrationRecorder(connection).applied_migrations()) == recorder_before
    assert [column.name for column in connection.introspection.get_table_description(connection.cursor(), MediaAsset._meta.db_table)] == columns_before
    assert list(MediaAsset.objects.order_by("id").values()) == rows_before
