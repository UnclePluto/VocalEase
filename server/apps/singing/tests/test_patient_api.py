import io
import uuid
from datetime import date

import pytest
from django.db import connection
from django.test.utils import CaptureQueriesContext
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts.models import Role, User
from apps.doctors.services import create_doctor
from apps.media.models import MediaAsset
from apps.media.services import claim_local_upload, complete_local_asset, create_upload_grant, get_storage_backend, publish_local_upload
from apps.patients.services import create_patient, transition_treatment_plan_status
from apps.patients.selectors import patient_singing_summary, patient_treatment_progress
from apps.patients.models import TreatmentPlan
from apps.singing.models import SingingSession
from apps.songs.models import Song


@pytest.fixture
def doctor(db):
    return create_doctor(
        name="演唱医生", gender="female", phone="13600000101",
        department="康复科", title="医师",
    )


@pytest.fixture
def patient(doctor):
    profile = create_patient(
        name="患者甲", gender="male", enrollment_age=36, phone="13500000101",
        doctor=doctor, start_date="2026-08-01", cycle_weeks=4,
    )
    profile.user.must_change_password = False
    profile.user.save(update_fields=["must_change_password"])
    plan = profile.treatment_plans.get()
    transition_treatment_plan_status(
        actor=doctor.user, plan=plan, status="active", request_id="activate-patient-a",
    )
    return profile


@pytest.fixture
def other_patient(doctor):
    profile = create_patient(
        name="患者乙", gender="female", enrollment_age=41, phone="13500000102",
        doctor=doctor, start_date="2026-08-01", cycle_weeks=4,
    )
    profile.user.must_change_password = False
    profile.user.save(update_fields=["must_change_password"])
    plan = profile.treatment_plans.get()
    transition_treatment_plan_status(
        actor=doctor.user, plan=plan, status="active", request_id="activate-patient-b",
    )
    return profile


def ready_song(tmp_path, settings, *, title="治疗歌曲"):
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song_id = uuid.uuid4()
    asset, grant = create_upload_grant(
        owner_type="song", owner_id=song_id, media_type="song_source", mime="audio/mpeg", size=6,
    )
    backend = get_storage_backend()
    nonce = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(
        object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(b"source"),
        mime="audio/mpeg", asset_id=asset.id,
    )
    publish_local_upload(asset_id=asset.id, nonce=nonce, prepared=prepared, backend=backend)
    asset = complete_local_asset(asset=asset)
    song = Song.objects.create(
        id=song_id, title=title, artist="歌手", genre="流行", language="中文",
        duration_seconds=90, source_asset=asset, publication_status="published",
    )
    from apps.songs.services import validate_source_asset
    validate_source_asset(song=song, asset=asset)
    return song


def complete_session(session, *, duration_seconds):
    SingingSession.objects.filter(pk=session.id).update(
        status=SingingSession.Status.COMPLETED,
        score=80,
        burp_count=2,
        duration_seconds=duration_seconds,
        is_mock=True,
        completed_at=timezone.now(),
    )
    session.refresh_from_db()
    return session


def active_treatment_plan_selects(captured_queries):
    return [
        query["sql"]
        for query in captured_queries
        if query["sql"].lstrip().upper().startswith("SELECT")
        and "patients_treatmentplan" in query["sql"]
        and "status" in query["sql"]
    ]


@pytest.mark.django_db
def test_patient_treatment_progress_only_counts_completed_sessions_in_active_plan(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    active_plan = patient.treatment_plans.get(status=TreatmentPlan.Status.ACTIVE)
    first = complete_session(
        SingingSession.objects.create_from_snapshots(patient=patient, song=song),
        duration_seconds=120,
    )
    second = complete_session(
        SingingSession.objects.create_from_snapshots(patient=patient, song=song),
        duration_seconds=180,
    )
    previous_plan = TreatmentPlan.objects.create(
        patient=patient,
        start_date=date(2026, 7, 1),
        cycle_weeks=4,
        target_session_count=12,
        status=TreatmentPlan.Status.COMPLETED,
    )
    complete_session(SingingSession.objects.create(
        patient=patient,
        song=song,
        treatment_plan=previous_plan,
        patient_snapshot=first.patient_snapshot,
        song_snapshot=first.song_snapshot,
        treatment_plan_snapshot={"id": str(previous_plan.id)},
        status=SingingSession.Status.CREATED,
    ), duration_seconds=300)
    SingingSession.objects.create(
        patient=patient,
        song=song,
        treatment_plan=active_plan,
        patient_snapshot=first.patient_snapshot,
        song_snapshot=first.song_snapshot,
        treatment_plan_snapshot=first.treatment_plan_snapshot,
        status=SingingSession.Status.PROCESSING,
    )

    snapshot = patient_treatment_progress(patient=patient, today=date(2026, 8, 17))

    assert snapshot == {
        "completed_session_count": 2,
        "target_session_count": active_plan.target_session_count,
        "progress_percent": "16.67",
        "current_week": 3,
    }
    assert patient_singing_summary(patient=patient) == {
        "completed_session_count": 3,
        "total_duration_seconds": 600,
    }


@pytest.mark.django_db
def test_patient_treatment_progress_is_none_without_active_plan_but_summary_remains(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    session = complete_session(
        SingingSession.objects.create_from_snapshots(patient=patient, song=song),
        duration_seconds=120,
    )
    TreatmentPlan.objects.filter(pk=session.treatment_plan_id).update(
        status=TreatmentPlan.Status.COMPLETED,
    )

    assert patient_treatment_progress(patient=patient, today=date(2026, 8, 17)) is None
    assert patient_singing_summary(patient=patient) == {
        "completed_session_count": 1,
        "total_duration_seconds": 120,
    }


@pytest.mark.django_db
def test_patient_reads_profile_active_plan_and_only_own_sessions(patient, other_patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    own = SingingSession.objects.create_from_snapshots(patient=patient, song=song)
    other = SingingSession.objects.create_from_snapshots(patient=other_patient, song=song)
    client = APIClient()
    client.force_authenticate(patient.user)

    with CaptureQueriesContext(connection) as me_queries:
        me = client.get("/api/v1/patient/me/")
    captured_me_queries = list(me_queries.captured_queries)
    sessions = client.get("/api/v1/patient/singing-sessions/")

    assert me.status_code == 200
    assert len(active_treatment_plan_selects(captured_me_queries)) == 1
    assert me.json()["data"]["id"] == str(patient.id)
    assert me.json()["data"]["active_treatment_plan"]["status"] == "active"
    progress = me.json()["data"]["treatment_progress"]
    assert set(progress) == {
        "completed_session_count", "target_session_count", "progress_percent", "current_week",
    }
    assert progress["completed_session_count"] == 0
    assert progress["target_session_count"] == patient.treatment_plans.get().target_session_count
    assert progress["progress_percent"] == "0.00"
    assert me.json()["data"]["singing_summary"] == {
        "completed_session_count": 0,
        "total_duration_seconds": 0,
    }
    assert sessions.status_code == 200
    assert [row["id"] for row in sessions.json()["data"]["results"]] == [str(own.id)]
    assert client.get(f"/api/v1/patient/singing-sessions/{other.id}/").status_code == 404


@pytest.mark.django_db
def test_patient_me_keeps_history_summary_without_active_plan(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    session = complete_session(
        SingingSession.objects.create_from_snapshots(patient=patient, song=song),
        duration_seconds=120,
    )
    TreatmentPlan.objects.filter(pk=session.treatment_plan_id).update(
        status=TreatmentPlan.Status.COMPLETED,
    )
    client = APIClient()
    client.force_authenticate(patient.user)

    with CaptureQueriesContext(connection) as me_queries:
        response = client.get("/api/v1/patient/me/")

    assert response.status_code == 200
    assert len(active_treatment_plan_selects(me_queries.captured_queries)) == 1
    assert response.json()["data"]["active_treatment_plan"] is None
    assert response.json()["data"]["treatment_progress"] is None
    assert response.json()["data"]["singing_summary"] == {
        "completed_session_count": 1,
        "total_duration_seconds": 120,
    }


@pytest.mark.django_db
def test_create_session_requires_current_available_song_and_keeps_snapshots(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings, title="初始歌名")
    client = APIClient()
    client.force_authenticate(patient.user)

    response = client.post(
        "/api/v1/patient/singing-sessions/", {"song_id": str(song.id)}, format="json",
        HTTP_IDEMPOTENCY_KEY="create-snapshot-1",
    )

    assert response.status_code == 201
    session = SingingSession.objects.get(pk=response.json()["data"]["id"])
    assert session.status == "created"
    assert session.song_snapshot["title"] == "初始歌名"
    assert session.patient_snapshot["medical_record_no"] == patient.medical_record_no
    assert session.treatment_plan_snapshot["id"] == str(patient.treatment_plans.get().id)
    Song.objects.filter(pk=song.id).update(title="新歌名", deleted_at=timezone.now())
    detail = client.get(f"/api/v1/patient/singing-sessions/{session.id}/")
    assert detail.json()["data"]["song"]["title"] == "初始歌名"
    assert client.post(
        "/api/v1/patient/singing-sessions/", {"song_id": str(song.id)}, format="json",
        HTTP_IDEMPOTENCY_KEY="create-unavailable-song-1",
    ).status_code == 400


@pytest.mark.django_db
def test_create_session_exposes_idempotency_as_http_contract(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings, title="幂等歌曲")
    other_song = ready_song(tmp_path, settings, title="冲突歌曲")
    client = APIClient()
    client.force_authenticate(patient.user)
    url = "/api/v1/patient/singing-sessions/"
    payload = {"song_id": str(song.id)}

    first = client.post(
        url, payload, format="json", HTTP_IDEMPOTENCY_KEY="create-http-1",
    )
    replay = client.post(
        url, payload, format="json", HTTP_IDEMPOTENCY_KEY="create-http-1",
    )
    missing = client.post(url, payload, format="json")
    conflict = client.post(
        url,
        {"song_id": str(other_song.id)},
        format="json",
        HTTP_IDEMPOTENCY_KEY="create-http-1",
    )

    assert first.status_code == 201
    assert replay.status_code == 200
    assert replay.json()["data"]["id"] == first.json()["data"]["id"]
    assert missing.status_code == 400
    assert missing.json()["code"] == "validation_error"
    assert conflict.status_code == 409
    assert conflict.json()["code"] == "singing_creation_conflict"


@pytest.mark.parametrize("idempotency_key", ["", "   "])
@pytest.mark.django_db
def test_create_session_rejects_blank_idempotency_header(
    idempotency_key,
    patient,
    tmp_path,
    settings,
):
    song = ready_song(tmp_path, settings, title="空白幂等键歌曲")
    client = APIClient()
    client.force_authenticate(patient.user)

    response = client.post(
        "/api/v1/patient/singing-sessions/",
        {"song_id": str(song.id)},
        format="json",
        HTTP_IDEMPOTENCY_KEY=idempotency_key,
    )

    assert response.status_code == 400
    assert response.json()["code"] == "validation_error"
    assert SingingSession.objects.count() == 0


@pytest.mark.django_db
def test_session_upload_grant_is_bound_to_session_and_cross_patient_is_404(patient, other_patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    session = SingingSession.objects.create_from_snapshots(patient=patient, song=song)
    client = APIClient()
    client.force_authenticate(patient.user)

    grant = client.post(
        f"/api/v1/patient/singing-sessions/{session.id}/upload-grants/",
        {"media_type": "singing_audio", "mime": "audio/mpeg", "size": 6}, format="json",
    )

    assert grant.status_code == 201
    assert grant.json()["data"]["session_id"] == str(session.id)
    asset = MediaAsset.objects.get(pk=grant.json()["data"]["asset_id"])
    assert asset.patient_owner_id == patient.id
    session.refresh_from_db()
    assert session.status == "awaiting_upload"
    client.force_authenticate(other_patient.user)
    assert client.post(
        f"/api/v1/patient/singing-sessions/{session.id}/upload-grants/",
        {"media_type": "singing_audio", "mime": "audio/mpeg", "size": 6}, format="json",
    ).status_code == 404


@pytest.mark.django_db
def test_doctor_and_admin_read_all_singing_records_but_patient_cannot_use_admin_namespace(
    doctor, patient, other_patient, tmp_path, settings,
):
    song = ready_song(tmp_path, settings)
    SingingSession.objects.create_from_snapshots(patient=patient, song=song)
    SingingSession.objects.create_from_snapshots(patient=other_patient, song=song)
    admin = User.objects.create_user(
        login_id="singing-admin", password="888888", role=Role.SYSTEM_ADMIN,
        must_change_password=False,
    )
    client = APIClient()
    for user in (doctor.user, admin):
        user.must_change_password = False
        user.save(update_fields=["must_change_password"])
        client.force_authenticate(user)
        assert client.get("/api/v1/admin/singing-sessions/").json()["data"]["count"] == 2
    client.force_authenticate(patient.user)
    assert client.get("/api/v1/admin/singing-sessions/").status_code == 403


@pytest.mark.django_db
def test_history_filters_dates_and_detail_exposes_versioned_result_series(patient, tmp_path, settings):
    song = ready_song(tmp_path, settings)
    session = SingingSession.objects.create_from_snapshots(patient=patient, song=song)
    from apps.media.models import MediaAsset
    from apps.singing.models import SessionMedia
    audio = MediaAsset.objects.create(
        patient_owner=patient, owner_type="patient", owner_id=patient.id,
        media_type="singing_audio", backend="qiniu", object_key=f"test/singing_audio/{uuid.uuid4().hex}",
        mime="audio/mpeg", size=1024, etag="history-audio", status="ready", upload_expires_at=timezone.now(),
    )
    SessionMedia.objects.create(
        session=session, asset=audio, media_type="singing_audio", confirmed_at=timezone.now(),
    )
    video = MediaAsset.objects.create(
        patient_owner=patient, owner_type="patient", owner_id=patient.id,
        media_type="singing_video", backend="qiniu", object_key=f"test/singing_video/{uuid.uuid4().hex}",
        mime="video/mp4", size=1024, etag="history-video", status="ready", upload_expires_at=timezone.now(),
    )
    SessionMedia.objects.create(
        session=session, asset=video, media_type="singing_video", confirmed_at=timezone.now(),
    )
    session.status = "uploaded"
    session.save(update_fields=["status", "updated_at"])
    from apps.analysis.services import run_analysis
    from apps.singing.services import submit_session
    submitted = submit_session(
        session_id=session.id, patient_id=patient.id, idempotency_key="history-result",
    )
    assert len(submitted.task_ids) == 2
    for task_id in submitted.task_ids:
        run_analysis(task_id)
    client = APIClient()
    client.force_authenticate(patient.user)

    included = client.get("/api/v1/patient/singing-sessions/?created_from=2026-01-01&created_to=2026-12-31")
    excluded = client.get("/api/v1/patient/singing-sessions/?created_from=2027-01-01")
    detail = client.get(f"/api/v1/patient/singing-sessions/{session.id}/")

    assert included.status_code == 200 and included.json()["data"]["count"] == 1
    assert excluded.status_code == 200 and excluded.json()["data"]["count"] == 0
    result = next(
        row for row in detail.json()["data"]["analysis_results"]
        if row["task_type"] == "singing_audio_metrics"
    )
    assert result["task_type"] == "singing_audio_metrics"
    assert result["protocol_version"] == "1.0" and result["is_mock"] is True
    assert set(result["time_series"]) == {"volume", "pitch_hz", "snr_db"}
    assert result["payload"]["score"] == detail.json()["data"]["score"]


@pytest.mark.django_db
def test_create_session_preserves_temporary_storage_error_for_mobile_retry(patient, tmp_path, settings, monkeypatch):
    song = ready_song(tmp_path, settings)
    from apps.singing import services
    from apps.songs.services import SourceVerificationTemporary
    monkeypatch.setattr(
        services,
        "validate_source_asset",
        lambda **kwargs: (_ for _ in ()).throw(SourceVerificationTemporary()),
    )
    client = APIClient()
    client.force_authenticate(patient.user)

    response = client.post(
        "/api/v1/patient/singing-sessions/", {"song_id": str(song.id)}, format="json",
        HTTP_IDEMPOTENCY_KEY="create-temporary-source-error-1",
    )

    assert response.status_code == 503
    assert response.json()["code"] == "song_source_verification_deferred"
    assert SingingSession.objects.count() == 0


@pytest.mark.django_db
def test_singing_history_rejects_unknown_query_parameters(patient):
    client = APIClient()
    client.force_authenticate(patient.user)

    response = client.get("/api/v1/patient/singing-sessions/?unknown=value")

    assert response.status_code == 400
    assert response.json()["code"] == "validation_error"
    assert "unknown" in response.json()["data"]


@pytest.mark.django_db
def test_singing_history_is_lightweight_and_query_count_does_not_grow_with_page_size(
    patient,
    tmp_path,
    settings,
):
    song = ready_song(tmp_path, settings)
    SingingSession.objects.create_from_snapshots(patient=patient, song=song)
    client = APIClient()
    client.force_authenticate(patient.user)

    with CaptureQueriesContext(connection) as one_session_queries:
        one = client.get("/api/v1/patient/singing-sessions/?page_size=20")
    for _index in range(4):
        SingingSession.objects.create_from_snapshots(patient=patient, song=song)
    with CaptureQueriesContext(connection) as five_session_queries:
        five = client.get("/api/v1/patient/singing-sessions/?page_size=20")

    assert one.status_code == five.status_code == 200
    assert len(one_session_queries) == len(five_session_queries)
    assert len(five_session_queries) <= 6
    assert len(five.json()["data"]["results"]) == 5
    for row in five.json()["data"]["results"]:
        assert "analysis_results" not in row
        assert "analysis_task_ids" not in row
