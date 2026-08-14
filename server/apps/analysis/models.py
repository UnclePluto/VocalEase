import uuid

from django.db import models
from django.db.models import Q


class AnalysisTask(models.Model):
    class TaskType(models.TextChoices):
        VOCAL_SEPARATION = "vocal_separation", "人声分离"
        ACCOMPANIMENT_GENERATION = "accompaniment_generation", "伴奏生成"
        LYRICS_RECOGNITION = "lyrics_recognition", "歌词识别"

    class Status(models.TextChoices):
        PENDING = "pending", "待处理"
        PROCESSING = "processing", "处理中"
        SUCCEEDED = "succeeded", "成功"
        FAILED = "failed", "失败"
        RETRYING = "retrying", "重试中"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    song = models.ForeignKey("songs.Song", on_delete=models.PROTECT, related_name="analysis_tasks")
    source_asset = models.ForeignKey("media.MediaAsset", on_delete=models.PROTECT, related_name="analysis_tasks")
    task_type = models.CharField(max_length=32, choices=TaskType.choices)
    protocol_version = models.CharField(max_length=16, default="1.0")
    executor = models.CharField(max_length=64, default="mock_song")
    status = models.CharField(max_length=16, choices=Status.choices, default=Status.PENDING)
    attempt = models.PositiveSmallIntegerField(default=0)
    idempotency_key = models.CharField(max_length=128, unique=True)
    input_snapshot = models.JSONField(default=dict)
    result = models.JSONField(default=dict, blank=True)
    error_code = models.CharField(max_length=64, blank=True)
    error_summary = models.CharField(max_length=256, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    started_at = models.DateTimeField(null=True, blank=True)
    completed_at = models.DateTimeField(null=True, blank=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]
        constraints = [
            models.CheckConstraint(condition=Q(status__in=["pending", "processing", "succeeded", "failed", "retrying"]), name="analysis_task_status_valid"),
            models.CheckConstraint(condition=Q(attempt__gte=0), name="analysis_task_attempt_nonnegative"),
        ]


class AnalysisResult(models.Model):
    task = models.OneToOneField(AnalysisTask, on_delete=models.PROTECT, related_name="analysis_result")
    protocol_version = models.CharField(max_length=16)
    is_mock = models.BooleanField(default=False)
    payload = models.JSONField(default=dict)
    created_at = models.DateTimeField(auto_now_add=True)
