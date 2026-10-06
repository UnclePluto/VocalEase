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
def refresh_song_availability_scan_batch_task(
    token: str, batch_size: int = 100, batch_token: str | None = None,
    batch_version: int | None = None,
):
    from uuid import UUID
    return run_song_availability_scan_batch(
        UUID(token), batch_size=batch_size,
        batch_token=UUID(batch_token) if batch_token else None,
        batch_version=batch_version,
    )


@shared_task
def generate_reference_pitch_task(*, song_id, expected_fingerprint, version):
    from .reference_pitch_services import generate_reference_pitch
    return generate_reference_pitch(song_id=song_id, expected_fingerprint=expected_fingerprint, version=version)


@shared_task
def recover_reference_pitch_tasks():
    from django.db import transaction
    from django.db.models import Q
    from django.utils import timezone
    from .models import SongReferencePitch
    now = timezone.now(); count = 0
    with transaction.atomic():
        rows = SongReferencePitch.objects.select_for_update().filter(Q(status='pending', next_attempt_at__lte=now) | Q(status='pending', next_attempt_at__isnull=True) | Q(status='processing', lease_until__lt=now)).order_by('created_at')[:100]
        for row in rows:
            if row.attempt >= 3:
                row.status='failed';row.lease_token=None;row.lease_until=None;row.save();continue
            row.status='pending';row.lease_token=None;row.lease_until=None
            from datetime import timedelta
            row.next_attempt_at=None;row.save()
            transaction.on_commit(lambda row=row: generate_reference_pitch_task.delay(song_id=str(row.song_id), expected_fingerprint=row.input_fingerprint, version=str(row.id)))
            # 调度租约防止重复扫描；被投递的 worker 可在下一次恢复时处理。
            count += 1
    return count
