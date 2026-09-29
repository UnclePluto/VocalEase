from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from io import BytesIO
import threading
import time

import pytest
from django.db import close_old_connections, connection
from django.utils import timezone

from apps.analytics.models import ExportJob, ExportJobItem
from apps.analytics.assets import ExportAssetError, issue_export_private_url
from apps.analytics.tasks import claim_export_job, request_export_cleanup, run_export_job
from apps.media.backends.local import LocalStorageBackend
from apps.media.models import MediaAsset
from apps.media.services import publish_generated_asset


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

    def deterministic_render(stream, rows, export_format, **kwargs):
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

    def render(stream, rows, export_format, **kwargs):
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


def _short_export_runtime(settings):
    settings.ANALYTICS_EXPORT_TTL_SECONDS = 60
    settings.ANALYTICS_EXPORT_LEASE_SECONDS = 3
    settings.ANALYTICS_EXPORT_HEARTBEAT_SECONDS = 1
    settings.MEDIA_PRIVATE_URL_TTL_SECONDS = 30
    # 该用例故意让后端读取超过一个导出租约；上传 grant 本身不能先于测试窗口过期。
    settings.MEDIA_UPLOAD_GRANT_TTL_SECONDS = 30


def _heartbeat_job(*, doctor, patient, key):
    job = ExportJob.objects.create(
        creator=doctor.user, normalized_filters={}, selected_ids=[], snapshot_count=1,
        format="csv", expires_at=timezone.now() + timedelta(seconds=60),
        idempotency_key=key, request_fingerprint="8" * 64,
    )
    ExportJobItem.objects.create(job=job, patient_id=patient.id, position=0)
    return job


def test_postgresql_processing_heartbeat_prevents_takeover_during_long_render(
    settings, doctor, patient, tmp_path, monkeypatch,
):
    _short_export_runtime(settings)
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    job = _heartbeat_job(doctor=doctor, patient=patient, key="pg-render-heartbeat")
    rendering = threading.Event()
    from apps.analytics import tasks

    def slow_render(stream, rows, export_format, *, heartbeat=None):
        list(rows)
        rendering.set()
        for _ in range(7):
            heartbeat()
            time.sleep(0.7)
        stream.write(b"render-heartbeat\r\n")

    monkeypatch.setattr(tasks, "export_rows_to", slow_render)

    def worker():
        close_old_connections()
        try:
            return run_export_job(str(job.id))
        finally:
            close_old_connections()

    with ThreadPoolExecutor(max_workers=1) as pool:
        result = pool.submit(worker)
        assert rendering.wait(timeout=10)
        time.sleep(3.2)
        job.refresh_from_db()
        assert job.status == "processing" and job.lease_expires_at > timezone.now()
        assert claim_export_job(job.id) is None
        worker_result = result.result(timeout=10)

    assert worker_result["published"] is True


def test_postgresql_upload_heartbeat_prevents_takeover_while_local_backend_reads(
    settings, doctor, patient, tmp_path, monkeypatch,
):
    _short_export_runtime(settings)
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    job = _heartbeat_job(doctor=doctor, patient=patient, key="pg-upload-heartbeat")
    uploading = threading.Event()
    original_prepare = LocalStorageBackend.prepare_authorized_stream

    def slow_prepare(self, *, stream, **kwargs):
        uploading.set()
        content = b""
        for _ in range(7):
            content += stream.read(1)
            time.sleep(0.7)
        content += stream.read()
        return original_prepare(self, stream=BytesIO(content), **kwargs)

    monkeypatch.setattr(LocalStorageBackend, "prepare_authorized_stream", slow_prepare)

    def worker():
        close_old_connections()
        try:
            return run_export_job(str(job.id))
        finally:
            close_old_connections()

    with ThreadPoolExecutor(max_workers=1) as pool:
        result = pool.submit(worker)
        assert uploading.wait(timeout=10)
        time.sleep(3.2)
        job.refresh_from_db()
        assert job.status == "processing" and job.lease_expires_at > timezone.now()
        assert claim_export_job(job.id) is None
        worker_result = result.result(timeout=10)

    assert worker_result["published"] is True


def test_postgresql_stopped_heartbeat_allows_takeover_after_lease(settings, doctor):
    _short_export_runtime(settings)
    job = ExportJob.objects.create(
        creator=doctor.user, normalized_filters={}, selected_ids=[], snapshot_count=0,
        format="csv", expires_at=timezone.now() + timedelta(seconds=60),
        idempotency_key="pg-stopped-heartbeat", request_fingerprint="7" * 64,
    )

    first = claim_export_job(job.id)
    time.sleep(3.2)
    second = claim_export_job(job.id)

    assert first is not None and second is not None and first[0] != second[0]


def test_postgresql_private_signing_and_cleanup_share_job_asset_lock_order(
    settings, doctor, tmp_path, monkeypatch,
):
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    job = ExportJob.objects.create(
        creator=doctor.user, normalized_filters={}, selected_ids=[], snapshot_count=0,
        format="csv", expires_at=timezone.now() + timedelta(hours=1),
        idempotency_key="pg-sign-cleanup-lock", request_fingerprint="6" * 64,
    )
    asset = publish_generated_asset(owner_id=job.id, content=b"abc", mime="text/csv")
    ExportJob.objects.filter(pk=job.id).update(
        status="ready", result_asset_id=asset.id, completed_at=timezone.now(),
    )
    signing = threading.Event()
    release = threading.Event()
    original_sign = LocalStorageBackend.create_private_url

    def paused_sign(self, *args, **kwargs):
        signing.set()
        assert release.wait(timeout=10)
        return original_sign(self, *args, **kwargs)

    monkeypatch.setattr(LocalStorageBackend, "create_private_url", paused_sign)

    def sign_worker():
        close_old_connections()
        try:
            return issue_export_private_url(job.id)
        finally:
            close_old_connections()

    def cleanup_worker():
        close_old_connections()
        try:
            return request_export_cleanup(job.id, status=ExportJob.Status.EXPIRED)
        finally:
            close_old_connections()

    with ThreadPoolExecutor(max_workers=2) as pool:
        signed = pool.submit(sign_worker)
        assert signing.wait(timeout=10)
        cleaned = pool.submit(cleanup_worker)
        time.sleep(0.2)
        assert not cleaned.done()
        release.set()
        assert signed.result(timeout=10)[2].url
        cleaned.result(timeout=10)

    job.refresh_from_db(); asset.refresh_from_db()
    assert job.cleanup_status == "complete" and job.result_asset_id is None
    assert asset.status == "pending_cleanup"
    with pytest.raises(ExportAssetError):
        issue_export_private_url(job.id)
