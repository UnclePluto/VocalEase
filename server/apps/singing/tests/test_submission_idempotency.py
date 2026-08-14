import uuid

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts.models import Role, User
from apps.analysis.models import AnalysisTask
from apps.doctors.services import create_doctor
from apps.doctors.models import SequenceCounter
from apps.media.models import MediaAsset
from apps.patients.services import create_patient, transition_treatment_plan_status
from apps.singing.models import SessionMedia, SingingSession
from apps.singing.services import submit_session
from apps.singing.services import SingingSubmissionConflict
from apps.songs.models import Song


def uploaded_session():
    SequenceCounter.objects.bulk_create(
        [SequenceCounter(prefix="D"), SequenceCounter(prefix="P")], ignore_conflicts=True,
    )
    doctor = create_doctor(
        name="提交医生", gender="male", phone="13600000201", department="康复科", title="医师",
    )
    patient = create_patient(
        name="提交患者", gender="female", enrollment_age=35, phone="13500000201",
        doctor=doctor, start_date="2026-08-01", cycle_weeks=4,
    )
    patient.user.must_change_password = False
    patient.user.save(update_fields=["must_change_password"])
    plan = patient.treatment_plans.get()
    transition_treatment_plan_status(actor=doctor.user, plan=plan, status="active", request_id="activate-submit")
    song_id = uuid.uuid4()
    source = MediaAsset.objects.create(
        owner_type="song", owner_id=song_id, media_type="song_source", backend="qiniu",
        object_key=f"test/song_source/{song_id.hex}", mime="audio/mpeg", size=1024,
        etag=f"etag-{song_id.hex}", status="ready", upload_expires_at=timezone.now(),
    )
    song = Song.objects.create(
        id=song_id, title="提交歌曲", artist="歌手", genre="流行", language="中文", duration_seconds=60,
        source_asset=source, source_available=True, source_verified_at=timezone.now(),
        source_verified_asset_id=source.id, source_receipt_fingerprint="e" * 64,
        source_verified_backend="qiniu", source_verified_object_key=source.object_key,
        source_verified_size=source.size, source_verified_mime=source.mime,
        source_verified_etag=source.etag, publication_status="published",
    )
    session = SingingSession.objects.create_from_snapshots(patient=patient, song=song)
    audio = MediaAsset.objects.create(
        patient_owner=patient, owner_type="patient", owner_id=patient.id,
        media_type="singing_audio", backend="qiniu", object_key=f"test/singing_audio/{uuid.uuid4().hex}",
        mime="audio/mpeg", size=4096, etag="audio-etag", status="ready",
        upload_expires_at=timezone.now(),
    )
    SessionMedia.objects.create(
        session=session, asset=audio, media_type="singing_audio", confirmed_at=timezone.now(),
    )
    session.status = "uploaded"
    session.save(update_fields=["status", "updated_at"])
    return patient, session


@pytest.mark.django_db
def test_submit_session_same_key_returns_same_task_and_different_key_conflicts():
    patient, session = uploaded_session()
    client = APIClient()
    client.force_authenticate(patient.user)
    url = f"/api/v1/patient/singing-sessions/{session.id}/submit/"

    first = client.post(url, {}, format="json", HTTP_IDEMPOTENCY_KEY="submit-001")
    second = client.post(url, {}, format="json", HTTP_IDEMPOTENCY_KEY="submit-001")
    conflict = client.post(url, {}, format="json", HTTP_IDEMPOTENCY_KEY="submit-002")

    assert first.status_code == 202
    assert second.status_code == 200
    assert first.json()["data"]["analysis_task_ids"] == second.json()["data"]["analysis_task_ids"]
    assert conflict.status_code == 409
    assert conflict.json()["code"] == "singing_submission_conflict"
    assert AnalysisTask.objects.filter(target_type="singing_session", target_id=session.id).count() == 1


@pytest.mark.django_db
def test_submit_creates_audio_and_empty_face_tasks_when_video_is_ready():
    patient, session = uploaded_session()
    video = MediaAsset.objects.create(
        patient_owner=patient, owner_type="patient", owner_id=patient.id,
        media_type="singing_video", backend="qiniu", object_key=f"test/singing_video/{uuid.uuid4().hex}",
        mime="video/mp4", size=8192, etag="video-etag", status="ready",
        upload_expires_at=timezone.now(),
    )
    SessionMedia.objects.create(
        session=session, asset=video, media_type="singing_video", confirmed_at=timezone.now(),
    )

    result = submit_session(session_id=session.id, patient_id=patient.id, idempotency_key="two-tasks")

    tasks = AnalysisTask.objects.filter(target_type="singing_session", target_id=session.id)
    assert set(tasks.values_list("task_type", flat=True)) == {"singing_audio_metrics", "face_landmarks"}
    assert len(result.task_ids) == 2
    assert all(task.protocol_version == "1.0" and task.executor == "mock_singing" for task in tasks)


@pytest.mark.django_db
def test_video_submit_retry_returns_task_ids_in_the_same_order():
    patient, session = uploaded_session()
    video = MediaAsset.objects.create(
        patient_owner=patient, owner_type="patient", owner_id=patient.id,
        media_type="singing_video", backend="qiniu", object_key=f"test/singing_video/{uuid.uuid4().hex}",
        mime="video/mp4", size=8192, etag="video-etag", status="ready",
        upload_expires_at=timezone.now(),
    )
    SessionMedia.objects.create(
        session=session, asset=video, media_type="singing_video", confirmed_at=timezone.now(),
    )

    first = submit_session(session_id=session.id, patient_id=patient.id, idempotency_key="stable-order")
    second = submit_session(session_id=session.id, patient_id=patient.id, idempotency_key="stable-order")

    assert first.task_ids == second.task_ids


@pytest.mark.django_db
def test_illegal_transition_and_cancel_after_submit_return_stable_conflicts():
    patient, session = uploaded_session()
    client = APIClient()
    client.force_authenticate(patient.user)
    assert client.post(
        f"/api/v1/patient/singing-sessions/{session.id}/submit/", {}, format="json",
        HTTP_IDEMPOTENCY_KEY="submitted",
    ).status_code == 202
    cancelled = client.post(f"/api/v1/patient/singing-sessions/{session.id}/cancel/", {}, format="json")
    assert cancelled.status_code == 409
    assert cancelled.json()["code"] == "singing_state_conflict"


@pytest.mark.django_db
def test_submit_rejects_preexisting_idempotency_task_with_wrong_media_binding():
    patient, session = uploaded_session()
    rogue = MediaAsset.objects.create(
        patient_owner=patient, owner_type="patient", owner_id=patient.id,
        media_type="singing_audio", backend="qiniu", object_key=f"test/singing_audio/{uuid.uuid4().hex}",
        mime="audio/mpeg", size=1024, etag="rogue-audio", status="ready",
        upload_expires_at=timezone.now(),
    )
    AnalysisTask.objects.create(
        target_type="singing_session", target_id=session.id, source_asset=rogue,
        task_type="singing_audio_metrics", executor="mock_singing", protocol_version="1.0",
        idempotency_key=f"singing:{session.id}:singing_audio_metrics",
        input_snapshot={"session_id": str(session.id), "duration_seconds": 60, "media": {}},
    )

    with pytest.raises(SingingSubmissionConflict):
        submit_session(session_id=session.id, patient_id=patient.id, idempotency_key="submit-with-collision")

    session.refresh_from_db()
    assert session.status == "uploaded" and session.submission_idempotency_key == ""
