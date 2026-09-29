from __future__ import annotations

from datetime import timedelta
import hashlib
from itertools import islice
import logging
import tempfile
import time
from uuid import UUID, uuid4

from celery import shared_task
from django.db import connection, models, transaction
from django.utils import timezone

from apps.audit.services import record
from apps.media.models import MediaAsset
from apps.media.services import mark_asset_for_cleanup, publish_generated_asset

from .assets import EXPECTED_MIME, ExportAssetError, resolve_export_asset
from .exporters import export_rows_to
from .models import ExportJob, ExportJobItem
from .runtime import get_export_runtime_config
from .selectors import exportable_row, patient_metric_rows, patients_for_export_snapshot


logger = logging.getLogger(__name__)
EXPORT_BATCH_SIZE = 200
SPOOL_MEMORY_LIMIT = 8 * 1024 * 1024


class ExportLeaseLost(RuntimeError):
    pass


class ExportHeartbeat:
    def __init__(self, job_id, token, *, monotonic=time.monotonic):
        self.job_id = job_id
        self.token = token
        self.monotonic = monotonic
        self.interval = get_export_runtime_config().heartbeat_seconds
        self.last_pulse = monotonic()

    def pulse(self, *, force=False):
        now = self.monotonic()
        if not force and now - self.last_pulse < self.interval:
            return
        if not renew_export_claim(self.job_id, self.token):
            raise ExportLeaseLost("导出任务租约已失效")
        self.last_pulse = now


def _clear_claim(job):
    job.claim_token = None
    job.lease_expires_at = None
    job.heartbeat_at = None


def _clear_cleanup_claim(job):
    job.cleanup_claim_token = None
    job.cleanup_lease_expires_at = None


def request_export_cleanup(job_id: UUID, *, status: str, failure_reason=""):
    """统一进入可恢复清理状态；调用方可反复执行。"""
    with transaction.atomic():
        job = ExportJob.objects.select_for_update().get(pk=job_id)
        if job.cleanup_status == ExportJob.CleanupStatus.COMPLETE:
            return job
        now = timezone.now()
        job.status = status
        job.failure_reason = failure_reason
        job.completed_at = None
        if (
            job.cleanup_status == ExportJob.CleanupStatus.PROCESSING
            and job.cleanup_lease_expires_at
            and job.cleanup_lease_expires_at > now
        ):
            job.save(update_fields=["status", "failure_reason", "completed_at", "updated_at"])
            return job
        job.cleanup_status = ExportJob.CleanupStatus.PENDING
        _clear_claim(job)
        _clear_cleanup_claim(job)
        job.save()
    try:
        cleanup_export_job(job_id)
    except Exception as exc:
        # 媒体与任务表无法共用一个原子事务；保留 processing+lease，由 Beat
        # 在租约后接管，面向 HTTP 的过期/失效操作不因此变成 500。
        logger.error("export_cleanup_deferred job_id=%s exception=%s", job_id, exc.__class__.__name__)
    return ExportJob.objects.get(pk=job_id)


def cleanup_export_job(job_id: UUID):
    token = uuid4()
    with transaction.atomic():
        job = ExportJob.objects.select_for_update().get(pk=job_id)
        now = timezone.now()
        if job.cleanup_status == ExportJob.CleanupStatus.COMPLETE:
            return {"job_id": str(job_id), "cleaned": True}
        if job.cleanup_status == ExportJob.CleanupStatus.PROCESSING and job.cleanup_lease_expires_at > now:
            return {"job_id": str(job_id), "cleaned": False}
        if job.cleanup_status not in {ExportJob.CleanupStatus.PENDING, ExportJob.CleanupStatus.PROCESSING}:
            return {"job_id": str(job_id), "cleaned": False}
        job.cleanup_status = ExportJob.CleanupStatus.PROCESSING
        job.cleanup_attempt += 1
        job.cleanup_claim_token = token
        job.cleanup_lease_expires_at = now + timedelta(seconds=get_export_runtime_config().lease_seconds)
        asset_id = job.result_asset_id
        job.normalized_filters = {}
        job.selected_ids = []
        ExportJobItem.objects.filter(job=job).delete()
        job.save()

    with transaction.atomic():
        job = ExportJob.objects.select_for_update().get(pk=job_id)
        if job.cleanup_status != ExportJob.CleanupStatus.PROCESSING or job.cleanup_claim_token != token:
            return {"job_id": str(job_id), "cleaned": False}
        asset_validation = "none"
        if asset_id:
            try:
                asset = resolve_export_asset(job, mode="cleanup", lock=True)
                asset_validation = "trusted"
            except ExportAssetError as exc:
                # 不可信/孤儿链接只从 Job 解除，绝不触碰可能属于其他 owner 的媒体。
                asset_validation = exc.code
            else:
                if asset.status != MediaAsset.Status.PENDING_CLEANUP:
                    mark_asset_for_cleanup(asset=asset)
        record(
            actor=job.creator,
            action="analytics.export_cleanup",
            target=job,
            changes={
                "status": job.status,
                "asset_id": str(asset_id) if asset_id else None,
                "asset_validation": asset_validation,
            },
            request_id=f"cleanup:{job.id}",
        )
        job.result_asset_id = None
        job.cleanup_status = ExportJob.CleanupStatus.COMPLETE
        job.cleanup_completed_at = timezone.now()
        _clear_cleanup_claim(job)
        job.save()
    return {"job_id": str(job_id), "cleaned": True}


def claim_export_job(job_id: UUID):
    config = get_export_runtime_config()
    cleanup_status = None
    with transaction.atomic():
        job = ExportJob.objects.select_for_update().get(pk=job_id)
        now = timezone.now()
        if job.expires_at <= now:
            cleanup_status = ExportJob.Status.EXPIRED
        elif job.status == ExportJob.Status.PROCESSING and job.lease_expires_at and job.lease_expires_at > now:
            return None
        elif job.status not in {ExportJob.Status.PENDING, ExportJob.Status.PROCESSING}:
            return None
        elif job.attempt >= config.max_attempts:
            cleanup_status = ExportJob.Status.FAILED
        else:
            token = uuid4()
            job.status = ExportJob.Status.PROCESSING
            job.attempt += 1
            job.claim_token = token
            job.heartbeat_at = now
            job.lease_expires_at = now + timedelta(seconds=config.lease_seconds)
            job.failure_reason = ""
            job.save()
            return token, job.format
    if cleanup_status:
        request_export_cleanup(
            job_id,
            status=cleanup_status,
            failure_reason="导出生成多次失败" if cleanup_status == ExportJob.Status.FAILED else "",
        )
    return None


def renew_export_claim(job_id: UUID, token) -> bool:
    with transaction.atomic():
        job = ExportJob.objects.select_for_update().get(pk=job_id)
        now = timezone.now()
        if (
            job.status != ExportJob.Status.PROCESSING
            or job.claim_token != token
            or not job.lease_expires_at
            or job.lease_expires_at <= now
            or job.expires_at <= now
        ):
            return False
        job.heartbeat_at = now
        job.lease_expires_at = now + timedelta(seconds=get_export_runtime_config().lease_seconds)
        job.save(update_fields=["heartbeat_at", "lease_expires_at", "updated_at"])
        return True


def expire_export_job(job_id: UUID):
    job = ExportJob.objects.get(pk=job_id)
    if job.expires_at > timezone.now():
        return job
    return request_export_cleanup(job_id, status=ExportJob.Status.EXPIRED)


def _metric_rows_for_job(job_id: UUID, heartbeat: ExportHeartbeat):
    patient_id_iterator = ExportJobItem.objects.filter(job_id=job_id).order_by("position").values_list(
        "patient_id", flat=True,
    ).iterator(chunk_size=EXPORT_BATCH_SIZE)
    while True:
        patient_ids = list(islice(patient_id_iterator, EXPORT_BATCH_SIZE))
        if not patient_ids:
            return
        may_set_isolation = connection.vendor == "postgresql" and not connection.in_atomic_block
        with transaction.atomic():
            if may_set_isolation:
                with connection.cursor() as cursor:
                    cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            patients = list(patients_for_export_snapshot(patient_ids))
            patient_by_id = {patient.id: patient for patient in patients}
            ordered_patients = [patient_by_id[patient_id] for patient_id in patient_ids if patient_id in patient_by_id]
            rows_by_id = {
                row["id"]: row
                for row in patient_metric_rows(ordered_patients, heartbeat=heartbeat.pulse)
            }
        for patient_id in patient_ids:
            row = rows_by_id.get(str(patient_id))
            if row is not None:
                yield exportable_row(row)
        heartbeat.pulse()


def _mark_unlinked_export_asset(asset, job_id):
    if (
        asset.owner_type == MediaAsset.OwnerType.EXPORT
        and asset.owner_id == job_id
        and asset.media_type == "export"
        and asset.status != MediaAsset.Status.PENDING_CLEANUP
    ):
        mark_asset_for_cleanup(asset=asset)


def _stream_sha256(stream, heartbeat=None):
    digest = hashlib.sha256()
    stream.seek(0)
    while chunk := stream.read(1024 * 1024):
        if heartbeat:
            heartbeat()
        digest.update(chunk)
    stream.seek(0)
    return digest.hexdigest()


def run_export_job(job_id: str):
    job_uuid = UUID(str(job_id))
    claim = claim_export_job(job_uuid)
    if claim is None:
        return {"job_id": str(job_uuid), "claimed": False}
    token, export_format = claim
    heartbeat = ExportHeartbeat(job_uuid, token)
    asset = None
    try:
        with tempfile.SpooledTemporaryFile(max_size=SPOOL_MEMORY_LIMIT, mode="w+b") as stream:
            export_rows_to(
                stream,
                _metric_rows_for_job(job_uuid, heartbeat),
                export_format,
                heartbeat=heartbeat.pulse,
            )
            size = stream.tell()
            content_sha256 = _stream_sha256(stream, heartbeat.pulse)
            heartbeat.pulse(force=True)
            asset = publish_generated_asset(
                owner_id=job_uuid,
                stream=stream,
                size=size,
                content_sha256=content_sha256,
                mime=EXPECTED_MIME[export_format],
                heartbeat=heartbeat.pulse,
            )
            heartbeat.pulse(force=True)
        with transaction.atomic():
            job = ExportJob.objects.select_for_update().get(pk=job_uuid)
            now = timezone.now()
            if job.expires_at <= now:
                _mark_unlinked_export_asset(asset, job_uuid)
                # 统一清理在退出事务后取得同一行锁。
                expired = True
            else:
                expired = False
            if expired:
                pass
            elif job.status != ExportJob.Status.PROCESSING or job.claim_token != token or job.lease_expires_at <= now:
                # 内容寻址复用可能让接管 worker 与旧 worker 获得同一 MediaAsset。
                # 若新 worker 已把该资产链接为唯一 READY，旧 token 只被 fencing，
                # 绝不能撤销胜者资产；未被当前 Job 链接的孤立生成物才进入清理。
                if not (job.status == ExportJob.Status.READY and job.result_asset_id == asset.id):
                    _mark_unlinked_export_asset(asset, job_uuid)
                return {"job_id": str(job_uuid), "claimed": True, "published": False}
            else:
                locked_asset = MediaAsset.objects.select_for_update().get(pk=asset.id)
                try:
                    # 发布链接前再次核对 Task 4 的 owner/MIME/receipt 可信边界。
                    job.result_asset_id = locked_asset.id
                    resolve_export_asset(job, mode="publish", asset=locked_asset, verify_storage=True)
                except ExportAssetError:
                    job.result_asset_id = None
                    job.status = ExportJob.Status.PENDING
                    job.failure_reason = "导出媒体在发布期间失效"
                    _clear_claim(job)
                    job.save()
                    _mark_unlinked_export_asset(locked_asset, job_uuid)
                    return {"job_id": str(job_uuid), "claimed": True, "published": False}
                job.status = ExportJob.Status.READY
                job.completed_at = timezone.now()
                _clear_claim(job)
                job.save()
                return {"job_id": str(job_uuid), "claimed": True, "published": True}
        request_export_cleanup(job_uuid, status=ExportJob.Status.EXPIRED)
        return {"job_id": str(job_uuid), "claimed": True, "published": False}
    except Exception as exc:
        logger.error("export_job_failed job_id=%s exception=%s", job_uuid, exc.__class__.__name__)
        terminal = False
        expired = False
        orphan_asset = None
        with transaction.atomic():
            job = ExportJob.objects.select_for_update().get(pk=job_uuid)
            expired = job.expires_at <= timezone.now()
            if asset is not None and not (
                job.status == ExportJob.Status.READY and job.result_asset_id == asset.id
            ):
                orphan_asset = asset
            if job.status == ExportJob.Status.PROCESSING and job.claim_token == token and not expired:
                terminal = job.attempt >= get_export_runtime_config().max_attempts
                job.status = ExportJob.Status.FAILED if terminal else ExportJob.Status.PENDING
                job.failure_reason = "导出生成多次失败" if terminal else "导出生成失败，请稍后重试"
                if terminal:
                    job.cleanup_status = ExportJob.CleanupStatus.PENDING
                _clear_claim(job)
                job.save()
        if orphan_asset is not None:
            _mark_unlinked_export_asset(orphan_asset, job_uuid)
        if expired:
            request_export_cleanup(job_uuid, status=ExportJob.Status.EXPIRED)
            return {"job_id": str(job_uuid), "claimed": True, "published": False}
        if isinstance(exc, ExportLeaseLost):
            return {"job_id": str(job_uuid), "claimed": True, "published": False}
        if terminal:
            cleanup_export_job(job_uuid)
        raise


def recover_export_jobs(*, batch_size=100):
    now = timezone.now()
    recovered = failed = expired = cleaned = 0
    cleanup_ids = list(ExportJob.objects.filter(
        cleanup_status__in=[ExportJob.CleanupStatus.PENDING, ExportJob.CleanupStatus.PROCESSING],
    ).filter(
        models.Q(cleanup_status=ExportJob.CleanupStatus.PENDING)
        | models.Q(cleanup_lease_expires_at__lte=now)
    ).values_list("id", flat=True)[:batch_size])
    for job_id in cleanup_ids:
        cleaned += int(cleanup_export_job(job_id)["cleaned"])

    ids = list(ExportJob.objects.filter(
        status__in=[ExportJob.Status.PENDING, ExportJob.Status.PROCESSING, ExportJob.Status.READY],
        cleanup_status=ExportJob.CleanupStatus.NONE,
    ).filter(
        models.Q(status__in=[ExportJob.Status.PENDING, ExportJob.Status.PROCESSING])
        | models.Q(expires_at__lte=now)
    ).order_by("created_at").values_list("id", flat=True)[:batch_size])
    for job_id in ids:
        dispatch = False
        if ExportJob.objects.filter(pk=job_id, expires_at__lte=now).exists():
            expire_export_job(job_id)
            expired += 1
            continue
        terminal = False
        with transaction.atomic():
            job = ExportJob.objects.select_for_update().get(pk=job_id)
            if job.status == ExportJob.Status.PROCESSING and job.lease_expires_at and job.lease_expires_at <= now:
                terminal = job.attempt >= get_export_runtime_config().max_attempts
                job.status = ExportJob.Status.FAILED if terminal else ExportJob.Status.PENDING
                job.failure_reason = "导出生成多次失败" if terminal else ""
                if terminal:
                    job.cleanup_status = ExportJob.CleanupStatus.PENDING
                _clear_claim(job)
                job.save()
                if terminal:
                    failed += 1
                else:
                    recovered += 1
                    dispatch = True
            elif job.status == ExportJob.Status.PENDING:
                dispatch = True
        if terminal:
            cleanup_export_job(job_id)
        elif dispatch:
            run_export_job_task.delay(str(job_id))
    return {"recovered": recovered, "failed": failed, "expired": expired, "cleaned": cleaned}


@shared_task(bind=True, max_retries=3)
def run_export_job_task(self, job_id: str):
    try:
        return run_export_job(job_id)
    except Exception as exc:
        raise self.retry(exc=exc, countdown=5)


@shared_task
def recover_export_jobs_task(batch_size=100):
    return recover_export_jobs(batch_size=batch_size)
