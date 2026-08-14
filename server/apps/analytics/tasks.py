from __future__ import annotations

from datetime import timedelta
import logging
from uuid import UUID, uuid4

from celery import shared_task
from django.conf import settings
from django.db import transaction
from django.db import models
from django.utils import timezone

from apps.media.services import mark_asset_for_cleanup, publish_generated_asset
from apps.media.models import MediaAsset

from .exporters import CsvExporter, XlsxExporter, export_rows
from .models import ExportJob


logger = logging.getLogger(__name__)
MAX_ATTEMPTS = 4


def _clear_claim(job):
    job.claim_token = None
    job.lease_expires_at = None
    job.heartbeat_at = None


def claim_export_job(job_id: UUID):
    with transaction.atomic():
        job = ExportJob.objects.select_for_update().get(pk=job_id)
        now = timezone.now()
        if job.expires_at <= now:
            job.status = ExportJob.Status.EXPIRED
            _clear_claim(job)
            job.save(update_fields=["status", "claim_token", "lease_expires_at", "heartbeat_at", "updated_at"])
            return None
        if job.status == ExportJob.Status.PROCESSING and job.lease_expires_at and job.lease_expires_at > now:
            return None
        if job.status not in {ExportJob.Status.PENDING, ExportJob.Status.PROCESSING} or job.attempt >= MAX_ATTEMPTS:
            return None
        token = uuid4()
        job.status = ExportJob.Status.PROCESSING
        job.attempt += 1
        job.claim_token = token
        job.heartbeat_at = now
        job.lease_expires_at = now + timedelta(seconds=settings.ANALYTICS_EXPORT_LEASE_SECONDS)
        job.failure_reason = ""
        job.save()
        return token, job.format, list(job.rows_snapshot)


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
        job.lease_expires_at = now + timedelta(seconds=settings.ANALYTICS_EXPORT_LEASE_SECONDS)
        job.save(update_fields=["heartbeat_at", "lease_expires_at", "updated_at"])
        return True


def expire_export_job(job_id: UUID):
    asset_id = None
    with transaction.atomic():
        job = ExportJob.objects.select_for_update().get(pk=job_id)
        if job.expires_at > timezone.now():
            return job
        job.status = ExportJob.Status.EXPIRED
        _clear_claim(job)
        asset_id = job.result_asset_id
        job.rows_snapshot = []
        job.save(update_fields=["status", "claim_token", "lease_expires_at", "heartbeat_at", "rows_snapshot", "updated_at"])
    if asset_id:
        asset = MediaAsset.objects.filter(pk=asset_id, owner_type="export", owner_id=job_id).first()
        if asset and asset.status != MediaAsset.Status.PENDING_CLEANUP:
            mark_asset_for_cleanup(asset=asset)
    return job


def run_export_job(job_id: str):
    job_uuid = UUID(str(job_id))
    claim = claim_export_job(job_uuid)
    if claim is None:
        return {"job_id": str(job_uuid), "claimed": False}
    token, export_format, rows = claim
    exporter = CsvExporter() if export_format == "csv" else XlsxExporter()
    asset = None
    try:
        content = export_rows(rows, export_format)
        if not renew_export_claim(job_uuid, token):
            return {"job_id": str(job_uuid), "claimed": True, "published": False}
        asset = publish_generated_asset(owner_id=job_uuid, content=content, mime=exporter.mime)
        with transaction.atomic():
            job = ExportJob.objects.select_for_update().get(pk=job_uuid)
            now = timezone.now()
            if job.expires_at <= now:
                job.status = ExportJob.Status.EXPIRED
                job.rows_snapshot = []
                _clear_claim(job)
                job.save()
                mark_asset_for_cleanup(asset=asset)
                return {"job_id": str(job_uuid), "claimed": True, "published": False}
            if job.status != ExportJob.Status.PROCESSING or job.claim_token != token or job.lease_expires_at <= now:
                mark_asset_for_cleanup(asset=asset)
                return {"job_id": str(job_uuid), "claimed": True, "published": False}
            locked_asset = MediaAsset.objects.select_for_update().get(pk=asset.id)
            if (
                locked_asset.status != MediaAsset.Status.READY
                or locked_asset.owner_type != MediaAsset.OwnerType.EXPORT
                or locked_asset.owner_id != job_uuid
                or locked_asset.media_type != "export"
                or locked_asset.mime != exporter.mime
            ):
                job.status = ExportJob.Status.PENDING
                job.failure_reason = "导出媒体在发布期间失效"
                _clear_claim(job)
                job.save()
                return {"job_id": str(job_uuid), "claimed": True, "published": False}
            job.status = ExportJob.Status.READY
            job.result_asset_id = asset.id
            job.completed_at = timezone.now()
            job.rows_snapshot = []
            _clear_claim(job)
            job.save()
        return {"job_id": str(job_uuid), "claimed": True, "published": True}
    except Exception as exc:
        logger.error("export_job_failed job_id=%s exception=%s", job_uuid, exc.__class__.__name__)
        with transaction.atomic():
            job = ExportJob.objects.select_for_update().get(pk=job_uuid)
            if job.status == ExportJob.Status.PROCESSING and job.claim_token == token:
                job.status = ExportJob.Status.FAILED if job.attempt >= MAX_ATTEMPTS else ExportJob.Status.PENDING
                job.failure_reason = "导出生成失败，请稍后重试" if job.status == ExportJob.Status.PENDING else "导出生成多次失败"
                _clear_claim(job)
                job.save()
        raise


def recover_export_jobs(*, batch_size=100):
    now = timezone.now()
    recovered = failed = expired = 0
    ids = list(ExportJob.objects.filter(
        status__in=[ExportJob.Status.PENDING, ExportJob.Status.PROCESSING, ExportJob.Status.READY],
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
        with transaction.atomic():
            job = ExportJob.objects.select_for_update().get(pk=job_id)
            if job.status == ExportJob.Status.PROCESSING and job.lease_expires_at and job.lease_expires_at <= now:
                job.status = ExportJob.Status.FAILED if job.attempt >= MAX_ATTEMPTS else ExportJob.Status.PENDING
                job.failure_reason = "导出生成多次失败" if job.status == ExportJob.Status.FAILED else ""
                _clear_claim(job)
                job.save()
                if job.status == ExportJob.Status.FAILED:
                    failed += 1
                else:
                    recovered += 1
                    dispatch = True
            elif job.status == ExportJob.Status.PENDING:
                dispatch = True
        if dispatch:
            run_export_job_task.delay(str(job_id))
    return {"recovered": recovered, "failed": failed, "expired": expired}


@shared_task(bind=True, max_retries=3)
def run_export_job_task(self, job_id: str):
    try:
        return run_export_job(job_id)
    except Exception as exc:
        raise self.retry(exc=exc, countdown=5)


@shared_task
def recover_export_jobs_task(batch_size=100):
    return recover_export_jobs(batch_size=batch_size)
