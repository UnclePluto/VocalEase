import uuid

import pytest
from django.db import IntegrityError, transaction
from django.utils import timezone
from rest_framework.test import APIClient

from apps.analysis.models import AnalysisResult, AnalysisTask
from apps.analysis.services import run_analysis
from apps.media.models import MediaAsset
from apps.singing.executors import mock_face_result, mock_singing_result
from apps.singing.models import AnalysisTimeSeries, SessionMedia, SingingSession

from .test_submission_idempotency import uploaded_session


def _add_ready_video(patient, session):
    video = MediaAsset.objects.create(
        patient_owner=patient,
        owner_type="patient",
        owner_id=patient.id,
        media_type="singing_video",
        backend="qiniu",
        object_key=f"test/singing_video/{uuid.uuid4().hex}",
        mime="video/mp4",
        size=8192,
        etag="video-ready",
        status="ready",
        upload_expires_at=timezone.now(),
    )
    SessionMedia.objects.create(
        session=session,
        asset=video,
        media_type="singing_video",
        confirmed_at=timezone.now(),
    )
    return video


@pytest.mark.django_db
def test_audio_mock_is_deterministic_and_face_result_has_no_invented_findings():
    task_id = uuid.UUID("12345678-1234-5678-1234-567812345678")

    first = mock_singing_result(task_id, duration_seconds=60)
    second = mock_singing_result(task_id, duration_seconds=60)

    assert first == second
    assert first["protocol_version"] == "1.0"
    assert first["is_mock"] is True
    assert 68 <= first["score"] <= 96
    assert sorted(first["burp_events"]) == first["burp_events"]
    assert set(first["series"]) == {"volume", "pitch_hz", "snr_db"}
    assert mock_face_result(task_id) == {
        "protocol_version": "1.0", "is_mock": True, "landmarks": [],
    }


@pytest.mark.django_db
def test_running_audio_analysis_atomically_completes_session_and_time_series():
    patient, session = uploaded_session()
    from apps.singing.services import submit_session
    result = submit_session(session_id=session.id, patient_id=patient.id, idempotency_key="run-audio")

    task = run_analysis(result.task_ids[0])

    task.refresh_from_db()
    session.refresh_from_db()
    stored = AnalysisResult.objects.get(task=task)
    assert task.status == "succeeded"
    assert session.status == "completed"
    assert stored.protocol_version == "1.0" and stored.is_mock is True
    assert session.score == stored.payload["score"]
    assert session.burp_count == len(stored.payload["burp_events"])
    assert session.duration_seconds == 60
    assert set(AnalysisTimeSeries.objects.filter(task=task).values_list("metric_type", flat=True)) == {
        "volume", "pitch_hz", "snr_db",
    }


@pytest.mark.django_db
def test_face_analysis_returns_empty_landmarks_and_only_last_task_completes_session():
    patient, session = uploaded_session()
    from apps.media.models import MediaAsset
    from apps.singing.models import SessionMedia
    from django.utils import timezone
    video = MediaAsset.objects.create(
        patient_owner=patient, owner_type="patient", owner_id=patient.id,
        media_type="singing_video", backend="qiniu", object_key=f"test/singing_video/{uuid.uuid4().hex}",
        mime="video/mp4", size=1024, etag="video-ready", status="ready", upload_expires_at=timezone.now(),
    )
    SessionMedia.objects.create(
        session=session, asset=video, media_type="singing_video", confirmed_at=timezone.now(),
    )
    from apps.singing.services import submit_session
    submit_session(session_id=session.id, patient_id=patient.id, idempotency_key="run-two")
    audio_task = AnalysisTask.objects.get(target_id=session.id, task_type="singing_audio_metrics")
    face_task = AnalysisTask.objects.get(target_id=session.id, task_type="face_landmarks")

    run_analysis(audio_task.id)
    session.refresh_from_db()
    assert session.status == "processing"
    run_analysis(face_task.id)

    session.refresh_from_db()
    assert session.status == "completed"
    assert AnalysisResult.objects.get(task=face_task).payload == {
        "protocol_version": "1.0", "is_mock": True, "landmarks": [],
    }


@pytest.mark.django_db
def test_invalid_singing_result_fails_without_partial_result(monkeypatch):
    patient, session = uploaded_session()
    from apps.singing import executors
    from apps.singing.services import submit_session
    task_id = submit_session(
        session_id=session.id, patient_id=patient.id, idempotency_key="invalid-result",
    ).task_ids[0]
    monkeypatch.setattr(
        executors.MockSingingAudioExecutor,
        "execute",
        lambda self, task, context=None: {"protocol_version": "1.0", "is_mock": True, "score": 90},
    )

    task = run_analysis(task_id)

    session.refresh_from_db()
    assert task.status == "failed"
    assert task.error_code == "analysis_result_invalid"
    assert session.status == "failed"
    assert session.score is None and session.burp_count is None and session.completed_at is None
    assert not AnalysisResult.objects.filter(task_id=task_id).exists()
    assert not AnalysisTimeSeries.objects.filter(task_id=task_id).exists()


@pytest.mark.django_db(transaction=True)
def test_database_rejects_completed_session_without_mock_result_and_invalid_target_contract():
    patient, session = uploaded_session()
    with pytest.raises(IntegrityError), transaction.atomic():
        SingingSession.objects.filter(pk=session.id).update(status="completed")
    audio = session.media_bindings.get(media_type="singing_audio").asset
    with pytest.raises(IntegrityError), transaction.atomic():
        AnalysisTask.objects.create(
            target_type="singing_session", target_id=session.id, song=session.song,
            source_asset=audio, task_type="singing_audio_metrics", executor="mock_singing",
            idempotency_key="invalid-target",
        )


@pytest.mark.django_db(transaction=True)
def test_database_rejects_duplicate_singing_task_in_same_generation():
    patient, session = uploaded_session()
    from apps.singing.services import submit_session

    submit_session(
        session_id=session.id,
        patient_id=patient.id,
        idempotency_key="unique-generation",
    )
    original = AnalysisTask.objects.get(
        target_id=session.id,
        generation=0,
        task_type=AnalysisTask.TaskType.SINGING_AUDIO_METRICS,
    )

    with pytest.raises(IntegrityError), transaction.atomic():
        AnalysisTask.objects.create(
            target_type=original.target_type,
            target_id=original.target_id,
            source_asset=original.source_asset,
            task_type=original.task_type,
            executor=original.executor,
            generation=original.generation,
            idempotency_key="duplicate-generation-direct-db",
            input_snapshot=original.input_snapshot,
        )


@pytest.mark.django_db
def test_result_and_time_series_have_no_writable_generation_second_truth():
    patient, session = uploaded_session()
    from apps.singing.services import submit_session

    task_id = submit_session(
        session_id=session.id,
        patient_id=patient.id,
        idempotency_key="single-generation-truth",
    ).task_ids[0]
    task = AnalysisTask.objects.get(pk=task_id)

    with pytest.raises(TypeError):
        AnalysisResult(
            task=task,
            protocol_version="1.0",
            is_mock=True,
            generation=99,
            payload={},
        )
    with pytest.raises(TypeError):
        AnalysisTimeSeries(
            session=session,
            task=task,
            metric_type="volume",
            generation=99,
            sample_interval_ms=1000,
            values=[],
        )


@pytest.mark.django_db
def test_one_failed_task_atomically_hides_and_clears_successful_generation(monkeypatch):
    patient, session = uploaded_session()
    _add_ready_video(patient, session)
    from apps.singing import executors
    from apps.singing.services import submit_session

    submitted = submit_session(
        session_id=session.id,
        patient_id=patient.id,
        idempotency_key="atomic-generation-failure",
    )
    audio = AnalysisTask.objects.get(target_id=session.id, task_type="singing_audio_metrics")
    face = AnalysisTask.objects.get(target_id=session.id, task_type="face_landmarks")
    run_analysis(audio.id)
    monkeypatch.setattr(
        executors.MockFaceLandmarksExecutor,
        "execute",
        lambda self, task, context=None: {"bad": "payload"},
    )

    run_analysis(face.id)

    session.refresh_from_db()
    client = APIClient()
    client.force_authenticate(patient.user)
    detail = client.get(f"/api/v1/patient/singing-sessions/{session.id}/").json()["data"]
    assert session.status == "failed"
    assert not AnalysisResult.objects.filter(task__target_id=session.id).exists()
    assert not AnalysisTimeSeries.objects.filter(session=session).exists()
    assert all(row["payload"] is None and row["time_series"] == {} for row in detail["analysis_results"])


@pytest.mark.django_db
def test_retry_creates_fresh_generation_and_fences_late_previous_worker(monkeypatch):
    patient, session = uploaded_session()
    _add_ready_video(patient, session)
    from apps.singing import executors
    from apps.singing.services import (
        claim_singing_analysis_task,
        finalize_singing_success,
        retry_session,
        submit_session,
    )

    first = submit_session(
        session_id=session.id,
        patient_id=patient.id,
        idempotency_key="generation-zero",
    )
    old_audio = AnalysisTask.objects.get(
        target_id=session.id,
        task_type="singing_audio_metrics",
    )
    old_face = AnalysisTask.objects.get(
        target_id=session.id,
        task_type="face_landmarks",
    )
    old_claim = claim_singing_analysis_task(old_audio.id)
    monkeypatch.setattr(
        executors.MockFaceLandmarksExecutor,
        "execute",
        lambda self, task, context=None: {"bad": "payload"},
    )
    run_analysis(old_face.id)
    retry = retry_session(
        session_id=session.id,
        patient_id=patient.id,
        idempotency_key="generation-one",
    )

    finalize_singing_success(
        old_audio.id,
        old_claim.claim_token,
        mock_singing_result(old_audio.id, 60),
    )

    assert set(first.task_ids).isdisjoint(retry.task_ids)
    old_audio.refresh_from_db()
    assert old_audio.status == "failed"
    assert not AnalysisResult.objects.filter(task=old_audio).exists()
    assert AnalysisTask.objects.filter(
        target_id=session.id,
        generation=1,
    ).count() == 2
    monkeypatch.undo()
    for task_id in retry.task_ids:
        run_analysis(task_id)
    session.refresh_from_db()
    assert session.status == "completed" and session.analysis_generation == 1
    assert AnalysisResult.objects.filter(
        task__target_id=session.id,
        task__generation=1,
    ).count() == 2


@pytest.mark.parametrize(
    ("path", "value"),
    [
        (("session_id",), "00000000-0000-0000-0000-000000000000"),
        (("duration_seconds",), 61),
        (("protocol_version",), "9.9"),
        (("generation",), 99),
        (("media", "asset_id"), "00000000-0000-0000-0000-000000000000"),
        (("media", "object_key"), "test/singing_audio/tampered"),
        (("media", "generation"), "tampered-generation"),
        (("media", "blob"), "tampered-blob"),
        (("media", "receipt_fingerprint"), "0" * 64),
    ],
)
@pytest.mark.django_db
def test_worker_rejects_any_tampered_expected_input_snapshot(path, value):
    patient, session = uploaded_session()
    from apps.singing.services import submit_session

    task_id = submit_session(
        session_id=session.id,
        patient_id=patient.id,
        idempotency_key=f"tampered-{'-'.join(path)}",
    ).task_ids[0]
    task = AnalysisTask.objects.get(pk=task_id)
    snapshot = task.input_snapshot
    target = snapshot
    for key in path[:-1]:
        target = target.setdefault(key, {})
    target[path[-1]] = value
    AnalysisTask.objects.filter(pk=task.id).update(input_snapshot=snapshot)

    run_analysis(task.id)

    task.refresh_from_db()
    session.refresh_from_db()
    assert task.status == "failed"
    assert session.status == "failed"
    assert not AnalysisResult.objects.filter(task=task).exists()
    assert not AnalysisTimeSeries.objects.filter(task=task).exists()
