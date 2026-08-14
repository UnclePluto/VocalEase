from celery import shared_task

from .services import refresh_song_source_availability


@shared_task
def refresh_song_source_availability_task(batch_size: int = 100, after_id: str | None = None):
    from uuid import UUID
    cursor = UUID(after_id) if after_id else None
    return refresh_song_source_availability(batch_size=batch_size, after_id=cursor)
