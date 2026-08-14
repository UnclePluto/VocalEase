import io

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.analysis.contracts import TransientAnalysisError
from apps.analysis.models import AnalysisResult, AnalysisTask
from apps.analysis.services import AnalysisRetryRequested, MAX_ATTEMPTS, recover_analysis_tasks, run_analysis
from apps.singing import executors
from apps.singing.models import SessionMedia
from apps.singing.services import submit_session
from apps.media.backends.qiniu import QiniuStorageBackend
from apps.media.services import STORAGE_BACKEND_FACTORIES, create_upload_grant, reissue_upload_grant

from .test_patient_api import doctor, patient, ready_song  # noqa: F401
from .test_submission_idempotency import uploaded_session


@pytest.mark.django_db
def test_confirm_upload_ignores_client_ready_claim_and_requires_trusted_receipt(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    client = APIClient()
    client.force_authenticate(patient.user)
    session = client.post(
        "/api/v1/patient/singing-sessions/", {"song_id": str(song.id)}, format="json",
    ).json()["data"]
    grant = client.post(
        f"/api/v1/patient/singing-sessions/{session['id']}/upload-grants/",
        {"media_type": "singing_audio", "mime": "audio/mpeg", "size": 6}, format="json",
    ).json()["data"]

    forged = client.post(
        f"/api/v1/patient/singing-sessions/{session['id']}/confirm-upload/",
        {"asset_id": grant["asset_id"], "status": "ready"}, format="json",
    )

    assert forged.status_code == 409
    binding = SessionMedia.objects.get(asset_id=grant["asset_id"])
    assert binding.confirmed_at is None and binding.asset.status == "uploading"

    assert client.put(grant["upload_url"], b"source", content_type="audio/mpeg").status_code == 204
    confirmed = client.post(
        f"/api/v1/patient/singing-sessions/{session['id']}/confirm-upload/",
        {"object_key": grant["object_key"]}, format="json",
    )
    duplicate = client.post(
        f"/api/v1/patient/singing-sessions/{session['id']}/confirm-upload/",
        {"asset_id": grant["asset_id"]}, format="json",
    )
    assert confirmed.status_code == duplicate.status_code == 200
    assert duplicate.json()["data"]["status"] == "uploaded"


@pytest.mark.django_db
def test_retry_endpoint_only_accepts_failed_session_and_is_idempotent(monkeypatch):
    patient, session = uploaded_session()
    task_id = submit_session(
        session_id=session.id, patient_id=patient.id, idempotency_key="submit-before-retry",
    ).task_ids[0]
    monkeypatch.setattr(
        executors.MockSingingAudioExecutor,
        "execute",
        lambda self, task, context=None: {"bad": "payload"},
    )
    run_analysis(task_id)
    session.refresh_from_db()
    assert session.status == "failed"

    client = APIClient()
    client.force_authenticate(patient.user)
    url = f"/api/v1/patient/singing-sessions/{session.id}/retry/"
    first = client.post(url, {}, format="json", HTTP_IDEMPOTENCY_KEY="retry-001")
    second = client.post(url, {}, format="json", HTTP_IDEMPOTENCY_KEY="retry-001")
    conflict = client.post(url, {}, format="json", HTTP_IDEMPOTENCY_KEY="retry-002")

    assert first.status_code == 202
    assert second.status_code == 200
    assert first.json()["data"]["analysis_task_ids"] == second.json()["data"]["analysis_task_ids"]
    assert conflict.status_code == 409
    assert conflict.json()["code"] == "singing_retry_conflict"
    session.refresh_from_db()
    task = AnalysisTask.objects.get(pk=task_id)
    assert session.status == "processing" and session.retry_generation == 1
    assert task.status == "retrying"
    assert not AnalysisResult.objects.filter(task=task).exists()


@pytest.mark.django_db
def test_retry_rejects_nonfailed_and_exhausted_sessions():
    patient, session = uploaded_session()
    client = APIClient()
    client.force_authenticate(patient.user)
    url = f"/api/v1/patient/singing-sessions/{session.id}/retry/"
    assert client.post(url, {}, HTTP_IDEMPOTENCY_KEY="retry-nonfailed").status_code == 409
    result = submit_session(session_id=session.id, patient_id=patient.id, idempotency_key="submit-exhausted")
    task = AnalysisTask.objects.get(pk=result.task_ids[0])
    task.status = "failed"
    task.attempt = MAX_ATTEMPTS
    task.save(update_fields=["status", "attempt", "updated_at"])
    session.status = "failed"
    session.save(update_fields=["status", "updated_at"])
    exhausted = client.post(url, {}, HTTP_IDEMPOTENCY_KEY="retry-exhausted")
    assert exhausted.status_code == 409
    assert exhausted.json()["code"] == "singing_retry_exhausted"


@pytest.mark.django_db
def test_new_retry_key_can_start_the_next_generation_after_another_failure():
    patient, session = uploaded_session()
    task_id = submit_session(
        session_id=session.id, patient_id=patient.id, idempotency_key="submit-generations",
    ).task_ids[0]
    AnalysisTask.objects.filter(pk=task_id).update(status="failed", attempt=1)
    type(session).objects.filter(pk=session.id).update(status="failed")
    client = APIClient()
    client.force_authenticate(patient.user)
    url = f"/api/v1/patient/singing-sessions/{session.id}/retry/"
    assert client.post(url, {}, HTTP_IDEMPOTENCY_KEY="retry-generation-1").status_code == 202
    AnalysisTask.objects.filter(pk=task_id).update(status="failed", attempt=2)
    type(session).objects.filter(pk=session.id).update(status="failed")

    second_generation = client.post(url, {}, HTTP_IDEMPOTENCY_KEY="retry-generation-2")

    assert second_generation.status_code == 202
    session.refresh_from_db()
    assert session.retry_generation == 2


@pytest.mark.django_db
def test_upload_grant_same_key_reuses_one_bound_asset(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    client = APIClient()
    client.force_authenticate(patient.user)
    session_id = client.post(
        "/api/v1/patient/singing-sessions/", {"song_id": str(song.id)}, format="json",
    ).json()["data"]["id"]
    url = f"/api/v1/patient/singing-sessions/{session_id}/upload-grants/"
    payload = {"media_type": "singing_audio", "mime": "audio/mpeg", "size": 6}

    first = client.post(url, payload, format="json", HTTP_IDEMPOTENCY_KEY="grant-001")
    second = client.post(url, payload, format="json", HTTP_IDEMPOTENCY_KEY="grant-001")

    assert first.status_code == 201 and second.status_code == 200, second.json()
    assert first.json()["data"]["asset_id"] == second.json()["data"]["asset_id"]
    assert first.json()["data"]["object_key"] == second.json()["data"]["object_key"]
    assert SessionMedia.objects.filter(session_id=session_id, media_type="singing_audio").count() == 1


@pytest.mark.django_db
def test_qiniu_idempotent_reissue_keeps_object_deadline_and_insert_only_policy(monkeypatch):
    patient, _session = uploaded_session()

    class RecordingAuth:
        def __init__(self):
            self.calls = []

        def upload_token(self, bucket, object_key, *, expires, policy, strict_policy):
            self.calls.append((bucket, object_key, expires, dict(policy), strict_policy))
            return f"token-{len(self.calls)}"

    auth = RecordingAuth()
    backend = QiniuStorageBackend(
        access_key="ak", secret_key="sk", bucket="private", domain="https://cdn.test",
        callback_url="https://api.test/callback", environment="test", auth=auth,
        bucket_manager=object(),
    )
    monkeypatch.setitem(STORAGE_BACKEND_FACTORIES, "qiniu", lambda: backend)
    asset, first = create_upload_grant(
        owner=patient, media_type="singing_audio", mime="audio/mpeg", size=6, backend=backend,
    )

    second = reissue_upload_grant(asset=asset)

    assert second.object_key == first.object_key == asset.object_key
    assert second.expires_at == asset.upload_expires_at
    assert auth.calls[-1][3]["scope"] == f"private:{asset.object_key}"
    assert auth.calls[-1][3]["insertOnly"] == 1
    assert auth.calls[-1][3]["fsizeLimit"] == 6
    assert auth.calls[-1][3]["mimeLimit"] == "audio/mpeg"
    assert auth.calls[-1][4] is True


@pytest.mark.django_db
def test_upload_grant_rejects_overlong_idempotency_key_with_stable_validation(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    client = APIClient()
    client.force_authenticate(patient.user)
    session_id = client.post(
        "/api/v1/patient/singing-sessions/", {"song_id": str(song.id)}, format="json",
    ).json()["data"]["id"]

    response = client.post(
        f"/api/v1/patient/singing-sessions/{session_id}/upload-grants/",
        {"media_type": "singing_audio", "mime": "audio/mpeg", "size": 6}, format="json",
        HTTP_IDEMPOTENCY_KEY="x" * 129,
    )

    assert response.status_code == 400
    assert response.json()["code"] == "validation_error"


@pytest.mark.django_db
def test_transient_failure_retries_without_partial_session_and_recovery_completes(monkeypatch):
    patient, session = uploaded_session()
    task_id = submit_session(
        session_id=session.id, patient_id=patient.id, idempotency_key="transient",
    ).task_ids[0]
    monkeypatch.setattr(
        executors.MockSingingAudioExecutor,
        "execute",
        lambda self, task, context=None: (_ for _ in ()).throw(TransientAnalysisError("temporary")),
    )
    with pytest.raises(AnalysisRetryRequested):
        run_analysis(task_id)
    task = AnalysisTask.objects.get(pk=task_id)
    session.refresh_from_db()
    assert task.status == "retrying" and session.status == "processing"
    assert not AnalysisResult.objects.filter(task=task).exists()
    monkeypatch.undo()
    from apps.analysis.tasks import run_analysis_task
    monkeypatch.setattr(
        run_analysis_task, "delay", lambda task_id: run_analysis_task.apply(args=[task_id]),
    )
    AnalysisTask.objects.filter(pk=task_id).update(next_dispatch_at=timezone.now())

    outcome = recover_analysis_tasks()

    task.refresh_from_db()
    session.refresh_from_db()
    assert outcome["dispatched"] == 1
    assert task.status == "succeeded" and session.status == "completed"
