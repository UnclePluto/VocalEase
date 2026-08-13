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
    # PostgreSQL 在同一事务中有未提交的 FK 触发器时禁止 ALTER TABLE；真实升级前先提交旧数据。
    connection.commit()
    executor.loader.build_graph()
    executor.migrate(executor.loader.graph.leaf_nodes())
    from apps.media.models import MediaAsset
    upgraded = MediaAsset.objects.get(pk=legacy.pk)
    assert upgraded.etag == "legacy-etag"
    assert upgraded.status == "ready"
