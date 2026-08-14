from django.db.models import Q

from .models import Song


def songs_for_admin(*, keyword: str = "", genre: str = "", language: str = "", analysis_status: str = "", publication_status: str = "", ordering: str = "-created_at"):
    queryset = Song.objects.filter(deleted_at__isnull=True).select_related("source_asset")
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
    return queryset.filter(source_asset__status="ready", source_asset__deleted_at__isnull=True)
