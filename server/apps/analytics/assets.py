from __future__ import annotations

from dataclasses import dataclass

from django.db import transaction
from django.utils import timezone

from apps.media.backends.local import LocalStorageBackend
from apps.media.contracts import StorageValidationError
from apps.media.models import MediaAsset
from apps.media.services import backend_for_asset, ensure_local_asset_layout

from .models import ExportJob
from .runtime import get_export_runtime_config


EXPECTED_MIME = {
    ExportJob.Format.CSV: "text/csv",
    ExportJob.Format.XLSX: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
}


@dataclass(frozen=True)
class ExportAssetError(Exception):
    code: str
    message: str

    def __str__(self):
        return self.message


def resolve_export_asset(
    job: ExportJob, *, mode="read", verify_storage=True,
    asset: MediaAsset | None = None, lock=False,
) -> MediaAsset:
    if not job.result_asset_id:
        raise ExportAssetError("export_asset_missing", "导出媒体不存在")
    if asset is None:
        queryset = MediaAsset.objects.select_for_update() if lock else MediaAsset.objects
        asset = queryset.filter(pk=job.result_asset_id, deleted_at__isnull=True).first()
    if asset is None:
        raise ExportAssetError("export_asset_missing", "导出媒体不存在")
    if (
        asset.deleted_at is not None
        or asset.owner_type != MediaAsset.OwnerType.EXPORT
        or asset.owner_id != job.id
        or asset.media_type != "export"
        or asset.mime != EXPECTED_MIME[job.format]
    ):
        raise ExportAssetError("export_asset_invalid", "导出媒体归属或格式不可信")
    allowed_statuses = (
        {MediaAsset.Status.READY}
        if mode in {"read", "sign", "publish"}
        else {MediaAsset.Status.READY, MediaAsset.Status.PENDING_CLEANUP, MediaAsset.Status.FAILED}
    )
    if asset.status not in allowed_statuses:
        raise ExportAssetError("export_asset_not_ready", "导出媒体尚不可用")
    if not verify_storage:
        return asset
    try:
        backend = backend_for_asset(asset)
        if isinstance(backend, LocalStorageBackend):
            asset = ensure_local_asset_layout(asset=asset)
            if asset.status not in allowed_statuses:
                raise ExportAssetError("export_asset_not_ready", "导出媒体尚不可用")
            receipt = backend.stat(asset.object_key, expected_generation=asset.manifest_generation)
            trusted = (
                receipt.size == asset.size and receipt.mime == asset.mime
                and receipt.sha256 == asset.sha256 and receipt.generation == asset.manifest_generation
            )
        else:
            receipt = backend.stat(asset.object_key)
            trusted = receipt.size == asset.size and receipt.mime == asset.mime and receipt.etag == asset.etag
    except ExportAssetError:
        raise
    except (StorageValidationError, ValueError) as exc:
        raise ExportAssetError("export_asset_unverifiable", "导出媒体存储回执无法验证") from exc
    if not trusted:
        raise ExportAssetError("export_asset_invalid", "导出媒体存储回执不一致")
    return asset


def issue_export_private_url(job_id, *, now=None):
    """按 Job→Asset 固定锁序完成验证和签发，避免与清理交错。"""
    now = now or timezone.now()
    with transaction.atomic():
        try:
            job = ExportJob.objects.select_for_update().get(pk=job_id)
        except ExportJob.DoesNotExist as exc:
            raise ExportAssetError("export_not_found", "导出任务不存在") from exc
        remaining = int((job.expires_at - now).total_seconds())
        if remaining <= 0:
            raise ExportAssetError("export_expired", "导出文件已过期")
        if job.status != ExportJob.Status.READY or not job.result_asset_id:
            raise ExportAssetError("export_not_ready", "导出文件尚不可下载")
        asset = resolve_export_asset(job, mode="sign", lock=True)
        try:
            backend = backend_for_asset(asset)
            ttl = min(get_export_runtime_config().private_url_ttl_seconds, remaining)
            if isinstance(backend, LocalStorageBackend):
                private = backend.create_private_url(
                    asset.object_key,
                    ttl_seconds=ttl,
                    asset_id=asset.id,
                    expected_generation=asset.manifest_generation,
                )
            else:
                private = backend.create_private_url(asset.object_key, ttl_seconds=ttl)
        except (StorageValidationError, OSError, ValueError) as exc:
            raise ExportAssetError("export_asset_unverifiable", "导出媒体签发失败") from exc
        return job, asset, private
