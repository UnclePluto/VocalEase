from io import StringIO

import pytest
from django.core.management import call_command

from apps.accounts.models import Role, User
from apps.media.models import MediaAsset
from apps.media.services import storage_backend_for
from apps.patients.models import PatientProfile
from apps.songs.models import SongUploadIntent
from apps.songs.services import issue_song_upload_grant


@pytest.mark.django_db
def test_qa_e2e_account_has_unique_prefix_and_is_hard_cleaned():
    output = StringIO()
    call_command("qa_e2e", "--run-id", "qa13-ab12cd34", stdout=output)
    login_id = output.getvalue().strip()
    user = User.objects.get(login_id=login_id)

    assert login_id == "QA13AB12CD34"
    assert user.role == Role.SYSTEM_ADMIN
    assert user.must_change_password is True
    assert user.check_password("888888")

    call_command("qa_e2e", "--run-id", "qa13-ab12cd34", "--cleanup", stdout=StringIO())

    assert not User.objects.filter(login_id=login_id).exists()


@pytest.mark.django_db
def test_qa_e2e_rejects_unscoped_run_id():
    with pytest.raises(ValueError, match="qa13"):
        call_command("qa_e2e", "--run-id", "production", stdout=StringIO())


@pytest.mark.django_db
def test_qa_e2e_refuses_to_delete_an_unmarked_colliding_account():
    login_id = "QA13AB12CD34"
    existing = User.objects.create_user(
        login_id=login_id,
        password="unrelated-password",
        role=Role.SYSTEM_ADMIN,
    )

    with pytest.raises(ValueError, match="非 QA"):
        call_command("qa_e2e", "--run-id", "qa13-ab12cd34", "--cleanup", stdout=StringIO())

    assert User.objects.filter(pk=existing.pk).exists()


@pytest.mark.django_db
def test_qa_e2e_refuses_to_run_outside_local_or_test_environment(settings):
    settings.MEDIA_ENVIRONMENT = "production"

    with pytest.raises(ValueError, match="仅允许在 local/test"):
        call_command("qa_e2e", "--run-id", "qa13-ab12cd34", stdout=StringIO())

    assert not User.objects.filter(login_id="QA13AB12CD34").exists()


@pytest.mark.django_db(transaction=True)
def test_qa_e2e_cleanup_removes_interrupted_song_intent_asset_and_local_files(
    tmp_path, settings
):
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    run_id = "qa13-ab12cd34"
    call_command("qa_e2e", "--run-id", run_id, stdout=StringIO())
    admin = User.objects.get(login_id="QA13AB12CD34")
    backend = storage_backend_for("local")
    baseline_blobs = set((tmp_path / ".blobs").glob("*"))
    baseline_manifests = set((tmp_path / ".manifests").rglob("*.json"))
    payload = b"RIFF\x04\x00\x00\x00WAVE"
    _song_id, asset, grant = issue_song_upload_grant(
        actor=admin,
        request_id="qa-cleanup-test",
        mime="audio/wav",
        size=len(payload),
    )
    backend.write_upload(
        grant=grant,
        content=payload,
        mime="audio/wav",
        asset_id=asset.id,
    )
    assert SongUploadIntent.objects.filter(asset=asset).exists()
    assert set((tmp_path / ".blobs").glob("*")) != baseline_blobs

    call_command(
        "qa_e2e", "--run-id", run_id, "--cleanup-artifacts", stdout=StringIO()
    )

    assert not SongUploadIntent.objects.filter(asset_id=asset.id).exists()
    assert not MediaAsset.objects.filter(id=asset.id).exists()
    assert set((tmp_path / ".blobs").glob("*")) == baseline_blobs
    assert set((tmp_path / ".manifests").rglob("*.json")) == baseline_manifests


@pytest.mark.django_db(transaction=True)
def test_qa_e2e_analytics_load_is_run_scoped_and_hard_cleaned(tmp_path, settings):
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    run_id = "qa13-ab12cd34"
    call_command("seed_demo", stdout=StringIO())
    call_command("qa_e2e", "--run-id", run_id, stdout=StringIO())

    call_command(
        "qa_e2e",
        "--run-id",
        run_id,
        "--prepare-analytics-load",
        stdout=StringIO(),
    )

    assert PatientProfile.objects.filter(medical_record_no__startswith="QAB12CD34").count() == 1001
    assert User.objects.filter(login_id__startswith="QAB12CD34").count() == 1001

    call_command(
        "qa_e2e", "--run-id", run_id, "--cleanup-artifacts", stdout=StringIO()
    )

    assert User.objects.filter(login_id="QA13AB12CD34").exists()
    assert PatientProfile.objects.filter(medical_record_no__startswith="QAB12CD34").count() == 0
    call_command("qa_e2e", "--run-id", run_id, "--cleanup", stdout=StringIO())

    assert PatientProfile.objects.filter(medical_record_no__startswith="QAB12CD34").count() == 0
    assert User.objects.filter(login_id__startswith="QAB12CD34").count() == 0
    assert PatientProfile.objects.filter(medical_record_no="PDEMO001").exists()
