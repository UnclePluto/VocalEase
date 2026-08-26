from io import BytesIO, StringIO
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
from apps.singing.models import SingingSession
from apps.songs.models import Song
from apps.media.services import storage_backend_for


DEMO_LOGIN_IDS = {"demo-admin", "DDEMO001", "PDEMO001", "PDEMO002"}


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
        if asset.media_type == "singing_video":
            assert asset.mime == "video/mp4"
            assert content[4:8] == b"ftyp"
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
