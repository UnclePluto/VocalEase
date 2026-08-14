from __future__ import annotations

from dataclasses import dataclass
from datetime import timedelta
import logging
from uuid import UUID
from uuid import uuid4

from django.db import transaction
from django.utils import timezone
from rest_framework.exceptions import APIException, NotFound, ValidationError

from apps.analysis.models import AnalysisResult, AnalysisTask
from apps.media.models import MediaAsset
from apps.media.contracts import UploadGrant
from apps.media.services import complete_local_asset, create_upload_grant, reissue_upload_grant
from apps.patients.models import PatientProfile, TreatmentPlan
from apps.songs.models import Song
from apps.songs.services import SourceAssetInvalid, SourceVerificationTemporary, source_receipt_fingerprint, validate_source_asset

from .models import AnalysisTimeSeries, SessionMedia, SingingSession


logger = logging.getLogger(__name__)


class SingingStateConflict(APIException):
    status_code = 409
    default_code = "singing_state_conflict"
    default_detail = "演唱会话当前状态不允许此操作"


class SingingSubmissionConflict(APIException):
    status_code = 409
    default_code = "singing_submission_conflict"
    default_detail = "演唱会话已使用其他幂等键提交"


class SingingMediaConflict(APIException):
    status_code = 409
    default_code = "singing_media_conflict"
    default_detail = "演唱媒体与会话不匹配或尚未就绪"


class SingingRetryConflict(APIException):
    status_code = 409
    default_code = "singing_retry_conflict"
    default_detail = "演唱会话已使用其他幂等键重试"


class SingingRetryExhausted(APIException):
    status_code = 409
    default_code = "singing_retry_exhausted"
    default_detail = "演唱分析已达到最大尝试次数"


@dataclass(frozen=True)
class SubmissionResult:
    session: SingingSession
    task_ids: tuple[UUID, ...]
    created: bool


@dataclass(frozen=True)
class UploadGrantResult:
    session: SingingSession
    asset: MediaAsset
    grant: UploadGrant
    created: bool


def _active_patient_locked(patient_id: UUID) -> PatientProfile:
    try:
        return PatientProfile.objects.select_for_update().select_related("user").get(
            pk=patient_id, deleted_at__isnull=True, user__deleted_at__isnull=True,
            user__is_active=True,
        )
    except PatientProfile.DoesNotExist as exc:
        raise NotFound("患者资料不存在或不可用", code="patient_profile_not_found") from exc


def create_session(*, patient_id: UUID, song_id: UUID, created_source="patient_android_api") -> SingingSession:
    validation_error = None
    session = None
    with transaction.atomic():
        patient = _active_patient_locked(patient_id)
        try:
            plan = TreatmentPlan.objects.select_for_update().get(
                patient=patient, status=TreatmentPlan.Status.ACTIVE, deleted_at__isnull=True,
            )
        except TreatmentPlan.DoesNotExist as exc:
            raise ValidationError({"treatment_plan": "当前没有进行中的治疗计划"}, code="active_treatment_plan_required") from exc
        try:
            song = Song.objects.select_for_update().get(
                pk=song_id, deleted_at__isnull=True, publication_status=Song.PublicationStatus.PUBLISHED,
                source_available=True,
            )
        except Song.DoesNotExist as exc:
            raise ValidationError({"song_id": "歌曲不存在或当前不可用"}, code="song_unavailable") from exc
        if not song.source_asset_id:
            raise ValidationError({"song_id": "歌曲不存在或当前不可用"}, code="song_unavailable")
        try:
            source_asset = MediaAsset.objects.select_for_update().get(
                pk=song.source_asset_id, deleted_at__isnull=True,
            )
        except MediaAsset.DoesNotExist as exc:
            raise ValidationError({"song_id": "歌曲不存在或当前不可用"}, code="song_unavailable") from exc
        try:
            validate_source_asset(song=song, asset=source_asset)
        except SourceVerificationTemporary:
            raise
        except SourceAssetInvalid:
            validation_error = ValidationError({"song_id": "歌曲不存在或当前不可用"}, code="song_unavailable")
        if validation_error is None:
            session = SingingSession.objects.create(
                patient=patient,
                song=song,
                treatment_plan=plan,
                patient_snapshot={"id": str(patient.id), "medical_record_no": patient.medical_record_no, "name": patient.name},
                song_snapshot={"id": str(song.id), "title": song.title, "artist": song.artist, "duration_seconds": song.duration_seconds},
                treatment_plan_snapshot={
                    "id": str(plan.id), "start_date": plan.start_date.isoformat(),
                    "cycle_weeks": plan.cycle_weeks, "target_session_count": plan.target_session_count,
                },
                created_source=created_source,
            )
    if validation_error is not None:
        raise validation_error
    assert session is not None
    return session


def issue_session_upload_grant(*, session_id: UUID, patient_id: UUID, media_type: str, mime: str, size: int, idempotency_key: str = "") -> UploadGrantResult:
    if media_type not in {"singing_audio", "singing_video"}:
        raise ValidationError({"media_type": "仅支持演唱音频或录像"}, code="invalid_media")
    if len(idempotency_key) > 128:
        raise ValidationError({"idempotency_key": "幂等键长度不能超过 128"})
    with transaction.atomic():
        try:
            session = SingingSession.objects.select_for_update().get(pk=session_id, patient_id=patient_id)
        except SingingSession.DoesNotExist as exc:
            raise NotFound() from exc
        if idempotency_key:
            existing = SessionMedia.objects.select_for_update().select_related("asset").filter(
                session=session, grant_idempotency_key=idempotency_key,
            ).first()
            if existing is not None:
                asset = existing.asset
                if existing.media_type != media_type or asset.mime != mime or asset.size != size:
                    raise SingingMediaConflict("幂等键已用于不同的上传凭证请求")
                if asset.status == MediaAsset.Status.READY:
                    if session.status not in {
                        SingingSession.Status.AWAITING_UPLOAD,
                        SingingSession.Status.UPLOADED,
                    }:
                        raise SingingStateConflict()
                    grant = UploadGrant(object_key=asset.object_key, expires_at=asset.upload_expires_at)
                else:
                    if session.status not in {
                        SingingSession.Status.CREATED,
                        SingingSession.Status.AWAITING_UPLOAD,
                    }:
                        raise SingingStateConflict()
                    grant = reissue_upload_grant(asset=asset)
                return UploadGrantResult(session=session, asset=asset, grant=grant, created=False)
        if session.status not in {SingingSession.Status.CREATED, SingingSession.Status.AWAITING_UPLOAD}:
            raise SingingStateConflict()
        if SessionMedia.objects.filter(session=session, media_type=media_type).exists():
            raise SingingMediaConflict("该类型媒体已申请上传凭证")
        asset, grant = create_upload_grant(
            owner_type="patient", owner_id=patient_id, media_type=media_type, mime=mime, size=size,
        )
        SessionMedia.objects.create(
            session=session, asset=asset, media_type=media_type,
            grant_idempotency_key=idempotency_key or uuid4().hex,
        )
        if session.status == SingingSession.Status.CREATED:
            session.status = SingingSession.Status.AWAITING_UPLOAD
            session.save(update_fields=["status", "updated_at"])
        return UploadGrantResult(session=session, asset=asset, grant=grant, created=True)


def confirm_session_media(*, session_id: UUID, patient_id: UUID, asset_id: UUID | None = None, object_key: str = ""):
    if not asset_id and not object_key:
        raise ValidationError({"media": "asset_id 或 object_key 至少提供一个"})
    with transaction.atomic():
        try:
            session = SingingSession.objects.select_for_update().get(pk=session_id, patient_id=patient_id)
        except SingingSession.DoesNotExist as exc:
            raise NotFound() from exc
        if session.status not in {SingingSession.Status.AWAITING_UPLOAD, SingingSession.Status.UPLOADED}:
            raise SingingStateConflict()
        bindings = SessionMedia.objects.select_for_update().select_related("asset").filter(session=session)
        if asset_id:
            bindings = bindings.filter(asset_id=asset_id)
        if object_key:
            bindings = bindings.filter(asset__object_key=object_key)
        binding = bindings.first()
        if binding is None:
            raise SingingMediaConflict()
        asset = binding.asset
        if (
            asset.owner_type != "patient" or asset.patient_owner_id != patient_id
            or asset.owner_id != patient_id or asset.media_type != binding.media_type
            or asset.deleted_at is not None
        ):
            raise SingingMediaConflict()
        if asset.backend == "local" and asset.status != MediaAsset.Status.READY:
            asset = complete_local_asset(asset=asset)
        if asset.status != MediaAsset.Status.READY:
            raise SingingMediaConflict("媒体尚未收到可信上传完成回执")
        if binding.confirmed_at is None:
            binding.confirmed_at = timezone.now()
            binding.save(update_fields=["confirmed_at"])
        bindings = list(SessionMedia.objects.select_related("asset").filter(session=session))
        audio_ready = any(item.media_type == "singing_audio" and item.confirmed_at and item.asset.status == MediaAsset.Status.READY for item in bindings)
        all_ready = all(item.confirmed_at and item.asset.status == MediaAsset.Status.READY for item in bindings)
        if audio_ready and all_ready and session.status != SingingSession.Status.UPLOADED:
            session.status = SingingSession.Status.UPLOADED
            session.save(update_fields=["status", "updated_at"])
        return session, binding


def _asset_snapshot(asset: MediaAsset) -> dict[str, object]:
    return {
        "asset_id": str(asset.id), "object_key": asset.object_key, "media_type": asset.media_type,
        "mime": asset.mime, "size": asset.size, "backend": asset.backend,
        "generation": asset.manifest_generation,
        "blob": asset.sha256 if asset.backend == "local" else asset.etag,
        "receipt_fingerprint": source_receipt_fingerprint(asset),
    }


def _task_snapshot(*, session: SingingSession, asset: MediaAsset, generation: int) -> dict[str, object]:
    return {
        "session_id": str(session.id),
        "duration_seconds": session.song_snapshot["duration_seconds"],
        "protocol_version": "1.0",
        "generation": generation,
        "media": _asset_snapshot(asset),
    }


def _session_tasks(session_id, *, generation=None):
    queryset = AnalysisTask.objects.filter(
        target_type=AnalysisTask.TargetType.SINGING_SESSION,
        target_id=session_id,
    )
    if generation is not None:
        queryset = queryset.filter(generation=generation)
    return queryset.order_by("task_type")


def _task_idempotency_key(session_id: UUID, generation: int, task_type: str) -> str:
    if generation == 0:
        return f"singing:{session_id}:{task_type}"
    return f"singing:{session_id}:{generation}:{task_type}"


def _locked_ready_bindings(session: SingingSession) -> dict[str, SessionMedia]:
    bindings = list(
        SessionMedia.objects.select_for_update().select_related("asset").filter(session=session)
    )
    by_type = {binding.media_type: binding for binding in bindings}
    audio = by_type.get("singing_audio")
    if not audio or not audio.confirmed_at or audio.asset.status != MediaAsset.Status.READY:
        raise SingingMediaConflict("演唱音频尚未通过可信回执确认")
    if any(not item.confirmed_at or item.asset.status != MediaAsset.Status.READY for item in bindings):
        raise SingingMediaConflict("存在尚未确认完成的演唱媒体")
    return by_type


def _create_generation_tasks_locked(*, session: SingingSession, generation: int) -> list[AnalysisTask]:
    by_type = _locked_ready_bindings(session)
    task_specs = [(AnalysisTask.TaskType.SINGING_AUDIO_METRICS, by_type["singing_audio"].asset)]
    video = by_type.get("singing_video")
    if video:
        task_specs.append((AnalysisTask.TaskType.FACE_LANDMARKS, video.asset))
    tasks = []
    for task_type, asset in task_specs:
        defaults = {
            "target_type": AnalysisTask.TargetType.SINGING_SESSION,
            "target_id": session.id,
            "song": None,
            "source_asset": asset,
            "task_type": task_type,
            "protocol_version": "1.0",
            "executor": "mock_singing",
            "generation": generation,
            "input_snapshot": _task_snapshot(
                session=session,
                asset=asset,
                generation=generation,
            ),
        }
        task, created = AnalysisTask.objects.get_or_create(
            idempotency_key=_task_idempotency_key(session.id, generation, task_type),
            defaults=defaults,
        )
        if not created and (
            task.target_type != defaults["target_type"] or task.target_id != defaults["target_id"]
            or task.song_id is not None or task.source_asset_id != asset.id
            or task.task_type != task_type or task.protocol_version != "1.0"
            or task.executor != "mock_singing" or task.generation != generation
            or task.input_snapshot != defaults["input_snapshot"]
        ):
            raise SingingSubmissionConflict("幂等分析任务与当前会话媒体不一致")
        tasks.append(task)
    return tasks


def submit_session(*, session_id: UUID, patient_id: UUID, idempotency_key: str) -> SubmissionResult:
    if not idempotency_key or len(idempotency_key) > 128:
        raise ValidationError({"idempotency_key": "必须提供长度不超过 128 的幂等键"})
    with transaction.atomic():
        try:
            session = SingingSession.objects.select_for_update().get(pk=session_id, patient_id=patient_id)
        except SingingSession.DoesNotExist as exc:
            raise NotFound() from exc
        if session.submission_idempotency_key:
            if session.submission_idempotency_key != idempotency_key:
                raise SingingSubmissionConflict()
            tasks = tuple(_session_tasks(session.id, generation=0).values_list("id", flat=True))
            return SubmissionResult(session=session, task_ids=tasks, created=False)
        if session.status != SingingSession.Status.UPLOADED:
            raise SingingStateConflict()
        if session.analysis_generation != 0:
            raise SingingSubmissionConflict("首次提交的分析代际无效")
        tasks = _create_generation_tasks_locked(session=session, generation=0)
        session.submission_idempotency_key = idempotency_key
        session.status = SingingSession.Status.PROCESSING
        session.submitted_at = timezone.now()
        session.save(update_fields=["submission_idempotency_key", "status", "submitted_at", "updated_at"])
        for task in tasks:
            transaction.on_commit(lambda task_id=task.id: schedule_singing_analysis_task(task_id))
        task_ids = tuple(_session_tasks(session.id, generation=0).values_list("id", flat=True))
        return SubmissionResult(session=session, task_ids=task_ids, created=True)


def cancel_session(*, session_id: UUID, patient_id: UUID) -> SingingSession:
    with transaction.atomic():
        try:
            session = SingingSession.objects.select_for_update().get(pk=session_id, patient_id=patient_id)
        except SingingSession.DoesNotExist as exc:
            raise NotFound() from exc
        if session.status == SingingSession.Status.CANCELLED:
            return session
        if session.status not in {SingingSession.Status.CREATED, SingingSession.Status.AWAITING_UPLOAD, SingingSession.Status.UPLOADED}:
            raise SingingStateConflict()
        session.status = SingingSession.Status.CANCELLED
        session.save(update_fields=["status", "updated_at"])
        return session


def retry_session(*, session_id: UUID, patient_id: UUID, idempotency_key: str) -> SubmissionResult:
    from apps.analysis.services import MAX_ATTEMPTS
    if not idempotency_key or len(idempotency_key) > 128:
        raise ValidationError({"idempotency_key": "必须提供长度不超过 128 的幂等键"})
    with transaction.atomic():
        try:
            session = SingingSession.objects.select_for_update().get(pk=session_id, patient_id=patient_id)
        except SingingSession.DoesNotExist as exc:
            raise NotFound() from exc
        if session.retry_idempotency_key == idempotency_key:
            task_ids = tuple(_session_tasks(
                session.id,
                generation=session.analysis_generation,
            ).values_list("id", flat=True))
            return SubmissionResult(session=session, task_ids=task_ids, created=False)
        if session.retry_idempotency_key and session.status == SingingSession.Status.PROCESSING:
            raise SingingRetryConflict()
        if session.status != SingingSession.Status.FAILED:
            raise SingingStateConflict()
        tasks = list(AnalysisTask.objects.select_for_update().filter(
            target_type=AnalysisTask.TargetType.SINGING_SESSION, target_id=session.id,
            generation=session.analysis_generation,
        ).order_by("task_type"))
        failed_tasks = [task for task in tasks if task.status == AnalysisTask.Status.FAILED]
        if not failed_tasks:
            raise SingingStateConflict("演唱会话没有可重试的失败任务")
        if any(task.attempt >= MAX_ATTEMPTS for task in failed_tasks):
            raise SingingRetryExhausted()
        next_generation = session.analysis_generation + 1
        new_tasks = _create_generation_tasks_locked(
            session=session,
            generation=next_generation,
        )
        session.retry_idempotency_key = idempotency_key
        session.retry_generation += 1
        session.analysis_generation = next_generation
        session.status = SingingSession.Status.PROCESSING
        session.score = None
        session.burp_count = None
        session.duration_seconds = None
        session.is_mock = False
        session.completed_at = None
        session.save(update_fields=[
            "retry_idempotency_key", "retry_generation", "analysis_generation", "status",
            "score", "burp_count", "duration_seconds", "is_mock", "completed_at", "updated_at",
        ])
        for task in new_tasks:
            transaction.on_commit(lambda task_id=task.id: schedule_singing_analysis_task(task_id))
        return SubmissionResult(
            session=session,
            task_ids=tuple(task.id for task in sorted(new_tasks, key=lambda value: value.task_type)),
            created=True,
        )


def _clear_task_claim(task: AnalysisTask):
    task.claim_token = None
    task.lease_expires_at = None
    task.heartbeat_at = None


def _task_asset_is_valid(*, session: SingingSession, task: AnalysisTask) -> bool:
    expected_types = {
        AnalysisTask.TaskType.SINGING_AUDIO_METRICS: "singing_audio",
        AnalysisTask.TaskType.FACE_LANDMARKS: "singing_video",
    }
    expected_media_type = expected_types.get(task.task_type)
    if expected_media_type is None:
        return False
    binding = SessionMedia.objects.select_for_update().select_related("asset").filter(
        session=session, asset_id=task.source_asset_id, media_type=expected_media_type,
        confirmed_at__isnull=False,
    ).first()
    if binding is None:
        return False
    asset = binding.asset
    return bool(
        task.generation == session.analysis_generation
        and task.protocol_version == "1.0" and task.executor == "mock_singing"
        and asset.status == MediaAsset.Status.READY and asset.deleted_at is None
        and asset.owner_type == "patient" and asset.owner_id == session.patient_id
        and asset.patient_owner_id == session.patient_id and asset.media_type == expected_media_type
        and task.input_snapshot == _task_snapshot(
            session=session,
            asset=asset,
            generation=session.analysis_generation,
        )
    )


def _lock_singing_task(task_id: UUID):
    target_id = AnalysisTask.objects.only("target_id").get(pk=task_id).target_id
    session = SingingSession.objects.select_for_update().get(pk=target_id)
    task = AnalysisTask.objects.select_for_update().get(
        pk=task_id, target_type=AnalysisTask.TargetType.SINGING_SESSION, target_id=session.id,
    )
    return session, task


def _fail_singing_generation_locked(task: AnalysisTask, session: SingingSession, *, code: str, summary: str):
    if task.generation != session.analysis_generation:
        return task
    now = timezone.now()
    generation_tasks = AnalysisTask.objects.select_for_update().filter(
        target_type=AnalysisTask.TargetType.SINGING_SESSION,
        target_id=session.id,
        generation=session.analysis_generation,
    )
    task_ids = list(generation_tasks.values_list("id", flat=True))
    AnalysisResult.objects.filter(task_id__in=task_ids).delete()
    AnalysisTimeSeries.objects.filter(task_id__in=task_ids).delete()
    generation_tasks.update(
        status=AnalysisTask.Status.FAILED,
        claim_token=None,
        lease_expires_at=None,
        heartbeat_at=None,
        next_dispatch_at=None,
        error_code=code,
        error_summary=summary[:256],
        completed_at=now,
        updated_at=now,
    )
    session.status = SingingSession.Status.FAILED
    session.score = None
    session.burp_count = None
    session.duration_seconds = None
    session.is_mock = False
    session.completed_at = None
    session.save(update_fields=[
        "status", "score", "burp_count", "duration_seconds", "is_mock", "completed_at", "updated_at",
    ])
    task.refresh_from_db()
    return task


def fail_exhausted_singing_task(task_id: UUID) -> bool:
    from apps.analysis.services import MAX_ATTEMPTS, _is_recoverable_task
    with transaction.atomic():
        session, task = _lock_singing_task(task_id)
        if task.attempt < MAX_ATTEMPTS or not _is_recoverable_task(task, now=timezone.now()):
            return False
        _fail_singing_generation_locked(
            task, session, code="analysis_retry_exhausted",
            summary="分析服务暂时不可用，已超过重试次数",
        )
        return True


def claim_singing_analysis_task(task_id: UUID, *, now=None):
    from apps.analysis.services import AnalysisClaim, MAX_ATTEMPTS, analysis_lease_seconds
    from apps.analysis.executors import resolve_executor
    from apps.analysis.contracts import AnalysisProtocolError
    now = now or timezone.now()
    lease_seconds = analysis_lease_seconds()
    with transaction.atomic():
        session, task = _lock_singing_task(task_id)
        if task.status in {AnalysisTask.Status.SUCCEEDED, AnalysisTask.Status.FAILED, AnalysisTask.Status.SUPERSEDED}:
            return None
        if task.generation != session.analysis_generation:
            return None
        if task.status == AnalysisTask.Status.PROCESSING and task.lease_expires_at and task.lease_expires_at > now:
            return None
        if task.attempt >= MAX_ATTEMPTS:
            _fail_singing_generation_locked(task, session, code="analysis_retry_exhausted", summary="分析服务暂时不可用，已超过重试次数")
            return None
        if session.status != SingingSession.Status.PROCESSING or not _task_asset_is_valid(session=session, task=task):
            _fail_singing_generation_locked(task, session, code="analysis_source_unavailable", summary="演唱媒体已不可用")
            return None
        try:
            resolve_executor(task.task_type, task.executor, task.protocol_version)
        except AnalysisProtocolError:
            _fail_singing_generation_locked(task, session, code="analysis_protocol_unsupported", summary="分析任务协议不受支持")
            return None
        token = uuid4()
        task.status = AnalysisTask.Status.PROCESSING
        task.attempt += 1
        task.claim_token = token
        task.heartbeat_at = now
        task.lease_expires_at = now + timedelta(seconds=lease_seconds)
        task.started_at = now
        task.completed_at = None
        task.next_dispatch_at = None
        task.error_code = ""
        task.error_summary = ""
        task.save()
        return AnalysisClaim(task.id, token)


def _validate_singing_claim(claim):
    from apps.analysis.contracts import PermanentAnalysisError
    with transaction.atomic():
        session, task = _lock_singing_task(claim.task_id)
        valid = bool(
            task.status == AnalysisTask.Status.PROCESSING and task.claim_token == claim.claim_token
            and task.lease_expires_at and task.lease_expires_at > timezone.now()
            and session.status == SingingSession.Status.PROCESSING
            and _task_asset_is_valid(session=session, task=task)
        )
        if not valid:
            raise PermanentAnalysisError("演唱分析任务租约或媒体快照已失效")
        return task


def finalize_singing_success(task_id: UUID, claim_token: UUID, payload):
    from apps.analysis.executors import resolve_executor
    from apps.analysis.contracts import AnalysisProtocolError
    with transaction.atomic():
        session, task = _lock_singing_task(task_id)
        valid = bool(
            task.status == AnalysisTask.Status.PROCESSING and task.claim_token == claim_token
            and task.lease_expires_at and task.lease_expires_at > timezone.now()
        )
        if not valid:
            return task
        if not _task_asset_is_valid(session=session, task=task):
            return _fail_singing_generation_locked(task, session, code="analysis_source_unavailable", summary="演唱媒体已不可用")
        try:
            parsed = resolve_executor(task.task_type, task.executor, task.protocol_version).result_parser(payload)
        except AnalysisProtocolError:
            return _fail_singing_generation_locked(task, session, code="analysis_result_invalid", summary="分析结果协议无效")
        stored_payload = parsed.as_dict()
        AnalysisResult.objects.update_or_create(
            task=task,
            defaults={
                "protocol_version": parsed.protocol_version,
                "is_mock": parsed.is_mock,
                "generation": task.generation,
                "payload": stored_payload,
            },
        )
        AnalysisTimeSeries.objects.filter(task=task).delete()
        if task.task_type == AnalysisTask.TaskType.SINGING_AUDIO_METRICS:
            AnalysisTimeSeries.objects.bulk_create([
                AnalysisTimeSeries(
                    session=session, task=task, generation=task.generation, metric_type=metric,
                    sample_interval_ms=parsed.sample_interval_ms, values=list(values),
                )
                for metric, values in parsed.series.items()
            ])
        task.status = AnalysisTask.Status.SUCCEEDED
        _clear_task_claim(task)
        task.next_dispatch_at = None
        task.error_code = ""
        task.error_summary = ""
        task.completed_at = timezone.now()
        task.save()
        unfinished = AnalysisTask.objects.filter(
            target_type=AnalysisTask.TargetType.SINGING_SESSION, target_id=session.id,
            generation=session.analysis_generation,
        ).exclude(status=AnalysisTask.Status.SUCCEEDED).exists()
        if not unfinished:
            audio_result = AnalysisResult.objects.get(
                task__target_type=AnalysisTask.TargetType.SINGING_SESSION,
                task__target_id=session.id,
                task__task_type=AnalysisTask.TaskType.SINGING_AUDIO_METRICS,
                task__generation=session.analysis_generation,
                generation=session.analysis_generation,
            ).payload
            session.status = SingingSession.Status.COMPLETED
            session.score = audio_result["score"]
            session.burp_count = len(audio_result["burp_events"])
            session.duration_seconds = int(session.song_snapshot["duration_seconds"])
            session.is_mock = True
            session.completed_at = timezone.now()
            session.save(update_fields=[
                "status", "score", "burp_count", "duration_seconds", "is_mock", "completed_at", "updated_at",
            ])
        return task


def finalize_singing_failure(task_id: UUID, claim_token: UUID, *, code: str, summary: str):
    with transaction.atomic():
        session, task = _lock_singing_task(task_id)
        if (
            task.status != AnalysisTask.Status.PROCESSING or task.claim_token != claim_token
            or not task.lease_expires_at or task.lease_expires_at <= timezone.now()
            or task.generation != session.analysis_generation
        ):
            return task
        return _fail_singing_generation_locked(task, session, code=code, summary=summary)


def handle_singing_transient(task_id: UUID, claim_token: UUID):
    from apps.analysis.services import MAX_ATTEMPTS, DISPATCH_LEASE_SECONDS, TransientOutcome
    with transaction.atomic():
        session, task = _lock_singing_task(task_id)
        if (
            task.status != AnalysisTask.Status.PROCESSING or task.claim_token != claim_token
            or not task.lease_expires_at or task.lease_expires_at <= timezone.now()
            or task.generation != session.analysis_generation
        ):
            return TransientOutcome(task=task, should_retry=False)
        AnalysisResult.objects.filter(task=task).delete()
        AnalysisTimeSeries.objects.filter(task=task).delete()
        if task.attempt >= MAX_ATTEMPTS:
            _fail_singing_generation_locked(task, session, code="analysis_retry_exhausted", summary="分析服务暂时不可用，已超过重试次数")
            return TransientOutcome(task=task, should_retry=False)
        task.status = AnalysisTask.Status.RETRYING
        _clear_task_claim(task)
        task.next_dispatch_at = timezone.now() + timedelta(seconds=DISPATCH_LEASE_SECONDS)
        task.error_code = "analysis_transient_error"
        task.error_summary = "分析服务暂时不可用，稍后将自动重试"
        task.completed_at = None
        task.save()
        return TransientOutcome(task=task, should_retry=True)


def run_singing_analysis(task_id: UUID):
    from apps.analysis.contracts import AnalysisProtocolError, PermanentAnalysisError, TransientAnalysisError
    from apps.analysis.executors import resolve_executor
    from apps.analysis.services import AnalysisRetryRequested, ExecutionContext, LeaseGuard
    claim = claim_singing_analysis_task(task_id)
    if claim is None:
        return AnalysisTask.objects.get(pk=task_id)
    context = ExecutionContext(claim.task_id, claim.claim_token)
    try:
        with LeaseGuard(context):
            task = _validate_singing_claim(claim)
            payload = resolve_executor(task.task_type, task.executor, task.protocol_version).factory().execute(task, context=context)
        if context.cancelled:
            return AnalysisTask.objects.get(pk=task_id)
        return finalize_singing_success(task.id, claim.claim_token, payload)
    except TransientAnalysisError as exc:
        outcome = handle_singing_transient(claim.task_id, claim.claim_token)
        raise AnalysisRetryRequested(should_retry=outcome.should_retry) from exc
    except AnalysisProtocolError:
        return finalize_singing_failure(claim.task_id, claim.claim_token, code="analysis_result_invalid", summary="分析结果协议无效")
    except PermanentAnalysisError:
        return finalize_singing_failure(claim.task_id, claim.claim_token, code="analysis_execution_failed", summary="分析执行失败")
    except Exception:
        return finalize_singing_failure(claim.task_id, claim.claim_token, code="analysis_execution_failed", summary="分析执行失败")


def _release_singing_dispatch(task_id: UUID, expected_next_dispatch_at) -> bool:
    from apps.analysis.services import _is_recoverable_task
    with transaction.atomic():
        _session, task = _lock_singing_task(task_id)
        if (
            task.generation != _session.analysis_generation
            or task.next_dispatch_at != expected_next_dispatch_at
            or not _is_recoverable_task(task, now=timezone.now())
        ):
            return False
        task.next_dispatch_at = None
        task.save(update_fields=["next_dispatch_at", "updated_at"])
        return True


def schedule_singing_analysis_task(task_id: UUID) -> bool:
    from apps.analysis.services import DISPATCH_LEASE_SECONDS, MAX_ATTEMPTS, _has_active_claim, _is_recoverable_task
    now = timezone.now()
    with transaction.atomic():
        session, task = _lock_singing_task(task_id)
        if task.status in {AnalysisTask.Status.SUCCEEDED, AnalysisTask.Status.FAILED, AnalysisTask.Status.SUPERSEDED}:
            return False
        if task.generation != session.analysis_generation or session.status != SingingSession.Status.PROCESSING:
            return False
        if _has_active_claim(task, now=now) or not _is_recoverable_task(task, now=now):
            return False
        if task.attempt >= MAX_ATTEMPTS:
            _fail_singing_generation_locked(task, session, code="analysis_retry_exhausted", summary="分析服务暂时不可用，已超过重试次数")
            return False
        if task.next_dispatch_at and task.next_dispatch_at > now:
            return False
        task.next_dispatch_at = now + timedelta(seconds=DISPATCH_LEASE_SECONDS)
        task.save(update_fields=["next_dispatch_at", "updated_at"])
        expected = task.next_dispatch_at
    try:
        from apps.analysis.tasks import run_analysis_task
        run_analysis_task.delay(str(task_id))
    except Exception as exc:
        logger.error("singing_analysis_dispatch_failed task_id=%s exception=%s", task_id, exc.__class__.__name__)
        try:
            _release_singing_dispatch(task_id, expected)
        except Exception:
            logger.error("singing_analysis_dispatch_release_failed task_id=%s", task_id)
        return False
    return True
