from __future__ import annotations

from typing import Any, Mapping
from uuid import UUID
from uuid import uuid4
from datetime import timedelta

from django.conf import settings
from django.db import transaction
from django.utils import timezone
from rest_framework.exceptions import APIException, ValidationError

from apps.media.backends.local import LocalStorageBackend
from apps.media.backends.qiniu import QiniuStorageBackend
from apps.media.contracts import BACKENDS, StorageBackend, StorageValidationError, UploadGrant, UploadReceipt, validate_media_request
from apps.media.models import MediaAsset
from apps.patients.models import PatientProfile


class MediaConflict(APIException):
    status_code = 409
    default_code = "media_state_conflict"
    default_detail = "媒体当前状态不允许此操作"


def storage_backend_for(name: str) -> StorageBackend:
    if name == "local":
        return LocalStorageBackend(root=settings.MEDIA_LOCAL_ROOT, signing_secret=settings.SECRET_KEY, environment=settings.MEDIA_ENVIRONMENT)
    if name == "qiniu":
        return QiniuStorageBackend.from_settings()
    raise StorageValidationError("未知媒体存储后端")


def get_storage_backend() -> StorageBackend:
    return storage_backend_for(settings.MEDIA_BACKEND)


def backend_for_asset(asset: MediaAsset) -> StorageBackend:
    return storage_backend_for(asset.backend)


def _resolve_owner(*, owner_type: str, owner_id: UUID) -> tuple[PatientProfile | None, UUID]:
    if owner_type == MediaAsset.OwnerType.PATIENT:
        try:
            patient = PatientProfile.objects.get(pk=owner_id, deleted_at__isnull=True)
        except PatientProfile.DoesNotExist as exc:
            raise ValidationError({"owner_id": "患者资料不存在或不可用"}, code="invalid_media_owner") from exc
        return patient, patient.id
    if owner_type in {MediaAsset.OwnerType.SONG, MediaAsset.OwnerType.SYSTEM, MediaAsset.OwnerType.EXPORT}:
        return None, owner_id
    raise ValidationError({"owner_type": "不支持的媒体所有者类型"}, code="invalid_media_owner")


def create_upload_grant(*, owner: PatientProfile | None = None, owner_type: str = "patient", owner_id: UUID | None = None, media_type: str, mime: str, size: int, backend: StorageBackend | None = None) -> tuple[MediaAsset, UploadGrant]:
    if owner is not None:
        owner_type, owner_id = MediaAsset.OwnerType.PATIENT, owner.id
    if owner_id is None:
        raise ValidationError({"owner_id": "媒体所有者不能为空"}, code="invalid_media_owner")
    allowed_types = {
        "patient": {"singing_audio", "singing_video"},
        "song": {"song_source", "song_accompaniment", "song_vocal", "lyrics"},
        "export": {"export"},
        "system": {"waveform", "song_accompaniment", "song_vocal", "lyrics"},
    }
    if media_type not in allowed_types.get(owner_type, set()):
        raise ValidationError({"media_type": "媒体类型与所有者类型不匹配"}, code="invalid_media_owner")
    try:
        validate_media_request(media_type=media_type, mime=mime, size=size)
    except StorageValidationError as exc:
        raise ValidationError({"media": str(exc)}, code="invalid_media") from exc
    patient_owner, actual_owner_id = _resolve_owner(owner_type=owner_type, owner_id=owner_id)
    backend = backend or get_storage_backend()
    grant = backend.create_upload_grant(owner_id=actual_owner_id, media_type=media_type, mime=mime, size=size)
    return MediaAsset.objects.create(patient_owner=patient_owner, owner_type=owner_type, owner_id=actual_owner_id, media_type=media_type, backend=next(name for name in BACKENDS if backend.__class__.__name__.lower().startswith(name)), object_key=grant.object_key, mime=mime, size=size, status=MediaAsset.Status.UPLOADING, upload_expires_at=grant.expires_at), grant


def _check_receipt(asset: MediaAsset, receipt: UploadReceipt) -> None:
    if receipt.object_key != asset.object_key or receipt.size != asset.size or receipt.mime != asset.mime:
        raise MediaConflict("上传对象元数据与凭证不一致", code="media_metadata_mismatch")
    if asset.backend == "local" and len(receipt.sha256) != 64:
        raise MediaConflict("本地媒体缺少可信哈希", code="media_hash_invalid")
    if asset.backend == "qiniu" and not receipt.etag:
        raise MediaConflict("七牛媒体缺少可信 ETag", code="media_etag_invalid")


def complete_local_asset(*, asset: MediaAsset) -> MediaAsset:
    if asset.backend != "local":
        raise MediaConflict("七牛媒体只能等待已验签回调", code="media_callback_required")
    backend = backend_for_asset(asset)
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset.pk)
        if locked.status == MediaAsset.Status.FAILED:
            raise MediaConflict()
        if locked.status == MediaAsset.Status.READY:
            return locked
        if locked.status not in {MediaAsset.Status.UPLOADING, MediaAsset.Status.RECEIVING} or locked.upload_expires_at <= timezone.now():
            if locked.status == MediaAsset.Status.RECEIVING:
                locked.status = MediaAsset.Status.UPLOADING
                locked.upload_nonce = None
                locked.upload_lease_expires_at = None
                locked.save(update_fields=["status", "upload_nonce", "upload_lease_expires_at", "updated_at"])
            raise MediaConflict("上传凭证已过期或状态不可用", code="media_grant_expired")
        try:
            receipt = backend.verify_completion(locked.object_key)
        except StorageValidationError as exc:
            raise MediaConflict(str(exc), code="media_verification_failed") from exc
        _check_receipt(locked, receipt)
        locked.sha256, locked.status = receipt.sha256, MediaAsset.Status.READY
        locked.save(update_fields=["sha256", "status", "updated_at"])
        return locked


def claim_local_upload(*, asset: MediaAsset) -> UUID:
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset.pk)
        if locked.status != MediaAsset.Status.UPLOADING:
            raise MediaConflict("上传已在进行或不可用", code="media_upload_in_progress")
        nonce = uuid4()
        locked.status = MediaAsset.Status.RECEIVING
        locked.upload_nonce = nonce
        locked.upload_lease_expires_at = timezone.now() + timedelta(minutes=5)
        locked.save(update_fields=["status", "upload_nonce", "upload_lease_expires_at", "updated_at"])
        return nonce


def release_local_upload(*, asset_id: UUID, nonce: UUID, success: bool) -> None:
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset_id)
        if locked.upload_nonce != nonce:
            raise MediaConflict("上传租约不匹配", code="media_upload_lease_invalid")
        if not success:
            locked.status = MediaAsset.Status.UPLOADING
        locked.upload_nonce = None
        locked.upload_lease_expires_at = None
        locked.save(update_fields=["status", "upload_nonce", "upload_lease_expires_at", "updated_at"])


def complete_qiniu_callback(*, payload: Mapping[str, Any], backend: QiniuStorageBackend) -> MediaAsset:
    object_key = str(payload.get("key", ""))
    with transaction.atomic():
        try:
            locked = MediaAsset.objects.select_for_update().get(object_key=object_key, backend="qiniu", deleted_at__isnull=True)
        except MediaAsset.DoesNotExist as exc:
            raise MediaConflict("未找到对应上传凭证", code="media_grant_not_found") from exc
        if locked.status == MediaAsset.Status.FAILED:
            raise MediaConflict()
        if locked.status == MediaAsset.Status.READY:
            if locked.etag != str(payload.get("hash", "")):
                raise MediaConflict("重复回调 ETag 不一致", code="media_metadata_mismatch")
            return locked
        if locked.status != MediaAsset.Status.UPLOADING or locked.upload_expires_at <= timezone.now():
            raise MediaConflict("上传凭证已过期或状态不可用", code="media_grant_expired")
        try:
            receipt = backend.verify_completion(locked.object_key, payload)
        except StorageValidationError as exc:
            raise MediaConflict(str(exc), code="media_verification_failed") from exc
        _check_receipt(locked, receipt)
        locked.etag, locked.sha256, locked.status = receipt.etag, receipt.sha256, MediaAsset.Status.READY
        locked.save(update_fields=["etag", "sha256", "status", "updated_at"])
        return locked


def mark_asset_for_cleanup(*, asset: MediaAsset) -> MediaAsset:
    with transaction.atomic():
        locked = MediaAsset.objects.select_for_update().get(pk=asset.pk)
        if locked.status == MediaAsset.Status.PENDING_CLEANUP:
            return locked
        locked.status = MediaAsset.Status.PENDING_CLEANUP
        locked.save(update_fields=["status", "updated_at"])
        return locked
