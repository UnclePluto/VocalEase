from __future__ import annotations

import base64
from datetime import timedelta
import hashlib
from io import BytesIO
import os
from uuid import NAMESPACE_URL, uuid5
import wave

from django.conf import settings
from django.core.management.base import BaseCommand
from django.db import transaction
from django.utils import timezone

from apps.accounts.models import Role, User
from apps.analysis.models import AnalysisResult, AnalysisTask
from apps.analysis.services import run_analysis
from apps.doctors.models import DoctorProfile
from apps.media.backends.local import LocalStorageBackend
from apps.media.models import MediaAsset
from apps.media.services import (
    claim_local_upload,
    complete_local_asset,
    create_upload_grant,
    publish_local_upload,
    storage_backend_for,
)
from apps.patients.models import PatientProfile, TreatmentPlan
from apps.singing.models import SessionMedia, SingingSession
from apps.singing.services import (
    _create_generation_tasks_locked,
    _task_snapshot,
    submit_session,
)
from apps.songs.models import Song
from apps.songs.services import validate_source_asset


DEMO_PASSWORD = "888888"
DEMO_NAMESPACE = "https://vocaease.local/demo/"
DEMO_MP4_BASE64 = (
    b"AAAAHGZ0eXBpc29tAAACAGlzb21pc28ybXA0MQAAA0Ftb292AAAAbG12aGQAAAAAAAAAAAAAAAAAAAPoAAAD6AABAAAB"
    b"AAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAC"
    b"AAACa3RyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAAAAAD6AAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAE"
    b"AAAAAAAAAAAAAAAAAAEAAAAAAEAAAABAAAAAAACRlZHRzAAAAHGVsc3QAAAAAAAAAAQAAA+gAAAAAAAEAAAAAAeNtZGlhAAAA"
    b"IG1kaGQAAAAAAAAAAAAAAAAAAEAAAABAAFXEAAAAAAAtaGRscgAAAAAAAAAAdmlkZQAAAAAAAAAAAAAAAFZpZGVvSGFuZGxlcg"
    b"AAAAGObWluZgAAABR2bWhkAAAAAQAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAABTnN0YmwA"
    b"AADqc3RzZAAAAAAAAAABAAAA2m1wNHYAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAEAAQAEgAAABIAAAAAAAAAAETTGF2YzYyLj"
    b"I4LjEwMSBtcGVnNAAAAAAAAAAAAAAAAAAY//8AAABgZXNkcwAAAAADgICATwABAASAgIBBIBEAAAAAAw1AAAAAiAWAgIAvAAAB"
    b"sAEAAAG1iRMAAAEAAAABIADEjYgADQCEAhRjAAABskxhdmM2Mi4yOC4xMDEGgICAAQIAAAAQcGFzcAAAAAEAAAABAAAAFGJ0"
    b"cnQAAAAAAAMNQAAAAIgAAAAYc3R0cwAAAAAAAAABAAAAAQAAQAAAAAAcc3RzYwAAAAAAAAABAAAAAQAAAAEAAAABAAAAFHN0c3"
    b"oAAAAAAAAAEQAAAAEAAAAUc3RjbwAAAAAAAAABAAADbQAAAGJ1ZHRhAAAAWm1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJh"
    b"cHBsAAAAAAAAAAAAAAAALWlsc3QAAAAlqXRvbwAAAB1kYXRhAAAAAQAAAABMYXZmNjIuMTIuMTAxAAAACGZyZWUAAAAZbWRhd"
    b"AAAAbMAEAcAAAG2FgUYI9t+"
)


def _demo_wav(label: str, duration_ms: int = 90_000) -> bytes:
    """生成可被浏览器解码的最小单声道 PCM WAV 演示音频。"""
    sample_rate = 8000
    frame_count = sample_rate * duration_ms // 1000
    seed = sum(label.encode("utf-8")) % 32
    frames = bytes(112 + ((index + seed) % 32) for index in range(frame_count))
    output = BytesIO()
    with wave.open(output, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(1)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(frames)
    return output.getvalue()


def _demo_mp4() -> bytes:
    """返回可解码的 16×16 单帧 MPEG-4 演示录像。"""
    return base64.b64decode(DEMO_MP4_BASE64, validate=True)


def _legacy_demo_mp4(label: str) -> bytes:
    """返回 fix base 曾发布的已知无效 MP4 字节，仅用于精确升级识别。"""
    ftyp = b"\x00\x00\x00\x18ftypisom\x00\x00\x00\x00isomiso2"
    payload = label.encode("utf-8")
    return ftyp + (len(payload) + 8).to_bytes(4, "big") + b"free" + payload


class Command(BaseCommand):
    help = "创建或更新 VocaEase 本地演示数据"

    @transaction.atomic
    def handle(self, *args, **options):
        settings_module = os.environ.get("DJANGO_SETTINGS_MODULE", "")
        if (
            settings_module
            not in {
                "vocaease.settings.local",
                "vocaease.settings.test",
                "vocaease.settings.postgresql_test",
            }
            or settings.MEDIA_ENVIRONMENT not in {"local", "test"}
        ):
            raise ValueError("seed_demo 仅允许在 local/test 环境运行")
        backend = storage_backend_for("local")
        if not isinstance(backend, LocalStorageBackend):
            raise ValueError("本地演示数据必须使用 LocalStorageBackend")

        admin = self._upsert_user("demo-admin", Role.SYSTEM_ADMIN)
        doctor_user = self._upsert_user("DDEMO001", Role.DOCTOR)
        doctor = self._upsert_doctor(doctor_user)
        patients = (
            self._upsert_patient(
                login_id="PDEMO001",
                name="演示患者甲",
                gender="female",
                age=35,
                phone="13500008001",
                doctor=doctor,
            ),
            self._upsert_patient(
                login_id="PDEMO002",
                name="演示患者乙",
                gender="male",
                age=42,
                phone="13500008002",
                doctor=doctor,
            ),
        )
        songs = (
            self._upsert_song(
                key="song-1",
                title="VocaEase 演示歌曲一",
                artist="演示歌手甲",
                backend=backend,
            ),
            self._upsert_song(
                key="song-2",
                title="VocaEase 演示歌曲二",
                artist="演示歌手乙",
                backend=backend,
            ),
        )
        for index in range(6):
            self._upsert_completed_session(
                key=f"session-{index + 1}",
                patient=patients[0] if index < 4 else patients[1],
                song=songs[index % 2],
                backend=backend,
                days_ago=5 - index,
            )

        self.stdout.write(
            self.style.SUCCESS(
                f"演示数据就绪：admin={admin.login_id}，医生=1，患者=2，歌曲=2，演唱=6"
            )
        )

    def _upsert_user(self, login_id: str, role: str) -> User:
        user = User.objects.filter(login_id=login_id).first()
        if user is not None and user.role != role:
            raise ValueError(f"演示账号 {login_id} 已被其他角色占用")
        if user is None:
            return User.objects.create_user(
                login_id=login_id,
                password=DEMO_PASSWORD,
                role=role,
                is_active=True,
                must_change_password=True,
            )
        was_deleted = user.deleted_at is not None
        user.is_active = True
        user.deleted_at = None
        if was_deleted:
            user.set_password(DEMO_PASSWORD)
            user.must_change_password = True
        user.save()
        return user

    def _upsert_doctor(self, user: User) -> DoctorProfile:
        conflict = DoctorProfile.objects.filter(employee_no="DDEMO001").exclude(user=user).first()
        if conflict is not None:
            raise ValueError("演示工号 DDEMO001 已被其他账号占用")
        doctor, _created = DoctorProfile.objects.update_or_create(
            user=user,
            defaults={
                "employee_no": "DDEMO001",
                "name": "演示医生",
                "gender": "female",
                "phone": "13600008001",
                "department": "消化内科",
                "title": "主治医师",
                "deleted_at": None,
            },
        )
        return doctor

    def _upsert_patient(
        self,
        *,
        login_id: str,
        name: str,
        gender: str,
        age: int,
        phone: str,
        doctor: DoctorProfile,
    ) -> PatientProfile:
        user = self._upsert_user(login_id, Role.PATIENT)
        conflict = PatientProfile.objects.filter(medical_record_no=login_id).exclude(user=user).first()
        if conflict is not None:
            raise ValueError(f"演示病历号 {login_id} 已被其他账号占用")
        patient, _created = PatientProfile.objects.update_or_create(
            user=user,
            defaults={
                "medical_record_no": login_id,
                "name": name,
                "gender": gender,
                "enrollment_age": age,
                "phone": phone,
                "primary_doctor": doctor,
                "notes": "演示病情备注，仅用于本地流程验证",
                "deleted_at": None,
            },
        )
        plan = TreatmentPlan.objects.filter(patient=patient).order_by("created_at").first()
        if plan is None:
            TreatmentPlan.objects.create(
                patient=patient,
                start_date="2026-08-01",
                cycle_weeks=8,
                target_session_count=24,
                status=TreatmentPlan.Status.ACTIVE,
            )
        else:
            TreatmentPlan.objects.filter(patient=patient).exclude(pk=plan.pk).update(
                status=TreatmentPlan.Status.COMPLETED
            )
            plan.start_date = "2026-08-01"
            plan.cycle_weeks = 8
            plan.target_session_count = 24
            plan.status = TreatmentPlan.Status.ACTIVE
            plan.deleted_at = None
            plan.save()
        return patient

    def _publish_asset(
        self,
        *,
        owner_type: str,
        owner_id,
        media_type: str,
        mime: str,
        content: bytes,
        backend: LocalStorageBackend,
    ) -> MediaAsset:
        asset = None
        try:
            asset, grant = create_upload_grant(
                owner_type=owner_type,
                owner_id=owner_id,
                media_type=media_type,
                mime=mime,
                size=len(content),
                backend=backend,
            )
            nonce = claim_local_upload(asset=asset)
            prepared = backend.prepare_authorized_stream(
                object_key=asset.object_key,
                token=grant.upload_token,
                stream=BytesIO(content),
                mime=mime,
                asset_id=asset.id,
            )
            publish_local_upload(
                asset_id=asset.id,
                nonce=nonce,
                prepared=prepared,
                backend=backend,
            )
            return complete_local_asset(asset=asset)
        except Exception:
            if asset is not None:
                backend.purge_for_qa(asset.object_key, asset_id=asset.id)
            raise

    def _upsert_song(
        self,
        *,
        key: str,
        title: str,
        artist: str,
        backend: LocalStorageBackend,
    ) -> Song:
        song_id = uuid5(NAMESPACE_URL, f"{DEMO_NAMESPACE}{key}")
        song = Song.objects.filter(pk=song_id).first()
        if song is None:
            source = self._publish_asset(
                owner_type=MediaAsset.OwnerType.SONG,
                owner_id=song_id,
                media_type="song_source",
                mime="audio/wav",
                content=_demo_wav(f"demo-song-{key}"),
                backend=backend,
            )
            song = Song.objects.create(
                id=song_id,
                title=title,
                artist=artist,
                genre="流行",
                language="中文",
                duration_seconds=90,
                source_asset=source,
                analysis_status=Song.AnalysisStatus.SUCCEEDED,
                publication_status=Song.PublicationStatus.PUBLISHED,
            )
        else:
            song.title = title
            song.artist = artist
            song.genre = "流行"
            song.language = "中文"
            song.duration_seconds = 90
            song.analysis_status = Song.AnalysisStatus.SUCCEEDED
            song.publication_status = Song.PublicationStatus.PUBLISHED
            song.deleted_at = None
            song.save()
        if song.source_asset_id is None:
            raise ValueError(f"演示歌曲 {title} 缺少源媒体，拒绝覆盖")
        validate_source_asset(song=song, asset=song.source_asset)
        song.analysis_status = Song.AnalysisStatus.SUCCEEDED
        song.publication_status = Song.PublicationStatus.PUBLISHED
        song.save(update_fields=["analysis_status", "publication_status", "updated_at"])
        return song

    def _upsert_completed_session(
        self,
        *,
        key: str,
        patient: PatientProfile,
        song: Song,
        backend: LocalStorageBackend,
        days_ago: int,
    ) -> SingingSession:
        session_id = uuid5(NAMESPACE_URL, f"{DEMO_NAMESPACE}{key}")
        session = SingingSession.objects.select_for_update().filter(pk=session_id).first()
        if session is None:
            plan = patient.treatment_plans.get(
                status=TreatmentPlan.Status.ACTIVE,
                deleted_at__isnull=True,
            )
            session = SingingSession.objects.create(
                id=session_id,
                patient=patient,
                song=song,
                treatment_plan=plan,
                patient_snapshot={
                    "id": str(patient.id),
                    "medical_record_no": patient.medical_record_no,
                    "name": patient.name,
                },
                song_snapshot={
                    "id": str(song.id),
                    "title": song.title,
                    "artist": song.artist,
                    "duration_seconds": song.duration_seconds,
                },
                treatment_plan_snapshot={
                    "id": str(plan.id),
                    "start_date": plan.start_date.isoformat(),
                    "cycle_weeks": plan.cycle_weeks,
                    "target_session_count": plan.target_session_count,
                },
                created_source="seed_demo",
                status=SingingSession.Status.AWAITING_UPLOAD,
            )
            audio = self._publish_asset(
                owner_type=MediaAsset.OwnerType.PATIENT,
                owner_id=patient.id,
                media_type="singing_audio",
                mime="audio/wav",
                content=_demo_wav(f"demo-audio-{key}"),
                backend=backend,
            )
            SessionMedia.objects.create(
                session=session,
                asset=audio,
                media_type="singing_audio",
                grant_idempotency_key=f"seed-demo:grant:{key}",
                confirmed_at=timezone.now(),
            )
            video = self._publish_asset(
                owner_type=MediaAsset.OwnerType.PATIENT,
                owner_id=patient.id,
                media_type="singing_video",
                mime="video/mp4",
                content=_demo_mp4(),
                backend=backend,
            )
            SessionMedia.objects.create(
                session=session,
                asset=video,
                media_type="singing_video",
                grant_idempotency_key=f"seed-demo:video-grant:{key}",
                confirmed_at=timezone.now(),
            )
            session.status = SingingSession.Status.UPLOADED
            session.save(update_fields=["status", "updated_at"])
            submitted = submit_session(
                session_id=session.id,
                patient_id=patient.id,
                idempotency_key=f"seed-demo:submit:{key}",
            )
            for task_id in submitted.task_ids:
                run_analysis(task_id)
            created_at = timezone.now() - timedelta(days=days_ago)
            SingingSession.objects.filter(pk=session.id).update(created_at=created_at)
            session.refresh_from_db()
        if (
            session.created_source != "seed_demo"
            or session.patient_id != patient.id
            or session.song_id != song.id
        ):
            raise ValueError(f"演示演唱 {key} 的固定标识已被其他数据占用")
        bindings = list(session.media_bindings.select_related("asset"))
        generation_tasks = AnalysisTask.objects.filter(
            target_type=AnalysisTask.TargetType.SINGING_SESSION,
            target_id=session.id,
            generation=session.analysis_generation,
        )
        binding_types = {binding.media_type for binding in bindings}
        task_types = set(generation_tasks.values_list("task_type", flat=True))
        result_types = set(
            AnalysisResult.objects.filter(task__in=generation_tasks).values_list(
                "task__task_type", flat=True,
            )
        )
        audio_binding = next(
            (binding for binding in bindings if binding.media_type == "singing_audio"),
            None,
        )
        video_binding = next(
            (binding for binding in bindings if binding.media_type == "singing_video"),
            None,
        )
        required_tasks = {
            AnalysisTask.TaskType.SINGING_AUDIO_METRICS,
            AnalysisTask.TaskType.FACE_LANDMARKS,
        }
        required_media = {"singing_audio", "singing_video"}
        legacy_video_content = _legacy_demo_mp4(f"demo-video-{key}")
        legacy_video_sha256 = hashlib.sha256(legacy_video_content).hexdigest()
        video_asset = video_binding.asset if video_binding else None
        object_key_parts = video_asset.object_key.split("/") if video_asset else []
        legacy_video_shape = bool(
            session.status == SingingSession.Status.COMPLETED
            and session.is_mock
            and binding_types == {"singing_audio", "singing_video"}
            and task_types == required_tasks
            and result_types == required_tasks
            and not generation_tasks.exclude(status=AnalysisTask.Status.SUCCEEDED).exists()
            and video_binding
            and video_binding.confirmed_at
            and video_binding.grant_idempotency_key == f"seed-demo:video-grant:{key}"
            and video_asset
            and video_asset.deleted_at is None
            and video_asset.patient_owner_id == patient.id
            and video_asset.owner_type == MediaAsset.OwnerType.PATIENT
            and video_asset.owner_id == patient.id
            and video_asset.media_type == "singing_video"
            and video_asset.backend == "local"
            and video_asset.mime == "video/mp4"
            and video_asset.size == len(legacy_video_content)
            and video_asset.sha256 == legacy_video_sha256
            and video_asset.status == MediaAsset.Status.READY
            and video_asset.metadata == {}
            and len(video_asset.manifest_generation) == 32
            and len(object_key_parts) == 6
            and object_key_parts[0] == settings.MEDIA_ENVIRONMENT
            and object_key_parts[1] == "singing_video"
            and all(part.isdigit() for part in object_key_parts[2:5])
            and len(object_key_parts[5]) == 32
            and all(character in "0123456789abcdef" for character in object_key_parts[5])
        )
        if legacy_video_shape:
            face_task = generation_tasks.get(
                task_type=AnalysisTask.TaskType.FACE_LANDMARKS,
            )
            legacy_video_shape = bool(
                face_task.source_asset_id == video_asset.id
                and face_task.input_snapshot == _task_snapshot(
                    session=session,
                    asset=video_asset,
                    generation=session.analysis_generation,
                )
            )
        if legacy_video_shape:
            private_url = backend.create_private_url(
                video_asset.object_key,
                ttl_seconds=60,
                asset_id=video_asset.id,
                expected_generation=video_asset.manifest_generation,
            )
            with backend.open_authorized_private(
                private_url.token,
                video_asset.object_key,
                asset_id=video_asset.id,
                expected_generation=video_asset.manifest_generation,
            ) as stream:
                legacy_video_shape = stream.read() == legacy_video_content
        if legacy_video_shape:
            replacement = None
            try:
                replacement = self._publish_asset(
                    owner_type=MediaAsset.OwnerType.PATIENT,
                    owner_id=patient.id,
                    media_type="singing_video",
                    mime="video/mp4",
                    content=_demo_mp4(),
                    backend=backend,
                )
                old_asset_id = video_asset.id
                old_object_key = video_asset.object_key
                video_binding.asset = replacement
                video_binding.save(update_fields=["asset"])
                face_task.source_asset = replacement
                face_task.input_snapshot = _task_snapshot(
                    session=session,
                    asset=replacement,
                    generation=session.analysis_generation,
                )
                face_task.status = AnalysisTask.Status.PENDING
                face_task.attempt = 0
                face_task.claim_token = None
                face_task.lease_expires_at = None
                face_task.heartbeat_at = None
                face_task.next_dispatch_at = None
                face_task.error_code = ""
                face_task.error_summary = ""
                face_task.started_at = None
                face_task.completed_at = None
                face_task.save()
                session.status = SingingSession.Status.PROCESSING
                session.completed_at = None
                session.save(update_fields=["status", "completed_at", "updated_at"])
                video_asset.delete()
                run_analysis(face_task.id)
                session.refresh_from_db()
                bindings = list(session.media_bindings.select_related("asset"))
                generation_tasks = AnalysisTask.objects.filter(
                    target_type=AnalysisTask.TargetType.SINGING_SESSION,
                    target_id=session.id,
                    generation=session.analysis_generation,
                )
                binding_types = {binding.media_type for binding in bindings}
                task_types = set(generation_tasks.values_list("task_type", flat=True))
                result_types = set(
                    AnalysisResult.objects.filter(task__in=generation_tasks).values_list(
                        "task__task_type", flat=True,
                    )
                )
                if (
                    session.status != SingingSession.Status.COMPLETED
                    or not session.is_mock
                    or binding_types != required_media
                    or any(
                        not binding.confirmed_at
                        or binding.asset.status != MediaAsset.Status.READY
                        for binding in bindings
                    )
                    or task_types != required_tasks
                    or result_types != required_tasks
                    or generation_tasks.exclude(status=AnalysisTask.Status.SUCCEEDED).exists()
                ):
                    raise ValueError(f"演示演唱 {key} 录像升级不完整")
            except Exception:
                if replacement is not None:
                    backend.purge_for_qa(
                        replacement.object_key,
                        asset_id=replacement.id,
                    )
                raise
            transaction.on_commit(
                lambda object_key=old_object_key, asset_id=old_asset_id: backend.purge_for_qa(
                    object_key,
                    asset_id=asset_id,
                )
            )
        legacy_audio_only = bool(
            session.status == SingingSession.Status.COMPLETED
            and session.is_mock
            and binding_types == {"singing_audio"}
            and audio_binding
            and audio_binding.confirmed_at
            and audio_binding.asset.status == MediaAsset.Status.READY
            and task_types == {AnalysisTask.TaskType.SINGING_AUDIO_METRICS}
            and result_types == {AnalysisTask.TaskType.SINGING_AUDIO_METRICS}
            and generation_tasks.get().status == AnalysisTask.Status.SUCCEEDED
        )
        if legacy_audio_only:
            video = self._publish_asset(
                owner_type=MediaAsset.OwnerType.PATIENT,
                owner_id=patient.id,
                media_type="singing_video",
                mime="video/mp4",
                content=_demo_mp4(),
                backend=backend,
            )
            SessionMedia.objects.create(
                session=session,
                asset=video,
                media_type="singing_video",
                grant_idempotency_key=f"seed-demo:video-grant:{key}",
                confirmed_at=timezone.now(),
            )
            session.status = SingingSession.Status.PROCESSING
            session.completed_at = None
            session.save(update_fields=["status", "completed_at", "updated_at"])
            tasks = _create_generation_tasks_locked(
                session=session,
                generation=session.analysis_generation,
            )
            face_task = next(
                task for task in tasks
                if task.task_type == AnalysisTask.TaskType.FACE_LANDMARKS
            )
            run_analysis(face_task.id)
            session.refresh_from_db()
            bindings = list(session.media_bindings.select_related("asset"))
            generation_tasks = AnalysisTask.objects.filter(
                target_type=AnalysisTask.TargetType.SINGING_SESSION,
                target_id=session.id,
                generation=session.analysis_generation,
            )
            binding_types = {binding.media_type for binding in bindings}
            task_types = set(generation_tasks.values_list("task_type", flat=True))
            result_types = set(
                AnalysisResult.objects.filter(task__in=generation_tasks).values_list(
                    "task__task_type", flat=True,
                )
            )
        if session.status != SingingSession.Status.COMPLETED or not session.is_mock:
            raise ValueError(f"演示演唱 {key} 状态不完整，拒绝静默覆盖")
        if (
            binding_types != required_media
            or any(
                not binding.confirmed_at or binding.asset.status != MediaAsset.Status.READY
                for binding in bindings
            )
            or task_types != required_tasks
            or result_types != required_tasks
            or generation_tasks.exclude(status=AnalysisTask.Status.SUCCEEDED).exists()
        ):
            raise ValueError(f"演示演唱 {key} 媒体或分析结果不完整，拒绝静默覆盖")
        return session
