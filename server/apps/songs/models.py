from django.db import models
from django.db.models import Q

from common.models import UUIDSoftDeleteModel


class Song(UUIDSoftDeleteModel):
    class IngestionMode(models.TextChoices):
        EXISTING = "existing", "现有上传"
        MANUAL = "manual", "人工上传"

    class AnalysisStatus(models.TextChoices):
        PENDING = "pending", "待分析"
        PROCESSING = "processing", "分析中"
        SUCCEEDED = "succeeded", "分析成功"
        FAILED = "failed", "分析失败"
        RETRYING = "retrying", "重试中"

    class PublicationStatus(models.TextChoices):
        DRAFT = "draft", "未发布"
        PUBLISHED = "published", "已发布"

    title = models.CharField(max_length=200)
    artist = models.CharField(max_length=200)
    genre = models.CharField(max_length=64)
    language = models.CharField(max_length=64)
    duration_seconds = models.PositiveIntegerField()
    source_asset = models.ForeignKey("media.MediaAsset", on_delete=models.PROTECT, null=True, blank=True, related_name="source_songs")
    vocal_asset = models.ForeignKey("media.MediaAsset", on_delete=models.PROTECT, null=True, blank=True, related_name="vocal_songs")
    accompaniment_asset = models.ForeignKey("media.MediaAsset", on_delete=models.PROTECT, null=True, blank=True, related_name="accompaniment_songs")
    lyrics_asset = models.ForeignKey("media.MediaAsset", on_delete=models.PROTECT, null=True, blank=True, related_name="lyrics_songs")
    ingestion_mode = models.CharField(max_length=16, choices=IngestionMode.choices, default=IngestionMode.EXISTING)
    source_available = models.BooleanField(default=False)
    source_verified_at = models.DateTimeField(null=True, blank=True)
    source_verified_asset_id = models.UUIDField(null=True, blank=True)
    source_receipt_fingerprint = models.CharField(max_length=64, blank=True)
    source_verified_backend = models.CharField(max_length=16, blank=True)
    source_verified_object_key = models.CharField(max_length=255, blank=True)
    source_verified_size = models.PositiveBigIntegerField(null=True, blank=True)
    source_verified_mime = models.CharField(max_length=127, blank=True)
    source_verified_sha256 = models.CharField(max_length=64, blank=True)
    source_verified_etag = models.CharField(max_length=128, blank=True)
    source_verified_generation = models.CharField(max_length=32, blank=True)
    analysis_status = models.CharField(max_length=16, choices=AnalysisStatus.choices, default=AnalysisStatus.PENDING)
    publication_status = models.CharField(max_length=16, choices=PublicationStatus.choices, default=PublicationStatus.DRAFT)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]
        constraints = [
            models.CheckConstraint(condition=Q(ingestion_mode__in=["existing", "manual"]), name="song_ingestion_mode_valid"),
            models.CheckConstraint(condition=Q(duration_seconds__gt=0), name="song_duration_positive"),
            models.CheckConstraint(condition=Q(analysis_status__in=["pending", "processing", "succeeded", "failed", "retrying"]), name="song_analysis_status_valid"),
            models.CheckConstraint(condition=Q(publication_status__in=["draft", "published"]), name="song_publication_status_valid"),
            models.CheckConstraint(
                condition=(
                    Q(
                        source_available=True, source_asset__isnull=False,
                        source_verified_at__isnull=False, source_verified_asset_id__isnull=False,
                        source_verified_asset_id=models.F("source_asset_id"),
                        source_receipt_fingerprint__gt="", source_verified_object_key__gt="",
                        source_verified_size__isnull=False, source_verified_mime__gt="",
                    )
                    & (
                        Q(
                            source_verified_backend="local", source_verified_sha256__regex=r"^[0-9a-f]{64}$",
                            source_verified_generation__regex=r"^[0-9a-f]{32}$", source_verified_etag="",
                        )
                        | Q(
                            source_verified_backend="qiniu", source_verified_etag__gt="",
                            source_verified_sha256="", source_verified_generation="",
                        )
                    )
                    | Q(
                        source_available=False, source_verified_asset_id__isnull=True,
                        source_receipt_fingerprint="", source_verified_backend="",
                        source_verified_object_key="", source_verified_size__isnull=True,
                        source_verified_mime="", source_verified_sha256="",
                        source_verified_etag="", source_verified_generation="",
                    )
                ),
                name="song_source_availability_valid",
            ),
        ]


class SongUploadIntent(models.Model):
    """服务端预分配的歌曲 UUID，防止通用 media owner 绕过歌曲归属验证。"""

    id = models.UUIDField(primary_key=True)
    song_id = models.UUIDField(unique=True)
    asset = models.OneToOneField("media.MediaAsset", on_delete=models.PROTECT, related_name="song_upload_intent")
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        constraints = [
            models.CheckConstraint(condition=Q(song_id=models.F("id")), name="song_upload_intent_id_matches_song"),
        ]


class SongAvailabilityScanState(models.Model):
    """全库可用性扫描的单例游标与租约。"""

    id = models.PositiveSmallIntegerField(primary_key=True, default=1, editable=False)
    claim_token = models.UUIDField(null=True, blank=True)
    lease_expires_at = models.DateTimeField(null=True, blank=True)
    cursor = models.UUIDField(null=True, blank=True)
    batch_version = models.PositiveBigIntegerField(default=0)
    pending_batch_token = models.UUIDField(null=True, blank=True)
    stats = models.JSONField(default=dict, blank=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        constraints = [
            models.CheckConstraint(condition=Q(id=1), name="song_availability_scan_singleton"),
            models.CheckConstraint(
                condition=(
                    Q(claim_token__isnull=True, lease_expires_at__isnull=True, pending_batch_token__isnull=True)
                    | Q(claim_token__isnull=False, lease_expires_at__isnull=False, pending_batch_token__isnull=False)
                ),
                name="song_availability_scan_claim_valid",
            ),
        ]
