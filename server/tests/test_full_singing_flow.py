import io
import uuid

import pytest
from rest_framework.test import APIClient

from apps.analysis.models import AnalysisResult, AnalysisTask
from apps.analysis.tasks import run_analysis_task
from apps.doctors.models import SequenceCounter
from apps.doctors.services import create_doctor
from apps.media.models import MediaAsset
from apps.media.services import (
    claim_local_upload,
    complete_local_asset,
    create_upload_grant,
    get_storage_backend,
    publish_local_upload,
)
from apps.patients.services import create_patient, transition_treatment_plan_status
from apps.singing.models import AnalysisTimeSeries, SingingSession
from apps.songs.models import Song
from apps.songs.services import validate_source_asset


@pytest.fixture(autouse=True)
def ensure_sequence_counters(db):
    SequenceCounter.objects.bulk_create(
        [SequenceCounter(prefix="D"), SequenceCounter(prefix="P")],
        ignore_conflicts=True,
    )


def _create_ready_song(*, tmp_path, settings):
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song_id = uuid.uuid4()
    asset, grant = create_upload_grant(
        owner_type="song",
        owner_id=song_id,
        media_type="song_source",
        mime="audio/mpeg",
        size=6,
    )
    backend = get_storage_backend()
    nonce = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(
        object_key=asset.object_key,
        token=grant.upload_token,
        stream=io.BytesIO(b"source"),
        mime="audio/mpeg",
        asset_id=asset.id,
    )
    publish_local_upload(
        asset_id=asset.id,
        nonce=nonce,
        prepared=prepared,
        backend=backend,
    )
    asset = complete_local_asset(asset=asset)
    song = Song.objects.create(
        id=song_id,
        title="跨模块验收歌曲",
        artist="演示歌手",
        genre="流行",
        language="中文",
        duration_seconds=90,
        source_asset=asset,
        publication_status=Song.PublicationStatus.PUBLISHED,
    )
    validate_source_asset(song=song, asset=asset)
    return song


def _create_active_patient(*, doctor, suffix):
    patient = create_patient(
        name=f"跨模块患者{suffix}",
        gender="female",
        enrollment_age=36,
        phone=f"13500009{suffix:03d}",
        doctor=doctor,
        start_date="2026-08-01",
        cycle_weeks=4,
    )
    patient.user.must_change_password = False
    patient.user.save(update_fields=["must_change_password"])
    transition_treatment_plan_status(
        actor=doctor.user,
        plan=patient.treatment_plans.get(),
        status="active",
        request_id=f"full-flow-plan-{suffix}",
    )
    return patient


def _upload_and_confirm(*, client, session_id, media_type, mime, body, key):
    grant = client.post(
        f"/api/v1/patient/singing-sessions/{session_id}/upload-grants/",
        {"media_type": media_type, "mime": mime, "size": len(body)},
        format="json",
        HTTP_IDEMPOTENCY_KEY=key,
    )
    assert grant.status_code == 201
    data = grant.json()["data"]
    uploaded = client.put(data["upload_url"], body, content_type=mime)
    assert uploaded.status_code == 204
    confirmed = client.post(
        f"/api/v1/patient/singing-sessions/{session_id}/confirm-upload/",
        {"object_key": data["object_key"]},
        format="json",
    )
    assert confirmed.status_code == 200
    return data


@pytest.mark.django_db(transaction=True)
def test_patient_upload_analysis_to_admin_detail_flow(tmp_path, settings):
    doctor = create_doctor(
        name="跨模块医生",
        gender="male",
        phone="13600000999",
        department="康复科",
        title="主治医师",
    )
    doctor.user.must_change_password = False
    doctor.user.save(update_fields=["must_change_password"])
    patient = _create_active_patient(doctor=doctor, suffix=1)
    other_patient = _create_active_patient(doctor=doctor, suffix=2)
    song = _create_ready_song(tmp_path=tmp_path, settings=settings)

    patient_client = APIClient()
    patient_client.force_authenticate(patient.user)
    created = patient_client.post(
        "/api/v1/patient/singing-sessions/",
        {"song_id": str(song.id)},
        format="json",
        HTTP_IDEMPOTENCY_KEY="full-flow-create",
    )
    assert created.status_code == 201
    session_id = created.json()["data"]["id"]

    audio = _upload_and_confirm(
        client=patient_client,
        session_id=session_id,
        media_type="singing_audio",
        mime="audio/mpeg",
        body=b"audio!",
        key="full-flow-audio",
    )
    video = _upload_and_confirm(
        client=patient_client,
        session_id=session_id,
        media_type="singing_video",
        mime="video/mp4",
        body=b"video!",
        key="full-flow-video",
    )
    assert MediaAsset.objects.filter(
        id__in=[audio["asset_id"], video["asset_id"]], status="ready"
    ).count() == 2

    submit_url = f"/api/v1/patient/singing-sessions/{session_id}/submit/"
    first_submit = patient_client.post(
        submit_url, {}, format="json", HTTP_IDEMPOTENCY_KEY="full-flow-submit"
    )
    duplicate_submit = patient_client.post(
        submit_url, {}, format="json", HTTP_IDEMPOTENCY_KEY="full-flow-submit"
    )
    assert first_submit.status_code == 202
    assert duplicate_submit.status_code == 200
    assert first_submit.json()["data"]["analysis_task_ids"] == duplicate_submit.json()["data"]["analysis_task_ids"]

    task_ids = first_submit.json()["data"]["analysis_task_ids"]
    assert AnalysisTask.objects.filter(id__in=task_ids, generation=0).count() == 2
    for task_id in task_ids:
        result = run_analysis_task.apply(args=[task_id])
        assert result.successful()

    session = SingingSession.objects.get(pk=session_id)
    assert session.status == "completed"
    assert session.analysis_generation == 0
    assert session.is_mock is True
    assert AnalysisResult.objects.filter(
        task__target_id=session.id,
        task__generation=session.analysis_generation,
        is_mock=True,
    ).count() == 2
    assert set(
        AnalysisTimeSeries.objects.filter(session=session).values_list("metric_type", flat=True)
    ) == {"volume", "pitch_hz", "snr_db"}

    own_detail = patient_client.get(f"/api/v1/patient/singing-sessions/{session_id}/")
    assert own_detail.status_code == 200
    own_data = own_detail.json()["data"]
    assert own_data["patient"]["medical_record_no"] == patient.medical_record_no
    assert own_data["song"]["title"] == "跨模块验收歌曲"
    assert own_data["treatment_plan"]["id"] == str(patient.treatment_plans.get().id)
    assert {row["generation"] for row in own_data["analysis_results"]} == {0}
    assert all(row["is_mock"] is True for row in own_data["analysis_results"])

    other_client = APIClient()
    other_client.force_authenticate(other_patient.user)
    assert other_client.get(f"/api/v1/patient/singing-sessions/{session_id}/").status_code == 404
    assert other_client.get(f"/api/v1/admin/singing-sessions/{session_id}/").status_code == 403

    doctor_client = APIClient()
    doctor_client.force_authenticate(doctor.user)
    admin_detail = doctor_client.get(f"/api/v1/admin/singing-sessions/{session_id}/")
    assert admin_detail.status_code == 200
    assert admin_detail.json()["data"] == own_data
