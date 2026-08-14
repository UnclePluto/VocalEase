from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
import threading

import pytest
from django.db import close_old_connections, connection
from django.utils import timezone

from apps.analytics.models import ExportJob, ExportJobItem
from apps.analytics.tasks import claim_export_job, request_export_cleanup, run_export_job
from apps.media.models import MediaAsset


pytestmark = [pytest.mark.postgresql, pytest.mark.django_db(transaction=True)]


@pytest.fixture(autouse=True)
def require_postgresql():
    if connection.vendor != "postgresql":
        pytest.skip("requires PostgreSQL")


def test_postgresql_export_job_claim_is_single_winner(doctor):
    job = ExportJob.objects.create(
        creator=doctor.user,
        normalized_filters={},
        selected_ids=[],
        snapshot_count=0,
        format="csv",
        expires_at=timezone.now() + timedelta(hours=1),
        idempotency_key="pg-single-claim",
        request_fingerprint="b" * 64,
    )
    barrier = threading.Barrier(2)

    def worker():
        close_old_connections()
        barrier.wait()
        try:
            return claim_export_job(job.id)
        finally:
            close_old_connections()

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: worker(), range(2)))

    assert sum(result is not None for result in results) == 1
    job.refresh_from_db()
    assert job.status == "processing" and job.attempt == 1 and job.claim_token is not None


def test_postgresql_takeover_fences_old_finalize_without_revoking_winner_asset(
    settings, doctor, patient, tmp_path, monkeypatch,
):
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    job = ExportJob.objects.create(
        creator=doctor.user,
        normalized_filters={},
        selected_ids=[],
        snapshot_count=1,
        format="csv",
        expires_at=timezone.now() + timedelta(hours=1),
        idempotency_key="pg-takeover-fence",
        request_fingerprint="c" * 64,
    )
    ExportJobItem.objects.create(job=job, patient_id=patient.id, position=0)
    first_published = threading.Event()
    release_first = threading.Event()
    publish_lock = threading.Lock()
    publish_count = 0
    from apps.analytics import tasks
    real_publish = tasks.publish_generated_asset

    def deterministic_render(stream, rows, export_format):
        list(rows)
        stream.write(b"\xef\xbb\xbfpatient\r\n")

    def publish_with_internal_barrier(**kwargs):
        nonlocal publish_count
        asset = real_publish(**kwargs)
        with publish_lock:
            publish_count += 1
            ordinal = publish_count
        if ordinal == 1:
            first_published.set()
            assert release_first.wait(timeout=10)
        return asset

    monkeypatch.setattr(tasks, "export_rows_to", deterministic_render)
    monkeypatch.setattr(tasks, "publish_generated_asset", publish_with_internal_barrier)

    def old_worker():
        close_old_connections()
        try:
            return run_export_job(str(job.id))
        finally:
            close_old_connections()

    with ThreadPoolExecutor(max_workers=1) as pool:
        old_result = pool.submit(old_worker)
        assert first_published.wait(timeout=10)
        ExportJob.objects.filter(pk=job.id).update(lease_expires_at=timezone.now() - timedelta(seconds=1))
        winner_result = run_export_job(str(job.id))
        release_first.set()
        stale_result = old_result.result(timeout=10)

    job.refresh_from_db()
    assets = list(MediaAsset.objects.filter(owner_type="export", owner_id=job.id))
    assert winner_result["published"] is True and stale_result["published"] is False
    assert job.status == "ready" and len(assets) == 1
    assert job.result_asset_id == assets[0].id and assets[0].status == "ready"


def test_postgresql_cleanup_interleaved_with_publish_converges_without_false_ready(
    settings, doctor, patient, tmp_path, monkeypatch,
):
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    job = ExportJob.objects.create(
        creator=doctor.user, normalized_filters={"name": "快照"}, selected_ids=[],
        snapshot_count=1, format="csv", expires_at=timezone.now() + timedelta(hours=1),
        idempotency_key="pg-cleanup-publish-race", request_fingerprint="d" * 64,
    )
    ExportJobItem.objects.create(job=job, patient_id=patient.id, position=0)
    published = threading.Event()
    release = threading.Event()
    from apps.analytics import tasks
    real_publish = tasks.publish_generated_asset

    def render(stream, rows, export_format):
        list(rows)
        stream.write(b"race\r\n")

    monkeypatch.setattr(tasks, "export_rows_to", render)

    def publish_then_pause(**kwargs):
        asset = real_publish(**kwargs)
        published.set()
        assert release.wait(timeout=10)
        return asset

    monkeypatch.setattr(tasks, "publish_generated_asset", publish_then_pause)

    def worker():
        close_old_connections()
        try:
            return run_export_job(str(job.id))
        finally:
            close_old_connections()

    with ThreadPoolExecutor(max_workers=1) as pool:
        result = pool.submit(worker)
        assert published.wait(timeout=10)
        request_export_cleanup(job.id, status=ExportJob.Status.EXPIRED)
        release.set()
        worker_result = result.result(timeout=10)

    job.refresh_from_db()
    assets = list(MediaAsset.objects.filter(owner_type="export", owner_id=job.id))
    assert worker_result["published"] is False
    assert job.status == "expired" and job.cleanup_status == "complete" and job.result_asset_id is None
    assert job.normalized_filters == {} and len(assets) == 1
    assert assets[0].status == "pending_cleanup"
