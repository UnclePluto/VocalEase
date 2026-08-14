from __future__ import annotations

from dataclasses import dataclass

from apps.media.backends.local import LocalStorageBackend
from apps.media.contracts import StorageValidationError
from apps.media.models import MediaAsset
from apps.media.services import backend_for_asset, ensure_local_asset_layout

from .models import ExportJob


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


def resolve_export_asset(job: ExportJob, *, require_ready=True, verify_storage=True) -> MediaAsset:
    if not job.result_asset_id:
        raise ExportAssetError("export_asset_missing", "导出媒体不存在")
    asset = MediaAsset.objects.filter(pk=job.result_asset_id, deleted_at__isnull=True).first()
    if asset is None:
        raise ExportAssetError("export_asset_missing", "导出媒体不存在")
    if (
        asset.owner_type != MediaAsset.OwnerType.EXPORT
        or asset.owner_id != job.id
        or asset.media_type != "export"
        or asset.mime != EXPECTED_MIME[job.format]
    ):
        raise ExportAssetError("export_asset_invalid", "导出媒体归属或格式不可信")
    if require_ready and asset.status != MediaAsset.Status.READY:
        raise ExportAssetError("export_asset_not_ready", "导出媒体尚不可用")
    if not verify_storage:
        return asset
    try:
        backend = backend_for_asset(asset)
        if isinstance(backend, LocalStorageBackend):
            asset = ensure_local_asset_layout(asset=asset)
            if asset.status != MediaAsset.Status.READY:
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
