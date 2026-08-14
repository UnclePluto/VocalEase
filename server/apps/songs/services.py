from __future__ import annotations

import hashlib
import logging
from dataclasses import dataclass
from datetime import timedelta
from uuid import UUID, uuid4

from django.conf import settings
from django.db import transaction
from django.utils import timezone
from rest_framework.exceptions import APIException

from apps.audit.services import record
from apps.media.backends.local import LocalStorageBackend
from apps.media.contracts import StorageValidationError
from apps.media.models import MediaAsset
from apps.media.services import backend_for_asset, create_upload_grant, ensure_local_asset_layout

from .models import Song, SongAvailabilityScanState, SongUploadIntent


class SongStateConflict(APIException):
    status_code = 409
    default_code = "song_state_conflict"
    default_detail = "歌曲当前状态不允许此操作"


class SourceAssetInvalid(APIException):
    status_code = 409
    default_code = "song_source_invalid"
    default_detail = "歌曲源媒体不可用或验证失败"


class SourceVerificationTemporary(APIException):
    """存储服务暂时不可用；不能据此抹掉上一次可信可用状态。"""

    status_code = 503
    default_code = "song_source_verification_deferred"
    default_detail = "媒体存储暂时不可用，请稍后重试"


logger = logging.getLogger(__name__)


def _source_snapshot(asset: MediaAsset) -> dict[str, object]:
    return {"asset_id": str(asset.id), "object_key": asset.object_key, "mime": asset.mime, "size": asset.size, "backend": asset.backend}


def source_receipt_fingerprint(asset: MediaAsset) -> str:
    receipt = asset.sha256 if asset.backend == "local" else asset.etag
    value = "|".join((str(asset.id), asset.backend, asset.object_key, asset.mime, str(asset.size), asset.manifest_generation, receipt))
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _mark_source_availability(*, song: Song | None, asset: MediaAsset, available: bool) -> None:
    if song is None or song.source_asset_id != asset.id:
        return
    values = {
        "source_available": available,
        "source_verified_at": timezone.now(),
        "source_verified_asset_id": asset.id if available else None,
        "source_receipt_fingerprint": source_receipt_fingerprint(asset) if available else "",
        "source_verified_backend": asset.backend if available else "",
        "source_verified_object_key": asset.object_key if available else "",
        "source_verified_size": asset.size if available else None,
        "source_verified_mime": asset.mime if available else "",
        "source_verified_sha256": asset.sha256 if available else "",
        "source_verified_etag": asset.etag if available else "",
        "source_verified_generation": asset.manifest_generation if available else "",
    }
    Song.objects.filter(pk=song.id, source_asset_id=asset.id).update(**values)
    for key, value in values.items():
        setattr(song, key, value)


def validate_source_asset(*, song: Song | None, asset: MediaAsset, song_id: UUID | None = None) -> MediaAsset:
    """以资产自身后端复核真实对象和可信回执，不能由全局后端或客户端数据替代。"""
    target_id = song.id if song is not None else song_id
    if target_id is None or asset.deleted_at is not None:
        _mark_source_availability(song=song, asset=asset, available=False)
        raise SourceAssetInvalid()
    if song is not None and song.deleted_at is not None:
        _mark_source_availability(song=song, asset=asset, available=False)
        raise SourceAssetInvalid()
    if (
        asset.owner_type != MediaAsset.OwnerType.SONG
        or asset.owner_id != target_id
        or asset.media_type != "song_source"
        or asset.status != MediaAsset.Status.READY
    ):
        _mark_source_availability(song=song, asset=asset, available=False)
        raise SourceAssetInvalid()
    try:
        # 本地恢复检查也会在发现真实对象不一致时将其转为 failed。
        if asset.backend == "local":
            asset = ensure_local_asset_layout(asset=asset)
            if asset.status != MediaAsset.Status.READY:
                raise SourceAssetInvalid()
        metadata = backend_for_asset(asset).stat(asset.object_key)
    except SourceAssetInvalid:
        _mark_source_availability(song=song, asset=asset, available=False)
        raise SourceAssetInvalid()
    except StorageValidationError as exc:
        if asset.backend == "qiniu" and "查询失败" in str(exc):
            logger.warning("song_source_verify_deferred song_id=%s asset_id=%s reason=storage_unavailable", target_id, asset.id)
            raise SourceVerificationTemporary("媒体存储暂时不可用") from exc
        _mark_source_availability(song=song, asset=asset, available=False)
        raise SourceAssetInvalid() from exc
    except Exception as exc:
        logger.warning("song_source_verify_deferred song_id=%s asset_id=%s reason=backend_exception", target_id, asset.id)
        raise SourceVerificationTemporary("媒体存储暂时不可用") from exc
    if metadata.object_key != asset.object_key or metadata.size != asset.size or metadata.mime != asset.mime:
        _mark_source_availability(song=song, asset=asset, available=False)
        raise SourceAssetInvalid()
    if asset.backend == "local" and (not asset.sha256 or metadata.sha256 != asset.sha256 or metadata.generation != asset.manifest_generation):
        _mark_source_availability(song=song, asset=asset, available=False)
        raise SourceAssetInvalid()
    if asset.backend == "qiniu" and (not asset.etag or metadata.etag != asset.etag):
        _mark_source_availability(song=song, asset=asset, available=False)
        raise SourceAssetInvalid()
    _mark_source_availability(song=song, asset=asset, available=True)
    return asset


def issue_song_upload_grant(*, actor, request_id: str, mime: str, size: int, song_id: UUID | None = None):
    target_id = song_id or uuid4()
    with transaction.atomic():
        existing_song = Song.objects.select_for_update().filter(pk=target_id).first()
        if existing_song is not None and existing_song.deleted_at is not None:
            raise SourceAssetInvalid()
        asset, grant = create_upload_grant(
            owner_type=MediaAsset.OwnerType.SONG, owner_id=target_id,
            media_type="song_source", mime=mime, size=size,
        )
        SongUploadIntent.objects.update_or_create(
            song_id=target_id,
            defaults={"id": target_id, "asset": asset},
        )
    record(actor=actor, action="song.upload_grant", target=asset, changes={"song_id": str(target_id), "media_type": "song_source", "size": size, "backend": asset.backend}, request_id=request_id)
    return target_id, asset, grant


def _require_bound_source(*, song_id: UUID, asset_id: UUID, require_intent: bool) -> MediaAsset:
    try:
        asset = MediaAsset.objects.select_for_update().get(pk=asset_id, deleted_at__isnull=True)
    except MediaAsset.DoesNotExist as exc:
        raise SourceAssetInvalid() from exc
    if require_intent:
        try:
            intent = SongUploadIntent.objects.select_for_update().get(song_id=song_id, asset_id=asset.id)
        except SongUploadIntent.DoesNotExist as exc:
            raise SourceAssetInvalid() from exc
        if intent.id != song_id:
            raise SourceAssetInvalid()
    return validate_source_asset(song=None, song_id=song_id, asset=asset)


def create_song(*, actor, request_id: str, song_id: UUID | None, source_asset: UUID | None, auto_analyze: bool = False, **values) -> Song:
    if song_id is None or source_asset is None:
        raise SourceAssetInvalid("新建歌曲必须使用歌曲专属的已完成源媒体", code="song_upload_intent_required")
    with transaction.atomic():
        if Song.objects.filter(pk=song_id).exists():
            raise SongStateConflict("歌曲标识已存在", code="song_id_exists")
        asset = _require_bound_source(song_id=song_id, asset_id=source_asset, require_intent=True)
        song = Song.objects.create(
            id=song_id, source_asset=asset, source_available=True,
            source_verified_at=timezone.now(),
            source_verified_asset_id=asset.id,
            source_receipt_fingerprint=source_receipt_fingerprint(asset),
            source_verified_backend=asset.backend,
            source_verified_object_key=asset.object_key,
            source_verified_size=asset.size,
            source_verified_mime=asset.mime,
            source_verified_sha256=asset.sha256,
            source_verified_etag=asset.etag,
            source_verified_generation=asset.manifest_generation,
            **values,
        )
        record(actor=actor, action="song.create", target=song, changes={"title": song.title, "artist": song.artist, "genre": song.genre, "language": song.language, "duration_seconds": song.duration_seconds, "source": _source_snapshot(asset)}, request_id=request_id)
        if auto_analyze:
            from apps.analysis.services import request_song_analysis
            request_song_analysis(song=song, source_asset=asset, task_type="vocal_separation")
    return song


def update_song(*, actor, request_id: str, song: Song, source_asset: UUID | None = None, **values) -> Song:
    with transaction.atomic():
        locked = Song.objects.select_for_update().get(pk=song.pk, deleted_at__isnull=True)
        editable = {key: value for key, value in values.items() if key in {"title", "artist", "genre", "language", "duration_seconds"}}
        if source_asset is not None and source_asset != locked.source_asset_id:
            asset = _require_bound_source(song_id=locked.id, asset_id=source_asset, require_intent=True)
            locked.source_asset = asset
            # 替换源文件后，旧成功结论与发布状态均不再适用。
            locked.analysis_status = Song.AnalysisStatus.PENDING
            locked.publication_status = Song.PublicationStatus.DRAFT
            locked.source_available = False
            locked.source_verified_at = None
            locked.source_verified_asset_id = None
            locked.source_receipt_fingerprint = ""
            locked.source_verified_backend = ""
            locked.source_verified_object_key = ""
            locked.source_verified_size = None
            locked.source_verified_mime = ""
            locked.source_verified_sha256 = ""
            locked.source_verified_etag = ""
            locked.source_verified_generation = ""
            editable.update({
                "source_asset": asset, "analysis_status": locked.analysis_status,
                "publication_status": locked.publication_status, "source_available": False,
                "source_verified_at": None, "source_verified_asset_id": None,
                "source_receipt_fingerprint": "", "source_verified_backend": "",
                "source_verified_object_key": "", "source_verified_size": None,
                "source_verified_mime": "", "source_verified_sha256": "",
                "source_verified_etag": "", "source_verified_generation": "",
            })
        if not editable:
            return locked
        audit_fields = {"title", "artist", "genre", "language", "duration_seconds"}
        before = {key: str(getattr(locked, key)) for key in editable if key in audit_fields}
        for key, value in values.items():
            if key in {"title", "artist", "genre", "language", "duration_seconds"}:
                setattr(locked, key, value)
        locked.save()
        if "source_asset" in editable:
            from apps.analysis.services import request_song_analysis, supersede_stale_song_analyses
            supersede_stale_song_analyses(song=locked)
            request_song_analysis(song=locked, source_asset=locked.source_asset, task_type="vocal_separation")
        changes = {key: {"from": before.get(key), "to": str(getattr(locked, key))} for key in editable if key in audit_fields}
        if "source_asset" in editable:
            changes["source_asset"] = {"changed": True}
        record(actor=actor, action="song.update", target=locked, changes=changes, request_id=request_id)
        return locked


def soft_delete_song(*, actor, request_id: str, song_id: UUID) -> Song | None:
    with transaction.atomic():
        locked = Song.objects.select_for_update().filter(pk=song_id).first()
        if locked is None:
            return None
        if locked.deleted_at is not None:
            return locked
        locked.deleted_at = timezone.now()
        locked.publication_status = Song.PublicationStatus.DRAFT
        locked.save(update_fields=["deleted_at", "publication_status", "updated_at"])
        record(actor=actor, action="song.delete", target=locked, changes={"deleted": True}, request_id=request_id)
        return locked


def publish_song(*, actor, request_id: str, song: Song, publish: bool) -> Song:
    validation_error = None
    with transaction.atomic():
        locked = Song.objects.select_for_update().get(pk=song.pk, deleted_at__isnull=True)
        if publish:
            from apps.analysis.models import AnalysisTask
            has_current_result = AnalysisTask.objects.filter(
                song=locked, source_asset_id=locked.source_asset_id,
                status=AnalysisTask.Status.SUCCEEDED,
                analysis_result__isnull=False,
            ).exists()
            if locked.analysis_status != Song.AnalysisStatus.SUCCEEDED or not locked.source_asset or not has_current_result:
                raise SongStateConflict("仅分析成功且源媒体真实存在的歌曲可以发布", code="song_not_publishable")
            try:
                validate_source_asset(song=locked, asset=locked.source_asset)
            except (SourceAssetInvalid, SourceVerificationTemporary) as exc:
                validation_error = exc
            if validation_error is None:
                locked.publication_status = Song.PublicationStatus.PUBLISHED
                action = "song.publish"
        else:
            locked.publication_status = Song.PublicationStatus.DRAFT
            action = "song.unpublish"
        if validation_error is None:
            locked.save(update_fields=["publication_status", "updated_at"])
            record(actor=actor, action=action, target=locked, changes={"publication_status": locked.publication_status}, request_id=request_id)
    if validation_error is not None:
        raise validation_error
    return locked


def preview_source(*, actor, request_id: str, song: Song):
    if song.deleted_at is not None or not song.source_asset_id:
        raise SourceAssetInvalid()
    asset = validate_source_asset(song=song, asset=song.source_asset)
    backend = backend_for_asset(asset)
    if isinstance(backend, LocalStorageBackend):
        private_url = backend.create_private_url(asset.object_key, ttl_seconds=settings.MEDIA_PRIVATE_URL_TTL_SECONDS, asset_id=asset.id, expected_generation=asset.manifest_generation)
    else:
        private_url = backend.create_private_url(asset.object_key, ttl_seconds=settings.MEDIA_PRIVATE_URL_TTL_SECONDS)
    record(actor=actor, action="song.preview_authorized", target=song, changes={"asset_id": str(asset.id), "backend": asset.backend, "media_type": asset.media_type}, request_id=request_id)
    return private_url


@dataclass(frozen=True)
class SourceReceiptSnapshot:
    asset_id: UUID
    owner_type: str
    owner_id: UUID
    media_type: str
    backend: str
    object_key: str
    mime: str
    size: int
    sha256: str
    etag: str
    manifest_generation: str
    status: str
    deleted_at: object | None

    @classmethod
    def from_asset(cls, asset: MediaAsset) -> SourceReceiptSnapshot:
        return cls(
            asset_id=asset.id, owner_type=asset.owner_type, owner_id=asset.owner_id,
            media_type=asset.media_type, backend=asset.backend,
            object_key=asset.object_key, mime=asset.mime, size=asset.size,
            sha256=asset.sha256, etag=asset.etag,
            manifest_generation=asset.manifest_generation,
            status=asset.status, deleted_at=asset.deleted_at,
        )

    def matches(self, asset: MediaAsset) -> bool:
        return self == SourceReceiptSnapshot.from_asset(asset)


@dataclass(frozen=True)
class AvailabilityEvaluation:
    """一次远程复核的纯结果；创建它不会写 Song。"""

    song_id: UUID
    expected_source_asset_id: UUID
    expected_receipt: SourceReceiptSnapshot
    outcome: str
    new_snapshot: dict[str, object] | None


@dataclass(frozen=True)
class AvailabilityEvaluationPage:
    evaluations: tuple[AvailabilityEvaluation, ...]
    next_after_id: UUID | None


def _availability_snapshot(*, asset: MediaAsset, available: bool, verified_at) -> dict[str, object]:
    return {
        "source_available": available,
        "source_verified_at": verified_at,
        "source_verified_asset_id": asset.id if available else None,
        "source_receipt_fingerprint": source_receipt_fingerprint(asset) if available else "",
        "source_verified_backend": asset.backend if available else "",
        "source_verified_object_key": asset.object_key if available else "",
        "source_verified_size": asset.size if available else None,
        "source_verified_mime": asset.mime if available else "",
        "source_verified_sha256": asset.sha256 if available else "",
        "source_verified_etag": asset.etag if available else "",
        "source_verified_generation": asset.manifest_generation if available else "",
    }


def evaluate_song_source_availability(song: Song) -> AvailabilityEvaluation:
    """在事务外做远程 I/O，仅返回评估，不修改 Song 或 MediaAsset。"""
    asset = song.source_asset
    if asset is None:
        raise ValueError("availability evaluation requires source_asset")
    expected_receipt = SourceReceiptSnapshot.from_asset(asset)

    def evaluation(outcome: str, *, available: bool | None = None) -> AvailabilityEvaluation:
        snapshot = None if available is None else _availability_snapshot(
            asset=asset, available=available, verified_at=timezone.now(),
        )
        return AvailabilityEvaluation(
            song_id=song.id, expected_source_asset_id=asset.id,
            expected_receipt=expected_receipt, outcome=outcome,
            new_snapshot=snapshot,
        )

    if (
        song.deleted_at is not None or asset.deleted_at is not None
        or asset.owner_type != MediaAsset.OwnerType.SONG
        or asset.owner_id != song.id or asset.media_type != "song_source"
        or asset.status != MediaAsset.Status.READY
    ):
        return evaluation("unavailable", available=False)
    try:
        metadata = backend_for_asset(asset).stat(asset.object_key)
    except StorageValidationError as exc:
        if asset.backend == "qiniu" and "查询失败" in str(exc):
            logger.warning(
                "song_source_verify_deferred song_id=%s asset_id=%s reason=storage_unavailable",
                song.id, asset.id,
            )
            return evaluation("deferred")
        return evaluation("unavailable", available=False)
    except Exception:
        logger.warning(
            "song_source_verify_deferred song_id=%s asset_id=%s reason=backend_exception",
            song.id, asset.id,
        )
        return evaluation("deferred")
    if metadata.object_key != asset.object_key or metadata.size != asset.size or metadata.mime != asset.mime:
        return evaluation("unavailable", available=False)
    if asset.backend == "local" and (
        not asset.sha256 or metadata.sha256 != asset.sha256
        or metadata.generation != asset.manifest_generation
    ):
        return evaluation("unavailable", available=False)
    if asset.backend == "qiniu" and (not asset.etag or metadata.etag != asset.etag):
        return evaluation("unavailable", available=False)
    return evaluation("verified", available=True)


def evaluate_song_availability_page(*, batch_size: int = 100, after_id: UUID | None = None) -> AvailabilityEvaluationPage:
    batch_size = max(1, min(int(batch_size), 500))
    queryset = Song.objects.filter(
        deleted_at__isnull=True, source_asset__isnull=False,
    ).select_related("source_asset").order_by("id")
    if after_id is not None:
        queryset = queryset.filter(id__gt=after_id)
    songs = list(queryset[:batch_size])
    evaluations = tuple(evaluate_song_source_availability(song) for song in songs)
    next_after_id = songs[-1].id if len(songs) == batch_size else None
    return AvailabilityEvaluationPage(evaluations=evaluations, next_after_id=next_after_id)


def _apply_song_availability_evaluations_locked(
    evaluations: tuple[AvailabilityEvaluation, ...] | list[AvailabilityEvaluation],
) -> dict[str, int]:
    """调用方须在 atomic 中；统一锁序为 Song→MediaAsset。"""
    stats = {"verified": 0, "unavailable": 0, "deferred": 0, "processed": 0}
    ordered = sorted(evaluations, key=lambda item: str(item.song_id))
    song_ids = sorted({item.song_id for item in ordered}, key=str)
    songs = {
        song.id: song
        for song in Song.objects.select_for_update().filter(id__in=song_ids).order_by("id")
    }
    asset_ids = sorted({item.expected_source_asset_id for item in ordered}, key=str)
    assets = {
        asset.id: asset
        for asset in MediaAsset.objects.select_for_update().filter(id__in=asset_ids).order_by("id")
    }
    for item in ordered:
        song = songs.get(item.song_id)
        asset = assets.get(item.expected_source_asset_id)
        if (
            song is None or song.deleted_at is not None
            or song.source_asset_id != item.expected_source_asset_id
            or asset is None or not item.expected_receipt.matches(asset)
        ):
            continue
        if item.new_snapshot is not None:
            Song.objects.filter(
                pk=song.id, source_asset_id=item.expected_source_asset_id,
            ).update(**item.new_snapshot)
        stats[item.outcome] += 1
        stats["processed"] += 1
    return stats


def apply_song_availability_evaluations(
    evaluations: tuple[AvailabilityEvaluation, ...] | list[AvailabilityEvaluation],
) -> dict[str, int]:
    with transaction.atomic():
        return _apply_song_availability_evaluations_locked(evaluations)


def refresh_song_source_availability(*, batch_size: int = 100, after_id: UUID | None = None) -> dict[str, int | str]:
    """兼容单批刷新入口：远程评估在事务外，短事务只做回执 CAS 写入。"""
    page = evaluate_song_availability_page(batch_size=batch_size, after_id=after_id)
    stats: dict[str, int | str] = apply_song_availability_evaluations(page.evaluations)
    stats["next_after_id"] = str(page.next_after_id or "")
    return stats


SONG_AVAILABILITY_SCAN_LEASE_SECONDS = 600


def _dispatch_availability_scan(token: UUID, batch_size: int, batch_token: UUID, batch_version: int) -> None:
    from .tasks import refresh_song_availability_scan_batch_task
    refresh_song_availability_scan_batch_task.apply_async(
        args=[str(token), batch_size, str(batch_token), batch_version],
    )


def _release_scan_after_dispatch_failure(token: UUID, batch_token: UUID, batch_version: int) -> None:
    SongAvailabilityScanState.objects.filter(
        pk=1, claim_token=token, pending_batch_token=batch_token,
        batch_version=batch_version,
    ).update(
        claim_token=None, lease_expires_at=None, pending_batch_token=None,
    )


def start_song_availability_scan(*, batch_size: int = 100, dispatcher=None) -> bool:
    """取得全库扫描单例租约；过期扫描保留游标并由下一轮续跑。"""
    dispatcher = dispatcher or _dispatch_availability_scan
    batch_size = max(1, min(int(batch_size), 100))
    now = timezone.now()
    with transaction.atomic():
        SongAvailabilityScanState.objects.get_or_create(pk=1)
        state = SongAvailabilityScanState.objects.select_for_update().get(pk=1)
        if state.claim_token and state.lease_expires_at and state.lease_expires_at > now:
            return False
        token = uuid4()
        state.claim_token = token
        state.lease_expires_at = now + timedelta(seconds=SONG_AVAILABILITY_SCAN_LEASE_SECONDS)
        state.batch_version += 1
        batch_version = state.batch_version
        batch_token = uuid4()
        state.pending_batch_token = batch_token
        if state.cursor is None:
            state.stats = {"verified": 0, "unavailable": 0, "deferred": 0, "processed": 0}
        state.save()
    try:
        dispatcher(token, batch_size, batch_token, batch_version)
    except Exception as exc:
        logger.error("song_availability_scan_dispatch_failed exception=%s", exc.__class__.__name__)
        _release_scan_after_dispatch_failure(token, batch_token, batch_version)
        return False
    return True


def run_song_availability_scan_batch(
    token: UUID, *, batch_size: int = 100, batch_token: UUID | None = None,
    batch_version: int | None = None, dispatcher=None,
) -> dict[str, int | str]:
    dispatcher = dispatcher or _dispatch_availability_scan
    now = timezone.now()
    with transaction.atomic():
        state = SongAvailabilityScanState.objects.select_for_update().get(pk=1)
        if (
            state.claim_token != token or state.pending_batch_token != batch_token
            or state.batch_version != batch_version or not state.lease_expires_at
            or state.lease_expires_at <= now
        ):
            return {"status": "lease_lost"}
        cursor = state.cursor
        state.lease_expires_at = now + timedelta(seconds=SONG_AVAILABILITY_SCAN_LEASE_SECONDS)
        state.save(update_fields=["lease_expires_at", "updated_at"])

    # 远程 stat/回执判断必须在任何业务行锁之外完成；此阶段只产生纯评估。
    page = evaluate_song_availability_page(batch_size=batch_size, after_id=cursor)
    next_cursor = page.next_after_id
    with transaction.atomic():
        state = SongAvailabilityScanState.objects.select_for_update().get(pk=1)
        cas_now = timezone.now()
        if (
            state.claim_token != token or state.pending_batch_token != batch_token
            or state.batch_version != batch_version or state.cursor != cursor
            or not state.lease_expires_at or state.lease_expires_at <= cas_now
        ):
            return {"status": "stale_batch"}
        # 全局锁序固定为 ScanState→Song(UUID)→MediaAsset(UUID)。CAS 通过前零业务写入。
        applied = _apply_song_availability_evaluations_locked(page.evaluations)
        aggregate = dict(state.stats)
        for key in ("verified", "unavailable", "deferred", "processed"):
            aggregate[key] = int(aggregate.get(key, 0)) + int(applied[key])
        state.stats = aggregate
        state.cursor = next_cursor
        if next_cursor is None:
            state.claim_token = None
            state.lease_expires_at = None
            state.pending_batch_token = None
        else:
            state.lease_expires_at = timezone.now() + timedelta(seconds=SONG_AVAILABILITY_SCAN_LEASE_SECONDS)
            state.batch_version += 1
            next_batch_version = state.batch_version
            next_batch_token = uuid4()
            state.pending_batch_token = next_batch_token
        state.save()
    if next_cursor is not None:
        try:
            dispatcher(token, batch_size, next_batch_token, next_batch_version)
        except Exception as exc:
            logger.error("song_availability_scan_dispatch_failed exception=%s", exc.__class__.__name__)
            _release_scan_after_dispatch_failure(token, next_batch_token, next_batch_version)
            return {**aggregate, "status": "dispatch_failed", "next_after_id": str(next_cursor)}
    return {**aggregate, "status": "completed" if next_cursor is None else "continued", "next_after_id": str(next_cursor or "")}
