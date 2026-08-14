from datetime import timedelta
import uuid

import pytest
from django.db import IntegrityError, transaction
from django.utils import timezone

from apps.analytics.models import ExportJob


def _job_values(doctor, key):
    return {
        "creator": doctor.user,
        "normalized_filters": {},
        "selected_ids": [],
        "snapshot_count": 0,
        "format": "csv",
        "expires_at": timezone.now() + timedelta(hours=1),
        "idempotency_key": key,
        "request_fingerprint": "a" * 64,
    }


@pytest.mark.django_db
def test_database_rejects_ready_without_result_and_completed_timestamp(doctor):
    with pytest.raises(IntegrityError), transaction.atomic():
        ExportJob.objects.create(**_job_values(doctor, "invalid-ready"), status="ready")


@pytest.mark.django_db
def test_database_rejects_result_attachment_on_pending_job(doctor):
    job = ExportJob.objects.create(**_job_values(doctor, "invalid-pending-result"))

    with pytest.raises(IntegrityError), transaction.atomic():
        ExportJob.objects.filter(pk=job.id).update(result_asset_id=uuid.uuid4())


@pytest.mark.django_db
def test_database_rejects_cleanup_processing_without_lease_and_negative_attempt(doctor):
    job = ExportJob.objects.create(**_job_values(doctor, "invalid-cleanup-claim"))
    with pytest.raises(IntegrityError), transaction.atomic():
        ExportJob.objects.filter(pk=job.id).update(status="failed", cleanup_status="processing")
    with pytest.raises(IntegrityError), transaction.atomic():
        ExportJob.objects.filter(pk=job.id).update(attempt=-1)
