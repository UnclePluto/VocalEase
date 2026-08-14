from django.db import models
from django.db.models import Q

from common.models import UUIDSoftDeleteModel


class Song(UUIDSoftDeleteModel):
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
    analysis_status = models.CharField(max_length=16, choices=AnalysisStatus.choices, default=AnalysisStatus.PENDING)
    publication_status = models.CharField(max_length=16, choices=PublicationStatus.choices, default=PublicationStatus.DRAFT)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ["-created_at"]
        constraints = [
            models.CheckConstraint(condition=Q(duration_seconds__gt=0), name="song_duration_positive"),
            models.CheckConstraint(condition=Q(analysis_status__in=["pending", "processing", "succeeded", "failed", "retrying"]), name="song_analysis_status_valid"),
            models.CheckConstraint(condition=Q(publication_status__in=["draft", "published"]), name="song_publication_status_valid"),
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
