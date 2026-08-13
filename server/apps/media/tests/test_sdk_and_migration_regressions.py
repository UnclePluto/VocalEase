import json
from io import BytesIO
from uuid import uuid4

import pytest
from django.db import IntegrityError, connection
from django.db.migrations.executor import MigrationExecutor
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
def test_media_migration_upgrades_legacy_qiniu_ready_metadata_without_constraint_failure():
    SequenceCounter.objects.bulk_create([SequenceCounter(prefix="D"), SequenceCounter(prefix="P")], ignore_conflicts=True)
    doctor = create_doctor(name="迁移医生", gender="male", phone="13600000771", department="康复科", title="医师")
    current_patient = create_patient(name="迁移患者", gender="female", enrollment_age=30, phone="13500000771", doctor=doctor, start_date="2026-01-01", cycle_weeks=1)
    executor = MigrationExecutor(connection)
    executor.migrate([("media", "0001_initial")])
    old_apps = executor.loader.project_state([("media", "0001_initial")]).apps
    Patient = old_apps.get_model("patients", "PatientProfile")
    Media = old_apps.get_model("media", "MediaAsset")
    patient = Patient.objects.get(pk=current_patient.id)
    legacy = Media.objects.create(owner=patient, owner_type="patient", media_type="singing_audio", backend="qiniu", object_key="test/singing_audio/2026/08/14/legacy", mime="audio/mpeg", size=3, sha256="", status="ready", upload_expires_at="2026-08-14T00:00:00Z", metadata={"qiniu_etag": "legacy-etag"})
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
    # 反向数据迁移先把 ETag 放回 legacy metadata，再安全撤销字段。
    executor.migrate([("media", "0001_initial")])
    old_apps = executor.loader.project_state([("media", "0001_initial")]).apps
    rolled_back = old_apps.get_model("media", "MediaAsset").objects.get(pk=legacy.pk)
    assert rolled_back.metadata["qiniu_etag"] == "legacy-etag"
    executor = MigrationExecutor(connection)
    executor.migrate(executor.loader.graph.leaf_nodes())
