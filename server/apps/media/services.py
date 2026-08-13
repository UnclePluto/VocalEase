from __future__ import annotations

from typing import Any, Mapping

from django.conf import settings
from django.db import transaction
from django.utils import timezone
from rest_framework.exceptions import APIException, ValidationError

from apps.media.backends.local import LocalStorageBackend
from apps.media.backends.qiniu import QiniuStorageBackend
from apps.media.contracts import StorageBackend, StorageValidationError, UploadGrant, UploadReceipt, validate_media_request
from apps.media.models import MediaAsset
from apps.patients.models import PatientProfile


class MediaConflict(APIException):
    status_code = 409
    default_code = "media_state_conflict"
    default_detail = "媒体当前状态不允许此操作"


def get_storage_backend() -> StorageBackend:
    if settings.MEDIA_BACKEND == "local":
        return LocalStorageBackend(
            root=settings.MEDIA_LOCAL_ROOT, signing_secret=settings.SECRET_KEY, environment=settings.MEDIA_ENVIRONMENT
        )
    if settings.MEDIA_BACKEND == "qiniu":
        return QiniuStorageBackend.from_settings()
    raise StorageValidationError("未知媒体存储后端")


def create_upload_grant(*, owner: PatientProfile, media_type: str, mime: str, size: int, backend: StorageBackend | None = None) -> tuple[MediaAsset, UploadGrant]:
    try:
        validate_media_request(media_type=media_type, mime=mime, size=size)
    except StorageValidationError as exc:
        raise ValidationError({"media": str(exc)}, code="invalid_media") from exc
    backend = backend or get_storage_backend()
    grant = backend.create_upload_grant(owner_id=owner.id, media_type=media_type, mime=mime, size=size)
    asset = MediaAsset.objects.create(
        owner=owner, media_type=media_type, backend=settings.MEDIA_BACKEND,
        owner_type=MediaAsset.OwnerType.PATIENT,
        object_key=grant.object_key, mime=mime, size=size, status=MediaAsset.Status.UPLOADING,
        upload_expires_at=grant.expires_at,
    )
    return asset, grant


def _verify_receipt(asset: MediaAsset, receipt: UploadReceipt) -> None:
    if receipt.object_key != asset.object_key or receipt.size != asset.size or receipt.mime != asset.mime:
        raise MediaConflict("上传对象元数据与凭证不一致", code="media_metadata_mismatch")
    if len(receipt.sha256) != 64:
        raise MediaConflict("上传对象哈希无效", code="media_hash_invalid")


def complete_asset(*, asset: MediaAsset, payload: Mapping[str, Any], backend: StorageBackend | None = None) -> MediaAsset:
    backend = backend or get_storage_backend()
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset.pk)
        if locked.status == MediaAsset.Status.FAILED:
            raise MediaConflict()
        if locked.status == MediaAsset.Status.READY:
            try:
                receipt = backend.verify_completion(locked.object_key, payload)
            except StorageValidationError as exc:
                raise MediaConflict(str(exc), code="media_verification_failed") from exc
            _verify_receipt(locked, receipt)
            if receipt.sha256 != locked.sha256:
                raise MediaConflict("重复确认的对象哈希不一致", code="media_metadata_mismatch")
            return locked
        if locked.status != MediaAsset.Status.UPLOADING:
            raise MediaConflict()
        if locked.upload_expires_at <= timezone.now():
            raise MediaConflict("上传凭证已过期", code="media_grant_expired")
        try:
            receipt = backend.verify_completion(locked.object_key, payload)
        except StorageValidationError as exc:
            raise MediaConflict(str(exc), code="media_verification_failed") from exc
        _verify_receipt(locked, receipt)
        locked.sha256 = receipt.sha256
        locked.status = MediaAsset.Status.READY
        locked.save(update_fields=["sha256", "status", "updated_at"])
        return locked


def complete_qiniu_callback(*, payload: Mapping[str, Any], backend: QiniuStorageBackend) -> MediaAsset:
    object_key = str(payload.get("key", ""))
    try:
        asset = MediaAsset.objects.get(object_key=object_key, backend="qiniu", deleted_at__isnull=True)
    except MediaAsset.DoesNotExist as exc:
        raise MediaConflict("未找到对应上传凭证", code="media_grant_not_found") from exc
    completed = complete_asset(asset=asset, payload=payload, backend=backend)
    etag = str(payload.get("hash", ""))
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=completed.pk)
        previous_etag = locked.metadata.get("qiniu_etag")
        if previous_etag and previous_etag != etag:
            raise MediaConflict("重复回调的对象哈希不一致", code="media_metadata_mismatch")
        if not previous_etag:
            locked.metadata = {**locked.metadata, "qiniu_etag": etag}
            locked.save(update_fields=["metadata", "updated_at"])
        return locked
