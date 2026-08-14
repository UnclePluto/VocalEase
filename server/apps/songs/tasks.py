from celery import shared_task

from .services import (refresh_song_source_availability,
                       run_song_availability_scan_batch,
                       start_song_availability_scan)


@shared_task
def refresh_song_source_availability_task(batch_size: int = 100, after_id: str | None = None):
    from uuid import UUID
    cursor = UUID(after_id) if after_id else None
    return refresh_song_source_availability(batch_size=batch_size, after_id=cursor)


@shared_task
def start_song_availability_scan_task(batch_size: int = 100):
    return start_song_availability_scan(batch_size=batch_size)


@shared_task
def refresh_song_availability_scan_batch_task(token: str, batch_size: int = 100):
    from uuid import UUID
    return run_song_availability_scan_batch(UUID(token), batch_size=batch_size)
