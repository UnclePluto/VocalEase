from __future__ import annotations

import hashlib
import hmac
import json
import re
import time
from dataclasses import dataclass, replace
from datetime import datetime, timedelta
from uuid import UUID

from django.conf import settings
from django.core import signing
from django.core.management.base import BaseCommand
from django.db import transaction
from django.utils import timezone
from django.utils.dateparse import parse_datetime
from qiniu import Auth, BucketManager

from apps.accounts.models import Role, User
from apps.analysis.models import AnalysisTask
from apps.audit.models import AuditLog
from apps.media.models import MediaAsset
from apps.patients.models import PatientProfile
from apps.singing.models import SessionMedia, SingingSession


RUN_ID_PATTERN = re.compile(r"^qa13-[0-9a-f]{8}$")
QA_MARKER_ACTION = "qa_e2e.account_created"
CONFIRMATION_SALT = "vocaease.qiniu-test-run-cleanup.v1"
CONFIRMATION_MAX_AGE_SECONDS = 10 * 60
QUIET_GATE_ACTION = "qiniu_test_run.cleanup_quiet_gate"
DEFAULT_STABLE_WINDOW_SECONDS = 5


@dataclass(frozen=True)
class CleanupInventory:
    run_id: str
    patient_id: UUID
    object_prefix: str
    session_ids: tuple[UUID, ...]
    asset_rows: tuple[tuple[UUID, str], ...]
    kodo_keys: tuple[str, ...] = ()

    @property
    def database_digest(self) -> str:
        payload = {
            "run_id": self.run_id,
            "patient_id": str(self.patient_id),
            "object_prefix": self.object_prefix,
            "session_ids": [str(value) for value in self.session_ids],
            "assets": [
                [str(asset_id), object_key] for asset_id, object_key in self.asset_rows
            ],
        }
        canonical = json.dumps(
            payload, ensure_ascii=True, sort_keys=True, separators=(",", ":")
        )
        return hashlib.sha256(canonical.encode()).hexdigest()

    @property
    def kodo_digest(self) -> str:
        canonical = json.dumps(self.kodo_keys, ensure_ascii=True, separators=(",", ":"))
        return hashlib.sha256(canonical.encode()).hexdigest()

    @property
    def digest(self) -> str:
        return f"{self.database_digest}.{self.kodo_digest}"


def expected_object_prefix(*, run_id: str, patient_id: UUID) -> str:
    patient_scope = hmac.new(
        settings.SECRET_KEY.encode(),
        f"qiniu-test-run:{run_id}:{patient_id}".encode(),
        hashlib.sha256,
    ).hexdigest()[:24]
    return f"test-{run_id}-{patient_scope}/"


class KodoCleanupClient:
    def __init__(
        self,
        *,
        access_key: str,
        secret_key: str,
        bucket: str,
        bucket_manager=None,
    ):
        self.bucket = bucket
        self.bucket_manager = bucket_manager or BucketManager(
            Auth(access_key, secret_key)
        )

    def list_keys(self, object_prefix: str) -> tuple[str, ...]:
        keys = []
        marker = None
        seen_markers = set()
        while True:
            try:
                result, eof, info = self.bucket_manager.list(
                    self.bucket,
                    prefix=object_prefix,
                    marker=marker,
                    limit=1000,
                )
            except Exception as exc:
                raise ValueError("七牛测试命名空间列举失败") from exc
            if getattr(info, "status_code", 0) != 200 or not isinstance(result, dict):
                raise ValueError("七牛测试命名空间列举失败")
            for item in result.get("items") or []:
                key = item.get("key") if isinstance(item, dict) else None
                if not isinstance(key, str) or not key.startswith(object_prefix):
                    raise ValueError("七牛测试命名空间返回了越界对象")
                keys.append(key)
            if eof:
                return tuple(sorted(set(keys)))
            marker = result.get("marker")
            if not marker or marker in seen_markers:
                raise ValueError("七牛测试命名空间分页标记无效")
            seen_markers.add(marker)

    def delete(self, object_key: str) -> None:
        try:
            _result, info = self.bucket_manager.delete(self.bucket, object_key)
        except Exception as exc:
            raise ValueError("七牛对象删除请求失败") from exc
        if getattr(info, "status_code", 0) not in {200, 612}:
            raise ValueError("七牛对象删除未获确认")

    def exists(self, object_key: str) -> bool:
        try:
            _result, info = self.bucket_manager.stat(self.bucket, object_key)
        except Exception as exc:
            raise ValueError("七牛对象删除后核验失败") from exc
        status_code = getattr(info, "status_code", 0)
        if status_code == 612:
            return False
        if status_code == 200:
            return True
        raise ValueError("七牛对象删除后核验失败")


class Command(BaseCommand):
    help = "安全清理单次七牛测试运行的 Kodo 对象与服务端数据"

    def add_arguments(self, parser):
        parser.add_argument("--run-id", required=True)
        parser.add_argument("--patient-id", required=True)
        parser.add_argument("--object-prefix")
        parser.add_argument("--derive-prefix", action="store_true")
        parser.add_argument("--dry-run", action="store_true")
        parser.add_argument("--confirm")

    def handle(self, *args, **options):
        run_id = options["run_id"].lower()
        if not RUN_ID_PATTERN.fullmatch(run_id):
            raise ValueError("run-id 必须使用 qa13- 加 8 位十六进制字符")
        try:
            patient_id = UUID(options["patient_id"])
        except (TypeError, ValueError) as exc:
            raise ValueError("patient-id 必须是有效 UUID") from exc
        expected_prefix = expected_object_prefix(run_id=run_id, patient_id=patient_id)
        if options["derive_prefix"]:
            if options["dry_run"] or options["confirm"] or options["object_prefix"]:
                raise ValueError("derive-prefix 不能与清理参数同时使用")
            self._validate_qa_provenance(run_id=run_id, patient_id=patient_id)
            self.stdout.write(f"object_prefix={expected_prefix}")
            return
        prefix = options["object_prefix"]
        if not prefix:
            raise ValueError("清理时必须提供服务端派生的 object-prefix")
        if (
            settings.MEDIA_ENVIRONMENT == "production"
            or not settings.MEDIA_ENVIRONMENT.startswith("test-")
        ):
            raise ValueError("仅允许清理隔离的七牛测试命名空间")
        if not hmac.compare_digest(prefix, expected_prefix):
            raise ValueError("对象前缀与 run-id、患者绑定不一致")
        if not hmac.compare_digest(settings.MEDIA_ENVIRONMENT + "/", expected_prefix):
            raise ValueError("当前媒体命名空间与待清理对象前缀不一致")

        database_inventory = self._load_inventory(
            run_id=run_id,
            patient_id=patient_id,
            object_prefix=prefix,
        )
        if options["dry_run"] and options["confirm"]:
            raise ValueError("dry-run 与 confirm 不能同时使用")
        if options["dry_run"]:
            kodo = self._build_kodo_client()
            inventory = replace(
                database_inventory,
                kodo_keys=kodo.list_keys(prefix),
            )
            token = signing.TimestampSigner(salt=CONFIRMATION_SALT).sign(
                inventory.digest
            )
            self.stdout.write(
                f"run={run_id[-8:]} patient={str(patient_id)[-8:]} "
                f"sessions={len(inventory.session_ids)} assets={len(inventory.asset_rows)} "
                f"kodo_objects={len(inventory.kodo_keys)}"
            )
            self.stdout.write(f"confirmation_token={token}")
            return
        if not options["confirm"]:
            raise ValueError("必须先执行 dry-run 并使用其确认令牌")
        confirmed_digest = self._read_confirmation(options["confirm"])
        confirmed_database_digest, separator, _confirmed_kodo_digest = (
            confirmed_digest.partition(".")
        )
        if not separator or not hmac.compare_digest(
            confirmed_database_digest,
            database_inventory.database_digest,
        ):
            raise ValueError("确认令牌与当前清理清单不一致，请重新 dry-run")
        kodo = self._build_kodo_client()
        inventory = replace(
            database_inventory,
            kodo_keys=kodo.list_keys(prefix),
        )
        if not hmac.compare_digest(confirmed_digest, inventory.digest):
            raise ValueError("确认令牌与当前清理清单不一致，请重新 dry-run")
        gate = self._quiet_gate(inventory)
        if gate is None:
            quiet_until = self._start_quiet_gate(inventory)
            self.stdout.write(
                f"quiet=started quiet_until={quiet_until.isoformat()} kodo=not_verified"
            )
            return
        quiet_until = self._refresh_quiet_gate(inventory, gate)
        if self._now() < quiet_until:
            self.stdout.write(
                f"quiet=waiting quiet_until={quiet_until.isoformat()} kodo=not_verified"
            )
            return
        self._sleep(self._stable_window_seconds())
        stable_database_inventory = self._load_inventory(
            run_id=run_id,
            patient_id=patient_id,
            object_prefix=prefix,
        )
        stable_inventory = replace(
            stable_database_inventory,
            kodo_keys=kodo.list_keys(prefix),
        )
        if not hmac.compare_digest(inventory.digest, stable_inventory.digest):
            raise ValueError("稳定窗口内清理清单发生变化，请重新 dry-run")
        inventory = stable_inventory
        database_keys = {object_key for _asset_id, object_key in inventory.asset_rows}
        keys_to_delete = tuple(sorted(database_keys | set(inventory.kodo_keys)))
        for object_key in keys_to_delete:
            kodo.delete(object_key)
        if any(kodo.exists(object_key) for object_key in keys_to_delete):
            raise ValueError("七牛对象仍存在，保留数据库记录以便安全重试")
        if kodo.list_keys(prefix):
            raise ValueError("七牛测试命名空间仍有对象，保留数据库记录以便安全重试")
        self._delete_database_records(inventory)
        self.stdout.write(
            f"sessions={len(inventory.session_ids)} assets={len(inventory.asset_rows)} "
            "quiet=stable kodo=verified database=verified"
        )

    def _now(self):
        return timezone.now()

    def _sleep(self, seconds: int) -> None:
        time.sleep(seconds)

    def _stable_window_seconds(self) -> int:
        value = int(
            getattr(
                settings,
                "QINIU_CLEANUP_STABLE_WINDOW_SECONDS",
                DEFAULT_STABLE_WINDOW_SECONDS,
            )
        )
        if value <= 0 or value > 60:
            raise ValueError("七牛清理稳定窗口必须在 1 到 60 秒之间")
        return value

    def _prefix_digest(self, object_prefix: str) -> str:
        return hashlib.sha256(object_prefix.encode()).hexdigest()

    def _quiet_gate(self, inventory: CleanupInventory):
        admin = self._qa_admin(inventory.run_id)
        return (
            AuditLog.objects.filter(
                actor=admin,
                action=QUIET_GATE_ACTION,
                target_type="patients.PatientProfile",
                target_id=inventory.patient_id,
                changes__run_id=inventory.run_id,
                changes__prefix_digest=self._prefix_digest(inventory.object_prefix),
                deleted_at__isnull=True,
            )
            .order_by("-created_at", "-id")
            .first()
        )

    def _quiet_deadline(self, *, started_at, assets) -> datetime:
        try:
            ttl_seconds = int(settings.MEDIA_UPLOAD_GRANT_TTL_SECONDS)
        except (TypeError, ValueError) as exc:
            raise ValueError("上传凭据 TTL 配置无效") from exc
        if ttl_seconds <= 0:
            raise ValueError("上传凭据 TTL 配置无效")
        deadlines = [started_at + timedelta(seconds=ttl_seconds)]
        for asset in assets:
            deadlines.append(asset.upload_expires_at)
            if asset.upload_lease_expires_at is not None:
                deadlines.append(asset.upload_lease_expires_at)
        return max(deadlines)

    def _locked_scope(self, inventory: CleanupInventory):
        patient = (
            PatientProfile.objects.select_for_update()
            .select_related("user")
            .get(pk=inventory.patient_id)
        )
        user = User.objects.select_for_update().get(pk=patient.user_id)
        sessions = list(
            SingingSession.objects.select_for_update()
            .filter(patient_id=patient.id)
            .order_by("id")
        )
        if [session.id for session in sessions] != list(inventory.session_ids):
            raise ValueError("清理清单已变化，请重新 dry-run")
        assets = list(
            MediaAsset.objects.select_for_update()
            .filter(
                backend="qiniu",
                object_key__startswith=inventory.object_prefix,
            )
            .order_by("id")
        )
        expected_asset_ids = [asset_id for asset_id, _key in inventory.asset_rows]
        if [asset.id for asset in assets] != expected_asset_ids:
            raise ValueError("清理清单已变化，请重新 dry-run")
        return patient, user, assets, sessions

    def _apply_quiet_state(self, *, patient, user, assets, sessions, now) -> None:
        if user.is_active:
            user.is_active = False
            user.save(update_fields=["is_active"])
        if patient.deleted_at is None:
            patient.deleted_at = now
            patient.save(update_fields=["deleted_at", "updated_at"])
        for asset in assets:
            asset.status = MediaAsset.Status.PENDING_CLEANUP
            asset.upload_nonce = None
            asset.upload_lease_expires_at = None
            asset.updated_at = now
        MediaAsset.objects.bulk_update(
            assets,
            ["status", "upload_nonce", "upload_lease_expires_at", "updated_at"],
        )
        for session in sessions:
            session.status = SingingSession.Status.CANCELLED
            session.updated_at = now
        SingingSession.objects.bulk_update(sessions, ["status", "updated_at"])

    def _start_quiet_gate(self, inventory: CleanupInventory):
        with transaction.atomic():
            if self._quiet_gate(inventory) is not None:
                raise ValueError("quiet gate 状态已变化，请重新 dry-run")
            patient, user, assets, sessions = self._locked_scope(inventory)
            if self._quiet_gate(inventory) is not None:
                raise ValueError("quiet gate 状态已变化，请重新 dry-run")
            now = self._now()
            quiet_until = self._quiet_deadline(started_at=now, assets=assets)
            self._apply_quiet_state(
                patient=patient,
                user=user,
                assets=assets,
                sessions=sessions,
                now=now,
            )
            admin = self._qa_admin(inventory.run_id)
            AuditLog.objects.create(
                actor=admin,
                action=QUIET_GATE_ACTION,
                target_type="patients.PatientProfile",
                target_id=patient.id,
                changes={
                    "run_id": inventory.run_id,
                    "prefix_digest": self._prefix_digest(inventory.object_prefix),
                    "started_at": now.isoformat(),
                    "quiet_until": quiet_until.isoformat(),
                },
            )
            return quiet_until

    def _refresh_quiet_gate(self, inventory: CleanupInventory, gate):
        with transaction.atomic():
            locked_gate = AuditLog.objects.select_for_update().get(pk=gate.pk)
            patient, user, assets, sessions = self._locked_scope(inventory)
            started_at = parse_datetime(str(locked_gate.changes.get("started_at", "")))
            stored_quiet_until = parse_datetime(
                str(locked_gate.changes.get("quiet_until", ""))
            )
            if started_at is None or stored_quiet_until is None:
                raise ValueError("quiet gate 记录无效")
            quiet_until = max(
                stored_quiet_until,
                self._quiet_deadline(started_at=started_at, assets=assets),
            )
            now = self._now()
            self._apply_quiet_state(
                patient=patient,
                user=user,
                assets=assets,
                sessions=sessions,
                now=now,
            )
            if quiet_until != stored_quiet_until:
                changes = dict(locked_gate.changes)
                changes["quiet_until"] = quiet_until.isoformat()
                locked_gate.changes = changes
                locked_gate.save(update_fields=["changes"])
            return quiet_until

    def _read_confirmation(self, token: str) -> str:
        try:
            return signing.TimestampSigner(salt=CONFIRMATION_SALT).unsign(
                token, max_age=CONFIRMATION_MAX_AGE_SECONDS
            )
        except signing.BadSignature as exc:
            raise ValueError("确认令牌无效或已过期") from exc

    def _build_kodo_client(self) -> KodoCleanupClient:
        if settings.MEDIA_BACKEND != "qiniu":
            raise ValueError("清理命令只允许用于七牛媒体后端")
        values = (
            settings.QINIU_ACCESS_KEY,
            settings.QINIU_SECRET_KEY,
            settings.QINIU_BUCKET,
        )
        if not all(values):
            raise ValueError("七牛清理凭据或 bucket 未配置")
        return KodoCleanupClient(
            access_key=settings.QINIU_ACCESS_KEY,
            secret_key=settings.QINIU_SECRET_KEY,
            bucket=settings.QINIU_BUCKET,
        )

    def _delete_database_records(self, inventory: CleanupInventory) -> None:
        asset_ids = [asset_id for asset_id, _object_key in inventory.asset_rows]
        session_ids = list(inventory.session_ids)
        with transaction.atomic():
            current = self._load_inventory(
                run_id=inventory.run_id,
                patient_id=inventory.patient_id,
                object_prefix=inventory.object_prefix,
            )
            if not hmac.compare_digest(
                current.database_digest,
                inventory.database_digest,
            ):
                raise ValueError("清理清单已变化，数据库记录保留以便核对")
            tasks = AnalysisTask.objects.filter(
                target_type=AnalysisTask.TargetType.SINGING_SESSION,
                target_id__in=session_ids,
            )
            task_ids = list(tasks.values_list("id", flat=True))
            AuditLog.objects.filter(
                target_id__in=[*session_ids, *asset_ids, *task_ids]
            ).delete()
            tasks.delete()
            SingingSession.objects.filter(id__in=session_ids).delete()
            MediaAsset.objects.filter(id__in=asset_ids).delete()
            if (
                MediaAsset.objects.filter(
                    backend="qiniu",
                    object_key__startswith=inventory.object_prefix,
                ).exists()
                or SingingSession.objects.filter(id__in=session_ids).exists()
                or MediaAsset.objects.filter(id__in=asset_ids).exists()
                or SessionMedia.objects.filter(session_id__in=session_ids).exists()
                or AnalysisTask.objects.filter(
                    target_type=AnalysisTask.TargetType.SINGING_SESSION,
                    target_id__in=session_ids,
                ).exists()
                or AuditLog.objects.filter(
                    target_id__in=[*session_ids, *asset_ids, *task_ids]
                ).exists()
            ):
                raise ValueError("数据库清理后核验失败")

    def _load_inventory(
        self,
        *,
        run_id: str,
        patient_id: UUID,
        object_prefix: str,
    ) -> CleanupInventory:
        patient = self._validate_qa_provenance(
            run_id=run_id,
            patient_id=patient_id,
        )

        prefix_assets = MediaAsset.objects.filter(
            backend="qiniu",
            object_key__startswith=object_prefix,
        )
        if prefix_assets.exclude(
            patient_owner_id=patient.id,
            owner_type=MediaAsset.OwnerType.PATIENT,
            owner_id=patient.id,
        ).exists():
            raise ValueError("对象前缀包含不属于目标患者的资产，拒绝清理")
        assets = prefix_assets.filter(
            patient_owner_id=patient.id,
            owner_type=MediaAsset.OwnerType.PATIENT,
            owner_id=patient.id,
        ).order_by("id")
        asset_rows = tuple(assets.values_list("id", "object_key"))
        asset_ids = [asset_id for asset_id, _ in asset_rows]
        session_ids = tuple(
            SingingSession.objects.filter(patient_id=patient.id)
            .order_by("id")
            .values_list("id", flat=True)
        )
        if (
            SessionMedia.objects.filter(session_id__in=session_ids)
            .exclude(asset_id__in=asset_ids)
            .exists()
        ):
            raise ValueError("目标会话包含对象前缀以外的资产，拒绝清理")
        if (
            SessionMedia.objects.filter(asset_id__in=asset_ids)
            .exclude(session_id__in=session_ids)
            .exists()
        ):
            raise ValueError("测试资产绑定到其他患者的会话，拒绝清理")
        return CleanupInventory(
            run_id=run_id,
            patient_id=patient.id,
            object_prefix=object_prefix,
            session_ids=session_ids,
            asset_rows=asset_rows,
        )

    def _validate_qa_provenance(
        self,
        *,
        run_id: str,
        patient_id: UUID,
    ) -> PatientProfile:
        admin = self._qa_admin(run_id)
        try:
            patient = PatientProfile.objects.get(pk=patient_id)
        except PatientProfile.DoesNotExist as exc:
            raise ValueError("未找到带 QA 来源标记的患者") from exc
        if not AuditLog.objects.filter(
            actor=admin,
            action="patient.create",
            target_type="patients.PatientProfile",
            target_id=patient.id,
            deleted_at__isnull=True,
        ).exists():
            raise ValueError("患者不属于该 run-id 的 QA 来源链，拒绝清理")
        if (
            patient.deleted_at is not None
            and not AuditLog.objects.filter(
                actor=admin,
                action=QUIET_GATE_ACTION,
                target_type="patients.PatientProfile",
                target_id=patient.id,
                changes__run_id=run_id,
                changes__prefix_digest=self._prefix_digest(
                    expected_object_prefix(run_id=run_id, patient_id=patient.id)
                ),
                deleted_at__isnull=True,
            ).exists()
        ):
            raise ValueError("患者已停用且没有匹配的 quiet gate，拒绝清理")
        return patient

    def _qa_admin(self, run_id: str) -> User:
        login_id = run_id.replace("-", "").upper()
        try:
            admin = User.objects.get(
                login_id=login_id,
                role=Role.SYSTEM_ADMIN,
                deleted_at__isnull=True,
            )
        except User.DoesNotExist as exc:
            raise ValueError("未找到 run-id 对应的 QA 来源标记") from exc
        if not AuditLog.objects.filter(
            actor=admin,
            action=QA_MARKER_ACTION,
            target_type="accounts.User",
            target_id=admin.id,
            changes__run_id=run_id,
            deleted_at__isnull=True,
        ).exists():
            raise ValueError("未找到 run-id 对应的 QA 来源标记")
        return admin
