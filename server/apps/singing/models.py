import uuid

from django.db import models
from django.db.models import Q


class SingingSessionManager(models.Manager):
    def create_from_snapshots(self, *, patient, song, created_source="patient_android_api"):
        plan = patient.treatment_plans.get(status="active", deleted_at__isnull=True)
        return self.create(
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
            created_source=created_source,
        )


class SingingSession(models.Model):
    class Status(models.TextChoices):
        CREATED = "created", "已创建"
        AWAITING_UPLOAD = "awaiting_upload", "等待上传"
        UPLOADED = "uploaded", "已上传"
        PROCESSING = "processing", "分析中"
        COMPLETED = "completed", "已完成"
        FAILED = "failed", "失败"
        CANCELLED = "cancelled", "已取消"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    patient = models.ForeignKey("patients.PatientProfile", on_delete=models.PROTECT, related_name="singing_sessions")
    song = models.ForeignKey("songs.Song", on_delete=models.PROTECT, related_name="singing_sessions")
    treatment_plan = models.ForeignKey("patients.TreatmentPlan", on_delete=models.PROTECT, related_name="singing_sessions")
    patient_snapshot = models.JSONField()
    song_snapshot = models.JSONField()
    treatment_plan_snapshot = models.JSONField()
    status = models.CharField(max_length=24, choices=Status.choices, default=Status.CREATED)
    submission_idempotency_key = models.CharField(max_length=128, blank=True)
    retry_idempotency_key = models.CharField(max_length=128, blank=True)
    retry_generation = models.PositiveSmallIntegerField(default=0)
    analysis_generation = models.PositiveIntegerField(default=0)
    score = models.PositiveSmallIntegerField(null=True, blank=True)
    burp_count = models.PositiveIntegerField(null=True, blank=True)
    duration_seconds = models.PositiveIntegerField(null=True, blank=True)
    is_mock = models.BooleanField(default=False)
    created_source = models.CharField(max_length=32, default="patient_android_api")
    submitted_at = models.DateTimeField(null=True, blank=True)
    completed_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    objects = SingingSessionManager()

    class Meta:
        ordering = ["-created_at"]
        indexes = [
            models.Index(fields=["patient", "status", "-created_at"]),
            models.Index(fields=["status", "-created_at"]),
        ]
        constraints = [
            models.CheckConstraint(
                condition=Q(status__in=["created", "awaiting_upload", "uploaded", "processing", "completed", "failed", "cancelled"]),
                name="singing_session_status_valid",
            ),
            models.CheckConstraint(
                condition=Q(duration_seconds__isnull=True) | Q(duration_seconds__gt=0),
                name="singing_session_duration_positive",
            ),
            models.CheckConstraint(
                condition=Q(score__isnull=True) | Q(score__gte=0, score__lte=100),
                name="singing_session_score_range",
            ),
            models.CheckConstraint(
                condition=(Q(status="completed", completed_at__isnull=False, score__isnull=False, burp_count__isnull=False, is_mock=True) | ~Q(status="completed")),
                name="singing_session_completed_result_valid",
            ),
        ]


class SessionMedia(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    session = models.ForeignKey(SingingSession, on_delete=models.CASCADE, related_name="media_bindings")
    asset = models.OneToOneField("media.MediaAsset", on_delete=models.PROTECT, related_name="singing_session_binding")
    media_type = models.CharField(max_length=32, choices=(("singing_audio", "演唱音频"), ("singing_video", "演唱录像")))
    grant_idempotency_key = models.CharField(max_length=128, blank=True)
    confirmed_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["session", "media_type"], name="one_media_type_per_singing_session"),
            models.UniqueConstraint(fields=["session", "grant_idempotency_key"], condition=~Q(grant_idempotency_key=""), name="singing_upload_grant_idempotency_unique"),
            models.CheckConstraint(condition=Q(media_type__in=["singing_audio", "singing_video"]), name="singing_session_media_type_valid"),
        ]


class AnalysisTimeSeries(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    session = models.ForeignKey(SingingSession, on_delete=models.CASCADE, related_name="time_series")
    task = models.ForeignKey("analysis.AnalysisTask", on_delete=models.CASCADE, related_name="time_series")
    metric_type = models.CharField(max_length=32)
    generation = models.PositiveIntegerField(default=0)
    sample_interval_ms = models.PositiveIntegerField()
    values = models.JSONField(default=list)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["task", "metric_type"], name="one_metric_series_per_analysis_task"),
            models.CheckConstraint(condition=Q(sample_interval_ms__gt=0), name="analysis_time_series_interval_positive"),
        ]
