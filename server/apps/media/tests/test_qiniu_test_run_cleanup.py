from io import StringIO
import hashlib
import hmac
from datetime import datetime, timedelta
from uuid import uuid4

import pytest
from django.core.management import call_command
from django.utils import timezone
from rest_framework.exceptions import ValidationError

from apps.accounts.models import User
from apps.analysis.models import AnalysisTask
from apps.audit.models import AuditLog
from apps.doctors.models import SequenceCounter
from apps.doctors.services import create_doctor
from apps.media.models import MediaAsset
from apps.media.backends.qiniu import QiniuStorageBackend
from apps.media.services import (
    MediaConflict,
    complete_qiniu_callback,
    create_upload_grant,
)
from apps.patients.models import PatientProfile
from apps.patients.services import create_patient
from apps.singing.models import SessionMedia, SingingSession
from apps.songs.models import Song


RUN_ID = "qa13-ab12cd34"


def _expected_prefix(settings, patient_id):
    patient_scope = hmac.new(
        settings.SECRET_KEY.encode(),
        f"qiniu-test-run:{RUN_ID}:{patient_id}".encode(),
        hashlib.sha256,
    ).hexdigest()[:24]
    return f"test-{RUN_ID}-{patient_scope}/"


def _dry_run_token(scope):
    output = StringIO()
    call_command(
        "cleanup_qiniu_test_run",
        "--run-id",
        RUN_ID,
        "--patient-id",
        str(scope["patient"].id),
        "--object-prefix",
        scope["prefix"],
        "--dry-run",
        stdout=output,
    )
    return next(
        line.removeprefix("confirmation_token=")
        for line in output.getvalue().splitlines()
        if line.startswith("confirmation_token=")
    )


class InMemoryKodoCleanupClient:
    def __init__(self, scope):
        self.objects = {
            *(asset.object_key for asset in scope["assets"]),
            scope["unrelated"].object_key,
        }
        self.target_asset_ids = [asset.id for asset in scope["assets"]]
        self.callback_rejections = []
        self.statuses_seen_before_delete = []

    def list_keys(self, object_prefix):
        return tuple(
            sorted(key for key in self.objects if key.startswith(object_prefix))
        )

    def delete(self, object_key):
        self.statuses_seen_before_delete.append(
            set(
                MediaAsset.objects.filter(id__in=self.target_asset_ids).values_list(
                    "status", flat=True
                )
            )
        )
        try:
            complete_qiniu_callback(
                payload={"key": object_key},
                backend=object(),
            )
        except MediaConflict as exc:
            self.callback_rejections.append(exc.get_codes())
        self.objects.discard(object_key)

    def exists(self, object_key):
        return object_key in self.objects


def _mature_quiet_gate(scope, monkeypatch, kodo):
    from apps.media.management.commands.cleanup_qiniu_test_run import Command

    clock = {"now": timezone.now()}
    monkeypatch.setattr(Command, "_build_kodo_client", lambda _command: kodo)
    monkeypatch.setattr(Command, "_now", lambda _command: clock["now"])
    monkeypatch.setattr(Command, "_sleep", lambda _command, _seconds: None)
    initial_token = _dry_run_token(scope)
    call_command(
        "cleanup_qiniu_test_run",
        "--run-id",
        RUN_ID,
        "--patient-id",
        str(scope["patient"].id),
        "--object-prefix",
        scope["prefix"],
        "--confirm",
        initial_token,
        stdout=StringIO(),
    )
    clock["now"] += timedelta(days=1)
    return _dry_run_token(scope)


@pytest.fixture
def qiniu_test_run(db, settings):
    call_command("qa_e2e", "--run-id", RUN_ID, stdout=StringIO())
    admin = User.objects.get(login_id="QA13AB12CD34")
    SequenceCounter.objects.bulk_create(
        [SequenceCounter(prefix="D"), SequenceCounter(prefix="P")],
        ignore_conflicts=True,
    )
    doctor = create_doctor(
        name="清理医生",
        gender="male",
        phone="13600000831",
        department="康复科",
        title="医师",
        actor=admin,
        request_id="qiniu-cleanup-doctor",
    )
    patient = create_patient(
        name="清理患者",
        gender="female",
        enrollment_age=42,
        phone="13500000831",
        doctor=doctor,
        start_date="2026-08-01",
        cycle_weeks=4,
        actor=admin,
        request_id="qiniu-cleanup-patient",
    )
    plan = patient.treatment_plans.get()
    plan.status = "active"
    plan.save(update_fields=["status", "updated_at"])
    song = Song.objects.create(
        title="清理测试歌曲",
        artist="测试歌手",
        genre="流行",
        language="中文",
        duration_seconds=88,
    )
    session = SingingSession.objects.create_from_snapshots(
        patient=patient,
        song=song,
    )
    prefix = _expected_prefix(settings, patient.id)
    assets = []
    for media_type, mime in (
        ("singing_audio", "audio/mpeg"),
        ("singing_video", "video/mp4"),
    ):
        asset = MediaAsset.objects.create(
            patient_owner=patient,
            owner_type="patient",
            owner_id=patient.id,
            media_type=media_type,
            backend="qiniu",
            object_key=f"{prefix}{media_type}/{uuid4().hex}",
            mime=mime,
            size=4096,
            etag=f"etag-{media_type}",
            status="ready",
            upload_expires_at=timezone.now() + timedelta(minutes=15),
        )
        SessionMedia.objects.create(
            session=session,
            asset=asset,
            media_type=media_type,
            confirmed_at=timezone.now(),
        )
        assets.append(asset)
    task = AnalysisTask.objects.create(
        target_type="singing_session",
        target_id=session.id,
        source_asset=assets[0],
        task_type="singing_audio_metrics",
        executor="mock_singing",
        idempotency_key=f"cleanup-{session.id}",
        input_snapshot={},
    )
    AuditLog.objects.create(
        actor=admin,
        action="qa_e2e.analysis_created",
        target_type="analysis.AnalysisTask",
        target_id=task.id,
        changes={"run_id": RUN_ID},
        request_id="qiniu-cleanup-analysis",
    )
    unrelated = MediaAsset.objects.create(
        patient_owner=patient,
        owner_type="patient",
        owner_id=patient.id,
        media_type="singing_audio",
        backend="qiniu",
        object_key=f"test/unrelated/{uuid4().hex}",
        mime="audio/mpeg",
        size=1,
        status="uploading",
        upload_expires_at=timezone.now() + timedelta(minutes=15),
    )
    settings.MEDIA_BACKEND = "qiniu"
    settings.MEDIA_ENVIRONMENT = prefix.removesuffix("/")
    return {
        "admin": admin,
        "patient": patient,
        "session": session,
        "assets": assets,
        "task": task,
        "unrelated": unrelated,
        "prefix": prefix,
    }


def test_cleanup_refuses_production_namespace_by_default(settings):
    settings.MEDIA_ENVIRONMENT = "production"

    with pytest.raises(ValueError, match="测试命名空间"):
        call_command(
            "cleanup_qiniu_test_run",
            "--run-id",
            "qa13-ab12cd34",
            "--patient-id",
            "00000000-0000-0000-0000-000000000001",
            "--object-prefix",
            "production/",
            "--dry-run",
            stdout=StringIO(),
        )


def test_dry_run_uses_qa_provenance_and_lists_kodo_without_deleting(
    qiniu_test_run, monkeypatch
):
    from apps.media.management.commands.cleanup_qiniu_test_run import Command

    kodo = InMemoryKodoCleanupClient(qiniu_test_run)
    before = set(kodo.objects)
    monkeypatch.setattr(Command, "_build_kodo_client", lambda _command: kodo)
    output = StringIO()

    call_command(
        "cleanup_qiniu_test_run",
        "--run-id",
        RUN_ID,
        "--patient-id",
        str(qiniu_test_run["patient"].id),
        "--object-prefix",
        qiniu_test_run["prefix"],
        "--dry-run",
        stdout=output,
    )

    rendered = output.getvalue()
    assert "sessions=1 assets=2 kodo_objects=2" in rendered
    assert "confirmation_token=" in rendered
    assert str(qiniu_test_run["session"].id) not in rendered
    assert all(str(asset.id) not in rendered for asset in qiniu_test_run["assets"])
    assert SingingSession.objects.filter(pk=qiniu_test_run["session"].id).exists()
    assert (
        MediaAsset.objects.filter(
            id__in=[asset.id for asset in qiniu_test_run["assets"]]
        ).count()
        == 2
    )
    assert kodo.objects == before


def test_cleanup_rejects_patient_without_this_run_source_marker(qiniu_test_run):
    AuditLog.objects.filter(
        actor=qiniu_test_run["admin"],
        action="patient.create",
        target_id=qiniu_test_run["patient"].id,
    ).delete()

    with pytest.raises(ValueError, match="QA 来源链"):
        call_command(
            "cleanup_qiniu_test_run",
            "--run-id",
            RUN_ID,
            "--patient-id",
            str(qiniu_test_run["patient"].id),
            "--object-prefix",
            qiniu_test_run["prefix"],
            "--dry-run",
            stdout=StringIO(),
        )


def test_confirmed_cleanup_drains_callbacks_then_deletes_kodo_and_database(
    qiniu_test_run, monkeypatch
):
    kodo = InMemoryKodoCleanupClient(qiniu_test_run)
    token = _mature_quiet_gate(qiniu_test_run, monkeypatch, kodo)
    output = StringIO()

    call_command(
        "cleanup_qiniu_test_run",
        "--run-id",
        RUN_ID,
        "--patient-id",
        str(qiniu_test_run["patient"].id),
        "--object-prefix",
        qiniu_test_run["prefix"],
        "--confirm",
        token,
        stdout=output,
    )

    assert kodo.statuses_seen_before_delete == [
        {MediaAsset.Status.PENDING_CLEANUP},
        {MediaAsset.Status.PENDING_CLEANUP},
    ]
    assert kodo.callback_rejections == [
        "media_grant_expired",
        "media_grant_expired",
    ]
    assert kodo.objects == {qiniu_test_run["unrelated"].object_key}
    assert not SingingSession.objects.filter(pk=qiniu_test_run["session"].id).exists()
    assert not MediaAsset.objects.filter(
        id__in=[asset.id for asset in qiniu_test_run["assets"]]
    ).exists()
    assert not AnalysisTask.objects.filter(
        target_id=qiniu_test_run["session"].id
    ).exists()
    assert not AuditLog.objects.filter(target_id=qiniu_test_run["task"].id).exists()
    assert MediaAsset.objects.filter(pk=qiniu_test_run["unrelated"].id).exists()
    assert "quiet=stable kodo=verified database=verified" in output.getvalue()


def test_confirmation_token_is_invalidated_when_scoped_inventory_changes(
    qiniu_test_run, monkeypatch
):
    from apps.media.management.commands.cleanup_qiniu_test_run import Command

    kodo = InMemoryKodoCleanupClient(qiniu_test_run)
    monkeypatch.setattr(Command, "_build_kodo_client", lambda _command: kodo)
    token = _dry_run_token(qiniu_test_run)
    original = qiniu_test_run["session"]
    added_session = SingingSession.objects.create(
        patient=original.patient,
        song=original.song,
        treatment_plan=original.treatment_plan,
        patient_snapshot=original.patient_snapshot,
        song_snapshot=original.song_snapshot,
        treatment_plan_snapshot=original.treatment_plan_snapshot,
    )
    added_asset = MediaAsset.objects.create(
        patient_owner=original.patient,
        owner_type="patient",
        owner_id=original.patient_id,
        media_type="singing_audio",
        backend="qiniu",
        object_key=f"{qiniu_test_run['prefix']}singing_audio/{uuid4().hex}",
        mime="audio/mpeg",
        size=1,
        status="uploading",
        upload_expires_at=timezone.now() + timedelta(minutes=15),
    )
    SessionMedia.objects.create(
        session=added_session,
        asset=added_asset,
        media_type="singing_audio",
    )
    monkeypatch.setattr(
        Command,
        "_build_kodo_client",
        lambda _command: (_ for _ in ()).throw(
            AssertionError("清单变更时不得访问 Kodo")
        ),
    )

    with pytest.raises(ValueError, match="清理清单不一致"):
        call_command(
            "cleanup_qiniu_test_run",
            "--run-id",
            RUN_ID,
            "--patient-id",
            str(qiniu_test_run["patient"].id),
            "--object-prefix",
            qiniu_test_run["prefix"],
            "--confirm",
            token,
            stdout=StringIO(),
        )

    assert MediaAsset.objects.filter(pk=added_asset.id).exists()
    assert SingingSession.objects.filter(pk=added_session.id).exists()


def test_cleanup_keeps_database_records_when_kodo_postcheck_finds_an_object(
    qiniu_test_run, monkeypatch
):
    class StickyKodoCleanupClient(InMemoryKodoCleanupClient):
        def delete(self, object_key):
            super().delete(object_key)
            self.objects.add(object_key)

    kodo = StickyKodoCleanupClient(qiniu_test_run)
    token = _mature_quiet_gate(qiniu_test_run, monkeypatch, kodo)

    with pytest.raises(ValueError, match="对象仍存在"):
        call_command(
            "cleanup_qiniu_test_run",
            "--run-id",
            RUN_ID,
            "--patient-id",
            str(qiniu_test_run["patient"].id),
            "--object-prefix",
            qiniu_test_run["prefix"],
            "--confirm",
            token,
            stdout=StringIO(),
        )

    assert SingingSession.objects.filter(pk=qiniu_test_run["session"].id).exists()
    assert (
        MediaAsset.objects.filter(
            id__in=[asset.id for asset in qiniu_test_run["assets"]],
            status=MediaAsset.Status.PENDING_CLEANUP,
        ).count()
        == 2
    )
    assert AnalysisTask.objects.filter(target_id=qiniu_test_run["session"].id).exists()


def test_operator_cannot_forge_a_different_test_prefix(qiniu_test_run, settings):
    forged_prefix = "test-qa13-ab12cd34-operator-controlled/"
    settings.MEDIA_ENVIRONMENT = forged_prefix.removesuffix("/")
    for asset in qiniu_test_run["assets"]:
        asset.object_key = asset.object_key.replace(
            qiniu_test_run["prefix"], forged_prefix
        )
        asset.save(update_fields=["object_key", "updated_at"])

    with pytest.raises(ValueError, match="run-id、患者绑定"):
        call_command(
            "cleanup_qiniu_test_run",
            "--run-id",
            RUN_ID,
            "--patient-id",
            str(qiniu_test_run["patient"].id),
            "--object-prefix",
            forged_prefix,
            "--dry-run",
            stdout=StringIO(),
        )


def test_cleanup_rejects_foreign_patient_asset_inside_bound_prefix(qiniu_test_run):
    foreign_user = User.objects.create_user(
        login_id="PFOREIGN1",
        password="888888",
        role="patient",
    )
    foreign_patient = PatientProfile.objects.create(
        user=foreign_user,
        medical_record_no="PFOREIGN1",
        name="其他患者",
        gender="male",
        enrollment_age=36,
        phone="13500000839",
        primary_doctor=qiniu_test_run["patient"].primary_doctor,
    )
    foreign_asset = MediaAsset.objects.create(
        patient_owner=foreign_patient,
        owner_type="patient",
        owner_id=foreign_patient.id,
        media_type="singing_audio",
        backend="qiniu",
        object_key=f"{qiniu_test_run['prefix']}singing_audio/{uuid4().hex}",
        mime="audio/mpeg",
        size=1,
        status="uploading",
        upload_expires_at=timezone.now() + timedelta(minutes=15),
    )

    with pytest.raises(ValueError, match="不属于目标患者"):
        call_command(
            "cleanup_qiniu_test_run",
            "--run-id",
            RUN_ID,
            "--patient-id",
            str(qiniu_test_run["patient"].id),
            "--object-prefix",
            qiniu_test_run["prefix"],
            "--dry-run",
            stdout=StringIO(),
        )

    assert MediaAsset.objects.filter(pk=foreign_asset.id).exists()


def test_derive_prefix_requires_qa_provenance_but_not_kodo_or_current_namespace(
    qiniu_test_run, settings, monkeypatch
):
    from apps.media.management.commands.cleanup_qiniu_test_run import Command

    settings.MEDIA_ENVIRONMENT = "local"
    monkeypatch.setattr(
        Command,
        "_build_kodo_client",
        lambda _command: (_ for _ in ()).throw(AssertionError("派生前缀不得访问 Kodo")),
    )
    output = StringIO()

    call_command(
        "cleanup_qiniu_test_run",
        "--run-id",
        RUN_ID,
        "--patient-id",
        str(qiniu_test_run["patient"].id),
        "--derive-prefix",
        stdout=output,
    )

    assert output.getvalue().strip() == f"object_prefix={qiniu_test_run['prefix']}"
    assert str(qiniu_test_run["patient"].id) not in output.getvalue()


def test_cleanup_deletes_orphaned_kodo_object_inside_bound_namespace(
    qiniu_test_run, monkeypatch
):
    orphan_key = f"{qiniu_test_run['prefix']}singing_video/orphaned-upload"
    kodo = InMemoryKodoCleanupClient(qiniu_test_run)
    kodo.objects.add(orphan_key)
    token = _mature_quiet_gate(qiniu_test_run, monkeypatch, kodo)

    call_command(
        "cleanup_qiniu_test_run",
        "--run-id",
        RUN_ID,
        "--patient-id",
        str(qiniu_test_run["patient"].id),
        "--object-prefix",
        qiniu_test_run["prefix"],
        "--confirm",
        token,
        stdout=StringIO(),
    )

    assert orphan_key not in kodo.objects
    assert not kodo.list_keys(qiniu_test_run["prefix"])


def test_confirmation_token_is_invalidated_when_kodo_inventory_changes(
    qiniu_test_run, monkeypatch
):
    from apps.media.management.commands.cleanup_qiniu_test_run import Command

    kodo = InMemoryKodoCleanupClient(qiniu_test_run)
    monkeypatch.setattr(Command, "_build_kodo_client", lambda _command: kodo)
    token = _dry_run_token(qiniu_test_run)
    late_key = f"{qiniu_test_run['prefix']}singing_audio/late-object"
    kodo.objects.add(late_key)

    with pytest.raises(ValueError, match="清理清单不一致"):
        call_command(
            "cleanup_qiniu_test_run",
            "--run-id",
            RUN_ID,
            "--patient-id",
            str(qiniu_test_run["patient"].id),
            "--object-prefix",
            qiniu_test_run["prefix"],
            "--confirm",
            token,
            stdout=StringIO(),
        )

    assert late_key in kodo.objects
    assert (
        MediaAsset.objects.filter(
            id__in=[asset.id for asset in qiniu_test_run["assets"]],
            status=MediaAsset.Status.READY,
        ).count()
        == 2
    )


def test_kodo_cleanup_client_paginates_and_treats_missing_delete_as_idempotent():
    from apps.media.management.commands.cleanup_qiniu_test_run import (
        KodoCleanupClient,
    )

    class Info:
        def __init__(self, status_code):
            self.status_code = status_code

    class BucketManagerDouble:
        def __init__(self):
            self.objects = {"test/scope/audio", "test/scope/video"}

        def list(self, bucket, *, prefix, marker, limit):
            assert bucket == "test-bucket"
            assert prefix == "test/scope/"
            assert limit == 1000
            if marker is None:
                return (
                    {
                        "items": [{"key": "test/scope/video"}],
                        "marker": "next-page",
                    },
                    False,
                    Info(200),
                )
            return {"items": [{"key": "test/scope/audio"}]}, True, Info(200)

        def delete(self, bucket, object_key):
            assert bucket == "test-bucket"
            existed = object_key in self.objects
            self.objects.discard(object_key)
            return None, Info(200 if existed else 612)

        def stat(self, bucket, object_key):
            assert bucket == "test-bucket"
            return None, Info(200 if object_key in self.objects else 612)

    manager = BucketManagerDouble()
    client = KodoCleanupClient(
        access_key="unused-access-key",
        secret_key="unused-secret-key",
        bucket="test-bucket",
        bucket_manager=manager,
    )

    assert client.list_keys("test/scope/") == (
        "test/scope/audio",
        "test/scope/video",
    )
    client.delete("test/scope/audio")
    client.delete("test/scope/audio")
    assert not client.exists("test/scope/audio")
    assert client.exists("test/scope/video")


def test_cleanup_also_deletes_run_patient_session_created_before_any_upload(
    qiniu_test_run, monkeypatch
):
    original = qiniu_test_run["session"]
    empty_session = SingingSession.objects.create(
        patient=original.patient,
        song=original.song,
        treatment_plan=original.treatment_plan,
        patient_snapshot=original.patient_snapshot,
        song_snapshot=original.song_snapshot,
        treatment_plan_snapshot=original.treatment_plan_snapshot,
    )
    kodo = InMemoryKodoCleanupClient(qiniu_test_run)
    token = _mature_quiet_gate(qiniu_test_run, monkeypatch, kodo)

    call_command(
        "cleanup_qiniu_test_run",
        "--run-id",
        RUN_ID,
        "--patient-id",
        str(qiniu_test_run["patient"].id),
        "--object-prefix",
        qiniu_test_run["prefix"],
        "--confirm",
        token,
        stdout=StringIO(),
    )

    assert not SingingSession.objects.filter(pk=empty_session.id).exists()


def test_quiet_gate_catches_object_written_after_the_previous_final_list(
    qiniu_test_run, monkeypatch
):
    from apps.media.management.commands.cleanup_qiniu_test_run import Command

    late_key = f"{qiniu_test_run['prefix']}singing_video/late-after-final-list"

    class LateWritingKodo(InMemoryKodoCleanupClient):
        def __init__(self, scope):
            super().__init__(scope)
            self.list_count = 0

        def list_keys(self, object_prefix):
            self.list_count += 1
            visible = super().list_keys(object_prefix)
            if self.list_count == 3:
                self.objects.add(late_key)
            return visible

    clock = {"now": timezone.now()}
    kodo = LateWritingKodo(qiniu_test_run)
    monkeypatch.setattr(Command, "_build_kodo_client", lambda _command: kodo)
    monkeypatch.setattr(
        Command,
        "_now",
        lambda _command: clock["now"],
        raising=False,
    )
    monkeypatch.setattr(
        Command, "_sleep", lambda _command, _seconds: None, raising=False
    )
    token = _dry_run_token(qiniu_test_run)
    gate_output = StringIO()

    call_command(
        "cleanup_qiniu_test_run",
        "--run-id",
        RUN_ID,
        "--patient-id",
        str(qiniu_test_run["patient"].id),
        "--object-prefix",
        qiniu_test_run["prefix"],
        "--confirm",
        token,
        stdout=gate_output,
    )

    assert "quiet=started" in gate_output.getvalue()
    assert "kodo=verified" not in gate_output.getvalue()
    assert SingingSession.objects.filter(pk=qiniu_test_run["session"].id).exists()

    clock["now"] += timedelta(minutes=16)
    stale_token = _dry_run_token(qiniu_test_run)
    with pytest.raises(ValueError, match="清理清单不一致"):
        call_command(
            "cleanup_qiniu_test_run",
            "--run-id",
            RUN_ID,
            "--patient-id",
            str(qiniu_test_run["patient"].id),
            "--object-prefix",
            qiniu_test_run["prefix"],
            "--confirm",
            stale_token,
            stdout=StringIO(),
        )

    fresh_token = _dry_run_token(qiniu_test_run)
    final_output = StringIO()
    call_command(
        "cleanup_qiniu_test_run",
        "--run-id",
        RUN_ID,
        "--patient-id",
        str(qiniu_test_run["patient"].id),
        "--object-prefix",
        qiniu_test_run["prefix"],
        "--confirm",
        fresh_token,
        stdout=final_output,
    )

    assert late_key not in kodo.objects
    assert "quiet=stable kodo=verified database=verified" in final_output.getvalue()


def test_quiet_gate_persistently_rejects_new_grants_and_callbacks(
    qiniu_test_run, monkeypatch
):
    from apps.media.management.commands.cleanup_qiniu_test_run import (
        Command,
        QUIET_GATE_ACTION,
    )

    kodo = InMemoryKodoCleanupClient(qiniu_test_run)
    monkeypatch.setattr(Command, "_build_kodo_client", lambda _command: kodo)
    monkeypatch.setattr(Command, "_now", lambda _command: timezone.now())
    token = _dry_run_token(qiniu_test_run)
    call_command(
        "cleanup_qiniu_test_run",
        "--run-id",
        RUN_ID,
        "--patient-id",
        str(qiniu_test_run["patient"].id),
        "--object-prefix",
        qiniu_test_run["prefix"],
        "--confirm",
        token,
        stdout=StringIO(),
    )

    backend = QiniuStorageBackend(
        access_key="offline-access-key",
        secret_key="offline-secret-key",
        bucket="offline-test-bucket",
        domain="https://cdn.example.test",
        callback_url="https://api.example.test/qiniu/callback/",
        environment=qiniu_test_run["prefix"].removesuffix("/"),
    )
    with pytest.raises(ValidationError, match="不存在或不可用"):
        create_upload_grant(
            owner_type="patient",
            owner_id=qiniu_test_run["patient"].id,
            media_type="singing_audio",
            mime="audio/mpeg",
            size=1,
            backend=backend,
        )
    with pytest.raises(MediaConflict) as callback_error:
        complete_qiniu_callback(
            payload={"key": qiniu_test_run["assets"][0].object_key},
            backend=object(),
        )

    qiniu_test_run["patient"].refresh_from_db()
    qiniu_test_run["patient"].user.refresh_from_db()
    assert qiniu_test_run["patient"].deleted_at is not None
    assert not qiniu_test_run["patient"].user.is_active
    assert callback_error.value.get_codes() == "media_grant_expired"
    assert AuditLog.objects.filter(
        action=QUIET_GATE_ACTION,
        target_id=qiniu_test_run["patient"].id,
    ).exists()


def test_quiet_gate_never_verifies_before_all_upload_leases_expire(
    qiniu_test_run, monkeypatch
):
    from apps.media.management.commands.cleanup_qiniu_test_run import Command

    clock = {"now": timezone.now()}
    future_expiry = clock["now"] + timedelta(hours=2)
    MediaAsset.objects.filter(pk=qiniu_test_run["assets"][0].id).update(
        upload_expires_at=future_expiry
    )
    kodo = InMemoryKodoCleanupClient(qiniu_test_run)
    monkeypatch.setattr(Command, "_build_kodo_client", lambda _command: kodo)
    monkeypatch.setattr(Command, "_now", lambda _command: clock["now"])
    monkeypatch.setattr(
        Command,
        "_sleep",
        lambda _command, _seconds: (_ for _ in ()).throw(
            AssertionError("quiet deadline 前不得进入稳定窗口")
        ),
    )
    initial_token = _dry_run_token(qiniu_test_run)
    call_command(
        "cleanup_qiniu_test_run",
        "--run-id",
        RUN_ID,
        "--patient-id",
        str(qiniu_test_run["patient"].id),
        "--object-prefix",
        qiniu_test_run["prefix"],
        "--confirm",
        initial_token,
        stdout=StringIO(),
    )
    gate = AuditLog.objects.get(
        action="qiniu_test_run.cleanup_quiet_gate",
        target_id=qiniu_test_run["patient"].id,
    )
    assert datetime.fromisoformat(gate.changes["quiet_until"]) >= future_expiry
    waiting_token = _dry_run_token(qiniu_test_run)
    output = StringIO()

    call_command(
        "cleanup_qiniu_test_run",
        "--run-id",
        RUN_ID,
        "--patient-id",
        str(qiniu_test_run["patient"].id),
        "--object-prefix",
        qiniu_test_run["prefix"],
        "--confirm",
        waiting_token,
        stdout=output,
    )

    assert "quiet=waiting" in output.getvalue()
    assert "kodo=verified" not in output.getvalue()
    assert SingingSession.objects.filter(pk=qiniu_test_run["session"].id).exists()
