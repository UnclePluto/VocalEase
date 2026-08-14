import uuid

from django.conf import settings
from django.db import models
from django.db.models import Q


class ExportJob(models.Model):
    class Format(models.TextChoices):
        CSV = "csv", "CSV"
        XLSX = "xlsx", "Excel"

    class Status(models.TextChoices):
        PENDING = "pending", "等待生成"
        PROCESSING = "processing", "生成中"
        READY = "ready", "可下载"
        FAILED = "failed", "生成失败"
        EXPIRED = "expired", "已过期"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    creator = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.PROTECT, related_name="export_jobs")
    normalized_filters = models.JSONField(default=dict)
    selected_ids = models.JSONField(default=list)
    rows_snapshot = models.JSONField(default=list)
    snapshot_count = models.PositiveIntegerField(default=0)
    format = models.CharField(max_length=8, choices=Format.choices)
    status = models.CharField(max_length=16, choices=Status.choices, default=Status.PENDING)
    # 只保存私有媒体标识，避免统计迁移改变 Task 4 媒体不可逆边界的依赖图。
    result_asset_id = models.UUIDField(null=True, blank=True, unique=True)
    failure_reason = models.CharField(max_length=256, blank=True)
    expires_at = models.DateTimeField()
    idempotency_key = models.CharField(max_length=128)
    request_fingerprint = models.CharField(max_length=64)
    attempt = models.PositiveSmallIntegerField(default=0)
    claim_token = models.UUIDField(null=True, blank=True)
    lease_expires_at = models.DateTimeField(null=True, blank=True)
    heartbeat_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)
    completed_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        ordering = ["-created_at"]
        constraints = [
            models.UniqueConstraint(fields=["creator", "idempotency_key"], name="analytics_export_creator_key_unique"),
            models.CheckConstraint(condition=Q(format__in=["csv", "xlsx"]), name="analytics_export_format_valid"),
            models.CheckConstraint(condition=Q(status__in=["pending", "processing", "ready", "failed", "expired"]), name="analytics_export_status_valid"),
            models.CheckConstraint(condition=Q(attempt__lte=4), name="analytics_export_attempt_limit"),
            models.CheckConstraint(
                condition=(
                    Q(status="processing", claim_token__isnull=False, lease_expires_at__isnull=False, heartbeat_at__isnull=False)
                    | (~Q(status="processing") & Q(claim_token__isnull=True, lease_expires_at__isnull=True, heartbeat_at__isnull=True))
                ),
                name="analytics_export_claim_valid",
            ),
            models.CheckConstraint(
                condition=(Q(status="ready", result_asset_id__isnull=False, completed_at__isnull=False) | ~Q(status="ready")),
                name="analytics_export_ready_result_valid",
            ),
        ]

    @property
    def result_asset(self):
        if not self.result_asset_id:
            return None
        from apps.media.models import MediaAsset
        return MediaAsset.objects.get(pk=self.result_asset_id)
