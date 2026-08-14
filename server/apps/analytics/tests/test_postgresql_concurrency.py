from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
import threading

import pytest
from django.db import close_old_connections, connection
from django.utils import timezone

from apps.analytics.models import ExportJob
from apps.analytics.tasks import claim_export_job


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
        rows_snapshot=[],
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
