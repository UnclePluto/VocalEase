from __future__ import annotations

from dataclasses import dataclass
from datetime import timedelta
import logging
from threading import Event, Thread
from uuid import UUID, uuid4

from django.conf import settings
from django.db import close_old_connections, transaction
from django.db.models import Q
from django.utils import timezone
from rest_framework.exceptions import APIException, ValidationError

from apps.media.models import MediaAsset
from apps.songs.models import Song
from apps.songs.services import (SourceAssetInvalid, SourceVerificationTemporary,
                                 source_receipt_fingerprint,
                                 validate_source_asset)

from .contracts import (AnalysisProtocolError, PermanentAnalysisError,
                        TransientAnalysisError)
from .executors import resolve_executor
from .models import AnalysisResult, AnalysisTask


logger = logging.getLogger(__name__)
MAX_ATTEMPTS = 4
DISPATCH_LEASE_SECONDS = 60


class AnalysisIdempotencyConflict(APIException):
    status_code = 409
    default_code = "analysis_idempotency_conflict"
    default_detail = "幂等键已用于不同的分析请求"


@dataclass(frozen=True)
class AnalysisClaim:
    task_id: UUID
    claim_token: UUID


@dataclass(frozen=True)
class TransientOutcome:
    task: AnalysisTask
    should_retry: bool


class AnalysisRetryRequested(TransientAnalysisError):
    def __init__(self, *, should_retry: bool):
        super().__init__("分析服务暂时不可用")
        self.should_retry = should_retry


class ExecutionContext:
    """执行器可观察的租约上下文；自动心跳与主动心跳共用同一 CAS。"""

    def __init__(self, task_id: UUID, claim_token: UUID):
        self.task_id = task_id
        self.claim_token = claim_token
        self._lost = Event()

    @property
    def cancelled(self) -> bool:
        return self._lost.is_set()

    def heartbeat(self) -> bool:
        if self.cancelled:
            return False
        alive = heartbeat_analysis_task(self.task_id, self.claim_token)
        if not alive:
            self._lost.set()
        return alive

    def mark_lost(self) -> None:
        self._lost.set()


class LeaseGuard:
    """执行期间自动续租；退出时可靠停止后台线程。"""

    def __init__(self, context: ExecutionContext, *, interval_seconds: float | None = None):
        lease_seconds = max(float(settings.ANALYSIS_TASK_LEASE_SECONDS), 0.12)
        self.context = context
        requested = interval_seconds if interval_seconds is not None else lease_seconds / 4
        self.interval_seconds = min(max(0.01, requested), lease_seconds / 4)
        self._stop = Event()
        self._thread: Thread | None = None

    def __enter__(self):
        self._thread = Thread(target=self._run, name=f"analysis-lease-{self.context.task_id}", daemon=True)
        self._thread.start()
        return self.context

    def _run(self) -> None:
        close_old_connections()
        try:
            while not self._stop.wait(self.interval_seconds):
                try:
                    if not self.context.heartbeat():
                        return
                except Exception as exc:
                    self.context.mark_lost()
                    logger.error(
                        "analysis_heartbeat_failed task_id=%s exception=%s",
                        self.context.task_id, exc.__class__.__name__,
                    )
                    return
        finally:
            close_old_connections()

    def __exit__(self, exc_type, exc, traceback):
        self._stop.set()
        if self._thread is not None:
            self._thread.join()


def _snapshot(asset: MediaAsset) -> dict[str, object]:
    return {
        "source_asset_id": str(asset.id),
        "object_key": asset.object_key,
        "mime": asset.mime,
        "size": asset.size,
        "backend": asset.backend,
        "receipt_fingerprint": source_receipt_fingerprint(asset),
    }


def _create_song_analysis(
    *, song: Song, source_asset: MediaAsset,
    task_type: str = "vocal_separation", idempotency_key: str | None = None,
    executor: str = "mock_song", protocol_version: str = "1.0",
) -> tuple[AnalysisTask, bool]:
    try:
        resolve_executor(task_type, executor, protocol_version)
    except AnalysisProtocolError as exc:
        raise ValidationError({"analysis": "不支持的任务类型、执行器或协议版本"}) from exc
    if song.deleted_at is not None:
        raise SourceAssetInvalid()
    validation_error = None
    task = None
    created = False
    with transaction.atomic():
        locked_song = Song.objects.select_for_update().get(pk=song.id, deleted_at__isnull=True)
        asset = MediaAsset.objects.select_for_update().get(pk=source_asset.id, deleted_at__isnull=True)
        if locked_song.source_asset_id != asset.id:
            raise SourceAssetInvalid("分析任务必须绑定歌曲当前源媒体", code="analysis_source_superseded")
        try:
            asset = validate_source_asset(song=locked_song, asset=asset)
        except (SourceAssetInvalid, SourceVerificationTemporary) as exc:
            validation_error = exc
        if validation_error is None:
            key = idempotency_key or uuid4().hex
            task, created = AnalysisTask.objects.get_or_create(
                idempotency_key=key,
                defaults={
                    "song": locked_song, "source_asset": asset, "task_type": task_type,
                    "protocol_version": protocol_version, "executor": executor,
                    "input_snapshot": _snapshot(asset),
                },
            )
            if not created and (
                task.song_id != locked_song.id or task.source_asset_id != asset.id
                or task.task_type != task_type or task.executor != executor
                or task.protocol_version != protocol_version
            ):
                raise AnalysisIdempotencyConflict()
            if created:
                locked_song.analysis_status = Song.AnalysisStatus.PENDING
                locked_song.publication_status = Song.PublicationStatus.DRAFT
                locked_song.save(update_fields=["analysis_status", "publication_status", "updated_at"])
    if validation_error is not None:
        raise validation_error
    assert task is not None
    return task, created


def create_song_analysis(**kwargs) -> AnalysisTask:
    task, _created = _create_song_analysis(**kwargs)
    return task


def schedule_analysis_task(task_id: UUID) -> bool:
    """带数据库投递租约的唯一 broker 出口。"""
    now = timezone.now()
    song_id = AnalysisTask.objects.only("song_id").filter(pk=task_id).values_list("song_id", flat=True).first()
    if song_id is None:
        return False
    with transaction.atomic():
        song = Song.objects.select_for_update().get(pk=song_id)
        task = AnalysisTask.objects.select_for_update().filter(pk=task_id).first()
        if task is None or task.status in {
            AnalysisTask.Status.SUCCEEDED, AnalysisTask.Status.FAILED,
            AnalysisTask.Status.SUPERSEDED,
        }:
            return False
        if task.attempt >= MAX_ATTEMPTS:
            _fail_exhausted_locked(task, now=now, song=song)
            return False
        if task.status == AnalysisTask.Status.PROCESSING and task.lease_expires_at and task.lease_expires_at > now:
            return False
        if task.next_dispatch_at and task.next_dispatch_at > now:
            return False
        dispatch_until = now + timedelta(seconds=DISPATCH_LEASE_SECONDS)
        task.next_dispatch_at = dispatch_until
        task.save(update_fields=["next_dispatch_at", "updated_at"])
    try:
        from .tasks import run_analysis_task
        run_analysis_task.delay(str(task_id))
    except Exception as exc:
        logger.error(
            "analysis_dispatch_failed task_id=%s exception=%s",
            task_id, exc.__class__.__name__,
        )
        AnalysisTask.objects.filter(
            pk=task_id,
            status__in=[AnalysisTask.Status.PENDING, AnalysisTask.Status.RETRYING],
            next_dispatch_at=dispatch_until,
        ).update(next_dispatch_at=None)
        return False
    return True


def schedule_analysis_on_commit(task_id: UUID) -> None:
    transaction.on_commit(lambda: schedule_analysis_task(task_id))


def request_song_analysis(**kwargs) -> AnalysisTask:
    task, created = _create_song_analysis(**kwargs)
    if created:
        schedule_analysis_on_commit(task.id)
    return task


def recover_analysis_tasks(*, batch_size: int = 100) -> dict[str, int]:
    now = timezone.now()
    limit = max(1, min(batch_size, 500))
    exhausted = 0
    exhausted_ids = list(
        AnalysisTask.objects.filter(
            Q(status__in=[AnalysisTask.Status.PENDING, AnalysisTask.Status.RETRYING])
            | Q(status=AnalysisTask.Status.PROCESSING, lease_expires_at__lte=now),
            attempt__gte=MAX_ATTEMPTS,
        ).order_by("created_at").values_list("id", flat=True)[:limit]
    )
    for task_id in exhausted_ids:
        song_id = AnalysisTask.objects.only("song_id").get(pk=task_id).song_id
        with transaction.atomic():
            song = Song.objects.select_for_update().get(pk=song_id)
            task = AnalysisTask.objects.select_for_update().get(pk=task_id, song_id=song.id)
            if task.attempt >= MAX_ATTEMPTS and (
                task.status in {AnalysisTask.Status.PENDING, AnalysisTask.Status.RETRYING}
                or task.status == AnalysisTask.Status.PROCESSING
                and task.lease_expires_at and task.lease_expires_at <= now
            ):
                _fail_exhausted_locked(task, now=now, song=song)
                exhausted += 1
    ids = list(
        AnalysisTask.objects.filter(
            Q(status__in=[AnalysisTask.Status.PENDING, AnalysisTask.Status.RETRYING])
            | Q(status=AnalysisTask.Status.PROCESSING, lease_expires_at__lte=now)
        ).filter(attempt__lt=MAX_ATTEMPTS).filter(
            Q(next_dispatch_at__isnull=True) | Q(next_dispatch_at__lte=now)
        ).order_by("created_at").values_list("id", flat=True)[:limit]
    )
    dispatched = sum(1 for task_id in ids if schedule_analysis_task(task_id))
    return {"dispatched": dispatched, "exhausted": exhausted, "examined": len(ids) + exhausted}


def redispatch_pending_analyses(*, batch_size: int = 100) -> int:
    return recover_analysis_tasks(batch_size=batch_size)["dispatched"]


def supersede_stale_song_analyses(*, song: Song) -> int:
    with transaction.atomic():
        locked_song = Song.objects.select_for_update().get(pk=song.id)
        stale = list(
            AnalysisTask.objects.select_for_update().filter(
                song=locked_song,
                status__in=[AnalysisTask.Status.PENDING, AnalysisTask.Status.RETRYING, AnalysisTask.Status.PROCESSING],
            ).exclude(source_asset_id=locked_song.source_asset_id)
        )
        now = timezone.now()
        for task in stale:
            task.status = AnalysisTask.Status.SUPERSEDED
            task.claim_token = None
            task.lease_expires_at = None
            task.heartbeat_at = None
            task.next_dispatch_at = None
            task.error_code = "analysis_source_superseded"
            task.error_summary = "歌曲源媒体已替换，任务不再适用"
            task.completed_at = now
            task.save(update_fields=[
                "status", "claim_token", "lease_expires_at", "heartbeat_at", "next_dispatch_at",
                "error_code", "error_summary", "completed_at", "updated_at",
            ])
        return len(stale)


def _clear_claim(task: AnalysisTask) -> None:
    task.claim_token = None
    task.lease_expires_at = None
    task.heartbeat_at = None


def _update_current_song_status(song: Song, task: AnalysisTask, status: str) -> bool:
    if song.deleted_at is not None or song.source_asset_id != task.source_asset_id:
        return False
    song.analysis_status = status
    song.save(update_fields=["analysis_status", "updated_at"])
    return True


def _fail_exhausted_locked(task: AnalysisTask, *, now=None, song: Song | None = None) -> AnalysisTask:
    now = now or timezone.now()
    AnalysisResult.objects.filter(task=task).delete()
    task.status = AnalysisTask.Status.FAILED
    _clear_claim(task)
    task.next_dispatch_at = None
    task.error_code = "analysis_retry_exhausted"
    task.error_summary = "分析服务暂时不可用，已超过重试次数"
    task.completed_at = now
    task.save()
    if song is not None:
        _update_current_song_status(song, task, Song.AnalysisStatus.FAILED)
    else:
        Song.objects.filter(
            pk=task.song_id, deleted_at__isnull=True, source_asset_id=task.source_asset_id,
        ).update(analysis_status=Song.AnalysisStatus.FAILED, updated_at=now)
    return task


def claim_analysis_task(task_id: UUID, *, now=None) -> AnalysisClaim | None:
    now = now or timezone.now()
    song_id = AnalysisTask.objects.only("song_id").get(pk=task_id).song_id
    with transaction.atomic():
        # source_asset 可为空；PostgreSQL 禁止对 LEFT JOIN 的可空侧 FOR UPDATE。
        # 全模块固定 Song -> AnalysisTask -> MediaAsset 锁序，避免换源与 worker 死锁。
        song = Song.objects.select_for_update().get(pk=song_id)
        task = AnalysisTask.objects.select_for_update().get(pk=task_id, song_id=song.id)
        if task.status in {AnalysisTask.Status.SUCCEEDED, AnalysisTask.Status.FAILED, AnalysisTask.Status.SUPERSEDED}:
            return None
        if task.source_asset_id != song.source_asset_id or song.deleted_at is not None:
            task.status = AnalysisTask.Status.SUPERSEDED
            _clear_claim(task)
            task.next_dispatch_at = None
            task.error_code = "analysis_source_superseded"
            task.error_summary = "歌曲源媒体已替换，任务不再适用"
            task.completed_at = now
            task.save()
            return None
        if task.status == AnalysisTask.Status.PROCESSING and task.lease_expires_at and task.lease_expires_at > now:
            return None
        if task.attempt >= MAX_ATTEMPTS:
            _fail_exhausted_locked(task, now=now, song=song)
            return None
        try:
            resolve_executor(task.task_type, task.executor, task.protocol_version)
        except AnalysisProtocolError:
            task.status = AnalysisTask.Status.FAILED
            _clear_claim(task)
            task.next_dispatch_at = None
            task.error_code = "analysis_protocol_unsupported"
            task.error_summary = "分析任务协议不受支持"
            task.completed_at = now
            task.save()
            _update_current_song_status(song, task, Song.AnalysisStatus.FAILED)
            return None
        token = uuid4()
        task.status = AnalysisTask.Status.PROCESSING
        task.attempt += 1
        task.claim_token = token
        task.heartbeat_at = now
        task.lease_expires_at = now + timedelta(seconds=settings.ANALYSIS_TASK_LEASE_SECONDS)
        task.started_at = now
        task.error_code = ""
        task.error_summary = ""
        task.completed_at = None
        task.next_dispatch_at = None
        task.save()
        _update_current_song_status(song, task, Song.AnalysisStatus.PROCESSING)
        return AnalysisClaim(task.id, token)


def heartbeat_analysis_task(task_id: UUID, claim_token: UUID, *, now=None) -> bool:
    now = now or timezone.now()
    with transaction.atomic():
        task = AnalysisTask.objects.select_for_update().get(pk=task_id)
        if task.status != AnalysisTask.Status.PROCESSING or task.claim_token != claim_token or not task.lease_expires_at or task.lease_expires_at <= now:
            return False
        task.heartbeat_at = now
        task.lease_expires_at = now + timedelta(seconds=settings.ANALYSIS_TASK_LEASE_SECONDS)
        task.save(update_fields=["heartbeat_at", "lease_expires_at", "updated_at"])
        return True


def _locked_claim(task_id: UUID, claim_token: UUID, *, require_unexpired: bool = True):
    song_id = AnalysisTask.objects.only("song_id").get(pk=task_id).song_id
    song = Song.objects.select_for_update().get(pk=song_id)
    task = AnalysisTask.objects.select_for_update().get(pk=task_id, song_id=song.id)
    valid = task.status == AnalysisTask.Status.PROCESSING and task.claim_token == claim_token
    if require_unexpired:
        valid = valid and bool(task.lease_expires_at and task.lease_expires_at > timezone.now())
    return task, song, valid


def _validate_claim_before_execute(claim: AnalysisClaim) -> AnalysisTask:
    problem = ""
    with transaction.atomic():
        task, song, valid = _locked_claim(claim.task_id, claim.claim_token)
        if not valid:
            problem = "lease"
        elif song.deleted_at is not None or song.source_asset_id != task.source_asset_id:
            task.status = AnalysisTask.Status.SUPERSEDED
            _clear_claim(task)
            task.next_dispatch_at = None
            task.error_code = "analysis_source_superseded"
            task.error_summary = "歌曲源媒体已替换，任务不再适用"
            task.completed_at = timezone.now()
            task.save()
            problem = "superseded"
        else:
            try:
                validate_source_asset(song=song, asset=song.source_asset)
            except SourceVerificationTemporary:
                problem = "temporary"
            except SourceAssetInvalid:
                problem = "unavailable"
    if problem == "temporary":
        raise TransientAnalysisError("源媒体暂时无法复核")
    if problem == "unavailable":
        raise SourceAssetInvalid()
    if problem:
        raise PermanentAnalysisError("分析任务租约或源快照已失效")
    return task


def finalize_analysis_success(task_id: UUID, claim_token: UUID, payload) -> AnalysisTask:
    with transaction.atomic():
        task, song, valid = _locked_claim(task_id, claim_token)
        if not valid:
            return task
        if song.deleted_at is not None or song.source_asset_id != task.source_asset_id:
            task.status = AnalysisTask.Status.SUPERSEDED
            _clear_claim(task)
            task.next_dispatch_at = None
            task.error_code = "analysis_source_superseded"
            task.error_summary = "歌曲源媒体已替换，任务不再适用"
            task.completed_at = timezone.now()
            task.save()
            return task
        try:
            validate_source_asset(song=song, asset=song.source_asset)
            registration = resolve_executor(task.task_type, task.executor, task.protocol_version)
            parsed = registration.result_parser(payload)
        except SourceVerificationTemporary as exc:
            raise TransientAnalysisError("源媒体暂时无法复核") from exc
        except SourceAssetInvalid:
            return finalize_analysis_failure(task_id, claim_token, code="analysis_source_unavailable", summary="分析源媒体已不可用")
        except AnalysisProtocolError:
            return finalize_analysis_failure(task_id, claim_token, code="analysis_result_invalid", summary="分析结果协议无效")
        AnalysisResult.objects.update_or_create(
            task=task,
            defaults={"protocol_version": parsed.protocol_version, "is_mock": parsed.is_mock, "payload": parsed.as_dict()},
        )
        task.status = AnalysisTask.Status.SUCCEEDED
        _clear_claim(task)
        task.next_dispatch_at = None
        task.error_code = ""
        task.error_summary = ""
        task.completed_at = timezone.now()
        task.save()
        _update_current_song_status(song, task, Song.AnalysisStatus.SUCCEEDED)
        return task


def handle_analysis_transient(task_id: UUID, claim_token: UUID) -> TransientOutcome:
    with transaction.atomic():
        task, song, valid = _locked_claim(task_id, claim_token)
        if not valid:
            return TransientOutcome(task=task, should_retry=False)
        AnalysisResult.objects.filter(task=task).delete()
        if task.attempt >= MAX_ATTEMPTS:
            _fail_exhausted_locked(task, song=song)
            return TransientOutcome(task=task, should_retry=False)
        task.status = AnalysisTask.Status.RETRYING
        _clear_claim(task)
        task.next_dispatch_at = timezone.now() + timedelta(seconds=DISPATCH_LEASE_SECONDS)
        task.error_code = "analysis_transient_error"
        task.error_summary = "分析服务暂时不可用，稍后将自动重试"
        task.completed_at = None
        task.save()
        _update_current_song_status(song, task, Song.AnalysisStatus.RETRYING)
        return TransientOutcome(task=task, should_retry=True)


def finalize_analysis_transient(task_id: UUID, claim_token: UUID) -> AnalysisTask:
    return handle_analysis_transient(task_id, claim_token).task


def finalize_analysis_failure(task_id: UUID, claim_token: UUID, *, code: str, summary: str) -> AnalysisTask:
    with transaction.atomic():
        task, song, valid = _locked_claim(task_id, claim_token, require_unexpired=False)
        if not valid:
            return task
        # 过期 worker 即使失败也不能覆盖新 worker；未被重领的过期任务留给 lease recovery。
        if not task.lease_expires_at or task.lease_expires_at <= timezone.now():
            return task
        AnalysisResult.objects.filter(task=task).delete()
        task.status = AnalysisTask.Status.FAILED
        _clear_claim(task)
        task.next_dispatch_at = None
        task.error_code = code
        task.error_summary = summary[:256]
        task.completed_at = timezone.now()
        task.save()
        _update_current_song_status(song, task, Song.AnalysisStatus.FAILED)
        return task


def run_analysis(task_id: UUID) -> AnalysisTask:
    claim = claim_analysis_task(task_id)
    if claim is None:
        return AnalysisTask.objects.get(pk=task_id)
    context = ExecutionContext(claim.task_id, claim.claim_token)
    try:
        with LeaseGuard(context):
            task = _validate_claim_before_execute(claim)
            registration = resolve_executor(task.task_type, task.executor, task.protocol_version)
            payload = registration.factory().execute(task, context=context)
        if context.cancelled:
            return AnalysisTask.objects.get(pk=task_id)
        return finalize_analysis_success(task.id, claim.claim_token, payload)
    except (TransientAnalysisError, SourceVerificationTemporary) as exc:
        outcome = handle_analysis_transient(claim.task_id, claim.claim_token)
        raise AnalysisRetryRequested(should_retry=outcome.should_retry) from exc
    except SourceAssetInvalid:
        return finalize_analysis_failure(claim.task_id, claim.claim_token, code="analysis_source_unavailable", summary="分析源媒体已不可用")
    except AnalysisProtocolError:
        return finalize_analysis_failure(claim.task_id, claim.claim_token, code="analysis_result_invalid", summary="分析结果协议无效")
    except PermanentAnalysisError:
        return finalize_analysis_failure(claim.task_id, claim.claim_token, code="analysis_execution_failed", summary="分析执行失败")
    except Exception:
        return finalize_analysis_failure(claim.task_id, claim.claim_token, code="analysis_execution_failed", summary="分析执行失败")
