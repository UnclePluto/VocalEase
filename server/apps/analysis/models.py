import uuid

from django.db import models
from django.db.models import Q
from django.core.exceptions import ObjectDoesNotExist


class AnalysisTask(models.Model):
    class TaskType(models.TextChoices):
        VOCAL_SEPARATION = "vocal_separation", "人声分离"
        ACCOMPANIMENT_GENERATION = "accompaniment_generation", "伴奏生成"
        LYRICS_RECOGNITION = "lyrics_recognition", "歌词识别"
        SINGING_AUDIO_METRICS = "singing_audio_metrics", "演唱音频指标"
        FACE_LANDMARKS = "face_landmarks", "面部关键点"

    class TargetType(models.TextChoices):
        SONG = "song", "歌曲"
        SINGING_SESSION = "singing_session", "演唱会话"

    class Status(models.TextChoices):
        PENDING = "pending", "待处理"
        PROCESSING = "processing", "处理中"
        SUCCEEDED = "succeeded", "成功"
        FAILED = "failed", "失败"
        RETRYING = "retrying", "重试中"
        SUPERSEDED = "superseded", "已被新源替代"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    target_type = models.CharField(max_length=24, choices=TargetType.choices, default=TargetType.SONG)
    target_id = models.UUIDField(null=True, blank=True, db_index=True)
    song = models.ForeignKey("songs.Song", on_delete=models.PROTECT, related_name="analysis_tasks", null=True, blank=True)
    source_asset = models.ForeignKey("media.MediaAsset", on_delete=models.PROTECT, related_name="analysis_tasks")
    task_type = models.CharField(max_length=32, choices=TaskType.choices)
    protocol_version = models.CharField(max_length=16, default="1.0")
    executor = models.CharField(max_length=64, default="mock_song")
    status = models.CharField(max_length=16, choices=Status.choices, default=Status.PENDING)
    attempt = models.PositiveSmallIntegerField(default=0)
    idempotency_key = models.CharField(max_length=128, unique=True)
    input_snapshot = models.JSONField(default=dict)
    claim_token = models.UUIDField(null=True, blank=True)
    lease_expires_at = models.DateTimeField(null=True, blank=True)
    heartbeat_at = models.DateTimeField(null=True, blank=True)
    next_dispatch_at = models.DateTimeField(null=True, blank=True)
    error_code = models.CharField(max_length=64, blank=True)
    error_summary = models.CharField(max_length=256, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    started_at = models.DateTimeField(null=True, blank=True)
    completed_at = models.DateTimeField(null=True, blank=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]
        constraints = [
            models.CheckConstraint(condition=Q(status__in=["pending", "processing", "succeeded", "failed", "retrying", "superseded"]), name="analysis_task_status_valid"),
            models.CheckConstraint(condition=Q(attempt__gte=0), name="analysis_task_attempt_nonnegative"),
            models.CheckConstraint(condition=Q(task_type__in=["vocal_separation", "accompaniment_generation", "lyrics_recognition", "singing_audio_metrics", "face_landmarks"]), name="analysis_task_type_valid"),
            models.CheckConstraint(condition=Q(executor__in=["mock_song", "mock_singing"]), name="analysis_executor_valid"),
            models.CheckConstraint(condition=Q(protocol_version="1.0"), name="analysis_protocol_valid"),
            models.CheckConstraint(
                condition=(
                    Q(target_type="song", song__isnull=False, target_id=models.F("song_id"), task_type__in=["vocal_separation", "accompaniment_generation", "lyrics_recognition"], executor="mock_song")
                    | Q(target_type="singing_session", song__isnull=True, target_id__isnull=False, task_type__in=["singing_audio_metrics", "face_landmarks"], executor="mock_singing")
                ),
                name="analysis_task_target_contract_valid",
            ),
            models.CheckConstraint(
                condition=(Q(status="processing", claim_token__isnull=False, lease_expires_at__isnull=False, heartbeat_at__isnull=False) | (~Q(status="processing") & Q(claim_token__isnull=True, lease_expires_at__isnull=True, heartbeat_at__isnull=True))),
                name="analysis_claim_lease_valid",
            ),
        ]

    @property
    def result(self):
        try:
            return self.analysis_result.payload
        except ObjectDoesNotExist:
            return {}


class AnalysisResult(models.Model):
    task = models.OneToOneField(AnalysisTask, on_delete=models.CASCADE, related_name="analysis_result")
    protocol_version = models.CharField(max_length=16)
    is_mock = models.BooleanField(default=False)
    payload = models.JSONField(default=dict)
    created_at = models.DateTimeField(auto_now_add=True)
