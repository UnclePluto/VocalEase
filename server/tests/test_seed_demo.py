from io import BytesIO, StringIO
import hashlib
import wave

import pytest
from django.core.management import call_command
from django.db.models import Q
from django.utils import timezone

from apps.accounts.models import Role, User
from apps.analysis.models import AnalysisResult, AnalysisTask
from apps.doctors.models import DoctorProfile
from apps.media.models import MediaAsset
from apps.patients.models import PatientProfile, TreatmentPlan
from apps.singing.models import SessionMedia, SingingSession
from apps.songs.models import Song
from apps.media.services import (
    claim_local_upload,
    complete_local_asset,
    create_upload_grant,
    publish_local_upload,
    storage_backend_for,
)


DEMO_LOGIN_IDS = {"demo-admin", "DDEMO001", "PDEMO001", "PDEMO002"}


def _mp4_boxes(content):
    offset = 0
    while offset < len(content):
        assert len(content) - offset >= 8
        size = int.from_bytes(content[offset:offset + 4], "big")
        box_type = content[offset + 4:offset + 8]
        header_size = 8
        if size == 1:
            assert len(content) - offset >= 16
            size = int.from_bytes(content[offset + 8:offset + 16], "big")
            header_size = 16
        elif size == 0:
            size = len(content) - offset
        assert size >= header_size
        end = offset + size
        assert end <= len(content)
        yield box_type, content[offset + header_size:end]
        offset = end
    assert offset == len(content)


def _assert_parseable_video_mp4(content):
    top_level = list(_mp4_boxes(content))
    top_types = [box_type for box_type, _payload in top_level]
    assert b"ftyp" in top_types
    assert b"moov" in top_types
    assert any(box_type == b"mdat" and payload for box_type, payload in top_level)

    moov = next(payload for box_type, payload in top_level if box_type == b"moov")
    tracks = [payload for box_type, payload in _mp4_boxes(moov) if box_type == b"trak"]
    handlers = []
    for track in tracks:
        mdia = next(
            (payload for box_type, payload in _mp4_boxes(track) if box_type == b"mdia"),
            None,
        )
        if mdia is None:
            continue
        hdlr = next(
            (payload for box_type, payload in _mp4_boxes(mdia) if box_type == b"hdlr"),
            None,
        )
        if hdlr is not None and len(hdlr) >= 12:
            handlers.append(hdlr[8:12])
    assert b"vide" in handlers


def _legacy_header_only_mp4(label):
    ftyp = b"\x00\x00\x00\x18ftypisom\x00\x00\x00\x00isomiso2"
    payload = label.encode("utf-8")
    return ftyp + (len(payload) + 8).to_bytes(4, "big") + b"free" + payload


def _publish_local_patient_asset(*, patient, content, backend):
    asset, grant = create_upload_grant(
        owner=patient,
        media_type="singing_video",
        mime="video/mp4",
        size=len(content),
        backend=backend,
    )
    nonce = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(
        object_key=asset.object_key,
        token=grant.upload_token,
        stream=BytesIO(content),
        mime="video/mp4",
        asset_id=asset.id,
    )
    publish_local_upload(
        asset_id=asset.id,
        nonce=nonce,
        prepared=prepared,
        backend=backend,
    )
    return complete_local_asset(asset=asset)


def _demo_counts():
    return {
        "users": User.objects.filter(login_id__in=DEMO_LOGIN_IDS).count(),
        "doctors": DoctorProfile.objects.filter(employee_no="DDEMO001").count(),
        "patients": PatientProfile.objects.filter(
            medical_record_no__in={"PDEMO001", "PDEMO002"}
        ).count(),
        "plans": TreatmentPlan.objects.filter(
            patient__medical_record_no__in={"PDEMO001", "PDEMO002"}
        ).count(),
        "songs": Song.objects.filter(title__startswith="VocaEase 演示歌曲").count(),
        "sessions": SingingSession.objects.filter(created_source="seed_demo").count(),
        "assets": MediaAsset.objects.filter(
            Q(source_songs__title__startswith="VocaEase 演示歌曲")
            | Q(singing_session_binding__session__created_source="seed_demo")
        ).distinct().count(),
        "tasks": AnalysisTask.objects.filter(
            target_type="singing_session",
            target_id__in=SingingSession.objects.filter(
                created_source="seed_demo"
            ).values("id"),
        ).count(),
        "results": AnalysisResult.objects.filter(
            task__target_type="singing_session",
            task__target_id__in=SingingSession.objects.filter(
                created_source="seed_demo"
            ).values("id"),
        ).count(),
    }


@pytest.mark.django_db(transaction=True)
def test_seed_demo_is_idempotent_and_outputs_no_sensitive_values(tmp_path, settings):
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    first_output = StringIO()
    second_output = StringIO()

    call_command("seed_demo", stdout=first_output)
    first_counts = _demo_counts()
    call_command("seed_demo", stdout=second_output)

    assert _demo_counts() == first_counts == {
        "users": 4,
        "doctors": 1,
        "patients": 2,
        "plans": 2,
        "songs": 2,
        "sessions": 6,
        "assets": 14,
        "tasks": 12,
        "results": 12,
    }
    assert User.objects.filter(
        login_id__in=DEMO_LOGIN_IDS,
        must_change_password=True,
        is_active=True,
        deleted_at__isnull=True,
    ).count() == 4
    assert all(
        user.check_password("888888")
        for user in User.objects.filter(login_id__in=DEMO_LOGIN_IDS)
    )
    output = first_output.getvalue() + second_output.getvalue()
    assert "888888" not in output
    assert "135" not in output
    assert "演示病情" not in output
    assert "demo-admin" in output
    backend = storage_backend_for("local")
    for asset in MediaAsset.objects.filter(
        Q(source_songs__title__startswith="VocaEase 演示歌曲")
        | Q(singing_session_binding__session__created_source="seed_demo")
    ).distinct():
        private_url = backend.create_private_url(
            asset.object_key,
            ttl_seconds=60,
            asset_id=asset.id,
            expected_generation=asset.manifest_generation,
        )
        with backend.open_authorized_private(
            private_url.token,
            asset.object_key,
            asset_id=asset.id,
            expected_generation=asset.manifest_generation,
        ) as stream:
            content = stream.read()
        assert asset.size == len(content)
        assert asset.sha256 == hashlib.sha256(content).hexdigest()
        if asset.media_type == "singing_video":
            assert asset.mime == "video/mp4"
            _assert_parseable_video_mp4(content)
        else:
            assert asset.mime == "audio/wav"
            header = content[:12]
            assert header[:4] == b"RIFF" and header[8:] == b"WAVE"
            with wave.open(BytesIO(content), "rb") as wav_file:
                assert wav_file.getnframes() / wav_file.getframerate() == 90


@pytest.mark.django_db(transaction=True)
def test_seed_demo_restores_soft_deleted_demo_records_without_duplicates(tmp_path, settings):
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    call_command("seed_demo", stdout=StringIO())
    patient = PatientProfile.objects.get(medical_record_no="PDEMO001")
    patient.deleted_at = patient.created_at
    patient.save(update_fields=["deleted_at"])
    patient.user.deleted_at = timezone.now()
    patient.user.is_active = False
    patient.user.save(update_fields=["deleted_at", "is_active"])

    call_command("seed_demo", stdout=StringIO())

    patient.refresh_from_db()
    patient.user.refresh_from_db()
    assert patient.deleted_at is None
    assert patient.user.deleted_at is None and patient.user.is_active is True
    assert _demo_counts()["patients"] == 2


@pytest.mark.django_db(transaction=True)
def test_seed_demo_upgrades_legacy_audio_only_session_in_place(tmp_path, settings):
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    call_command("seed_demo", stdout=StringIO())
    session = SingingSession.objects.filter(created_source="seed_demo").order_by("id").first()
    session_id = session.id
    video_binding = SessionMedia.objects.get(session=session, media_type="singing_video")
    video_asset_id = video_binding.asset_id
    face_task = AnalysisTask.objects.get(
        target_type="singing_session",
        target_id=session.id,
        generation=session.analysis_generation,
        task_type=AnalysisTask.TaskType.FACE_LANDMARKS,
    )
    AnalysisResult.objects.filter(task=face_task).delete()
    face_task.delete()
    video_binding.delete()
    MediaAsset.objects.filter(pk=video_asset_id).delete()

    assert set(session.media_bindings.values_list("media_type", flat=True)) == {"singing_audio"}
    assert AnalysisTask.objects.filter(target_id=session.id).count() == 1
    assert AnalysisResult.objects.filter(task__target_id=session.id).count() == 1

    call_command("seed_demo", stdout=StringIO())

    session.refresh_from_db()
    assert session.id == session_id
    assert session.status == SingingSession.Status.COMPLETED and session.is_mock is True
    bindings = list(session.media_bindings.select_related("asset").order_by("media_type"))
    assert {binding.media_type for binding in bindings} == {"singing_audio", "singing_video"}
    assert all(binding.confirmed_at and binding.asset.status == MediaAsset.Status.READY for binding in bindings)
    video_asset = next(binding.asset for binding in bindings if binding.media_type == "singing_video")
    backend = storage_backend_for("local")
    private_url = backend.create_private_url(
        video_asset.object_key,
        ttl_seconds=60,
        asset_id=video_asset.id,
        expected_generation=video_asset.manifest_generation,
    )
    with backend.open_authorized_private(
        private_url.token,
        video_asset.object_key,
        asset_id=video_asset.id,
        expected_generation=video_asset.manifest_generation,
    ) as stream:
        video_content = stream.read()
    assert video_asset.mime == "video/mp4"
    assert video_asset.size == len(video_content)
    assert video_asset.sha256 == hashlib.sha256(video_content).hexdigest()
    _assert_parseable_video_mp4(video_content)
    tasks = AnalysisTask.objects.filter(
        target_type="singing_session",
        target_id=session.id,
        generation=session.analysis_generation,
    )
    assert set(tasks.values_list("task_type", flat=True)) == {
        AnalysisTask.TaskType.SINGING_AUDIO_METRICS,
        AnalysisTask.TaskType.FACE_LANDMARKS,
    }
    assert AnalysisResult.objects.filter(task__in=tasks).count() == 2
    stable_binding_ids = tuple(bindings_item.id for bindings_item in bindings)
    stable_task_ids = tuple(tasks.order_by("task_type").values_list("id", flat=True))

    call_command("seed_demo", stdout=StringIO())

    assert tuple(session.media_bindings.order_by("media_type").values_list("id", flat=True)) == stable_binding_ids
    assert tuple(tasks.order_by("task_type").values_list("id", flat=True)) == stable_task_ids


@pytest.mark.django_db(transaction=True)
def test_seed_demo_replaces_fix_base_header_only_video_in_place(tmp_path, settings):
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    call_command("seed_demo", stdout=StringIO())
    session = SingingSession.objects.get(
        id="8ce19dd3-433a-5bf1-8329-1017a53b323f",
        created_source="seed_demo",
        patient__medical_record_no="PDEMO001",
    )
    binding = SessionMedia.objects.get(session=session, media_type="singing_video")
    face_task = AnalysisTask.objects.get(
        target_type=AnalysisTask.TargetType.SINGING_SESSION,
        target_id=session.id,
        generation=session.analysis_generation,
        task_type=AnalysisTask.TaskType.FACE_LANDMARKS,
        status=AnalysisTask.Status.SUCCEEDED,
    )
    face_result = AnalysisResult.objects.get(task=face_task)
    current_asset_id = binding.asset_id
    backend = storage_backend_for("local")
    legacy_content = _legacy_header_only_mp4("demo-video-session-1")
    legacy_asset = _publish_local_patient_asset(
        patient=session.patient,
        content=legacy_content,
        backend=backend,
    )
    binding.asset = legacy_asset
    binding.save(update_fields=["asset"])
    from apps.singing.services import _task_snapshot
    face_task.source_asset = legacy_asset
    face_task.input_snapshot = _task_snapshot(
        session=session,
        asset=legacy_asset,
        generation=session.analysis_generation,
    )
    face_task.save(update_fields=["source_asset", "input_snapshot", "updated_at"])
    MediaAsset.objects.filter(pk=current_asset_id).delete()
    stable_ids = (session.id, binding.id, face_task.id, face_result.id)

    assert legacy_asset.mime == "video/mp4"
    assert legacy_asset.size == len(legacy_content)
    assert legacy_asset.sha256 == hashlib.sha256(legacy_content).hexdigest()
    assert set(AnalysisTask.objects.filter(target_id=session.id).values_list("status", flat=True)) == {
        AnalysisTask.Status.SUCCEEDED,
    }
    assert AnalysisResult.objects.filter(task__target_id=session.id).count() == 2

    call_command("seed_demo", stdout=StringIO())

    session.refresh_from_db()
    binding.refresh_from_db()
    face_task.refresh_from_db()
    face_result.refresh_from_db()
    assert (session.id, binding.id, face_task.id, face_result.id) == stable_ids
    assert binding.asset_id != legacy_asset.id
    replacement = binding.asset
    private_url = backend.create_private_url(
        replacement.object_key,
        ttl_seconds=60,
        asset_id=replacement.id,
        expected_generation=replacement.manifest_generation,
    )
    with backend.open_authorized_private(
        private_url.token,
        replacement.object_key,
        asset_id=replacement.id,
        expected_generation=replacement.manifest_generation,
    ) as stream:
        replacement_content = stream.read()
    assert replacement.mime == "video/mp4"
    assert replacement.size == len(replacement_content)
    assert replacement.sha256 == hashlib.sha256(replacement_content).hexdigest()
    _assert_parseable_video_mp4(replacement_content)
    assert face_task.source_asset_id == replacement.id
    assert not MediaAsset.objects.filter(pk=legacy_asset.id).exists()
    stable_asset_id = replacement.id

    call_command("seed_demo", stdout=StringIO())

    session.refresh_from_db()
    binding.refresh_from_db()
    face_task.refresh_from_db()
    face_result.refresh_from_db()
    assert (session.id, binding.id, face_task.id, face_result.id) == stable_ids
    assert binding.asset_id == stable_asset_id
    assert _demo_counts()["assets"] == 14


@pytest.mark.django_db(transaction=True)
def test_seed_demo_rejects_a_fixed_login_owned_by_another_role(tmp_path, settings):
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    User.objects.create_user(
        login_id="DDEMO001",
        password="unrelated",
        role=Role.PATIENT,
    )

    with pytest.raises(ValueError, match="DDEMO001"):
        call_command("seed_demo", stdout=StringIO())

    assert DoctorProfile.objects.filter(employee_no="DDEMO001").count() == 0


@pytest.mark.django_db
def test_seed_demo_refuses_to_run_outside_local_or_test_environment(settings):
    settings.MEDIA_ENVIRONMENT = "production"

    with pytest.raises(ValueError, match="仅允许在 local/test"):
        call_command("seed_demo", stdout=StringIO())

    assert not User.objects.filter(login_id="demo-admin").exists()


@pytest.mark.django_db(transaction=True)
def test_seed_demo_does_not_reset_an_existing_changed_password(tmp_path, settings):
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    call_command("seed_demo", stdout=StringIO())
    user = User.objects.get(login_id="DDEMO001")
    user.set_password("changed-demo-password")
    user.must_change_password = False
    user.save()

    call_command("seed_demo", stdout=StringIO())

    user.refresh_from_db()
    assert user.check_password("changed-demo-password")
    assert user.must_change_password is False
