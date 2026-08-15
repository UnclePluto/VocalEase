import os
import re

from django.conf import settings
from django.core.management.base import BaseCommand
from django.db import transaction
from django.db.models import Q

from apps.accounts.models import Role, User
from apps.analysis.models import AnalysisTask
from apps.analytics.models import ExportJob
from apps.audit.models import AuditLog
from apps.doctors.models import DoctorProfile
from apps.media.models import MediaAsset
from apps.media.backends.local import LocalStorageBackend
from apps.media.services import backend_for_asset
from apps.patients.models import PatientProfile, TreatmentPlan
from apps.songs.models import Song, SongUploadIntent


RUN_ID_PATTERN = re.compile(r"^qa13-[0-9a-f]{8}$")
QA_MARKER_ACTION = "qa_e2e.account_created"
QA_ANALYTICS_MARKER_ACTION = "qa_e2e.analytics_load_created"


class Command(BaseCommand):
    help = "创建或硬清理 Task 13 独立浏览器验收账号"

    def add_arguments(self, parser):
        parser.add_argument("--run-id", required=True)
        parser.add_argument("--cleanup", action="store_true")
        parser.add_argument("--cleanup-artifacts", action="store_true")
        parser.add_argument("--prepare-analytics-load", action="store_true")

    def _has_marker(self, user: User, run_id: str) -> bool:
        return AuditLog.objects.filter(
            action=QA_MARKER_ACTION,
            target_type="accounts.User",
            target_id=user.id,
            changes__run_id=run_id,
        ).exists()

    def _analytics_prefix(self, run_id: str) -> str:
        return f"Q{run_id.removeprefix('qa13-').upper()}"

    def _purge_local_assets(self, assets) -> None:
        if assets.exclude(backend="local").exists():
            raise ValueError("QA 浏览器验收只能清理本地存储媒体")
        for asset in assets:
            backend = backend_for_asset(asset)
            if not isinstance(backend, LocalStorageBackend):
                raise ValueError("QA 浏览器验收只能清理 LocalStorageBackend 媒体")
            backend.purge_for_qa(asset.object_key, asset_id=asset.id)

    def _cleanup_analytics(self, *, admin: User, run_id: str) -> None:
        prefix = self._analytics_prefix(run_id)
        load_marker = AuditLog.objects.filter(
            action=QA_ANALYTICS_MARKER_ACTION,
            target_type="qa_e2e.analytics_load",
            target_id=admin.id,
            changes__run_id=run_id,
            changes__prefix=prefix,
        ).first()
        scoped_users = User.objects.filter(login_id__startswith=prefix, role=Role.PATIENT)
        if scoped_users.exists() and load_marker is None:
            raise ValueError(f"前缀 {prefix} 存在非 QA 命令创建的数据，拒绝删除")

        jobs = ExportJob.objects.filter(creator=admin)
        job_ids = list(jobs.values_list("id", flat=True))
        asset_ids = list(jobs.exclude(result_asset_id=None).values_list("result_asset_id", flat=True))
        if job_ids:
            AuditLog.objects.filter(target_type="analytics.ExportJob", target_id__in=job_ids).delete()
            jobs.delete()
        assets = MediaAsset.objects.filter(id__in=asset_ids)
        self._purge_local_assets(assets)
        assets.delete()

        PatientProfile.objects.filter(user__in=scoped_users).delete()
        scoped_users.delete()
        if load_marker is not None:
            load_marker.delete()

    def _cleanup_ui_mutations(self, *, admin: User) -> None:
        patient_ids = list(
            AuditLog.objects.filter(actor=admin, action="patient.create").values_list(
                "target_id", flat=True
            )
        )
        patients = PatientProfile.objects.filter(id__in=patient_ids)
        patient_user_ids = list(patients.values_list("user_id", flat=True))
        TreatmentPlan.objects.filter(patient__in=patients).delete()
        patients.delete()
        User.objects.filter(id__in=patient_user_ids).delete()

        doctor_ids = list(
            AuditLog.objects.filter(actor=admin, action="doctor.create").values_list(
                "target_id", flat=True
            )
        )
        doctors = DoctorProfile.objects.filter(id__in=doctor_ids)
        if PatientProfile.objects.filter(primary_doctor__in=doctors).exists():
            raise ValueError("QA 医生仍有关联患者，拒绝硬清理")
        doctor_user_ids = list(doctors.values_list("user_id", flat=True))
        doctors.delete()
        User.objects.filter(id__in=doctor_user_ids).delete()

        song_ids = list(
            AuditLog.objects.filter(actor=admin, action="song.create").values_list(
                "target_id", flat=True
            )
        )
        songs = Song.objects.filter(id__in=song_ids)
        source_asset_ids = list(
            songs.exclude(source_asset_id=None).values_list("source_asset_id", flat=True)
        )
        grant_asset_ids = list(
            AuditLog.objects.filter(actor=admin, action="song.upload_grant").values_list(
                "target_id", flat=True
            )
        )
        AnalysisTask.objects.filter(song_id__in=song_ids).delete()
        SongUploadIntent.objects.filter(
            Q(song_id__in=song_ids) | Q(asset_id__in=grant_asset_ids)
        ).delete()
        songs.delete()
        assets = MediaAsset.objects.filter(id__in=set(source_asset_ids + grant_asset_ids))
        self._purge_local_assets(assets)
        assets.delete()

    def _prepare_analytics(self, *, admin: User, run_id: str) -> int:
        self._cleanup_analytics(admin=admin, run_id=run_id)
        prefix = self._analytics_prefix(run_id)
        count = int(settings.ANALYTICS_SYNC_EXPORT_LIMIT) + 1
        doctor = DoctorProfile.objects.get(employee_no="DDEMO001", deleted_at__isnull=True)
        users = [
            User(
                login_id=f"{prefix}{index:04d}",
                password="!",
                role=Role.PATIENT,
                is_active=True,
                must_change_password=True,
            )
            for index in range(count)
        ]
        User.objects.bulk_create(users, batch_size=200)
        PatientProfile.objects.bulk_create(
            [
                PatientProfile(
                    user=user,
                    medical_record_no=user.login_id,
                    name=f"QA负载患者{index:04d}",
                    gender="female" if index % 2 else "male",
                    enrollment_age=30 + index % 40,
                    phone=f"139{index:08d}",
                    primary_doctor=doctor,
                    notes=f"{run_id} 浏览器异步导出负载",
                )
                for index, user in enumerate(users)
            ],
            batch_size=200,
        )
        AuditLog.objects.create(
            actor=admin,
            action=QA_ANALYTICS_MARKER_ACTION,
            target_type="qa_e2e.analytics_load",
            target_id=admin.id,
            changes={"run_id": run_id, "prefix": prefix, "count": count},
        )
        return count

    @transaction.atomic
    def handle(self, *args, **options):
        settings_module = os.environ.get("DJANGO_SETTINGS_MODULE", "")
        if (
            settings_module
            not in {
                "vocaease.settings.local",
                "vocaease.settings.test",
                "vocaease.settings.postgresql_test",
            }
            or settings.MEDIA_ENVIRONMENT not in {"local", "test"}
        ):
            raise ValueError("qa_e2e 仅允许在 local/test 环境运行")
        run_id = options["run_id"].lower()
        if not RUN_ID_PATTERN.fullmatch(run_id):
            raise ValueError("run-id 必须使用 qa13- 加 8 位十六进制字符")
        login_id = run_id.replace("-", "").upper()
        existing = User.objects.filter(login_id=login_id).first()
        if existing is not None:
            if not self._has_marker(existing, run_id):
                raise ValueError(f"账号 {login_id} 为非 QA 命令创建，拒绝删除")
            if options["prepare_analytics_load"]:
                count = self._prepare_analytics(admin=existing, run_id=run_id)
                self.stdout.write(str(count))
                return
            self._cleanup_analytics(admin=existing, run_id=run_id)
            self._cleanup_ui_mutations(admin=existing)
            if options["cleanup_artifacts"]:
                self.stdout.write(login_id)
                return
            AuditLog.objects.filter(
                Q(actor=existing)
                | Q(target_type="accounts.User", target_id=existing.id)
            ).delete()
            existing.delete()
        elif options["prepare_analytics_load"]:
            raise ValueError("请先创建带来源标记的 QA 管理员账号")
        elif options["cleanup_artifacts"]:
            raise ValueError("请先创建带来源标记的 QA 管理员账号")
        if options["cleanup"]:
            self.stdout.write(login_id)
            return
        user = User.objects.create_user(
            login_id=login_id,
            password="888888",
            role=Role.SYSTEM_ADMIN,
            is_active=True,
            must_change_password=True,
        )
        AuditLog.objects.create(
            actor=user,
            action=QA_MARKER_ACTION,
            target_type="accounts.User",
            target_id=user.id,
            changes={"run_id": run_id},
        )
        self.stdout.write(login_id)
