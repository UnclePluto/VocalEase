import io
from datetime import timedelta
from uuid import uuid4

import pytest
from django.db import models
from django.utils import timezone
from django.test import override_settings
from rest_framework.exceptions import ValidationError
from rest_framework.test import APIClient
from rest_framework.throttling import ScopedRateThrottle

from apps.accounts.models import Role, User
from apps.analysis import contracts, executors, services
from apps.analysis.models import AnalysisResult, AnalysisTask
from apps.analysis.tasks import run_analysis_task
from apps.analysis.contracts import TransientAnalysisError
from apps.media.contracts import ObjectMetadata, StorageValidationError
from apps.media.services import (claim_local_upload, complete_local_asset,
                                 create_upload_grant, get_storage_backend,
                                 publish_local_upload)
from apps.songs.models import Song, SongUploadIntent
from apps.songs import services as song_services
from apps.songs.services import (SourceAssetInvalid, SourceVerificationTemporary,
                                 create_song, publish_song, update_song)


def ready_song_source(song_id, content=b"source"):
    asset, grant = create_upload_grant(
        owner_type="song", owner_id=song_id, media_type="song_source",
        mime="audio/mpeg", size=len(content),
    )
    backend = get_storage_backend()
    nonce = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(
        object_key=asset.object_key, token=grant.upload_token,
        stream=io.BytesIO(content), mime="audio/mpeg", asset_id=asset.id,
    )
    publish_local_upload(asset_id=asset.id, nonce=nonce, prepared=prepared, backend=backend)
    return complete_local_asset(asset=asset)


def song_with_source():
    song_id = uuid4()
    asset = ready_song_source(song_id)
    song = Song.objects.create(
        id=song_id, title="隔离测试", artist="测试", genre="流行",
        language="中文", duration_seconds=60, source_asset=asset,
    )
    return song, asset


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_replacing_source_supersedes_old_tasks_and_creates_current_task(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, old_asset = song_with_source()
    old_task = services.create_song_analysis(song=song, source_asset=old_asset)
    new_asset = ready_song_source(song.id, b"new-source")
    SongUploadIntent.objects.update_or_create(song_id=song.id, defaults={"id": song.id, "asset": new_asset})

    update_song(actor=None, request_id="replace", song=song, source_asset=new_asset.id)

    old_task.refresh_from_db()
    song.refresh_from_db()
    assert old_task.status == "superseded"
    assert song.analysis_status == Song.AnalysisStatus.PENDING
    assert song.publication_status == Song.PublicationStatus.DRAFT
    assert AnalysisTask.objects.filter(song=song, source_asset=new_asset, status="pending").count() == 1


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_auto_analyze_uses_single_analysis_dispatcher_after_commit(tmp_path, settings, monkeypatch, django_capture_on_commit_callbacks):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song_id = uuid4()
    asset = ready_song_source(song_id)
    SongUploadIntent.objects.create(id=song_id, song_id=song_id, asset=asset)
    dispatched = []
    monkeypatch.setattr(services, "schedule_analysis_task", lambda task_id: dispatched.append(task_id), raising=False)

    with django_capture_on_commit_callbacks(execute=True):
        song = create_song(
            actor=None, request_id="auto", song_id=song_id, source_asset=asset.id,
            auto_analyze=True, title="自动分析", artist="测试", genre="流行",
            language="中文", duration_seconds=60,
        )

    task = AnalysisTask.objects.get(song=song)
    assert dispatched == [task.id]


@pytest.mark.django_db
def test_analysis_task_has_expiring_claim_lease_contract():
    field_names = {field.name for field in AnalysisTask._meta.local_fields}
    assert {"claim_token", "lease_expires_at", "heartbeat_at"} <= field_names
    assert callable(getattr(services, "claim_analysis_task", None))
    assert callable(getattr(services, "finalize_analysis_success", None))


def test_celery_retry_is_explicit_not_autoretry_black_box():
    assert not getattr(run_analysis_task, "autoretry_for", ())
    assert getattr(run_analysis_task, "max_retries", None) == 3


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_song_upload_grant_uses_credential_upload_throttle(tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    monkeypatch.setitem(ScopedRateThrottle.THROTTLE_RATES, "credential_upload", "1/min")
    admin = User.objects.create_user(
        login_id="song-throttle-admin", password="888888", role=Role.SYSTEM_ADMIN,
        must_change_password=False,
    )
    client = APIClient()
    client.force_authenticate(admin)
    payload = {"mime": "audio/mpeg", "size": 6}

    assert client.post("/api/v1/admin/songs/upload-grants/", payload, format="json").status_code == 201
    response = client.post("/api/v1/admin/songs/upload-grants/", payload, format="json")

    assert response.status_code == 429
    assert response.json()["code"] == "throttled"


def test_executor_registry_and_typed_result_are_single_protocol_authority():
    assert hasattr(contracts, "AnalysisPayload")
    registry = getattr(executors, "EXECUTOR_REGISTRY", {})
    assert ("vocal_separation", "mock_song", "1.0") in registry
    assert ("accompaniment_generation", "mock_song", "1.0") in registry
    assert ("lyrics_recognition", "mock_song", "1.0") in registry


def test_analysis_result_is_only_payload_truth_and_cascades_with_task():
    task_fields = {field.name for field in AnalysisTask._meta.local_fields}
    assert "result" not in task_fields
    assert AnalysisResult._meta.get_field("task").remote_field.on_delete is models.CASCADE


def test_patient_catalog_has_persistent_source_availability_index():
    field_names = {field.name for field in Song._meta.local_fields}
    assert {"source_available", "source_verified_at", "source_verified_asset_id", "source_receipt_fingerprint"} <= field_names


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local", ANALYSIS_TASK_LEASE_SECONDS=60)
def test_expired_worker_cannot_finalize_after_new_claim(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    task = services.create_song_analysis(song=song, source_asset=asset)
    old_claim = services.claim_analysis_task(task.id)
    assert old_claim is not None
    AnalysisTask.objects.filter(pk=task.id).update(lease_expires_at=timezone.now() - timedelta(seconds=1))

    new_claim = services.claim_analysis_task(task.id)
    assert new_claim is not None and new_claim.claim_token != old_claim.claim_token
    payload = {"protocol_version": "1.0", "is_mock": True, "artifacts": [], "metrics": {}}
    stale = services.finalize_analysis_success(task.id, old_claim.claim_token, payload)
    assert stale.status == "processing"
    assert stale.claim_token == new_claim.claim_token

    completed = services.finalize_analysis_success(task.id, new_claim.claim_token, payload)
    assert completed.status == "succeeded"
    assert completed.attempt == 2


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local", CELERY_TASK_ALWAYS_EAGER=True, CELERY_TASK_EAGER_PROPAGATES=False)
def test_explicit_celery_retry_runs_initial_plus_three_attempts(tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    task = services.create_song_analysis(song=song, source_asset=asset)
    calls = []

    def transient_then_success(_self, _task, **_kwargs):
        calls.append(_task.id)
        if len(calls) < 4:
            raise TransientAnalysisError("temporary secret must not leak")
        return {"protocol_version": "1.0", "is_mock": True, "artifacts": [], "metrics": {"attempt": 4}}

    monkeypatch.setattr(executors.MockSongExecutor, "execute", transient_then_success)
    result = run_analysis_task.delay(str(task.id))

    assert result.successful()
    task.refresh_from_db()
    assert task.status == "succeeded"
    assert task.attempt == 4
    assert len(calls) == 4
    assert task.result["metrics"] == {"attempt": 4}


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local", CELERY_TASK_ALWAYS_EAGER=True, CELERY_TASK_EAGER_PROPAGATES=False)
def test_permanent_executor_error_fails_once_without_partial_result(tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    task = services.create_song_analysis(song=song, source_asset=asset)
    calls = []

    def invalid_result(_self, _task, **_kwargs):
        calls.append(_task.id)
        return {"protocol_version": "1.0", "is_mock": True, "artifacts": [{"fake": True}], "metrics": {}}

    monkeypatch.setattr(executors.MockSongExecutor, "execute", invalid_result)
    result = run_analysis_task.delay(str(task.id))

    assert result.successful()
    task.refresh_from_db()
    assert task.status == "failed"
    assert task.error_code == "analysis_result_invalid"
    assert task.result == {}
    assert not AnalysisResult.objects.filter(task=task).exists()
    assert len(calls) == 1


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_auto_false_does_not_dispatch_and_broker_failure_is_contained(tmp_path, settings, monkeypatch, django_capture_on_commit_callbacks):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    dispatched = []
    monkeypatch.setattr(services, "schedule_analysis_task", lambda task_id: dispatched.append(task_id))
    song_id = uuid4()
    asset = ready_song_source(song_id)
    SongUploadIntent.objects.create(id=song_id, song_id=song_id, asset=asset)
    with django_capture_on_commit_callbacks(execute=True):
        create_song(actor=None, request_id="manual", song_id=song_id, source_asset=asset.id, auto_analyze=False, title="手动", artist="测试", genre="流行", language="中文", duration_seconds=60)
    assert dispatched == []

    song = Song.objects.get(pk=song_id)
    monkeypatch.setattr(services, "schedule_analysis_task", lambda task_id: False)
    with django_capture_on_commit_callbacks(execute=True):
        task = services.request_song_analysis(song=song, source_asset=asset)
    task.refresh_from_db()
    assert task.status == "pending"


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_reanalyze_api_contains_broker_failure(tmp_path, settings, monkeypatch, django_capture_on_commit_callbacks):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, _asset = song_with_source()
    admin = User.objects.create_user(login_id="broker-admin", password="888888", role=Role.SYSTEM_ADMIN, must_change_password=False)
    monkeypatch.setattr(run_analysis_task, "delay", lambda *_args, **_kwargs: (_ for _ in ()).throw(ConnectionError("redis://secret")))
    client = APIClient()
    client.force_authenticate(admin)

    with django_capture_on_commit_callbacks(execute=True):
        response = client.post(f"/api/v1/admin/songs/{song.id}/reanalyze/", {"task_type": "vocal_separation"}, format="json")

    assert response.status_code == 202
    task = AnalysisTask.objects.get(song=song)
    assert task.status == "pending"


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_protocol_registry_rejects_unknown_combination_and_mock_artifacts(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    with pytest.raises(ValidationError):
        services.create_song_analysis(song=song, source_asset=asset, task_type="unknown")
    assert AnalysisTask.objects.count() == 0


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_temporary_verification_keeps_last_availability_but_mismatch_clears_it(tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    Song.objects.filter(pk=song.id).update(
        source_available=True, source_verified_at=timezone.now(),
        source_verified_asset_id=asset.id, source_receipt_fingerprint="trusted",
        source_verified_backend=asset.backend, source_verified_object_key=asset.object_key,
        source_verified_size=asset.size, source_verified_mime=asset.mime,
        source_verified_sha256=asset.sha256, source_verified_etag=asset.etag,
        source_verified_generation=asset.manifest_generation,
    )

    class TemporaryBackend:
        def stat(self, _object_key):
            raise StorageValidationError("七牛对象状态查询失败")

    asset.backend = "qiniu"
    asset.etag = "trusted-etag"
    monkeypatch.setattr(song_services, "backend_for_asset", lambda _asset: TemporaryBackend())
    with pytest.raises(SourceVerificationTemporary):
        song_services.validate_source_asset(song=song, asset=asset)
    song.refresh_from_db()
    assert song.source_available is True
    assert song.source_receipt_fingerprint == "trusted"

    class MismatchBackend:
        def stat(self, object_key):
            return ObjectMetadata(object_key, asset.size + 1, asset.mime, etag=asset.etag)

    monkeypatch.setattr(song_services, "backend_for_asset", lambda _asset: MismatchBackend())
    with pytest.raises(SourceAssetInvalid):
        song_services.validate_source_asset(song=song, asset=asset)
    song.refresh_from_db()
    assert song.source_available is False
    assert song.source_receipt_fingerprint == ""


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_patient_catalog_paginates_without_storage_stat(tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    for index in range(2):
        song, _asset = song_with_source()
        Song.objects.filter(pk=song.id).update(
            publication_status=Song.PublicationStatus.PUBLISHED,
            source_available=True,
            source_verified_at=timezone.now(),
            source_verified_asset_id=_asset.id,
            source_receipt_fingerprint=f"trusted-{index}",
            source_verified_backend=_asset.backend,
            source_verified_object_key=_asset.object_key,
            source_verified_size=_asset.size,
            source_verified_mime=_asset.mime,
            source_verified_sha256=_asset.sha256,
            source_verified_etag=_asset.etag,
            source_verified_generation=_asset.manifest_generation,
        )
    patient = User.objects.create_user(login_id="catalog-patient", password="888888", role=Role.PATIENT, must_change_password=False)
    monkeypatch.setattr(song_services, "backend_for_asset", lambda _asset: (_ for _ in ()).throw(AssertionError("列表不得 stat")))
    client = APIClient()
    client.force_authenticate(patient)

    response = client.get("/api/v1/patient/songs/?page=1&page_size=1")

    assert response.status_code == 200
    assert response.json()["data"]["count"] == 2
    assert len(response.json()["data"]["results"]) == 1
