from datetime import datetime, timedelta, timezone as datetime_timezone
from decimal import Decimal
import uuid

import pytest
from django.db import connection
from django.test.utils import CaptureQueriesContext
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts.models import Role, User
from apps.analysis.models import AnalysisResult, AnalysisTask
from apps.analytics.calculations import METRIC_VERSION
from apps.media.models import MediaAsset
from apps.singing.models import SingingSession
from apps.songs.models import Song


def _source_asset(*, owner_type, owner_id, media_type, mime):
    return MediaAsset.objects.create(
        owner_type=owner_type,
        owner_id=owner_id,
        media_type=media_type,
        backend="qiniu",
        object_key=f"test/{media_type}/{uuid.uuid4().hex}",
        mime=mime,
        size=1024,
        etag=uuid.uuid4().hex,
        status="ready",
        upload_expires_at=timezone.now() + timedelta(hours=1),
    )


@pytest.fixture
def analytics_song():
    song_id = uuid.uuid4()
    source = _source_asset(owner_type="song", owner_id=song_id, media_type="song_source", mime="audio/mpeg")
    return Song.objects.create(
        id=song_id,
        title="统计歌曲",
        artist="歌手",
        genre="流行",
        language="中文",
        duration_seconds=60,
        source_asset=source,
    )


def _completed_session(patient, song, *, completed_at, score, burp_count, duration=60, generation=0, with_current_result=True):
    plan = patient.treatment_plans.get(status="active")
    session = SingingSession.objects.create(
        patient=patient,
        song=song,
        treatment_plan=plan,
        patient_snapshot={"id": str(patient.id), "medical_record_no": patient.medical_record_no, "name": patient.name},
        song_snapshot={"id": str(song.id), "title": song.title, "artist": song.artist, "duration_seconds": duration},
        treatment_plan_snapshot={"id": str(plan.id), "target_session_count": plan.target_session_count},
        status="completed",
        analysis_generation=generation,
        score=score,
        burp_count=burp_count,
        duration_seconds=duration,
        is_mock=True,
        submitted_at=completed_at - timedelta(minutes=1),
        completed_at=completed_at,
    )
    audio = MediaAsset.objects.create(
        patient_owner=patient,
        owner_type="patient",
        owner_id=patient.id,
        media_type="singing_audio",
        backend="qiniu",
        object_key=f"test/singing_audio/{uuid.uuid4().hex}",
        mime="audio/mpeg",
        size=1024,
        etag=uuid.uuid4().hex,
        status="ready",
        upload_expires_at=timezone.now() + timedelta(hours=1),
    )
    result_generation = generation if with_current_result else max(0, generation - 1)
    task = AnalysisTask.objects.create(
        target_type="singing_session",
        target_id=session.id,
        source_asset=audio,
        task_type="singing_audio_metrics",
        protocol_version="1.0",
        executor="mock_singing",
        status="succeeded",
        generation=result_generation,
        idempotency_key=f"analytics:{session.id}:{result_generation}",
        completed_at=completed_at,
    )
    AnalysisResult.objects.create(
        task=task,
        protocol_version="1.0",
        is_mock=True,
        payload={"protocol_version": "1.0", "is_mock": True, "score": score, "burp_events": list(range(burp_count))},
    )
    return session


@pytest.mark.django_db
def test_dashboard_and_patient_metrics_use_current_completed_generation(patient, analytics_song):
    start = datetime(2026, 8, 1, tzinfo=datetime_timezone.utc)
    for index, (score, burps, duration) in enumerate([
        (60, 4, 60), (60, 3, 60), (60, 2, 60), (80, 2, 60), (80, 1, 60), (80, 1, 60),
    ]):
        _completed_session(patient, analytics_song, completed_at=start + timedelta(days=index), score=score, burp_count=burps, duration=duration)
    # 状态虽为 completed，但当前代没有成功汇总，必须排除。
    _completed_session(patient, analytics_song, completed_at=start + timedelta(days=7), score=100, burp_count=50, generation=1, with_current_result=False)
    patient.treatment_plans.update(target_session_count=5)
    patient.user.must_change_password = False
    patient.user.save(update_fields=["must_change_password"])
    client = APIClient()
    client.force_authenticate(patient.primary_doctor.user)

    dashboard = client.get("/api/v1/admin/analytics/dashboard/")
    listing = client.get("/api/v1/admin/analytics/patients/?page_size=100")

    assert dashboard.status_code == listing.status_code == 200
    assert dashboard.json()["data"] == {
        "metric_version": METRIC_VERSION,
        "active_patient_count": 1,
        "completed_session_count": 6,
        "average_score": "70.00",
        "average_burp_count": "2.17",
        "is_mock": True,
    }
    row = listing.json()["data"]["results"][0]
    assert listing.json()["data"]["metric_version"] == METRIC_VERSION
    assert row["treatment_progress"] == "100.00"
    assert row["completed_count"] == 6
    assert row["total_duration_seconds"] == 360
    assert row["average_score"] == "70.00"
    assert row["score_trend"] == {"difference": "20.00", "direction": "up", "has_enough_data": True}
    assert row["burp_improvement"] == "0.5556"
    assert row["is_mock"] is True


@pytest.mark.django_db
def test_patient_metrics_stable_tie_order_uses_completed_submitted_and_id(patient, analytics_song):
    tied = datetime(2026, 8, 1, tzinfo=datetime_timezone.utc)
    sessions = [
        _completed_session(patient, analytics_song, completed_at=tied, score=score, burp_count=1)
        for score in (10, 20, 30, 70, 80, 90)
    ]
    # 同完成时间下 submitted_at 仍相同，最终 UUID 升序决定前后三次。
    ordered = sorted(sessions, key=lambda item: item.id)
    for session, score in zip(ordered, (10, 20, 30, 70, 80, 90), strict=True):
        SingingSession.objects.filter(pk=session.id).update(score=score)
        AnalysisResult.objects.filter(task__target_id=session.id).update(payload={
            "protocol_version": "1.0", "is_mock": True, "score": score, "burp_events": [1],
        })
    patient.primary_doctor.user.must_change_password = False
    patient.primary_doctor.user.save(update_fields=["must_change_password"])
    client = APIClient(); client.force_authenticate(patient.primary_doctor.user)

    row = client.get("/api/v1/admin/analytics/patients/").json()["data"]["results"][0]

    assert row["score_trend"]["difference"] == "60.00"


@pytest.mark.django_db
def test_admin_filters_are_normalized_unknown_params_rejected_and_patient_forbidden(patient):
    patient.primary_doctor.user.must_change_password = False
    patient.primary_doctor.user.save(update_fields=["must_change_password"])
    client = APIClient(); client.force_authenticate(patient.primary_doctor.user)
    filtered = client.get(
        f"/api/v1/admin/analytics/patients/?name=患者&medical_record_no={patient.medical_record_no}&treatment_status=active&primary_doctor={patient.primary_doctor_id}&created_from=2026-01-01&created_to=2026-12-31"
    )
    unknown = client.get("/api/v1/admin/analytics/patients/?unknown=1")
    client.force_authenticate(patient.user)
    forbidden = client.get("/api/v1/admin/analytics/dashboard/")

    assert filtered.status_code == 200 and filtered.json()["data"]["count"] == 1
    assert unknown.status_code == 400 and "unknown" in unknown.json()["data"]
    assert forbidden.status_code == 403


@pytest.mark.django_db
def test_patient_metric_query_count_is_constant_for_page_size(patient, other_patient, analytics_song):
    now = timezone.now()
    _completed_session(patient, analytics_song, completed_at=now, score=80, burp_count=1)
    _completed_session(other_patient, analytics_song, completed_at=now, score=70, burp_count=2)
    patient.primary_doctor.user.must_change_password = False
    patient.primary_doctor.user.save(update_fields=["must_change_password"])
    client = APIClient(); client.force_authenticate(patient.primary_doctor.user)

    with CaptureQueriesContext(connection) as queries:
        response = client.get("/api/v1/admin/analytics/patients/?page_size=100")

    assert response.status_code == 200 and response.json()["data"]["count"] == 2
    assert len(queries) <= 8


@pytest.mark.django_db
def test_system_admin_can_view_all_patients(patient):
    admin = User.objects.create_user(login_id="analytics-admin", password="888888", role=Role.SYSTEM_ADMIN, must_change_password=False)
    client = APIClient(); client.force_authenticate(admin)
    assert client.get("/api/v1/admin/analytics/patients/").json()["data"]["count"] == 1
