from datetime import timedelta
from uuid import uuid4

import pytest
from django.core.checks import run_checks
from django.db import IntegrityError, connection
from django.test import override_settings
from django.test.utils import CaptureQueriesContext
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts.models import Role, User
from apps.analysis import services
from apps.analysis.contracts import TransientAnalysisError
from apps.analysis.models import AnalysisResult, AnalysisTask
from apps.media.models import MediaAsset
from apps.songs.models import Song, SongAvailabilityScanState
from apps.songs import services as song_services

from .test_task5_regressions import song_with_source


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_database_attempt_budget_stops_claim_at_four(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    task = services.create_song_analysis(song=song, source_asset=asset)
    AnalysisTask.objects.filter(pk=task.id).update(status="retrying", attempt=4)

    assert services.claim_analysis_task(task.id) is None
    task.refresh_from_db()
    assert task.status == "failed"
    assert task.attempt == 4
    assert task.claim_token is None
    assert task.result == {}


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_six_redeliveries_execute_at_most_four_times(tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    task = services.create_song_analysis(song=song, source_asset=asset)
    calls = []

    def always_transient(_self, _task, *args, **kwargs):
        calls.append(_task.id)
        raise TransientAnalysisError("temporary")

    from apps.analysis.executors import MockSongExecutor
    monkeypatch.setattr(MockSongExecutor, "execute", always_transient)
    for _ in range(6):
        try:
            services.run_analysis(task.id)
        except TransientAnalysisError:
            pass

    task.refresh_from_db()
    assert task.status == "failed"
    assert task.attempt == 4
    assert len(calls) == 4


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_stale_transient_finalize_cannot_kill_new_claim(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    task = services.create_song_analysis(song=song, source_asset=asset)
    AnalysisTask.objects.filter(pk=task.id).update(attempt=2)
    old_claim = services.claim_analysis_task(task.id)
    AnalysisTask.objects.filter(pk=task.id).update(lease_expires_at=timezone.now() - timedelta(seconds=1))
    new_claim = services.claim_analysis_task(task.id)
    assert old_claim and new_claim and new_claim.claim_token != old_claim.claim_token

    outcome = services.handle_analysis_transient(task.id, old_claim.claim_token)

    task.refresh_from_db()
    assert outcome.should_retry is False
    assert task.status == "processing"
    assert task.claim_token == new_claim.claim_token
    assert task.attempt == 4


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_same_idempotency_key_dispatches_only_when_created(tmp_path, settings, monkeypatch, django_capture_on_commit_callbacks):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    sent = []
    monkeypatch.setattr(services, "schedule_analysis_task", lambda task_id: sent.append(task_id) or True)

    with django_capture_on_commit_callbacks(execute=True):
        first = services.request_song_analysis(song=song, source_asset=asset, idempotency_key="same")
        second = services.request_song_analysis(song=song, source_asset=asset, idempotency_key="same")

    assert first.id == second.id
    assert sent == [first.id]


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_dispatch_lease_suppresses_duplicate_broker_messages(tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    task = services.create_song_analysis(song=song, source_asset=asset)
    sent = []
    from apps.analysis.tasks import run_analysis_task
    monkeypatch.setattr(run_analysis_task, "delay", lambda task_id: sent.append(task_id))

    assert services.schedule_analysis_task(task.id) is True
    assert services.schedule_analysis_task(task.id) is False
    assert sent == [str(task.id)]


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local", ANALYSIS_TASK_LEASE_SECONDS=60)
def test_schedule_preserves_active_fourth_attempt_claim(tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    task = services.create_song_analysis(song=song, source_asset=asset)
    AnalysisTask.objects.filter(pk=task.id).update(attempt=3)
    claim = services.claim_analysis_task(task.id)
    assert claim is not None
    before = AnalysisTask.objects.get(pk=task.id)
    monkeypatch.setattr("apps.analysis.tasks.run_analysis_task.delay", lambda *_args: pytest.fail("有效 claim 不得重新投递"))

    assert services.schedule_analysis_task(task.id) is False

    task.refresh_from_db()
    assert task.status == AnalysisTask.Status.PROCESSING
    assert task.claim_token == before.claim_token
    assert task.lease_expires_at == before.lease_expires_at
    payload = {"protocol_version": "1.0", "is_mock": True, "artifacts": [], "metrics": {}}
    assert services.finalize_analysis_success(task.id, claim.claim_token, payload).status == AnalysisTask.Status.SUCCEEDED


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local", ANALYSIS_TASK_LEASE_SECONDS=60)
def test_broker_failure_immediately_releases_selected_expired_processing_dispatch(tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    task = services.create_song_analysis(song=song, source_asset=asset)
    claim = services.claim_analysis_task(task.id)
    expired_at = timezone.now() - timedelta(seconds=1)
    AnalysisTask.objects.filter(pk=task.id).update(lease_expires_at=expired_at)
    monkeypatch.setattr(
        "apps.analysis.tasks.run_analysis_task.delay",
        lambda *_args: (_ for _ in ()).throw(ConnectionError("redis://secret")),
    )

    assert services.schedule_analysis_task(task.id) is False

    task.refresh_from_db()
    assert task.status == AnalysisTask.Status.PROCESSING
    assert task.claim_token == claim.claim_token
    assert task.lease_expires_at == expired_at
    assert task.next_dispatch_at is None


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local", ANALYSIS_TASK_LEASE_SECONDS=60)
def test_old_broker_failure_cannot_clear_new_worker_dispatch_marker(tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    task = services.create_song_analysis(song=song, source_asset=asset)
    old_claim = services.claim_analysis_task(task.id)
    AnalysisTask.objects.filter(pk=task.id).update(lease_expires_at=timezone.now() - timedelta(seconds=1))
    marker = timezone.now() + timedelta(minutes=5)
    new_claims = []

    def claim_then_fail(*_args):
        new_claims.append(services.claim_analysis_task(task.id))
        AnalysisTask.objects.filter(pk=task.id).update(next_dispatch_at=marker)
        raise ConnectionError("redis://secret")

    monkeypatch.setattr("apps.analysis.tasks.run_analysis_task.delay", claim_then_fail)

    assert services.schedule_analysis_task(task.id) is False

    task.refresh_from_db()
    assert new_claims[0] is not None
    assert new_claims[0].claim_token != old_claim.claim_token
    assert task.claim_token == new_claims[0].claim_token
    assert task.next_dispatch_at == marker


def test_execution_context_and_automatic_lease_guard_exist():
    assert callable(getattr(services, "ExecutionContext", None))
    assert callable(getattr(services, "LeaseGuard", None))


def test_recovery_dispatch_lease_and_global_budget_fields_exist():
    field_names = {field.name for field in AnalysisTask._meta.local_fields}
    assert "next_dispatch_at" in field_names
    assert getattr(services, "MAX_ATTEMPTS", None) == 4
    assert callable(getattr(services, "recover_analysis_tasks", None))


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
@pytest.mark.parametrize("invalid_lease", [0, -1])
def test_invalid_analysis_lease_fails_closed_without_consuming_attempt(tmp_path, settings, invalid_lease):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    task = services.create_song_analysis(song=song, source_asset=asset)
    settings.ANALYSIS_TASK_LEASE_SECONDS = invalid_lease

    errors = run_checks()
    assert any(error.id == "analysis.E001" for error in errors)
    with pytest.raises(services.AnalysisConfigurationError):
        services.claim_analysis_task(task.id)
    task.refresh_from_db()
    assert task.status == AnalysisTask.Status.PENDING
    assert task.attempt == 0


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_patient_catalog_rejects_database_receipt_mutation_without_stat(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    services.create_song_analysis(song=song, source_asset=asset)
    Song.objects.filter(pk=song.id).update(publication_status="published")
    patient = User.objects.create_user(login_id="receipt-patient", password="888888", role=Role.PATIENT, must_change_password=False)
    client = APIClient()
    client.force_authenticate(patient)
    assert client.get("/api/v1/patient/songs/").json()["data"]["count"] == 1

    MediaAsset.objects.filter(pk=asset.id).update(sha256="b" * 64)

    assert client.get("/api/v1/patient/songs/").json()["data"]["count"] == 0


def test_song_receipt_snapshot_and_scan_state_contract_exist():
    song_fields = {field.name for field in Song._meta.local_fields}
    assert {
        "source_verified_backend", "source_verified_size", "source_verified_mime",
        "source_verified_sha256", "source_verified_etag", "source_verified_generation",
    } <= song_fields
    from apps.songs import models as song_models
    assert hasattr(song_models, "SongAvailabilityScanState")


@pytest.mark.django_db(transaction=True)
@override_settings(MEDIA_BACKEND="local")
def test_available_song_requires_complete_verified_receipt_snapshot(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    with pytest.raises(IntegrityError):
        Song.objects.filter(pk=song.id).update(
            source_available=True,
            source_verified_at=timezone.now(),
            source_verified_asset_id=asset.id,
            source_receipt_fingerprint="trusted",
        )


@pytest.mark.django_db
def test_analysis_status_query_count_is_constant():
    admin = User.objects.create_user(login_id="query-admin", password="888888", role=Role.SYSTEM_ADMIN, must_change_password=False)
    song = Song.objects.create(title="查询数", artist="测试", genre="流行", language="中文", duration_seconds=1)
    for index in range(6):
        task = AnalysisTask.objects.create(
            song=song, source_asset=MediaAsset.objects.create(
                owner_type="song", owner_id=song.id, media_type="song_source", backend="local",
                object_key=f"test/song_source/2026/08/14/{uuid4().hex}", mime="audio/mpeg",
                size=1, status="uploading", upload_expires_at=timezone.now() + timedelta(minutes=1),
            ),
            task_type="vocal_separation", idempotency_key=f"query-{index}", status="succeeded",
        )
        AnalysisResult.objects.create(task=task, protocol_version="1.0", is_mock=True, payload={"protocol_version": "1.0", "is_mock": True, "artifacts": [], "metrics": {}})
    client = APIClient()
    client.force_authenticate(admin)

    with CaptureQueriesContext(connection) as queries:
        response = client.get(f"/api/v1/admin/songs/{song.id}/analysis/")

    assert response.status_code == 200
    assert len(queries) <= 3


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_availability_scan_chains_all_pages(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    songs = [song_with_source()[0] for _ in range(3)]
    Song.objects.filter(pk__in=[song.id for song in songs]).update(
        source_available=False, source_verified_at=None, source_verified_asset_id=None,
        source_receipt_fingerprint="", source_verified_backend="",
        source_verified_object_key="", source_verified_size=None,
        source_verified_mime="", source_verified_sha256="",
        source_verified_etag="", source_verified_generation="",
    )
    queue = []
    dispatcher = lambda *args: queue.append(args)

    assert song_services.start_song_availability_scan(batch_size=1, dispatcher=dispatcher)
    while queue:
        token, batch_size, batch_token, batch_version = queue.pop(0)
        song_services.run_song_availability_scan_batch(
            token, batch_size=batch_size, batch_token=batch_token,
            batch_version=batch_version, dispatcher=dispatcher,
        )

    assert Song.objects.filter(pk__in=[song.id for song in songs], source_available=True).count() == 3
    state = SongAvailabilityScanState.objects.get(pk=1)
    assert state.claim_token is None
    assert state.stats["processed"] == 3


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_availability_evaluation_is_pure_until_cas_apply(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, _asset = song_with_source()
    Song.objects.filter(pk=song.id).update(
        source_available=False, source_verified_at=None, source_verified_asset_id=None,
        source_receipt_fingerprint="", source_verified_backend="",
        source_verified_object_key="", source_verified_size=None,
        source_verified_mime="", source_verified_sha256="",
        source_verified_etag="", source_verified_generation="",
    )
    song = Song.objects.select_related("source_asset").get(pk=song.id)
    before_apply = song.updated_at

    evaluation = song_services.evaluate_song_source_availability(song)

    song.refresh_from_db()
    assert evaluation.outcome == "verified"
    assert song.source_available is False
    assert song_services.apply_song_availability_evaluations([evaluation]) == {
        "verified": 1, "unavailable": 0, "deferred": 0, "processed": 1,
    }
    song.refresh_from_db()
    assert song.source_available is True
    assert song.updated_at > before_apply


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_availability_apply_skips_changed_receipt_snapshot(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    Song.objects.filter(pk=song.id).update(
        source_available=False, source_verified_at=None, source_verified_asset_id=None,
        source_receipt_fingerprint="", source_verified_backend="",
        source_verified_object_key="", source_verified_size=None,
        source_verified_mime="", source_verified_sha256="",
        source_verified_etag="", source_verified_generation="",
    )
    song = Song.objects.select_related("source_asset").get(pk=song.id)
    before_apply = song.updated_at
    evaluation = song_services.evaluate_song_source_availability(song)

    MediaAsset.objects.filter(pk=asset.id).update(status="failed")

    assert song_services.apply_song_availability_evaluations([evaluation])["processed"] == 0
    song.refresh_from_db()
    assert song.source_available is False
    assert song.updated_at == before_apply


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_availability_deferred_evaluation_preserves_last_trusted_snapshot(tmp_path, settings, monkeypatch):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    song_services.validate_source_asset(song=song, asset=asset)
    song.refresh_from_db()
    trusted_fingerprint = song.source_receipt_fingerprint
    before_apply = song.updated_at

    class TemporarilyUnavailableBackend:
        def stat(self, _object_key):
            raise RuntimeError("temporary storage outage")

    monkeypatch.setattr(
        song_services, "backend_for_asset", lambda _asset: TemporarilyUnavailableBackend(),
    )
    song = Song.objects.select_related("source_asset").get(pk=song.id)
    evaluation = song_services.evaluate_song_source_availability(song)

    assert evaluation.outcome == "deferred"
    assert song_services.apply_song_availability_evaluations([evaluation]) == {
        "verified": 0, "unavailable": 0, "deferred": 1, "processed": 1,
    }
    song.refresh_from_db()
    assert song.source_available is True
    assert song.source_receipt_fingerprint == trusted_fingerprint
    assert song.updated_at == before_apply


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_availability_deterministic_clear_updates_song_timestamp(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    song_services.validate_source_asset(song=song, asset=asset)
    song.refresh_from_db()
    before_apply = song.updated_at
    MediaAsset.objects.filter(pk=asset.id).update(status="failed")
    song = Song.objects.select_related("source_asset").get(pk=song.id)
    evaluation = song_services.evaluate_song_source_availability(song)

    assert evaluation.outcome == "unavailable"
    assert song_services.apply_song_availability_evaluations([evaluation]) == {
        "verified": 0, "unavailable": 1, "deferred": 0, "processed": 1,
    }
    song.refresh_from_db()
    assert song.source_available is False
    assert song.updated_at > before_apply


@pytest.mark.django_db
def test_availability_scan_has_singleton_lease_and_recovers_broker_failure():
    queued = []
    dispatcher = lambda *args: queued.append(args)
    assert song_services.start_song_availability_scan(batch_size=2, dispatcher=dispatcher)
    assert not song_services.start_song_availability_scan(batch_size=2, dispatcher=dispatcher)
    assert len(queued) == 1

    SongAvailabilityScanState.objects.update(
        claim_token=None, lease_expires_at=None, pending_batch_token=None,
    )

    def broken_dispatcher(*_args):
        raise ConnectionError("redis://secret")

    assert not song_services.start_song_availability_scan(batch_size=2, dispatcher=broken_dispatcher)
    assert song_services.start_song_availability_scan(batch_size=2, dispatcher=dispatcher)


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_patient_detail_revalidates_and_repairs_stale_availability_snapshot(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song, asset = song_with_source()
    services.create_song_analysis(song=song, source_asset=asset)
    Song.objects.filter(pk=song.id).update(
        publication_status="published", source_available=False,
        source_verified_at=None, source_verified_asset_id=None,
        source_receipt_fingerprint="", source_verified_backend="",
        source_verified_object_key="", source_verified_size=None,
        source_verified_mime="", source_verified_sha256="",
        source_verified_etag="", source_verified_generation="",
    )
    patient = User.objects.create_user(login_id="detail-patient", password="888888", role=Role.PATIENT, must_change_password=False)
    client = APIClient()
    client.force_authenticate(patient)

    response = client.get(f"/api/v1/patient/songs/{song.id}/")

    assert response.status_code == 200
    song.refresh_from_db()
    assert song.source_available is True
    assert song.source_verified_asset_id == asset.id
