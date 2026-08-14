import uuid

import pytest
from django.db import IntegrityError, transaction

from apps.analysis.models import AnalysisResult, AnalysisTask
from apps.analysis.services import run_analysis
from apps.singing.executors import mock_face_result, mock_singing_result
from apps.singing.models import AnalysisTimeSeries, SingingSession

from .test_submission_idempotency import uploaded_session


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
