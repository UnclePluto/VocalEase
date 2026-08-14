from __future__ import annotations

from uuid import UUID, uuid4

from django.db import transaction
from django.utils import timezone
from rest_framework.exceptions import APIException

from apps.media.models import MediaAsset
from apps.songs.models import Song
from apps.songs.services import SourceAssetInvalid, validate_source_asset

from .contracts import PermanentAnalysisError, TransientAnalysisError
from .executors import MockSongExecutor
from .models import AnalysisResult, AnalysisTask


MAX_TRANSIENT_RETRIES = 3


class AnalysisIdempotencyConflict(APIException):
    status_code = 409
    default_code = "analysis_idempotency_conflict"
    default_detail = "幂等键已用于不同的分析请求"


def _snapshot(asset: MediaAsset) -> dict[str, object]:
    return {"source_asset_id": str(asset.id), "object_key": asset.object_key, "mime": asset.mime, "size": asset.size, "backend": asset.backend}


def create_song_analysis(*, song: Song, source_asset: MediaAsset, task_type: str = "vocal_separation", idempotency_key: str | None = None) -> AnalysisTask:
    if song.deleted_at is not None:
        raise SourceAssetInvalid()
    with transaction.atomic():
        locked_song = Song.objects.select_for_update().get(pk=song.id, deleted_at__isnull=True)
        asset = MediaAsset.objects.select_for_update().get(pk=source_asset.id, deleted_at__isnull=True)
        asset = validate_source_asset(song=locked_song, asset=asset)
        key = idempotency_key or uuid4().hex
        task, created = AnalysisTask.objects.get_or_create(
            idempotency_key=key,
            defaults={
                "song": locked_song, "source_asset": asset, "task_type": task_type,
                "protocol_version": "1.0", "executor": "mock_song", "input_snapshot": _snapshot(asset),
            },
        )
        if not created and (task.song_id != locked_song.id or task.source_asset_id != asset.id or task.task_type != task_type):
            raise AnalysisIdempotencyConflict()
        if created:
            locked_song.analysis_status = Song.AnalysisStatus.PENDING
            locked_song.save(update_fields=["analysis_status", "updated_at"])
        return task


def _set_song_status(song_id: UUID, status: str) -> None:
    Song.objects.filter(pk=song_id, deleted_at__isnull=True).update(analysis_status=status)


def _fail(task_id: UUID, *, code: str, summary: str) -> AnalysisTask:
    with transaction.atomic():
        task = AnalysisTask.objects.select_for_update().get(pk=task_id)
        task.status = AnalysisTask.Status.FAILED
        task.result = {}
        task.error_code = code
        task.error_summary = summary[:256]
        task.completed_at = timezone.now()
        task.save(update_fields=["status", "result", "error_code", "error_summary", "completed_at", "updated_at"])
        _set_song_status(task.song_id, Song.AnalysisStatus.FAILED)
        return task


def _retry_or_fail(task_id: UUID) -> AnalysisTask:
    with transaction.atomic():
        task = AnalysisTask.objects.select_for_update().get(pk=task_id)
        if task.attempt > MAX_TRANSIENT_RETRIES:
            task.status = AnalysisTask.Status.FAILED
            task.result = {}
            task.error_code = "analysis_retry_exhausted"
            task.error_summary = "分析服务暂时不可用，已超过重试次数"
            task.completed_at = timezone.now()
            _set_song_status(task.song_id, Song.AnalysisStatus.FAILED)
        else:
            task.status = AnalysisTask.Status.RETRYING
            task.result = {}
            task.error_code = "analysis_transient_error"
            task.error_summary = "分析服务暂时不可用，稍后将自动重试"
            _set_song_status(task.song_id, Song.AnalysisStatus.RETRYING)
        task.save(update_fields=["status", "result", "error_code", "error_summary", "completed_at", "updated_at"])
        return task


def run_analysis(task_id: UUID) -> AnalysisTask:
    """行锁领取任务；重复 Celery 投递只能观察同一终态，不能重复执行。"""
    with transaction.atomic():
        task = AnalysisTask.objects.select_for_update().select_related("song", "source_asset").get(pk=task_id)
        if task.status in {AnalysisTask.Status.SUCCEEDED, AnalysisTask.Status.FAILED}:
            return task
        if task.status == AnalysisTask.Status.PROCESSING:
            return task
        task.status = AnalysisTask.Status.PROCESSING
        task.attempt += 1
        task.started_at = timezone.now()
        task.result = {}
        task.error_code = ""
        task.error_summary = ""
        task.save(update_fields=["status", "attempt", "started_at", "result", "error_code", "error_summary", "updated_at"])
        _set_song_status(task.song_id, Song.AnalysisStatus.PROCESSING)
        task_id = task.id

    try:
        fresh = AnalysisTask.objects.select_related("song", "source_asset").get(pk=task_id)
        validate_source_asset(song=fresh.song, asset=fresh.source_asset)
        payload = MockSongExecutor().execute(fresh)
        if payload != {"protocol_version": "1.0", "is_mock": True, "artifacts": [], "metrics": {}}:
            raise PermanentAnalysisError("模拟执行器协议无效")
    except SourceAssetInvalid:
        return _fail(task_id, code="analysis_source_unavailable", summary="分析源媒体已不可用")
    except TransientAnalysisError:
        retried = _retry_or_fail(task_id)
        if retried.status == AnalysisTask.Status.RETRYING:
            raise
        return retried
    except Exception:
        return _fail(task_id, code="analysis_execution_failed", summary="分析执行失败")

    with transaction.atomic():
        task = AnalysisTask.objects.select_for_update().get(pk=task_id)
        if task.status != AnalysisTask.Status.PROCESSING:
            return task
        task.status = AnalysisTask.Status.SUCCEEDED
        task.result = payload
        task.error_code = ""
        task.error_summary = ""
        task.completed_at = timezone.now()
        task.save(update_fields=["status", "result", "error_code", "error_summary", "completed_at", "updated_at"])
        AnalysisResult.objects.update_or_create(
            task=task,
            defaults={"protocol_version": payload["protocol_version"], "is_mock": True, "payload": payload},
        )
        _set_song_status(task.song_id, Song.AnalysisStatus.SUCCEEDED)
        return task
