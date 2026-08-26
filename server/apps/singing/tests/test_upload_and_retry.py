import io
import json
from datetime import datetime, timedelta, timezone as datetime_timezone
import uuid

import pytest
from django.utils import timezone
from rest_framework.test import APIClient
from qiniu import Auth

from apps.analysis.contracts import TransientAnalysisError
from apps.analysis.models import AnalysisResult, AnalysisTask
from apps.analysis.services import AnalysisRetryRequested, MAX_ATTEMPTS, recover_analysis_tasks, run_analysis
from apps.singing import executors
from apps.singing.models import SessionMedia, SingingSession
from apps.singing.services import submit_session
from apps.media.backends.local import LocalStorageBackend
from apps.media.backends.qiniu import QiniuStorageBackend
from apps.media.contracts import StorageValidationError
from apps.media.services import (
    MediaConflict,
    STORAGE_BACKEND_FACTORIES,
    create_upload_grant,
    reissue_upload_grant,
)

from .test_patient_api import doctor, patient, ready_song  # noqa: F401
from .test_submission_idempotency import uploaded_session


@pytest.mark.django_db
def test_confirm_upload_ignores_client_ready_claim_and_requires_trusted_receipt(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    client = APIClient()
    client.force_authenticate(patient.user)
    session = client.post(
        "/api/v1/patient/singing-sessions/", {"song_id": str(song.id)}, format="json",
        HTTP_IDEMPOTENCY_KEY="confirm-receipt-create",
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
    retry_task = AnalysisTask.objects.get(pk=first.json()["data"]["analysis_task_ids"][0])
    assert session.status == "processing" and session.retry_generation == 1
    assert task.status == "failed" and retry_task.status == "pending"
    assert task.generation == 0 and retry_task.generation == 1
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
    first_generation = client.post(url, {}, HTTP_IDEMPOTENCY_KEY="retry-generation-1")
    assert first_generation.status_code == 202
    current_task_id = first_generation.json()["data"]["analysis_task_ids"][0]
    AnalysisTask.objects.filter(pk=current_task_id).update(status="failed", attempt=2)
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
        HTTP_IDEMPOTENCY_KEY="upload-reuse-create",
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
            self.signed_policies = []

        def upload_token(self, bucket, object_key, *, expires, policy, strict_policy):
            self.calls.append((bucket, object_key, expires, dict(policy), strict_policy))
            return f"token-{len(self.calls)}"

        def token_with_data(self, data):
            self.signed_policies.append(json.loads(data))
            return f"reissued-token-{len(self.signed_policies)}"

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
    policy = auth.signed_policies[-1]
    assert policy["scope"] == f"private:{asset.object_key}"
    assert policy["insertOnly"] == 1
    assert policy["fsizeLimit"] == 6
    assert policy["mimeLimit"] == "audio/mpeg"
    assert policy["deadline"] == int(asset.upload_expires_at.timestamp())


@pytest.mark.django_db
def test_upload_grant_rejects_overlong_idempotency_key_with_stable_validation(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    client = APIClient()
    client.force_authenticate(patient.user)
    session_id = client.post(
        "/api/v1/patient/singing-sessions/", {"song_id": str(song.id)}, format="json",
        HTTP_IDEMPOTENCY_KEY="overlong-grant-create",
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


@pytest.mark.parametrize("session_status", ["uploaded", "processing", "failed", "cancelled"])
@pytest.mark.django_db
def test_same_upload_key_cannot_reissue_in_session_state_that_disallows_upload(
    session_status,
    patient,
    tmp_path,
    settings,
):
    song = ready_song(tmp_path, settings)
    client = APIClient()
    client.force_authenticate(patient.user)
    session_id = client.post(
        "/api/v1/patient/singing-sessions/",
        {"song_id": str(song.id)},
        format="json",
        HTTP_IDEMPOTENCY_KEY=f"state-bound-create-{session_status}",
    ).json()["data"]["id"]
    url = f"/api/v1/patient/singing-sessions/{session_id}/upload-grants/"
    payload = {"media_type": "singing_audio", "mime": "audio/mpeg", "size": 6}
    assert client.post(
        url,
        payload,
        format="json",
        HTTP_IDEMPOTENCY_KEY="state-bound-grant",
    ).status_code == 201
    SingingSession.objects.filter(pk=session_id).update(status=session_status)

    repeated = client.post(
        url,
        payload,
        format="json",
        HTTP_IDEMPOTENCY_KEY="state-bound-grant",
    )

    assert repeated.status_code == 409
    assert repeated.json()["code"] == "singing_state_conflict"


@pytest.mark.parametrize("asset_status", ["ready", "receiving", "staged", "failed", "pending_cleanup"])
@pytest.mark.django_db
def test_only_uploading_asset_can_reissue_upload_credential(
    asset_status,
    patient,
    tmp_path,
    settings,
):
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    asset, _grant = create_upload_grant(
        owner=patient,
        media_type="singing_audio",
        mime="audio/mpeg",
        size=6,
    )
    updates = {"status": asset_status}
    if asset_status in {"ready", "staged"}:
        updates.update(sha256="a" * 64, manifest_generation=uuid.uuid4().hex)
    if asset_status == "receiving":
        updates.update(
            upload_nonce=uuid.uuid4(),
            upload_lease_expires_at=timezone.now() + timedelta(minutes=5),
        )
    type(asset).objects.filter(pk=asset.id).update(**updates)
    asset.refresh_from_db()

    with pytest.raises(MediaConflict):
        reissue_upload_grant(asset=asset)


@pytest.mark.django_db
def test_ready_bound_asset_repeats_as_confirmation_without_new_upload_signature(
    patient,
    tmp_path,
    settings,
):
    song = ready_song(tmp_path, settings)
    client = APIClient()
    client.force_authenticate(patient.user)
    session_id = client.post(
        "/api/v1/patient/singing-sessions/",
        {"song_id": str(song.id)},
        format="json",
        HTTP_IDEMPOTENCY_KEY="ready-grant-create",
    ).json()["data"]["id"]
    url = f"/api/v1/patient/singing-sessions/{session_id}/upload-grants/"
    payload = {"media_type": "singing_audio", "mime": "audio/mpeg", "size": 6}
    first = client.post(
        url,
        payload,
        format="json",
        HTTP_IDEMPOTENCY_KEY="ready-grant",
    ).json()["data"]
    binding = SessionMedia.objects.get(asset_id=first["asset_id"])
    type(binding.asset).objects.filter(pk=binding.asset_id).update(
        status="ready",
        sha256="a" * 64,
        manifest_generation=uuid.uuid4().hex,
    )
    SingingSession.objects.filter(pk=session_id).update(status="uploaded")

    repeated = client.post(
        url,
        payload,
        format="json",
        HTTP_IDEMPOTENCY_KEY="ready-grant",
    )

    assert repeated.status_code == 200
    assert repeated.json()["data"]["asset_id"] == first["asset_id"]
    assert repeated.json()["data"]["upload_url"] == ""
    assert repeated.json()["data"]["upload_token"] == ""


def test_local_reissued_signature_never_exceeds_database_deadline(tmp_path, monkeypatch):
    fixed_now = timezone.now()
    database_deadline = fixed_now + timedelta(seconds=2, microseconds=200_000)
    monkeypatch.setattr("apps.media.backends.local.timezone.now", lambda: fixed_now)
    root = tmp_path / "local-deadline"
    root.mkdir()
    backend = LocalStorageBackend(
        root=root,
        signing_secret="deadline-secret",
        environment="test",
    )

    grant = backend.reissue_upload_grant(
        object_key=f"test/singing_audio/2026/08/14/{uuid.uuid4().hex}",
        owner_id=uuid.uuid4(),
        media_type="singing_audio",
        mime="audio/mpeg",
        size=6,
        expires_at=database_deadline,
    )

    payload = backend.signer.unsign_object(grant.upload_token)
    assert payload["expires_at"] <= database_deadline.timestamp()
    assert grant.expires_at == database_deadline


def test_qiniu_reissued_token_uses_database_absolute_deadline(monkeypatch):
    fixed_now = timezone.now().replace(microsecond=0)
    database_deadline = fixed_now + timedelta(seconds=2, microseconds=200_000)

    class RecordingAuth:
        def __init__(self):
            self.policy = None

        def token_with_data(self, data):
            self.policy = json.loads(data)
            return "deadline-token"

    auth = RecordingAuth()
    backend = QiniuStorageBackend(
        access_key="ak",
        secret_key="sk",
        bucket="private",
        domain="https://cdn.test",
        callback_url="https://api.test/callback",
        environment="test",
        auth=auth,
        bucket_manager=object(),
    )
    monkeypatch.setattr("apps.media.backends.qiniu.timezone.now", lambda: fixed_now)

    grant = backend.reissue_upload_grant(
        object_key=f"test/singing_audio/2026/08/14/{uuid.uuid4().hex}",
        owner_id=uuid.uuid4(),
        media_type="singing_audio",
        mime="audio/mpeg",
        size=6,
        expires_at=database_deadline,
    )

    assert auth.policy["deadline"] == int(database_deadline.timestamp())
    assert grant.expires_at == database_deadline


@pytest.mark.parametrize(
    ("now_offset", "expires_offset", "should_issue"),
    [
        (0.1, 0.9, False),
        (0.9, 1.1, True),
        (0.1, 120.8, True),
        (0.5, 0.4, False),
    ],
)
def test_qiniu_real_auth_reissue_requires_usable_absolute_deadline(
    now_offset,
    expires_offset,
    should_issue,
    monkeypatch,
):
    epoch = 1_786_700_000
    fixed_now = datetime.fromtimestamp(epoch + now_offset, tz=datetime_timezone.utc)
    database_deadline = datetime.fromtimestamp(
        epoch + expires_offset,
        tz=datetime_timezone.utc,
    )
    auth = Auth("ak", "sk")
    backend = QiniuStorageBackend(
        access_key="ak", secret_key="sk", bucket="private", domain="https://cdn.test",
        callback_url="https://api.test/callback", environment="test", auth=auth,
        bucket_manager=object(),
    )
    monkeypatch.setattr("apps.media.backends.qiniu.timezone.now", lambda: fixed_now)

    def issue():
        return backend.reissue_upload_grant(
            object_key="test/singing_audio/2026/08/14/usable-deadline",
            owner_id=uuid.uuid4(),
            media_type="singing_audio",
            mime="audio/mpeg",
            size=6,
            expires_at=database_deadline,
        )
    if not should_issue:
        with pytest.raises(StorageValidationError):
            issue()
        return

    grant = issue()

    _ak, _signature, policy = Auth.up_token_decode(grant.upload_token)
    assert policy["deadline"] == int(database_deadline.timestamp())
    assert policy["deadline"] > int(fixed_now.timestamp())
    assert policy["deadline"] <= database_deadline.timestamp()
    assert policy["scope"] == "private:test/singing_audio/2026/08/14/usable-deadline"
    assert policy["insertOnly"] == 1
    assert policy["fsizeLimit"] == 6
    assert policy["mimeLimit"] == "audio/mpeg"


def test_qiniu_absolute_deadline_reissue_fails_closed_without_positive_time(monkeypatch):
    fixed_now = datetime.fromtimestamp(1_786_700_000.5, tz=datetime_timezone.utc)
    backend = QiniuStorageBackend(
        access_key="ak", secret_key="sk", bucket="private", domain="https://cdn.test",
        callback_url="https://api.test/callback", environment="test", auth=Auth("ak", "sk"),
        bucket_manager=object(),
    )
    monkeypatch.setattr("apps.media.backends.qiniu.timezone.now", lambda: fixed_now)

    with pytest.raises(StorageValidationError):
        backend.reissue_upload_grant(
            object_key="test/singing_audio/2026/08/14/expired",
            owner_id=uuid.uuid4(), media_type="singing_audio", mime="audio/mpeg", size=6,
            expires_at=fixed_now,
        )
