from django.db.models import F, Q

from .models import Song


def songs_for_admin(*, keyword: str = "", genre: str = "", language: str = "", analysis_status: str = "", publication_status: str = "", ordering: str = "-created_at"):
    queryset = Song.objects.filter(deleted_at__isnull=True).select_related("source_asset", "vocal_asset", "accompaniment_asset", "lyrics_asset")
    if keyword:
        queryset = queryset.filter(Q(title__icontains=keyword) | Q(artist__icontains=keyword))
    if genre:
        queryset = queryset.filter(genre=genre)
    if language:
        queryset = queryset.filter(language=language)
    if analysis_status:
        queryset = queryset.filter(analysis_status=analysis_status)
    if publication_status:
        queryset = queryset.filter(publication_status=publication_status)
    return queryset.order_by(ordering)


def songs_for_patient(*, keyword: str = "", ordering: str = "-created_at"):
    queryset = songs_for_admin(keyword=keyword, publication_status=Song.PublicationStatus.PUBLISHED, ordering=ordering)
    queryset = queryset.filter(
        source_available=True,
        source_verified_asset_id=F("source_asset_id"),
        source_receipt_fingerprint__gt="",
        source_verified_backend=F("source_asset__backend"),
        source_verified_object_key=F("source_asset__object_key"),
        source_verified_size=F("source_asset__size"),
        source_verified_mime=F("source_asset__mime"),
        source_asset__status="ready",
        source_asset__deleted_at__isnull=True,
    )
    return queryset.filter(
        Q(
            source_asset__backend="local",
            source_verified_sha256=F("source_asset__sha256"),
            source_verified_generation=F("source_asset__manifest_generation"),
            source_verified_etag="",
        )
        | Q(
            source_asset__backend="qiniu",
            source_verified_etag=F("source_asset__etag"),
            source_verified_sha256="",
            source_verified_generation="",
        )
    )
