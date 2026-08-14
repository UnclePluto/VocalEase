from io import BytesIO

import pytest
from django.utils import timezone
from openpyxl import load_workbook
from rest_framework.test import APIClient

from apps.analytics.models import ExportJob
from apps.analytics.tasks import recover_export_jobs, run_export_job
from apps.audit.models import AuditLog
from apps.media.models import MediaAsset


@pytest.mark.django_db
def test_sync_export_intersects_selected_ids_with_current_filters(doctor, patient, other_patient):
    doctor.user.must_change_password = False
    doctor.user.save(update_fields=["must_change_password"])
    client = APIClient(); client.force_authenticate(doctor.user)
    response = client.post("/api/v1/admin/analytics/exports/", {
        "format": "xlsx",
        "filters": {"name": "患者甲"},
        "selected_ids": [str(patient.id), str(other_patient.id)],
        "idempotency_key": "sync-filter-intersection",
    }, format="json")

    assert response.status_code == 200
    assert response["Content-Type"] == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    assert "filename*=UTF-8''" in response["Content-Disposition"]
    rows = list(load_workbook(BytesIO(response.content), read_only=True).active.iter_rows(values_only=True))
    assert len(rows) == 2 and rows[1][0] == patient.medical_record_no
    assert AuditLog.objects.filter(action="analytics.export_sync", actor=doctor.user).count() == 1
    assert MediaAsset.objects.filter(owner_type="export").count() == 0


@pytest.mark.django_db
def test_async_export_uses_same_count_and_produces_private_media(settings, doctor, patient, other_patient, tmp_path):
    settings.ANALYTICS_SYNC_EXPORT_LIMIT = 1
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    doctor.user.must_change_password = False
    doctor.user.save(update_fields=["must_change_password"])
    client = APIClient(); client.force_authenticate(doctor.user)
    response = client.post("/api/v1/admin/analytics/exports/", {
        "format": "csv",
        "filters": {"treatment_status": "active"},
        "selected_ids": [],
        "idempotency_key": "async-active-patients",
    }, format="json")

    assert response.status_code == 202
    job = ExportJob.objects.get(pk=response.json()["data"]["id"])
    assert job.snapshot_count == 2 and job.normalized_filters["treatment_status"] == "active"

    run_export_job(str(job.id))

    job.refresh_from_db()
    assert job.status == "ready" and job.result_asset.status == "ready"
    assert job.result_asset.owner_type == "export" and job.result_asset.owner_id == job.id
    private = client.post(f"/api/v1/admin/analytics/exports/{job.id}/private-url/")
    assert private.status_code == 200
    assert private.json()["data"]["expires_at"] <= job.expires_at.isoformat()
    assert AuditLog.objects.filter(action="analytics.export_create", target_id=job.id).exists()
    assert AuditLog.objects.filter(action="analytics.export_download", target_id=job.id).exists()


@pytest.mark.django_db
def test_export_idempotency_rejects_changed_request(settings, doctor, patient, other_patient):
    settings.ANALYTICS_SYNC_EXPORT_LIMIT = 1
    doctor.user.must_change_password = False
    doctor.user.save(update_fields=["must_change_password"])
    client = APIClient(); client.force_authenticate(doctor.user)
    payload = {"format": "csv", "filters": {}, "selected_ids": [], "idempotency_key": "same-key"}
    first = client.post("/api/v1/admin/analytics/exports/", payload, format="json")
    second = client.post("/api/v1/admin/analytics/exports/", payload, format="json")
    changed = client.post("/api/v1/admin/analytics/exports/", {**payload, "format": "xlsx"}, format="json")

    assert first.status_code == 202 and second.status_code == 200
    assert first.json()["data"]["id"] == second.json()["data"]["id"]
    assert changed.status_code == 409
    assert ExportJob.objects.count() == 1


@pytest.mark.django_db
def test_patient_cannot_read_or_download_export_job(settings, doctor, patient, other_patient):
    settings.ANALYTICS_SYNC_EXPORT_LIMIT = 1
    doctor.user.must_change_password = False
    doctor.user.save(update_fields=["must_change_password"])
    client = APIClient(); client.force_authenticate(doctor.user)
    created = client.post("/api/v1/admin/analytics/exports/", {
        "format": "csv", "filters": {}, "selected_ids": [], "idempotency_key": "patient-denied",
    }, format="json")
    job_id = created.json()["data"]["id"]
    client.force_authenticate(patient.user)
    assert client.get(f"/api/v1/admin/analytics/exports/{job_id}/").status_code == 403
    assert client.post(f"/api/v1/admin/analytics/exports/{job_id}/private-url/").status_code == 403


@pytest.mark.django_db
def test_export_failure_never_leaves_false_ready_job(settings, doctor, patient, other_patient, monkeypatch):
    settings.ANALYTICS_SYNC_EXPORT_LIMIT = 1
    doctor.user.must_change_password = False
    doctor.user.save(update_fields=["must_change_password"])
    client = APIClient(); client.force_authenticate(doctor.user)
    created = client.post("/api/v1/admin/analytics/exports/", {
        "format": "csv", "filters": {}, "selected_ids": [], "idempotency_key": "publish-crash",
    }, format="json")
    job = ExportJob.objects.get(pk=created.json()["data"]["id"])
    monkeypatch.setattr("apps.analytics.tasks.publish_generated_asset", lambda **kwargs: (_ for _ in ()).throw(RuntimeError("storage-down")))

    with pytest.raises(RuntimeError, match="storage-down"):
        run_export_job(str(job.id))

    job.refresh_from_db()
    assert job.status == "pending" and job.result_asset_id is None
    assert job.claim_token is None and job.lease_expires_at is None


@pytest.mark.django_db
def test_recovery_requeues_expired_lease_without_double_claim(settings, doctor, monkeypatch):
    from datetime import timedelta
    import uuid

    job = ExportJob.objects.create(
        creator=doctor.user, normalized_filters={}, selected_ids=[], rows_snapshot=[], snapshot_count=0,
        format="csv", status="processing", expires_at=timezone.now() + timedelta(hours=1),
        idempotency_key="recover-expired", request_fingerprint="a" * 64, attempt=1,
        claim_token=uuid.uuid4(), heartbeat_at=timezone.now() - timedelta(minutes=10),
        lease_expires_at=timezone.now() - timedelta(minutes=5),
    )
    dispatched = []
    monkeypatch.setattr("apps.analytics.tasks.run_export_job_task.delay", lambda job_id: dispatched.append(job_id))

    result = recover_export_jobs()

    job.refresh_from_db()
    assert result == {"recovered": 1, "failed": 0, "expired": 0}
    assert job.status == "pending" and job.claim_token is None
    assert dispatched == [str(job.id)]


@pytest.mark.django_db
def test_dashboard_rejects_unknown_query_parameters(doctor):
    client = APIClient(); client.force_authenticate(doctor.user)
    response = client.get("/api/v1/admin/analytics/dashboard/?unexpected=1")
    assert response.status_code == 400
    assert "unexpected" in response.json()["data"]


@pytest.mark.django_db
def test_export_worker_renews_claim_after_render_before_publishing(settings, doctor, monkeypatch):
    from datetime import timedelta
    import uuid

    job = ExportJob.objects.create(
        creator=doctor.user, normalized_filters={}, selected_ids=[], rows_snapshot=[], snapshot_count=0,
        format="csv", expires_at=timezone.now() + timedelta(hours=1), idempotency_key="heartbeat-before-publish",
        request_fingerprint="c" * 64,
    )
    asset = MediaAsset.objects.create(
        owner_type="export", owner_id=job.id, media_type="export", backend="qiniu",
        object_key=f"test/export/{uuid.uuid4().hex}", mime="text/csv", size=3,
        etag="export-ready", status="ready", upload_expires_at=timezone.now() + timedelta(hours=1),
    )

    def slow_render(rows, export_format):
        ExportJob.objects.filter(pk=job.id).update(lease_expires_at=timezone.now() + timedelta(seconds=1))
        return b"abc"

    def publish(**kwargs):
        leased = ExportJob.objects.get(pk=job.id)
        assert leased.lease_expires_at > timezone.now() + timedelta(seconds=100)
        return asset

    monkeypatch.setattr("apps.analytics.tasks.export_rows", slow_render)
    monkeypatch.setattr("apps.analytics.tasks.publish_generated_asset", publish)

    result = run_export_job(str(job.id))

    job.refresh_from_db()
    assert result["published"] is True
    assert job.status == "ready" and job.result_asset_id == asset.id


@pytest.mark.django_db
def test_expired_export_revokes_its_private_media(doctor):
    from datetime import timedelta
    import uuid

    job = ExportJob.objects.create(
        creator=doctor.user, normalized_filters={}, selected_ids=[], rows_snapshot=[], snapshot_count=0,
        format="csv", status="pending", expires_at=timezone.now() - timedelta(seconds=1),
        idempotency_key="expire-media", request_fingerprint="d" * 64,
    )
    asset = MediaAsset.objects.create(
        owner_type="export", owner_id=job.id, media_type="export", backend="qiniu",
        object_key=f"test/export/{uuid.uuid4().hex}", mime="text/csv", size=3,
        etag="expired-ready", status="ready", upload_expires_at=timezone.now() + timedelta(hours=1),
    )
    ExportJob.objects.filter(pk=job.id).update(status="ready", result_asset_id=asset.id, completed_at=timezone.now())
    client = APIClient(); client.force_authenticate(doctor.user)

    response = client.get(f"/api/v1/admin/analytics/exports/{job.id}/")

    job.refresh_from_db(); asset.refresh_from_db()
    assert response.status_code == 200 and job.status == "expired"
    assert asset.status == "pending_cleanup"


@pytest.mark.django_db
def test_worker_never_links_media_revoked_during_lease_takeover(doctor, monkeypatch):
    from datetime import timedelta
    import uuid

    job = ExportJob.objects.create(
        creator=doctor.user, normalized_filters={}, selected_ids=[], rows_snapshot=[], snapshot_count=0,
        format="csv", expires_at=timezone.now() + timedelta(hours=1),
        idempotency_key="revoked-before-finalize", request_fingerprint="e" * 64,
    )
    asset = MediaAsset.objects.create(
        owner_type="export", owner_id=job.id, media_type="export", backend="qiniu",
        object_key=f"test/export/{uuid.uuid4().hex}", mime="text/csv", size=3,
        etag="revoked", status="pending_cleanup", upload_expires_at=timezone.now() + timedelta(hours=1),
    )
    monkeypatch.setattr("apps.analytics.tasks.export_rows", lambda rows, export_format: b"abc")
    monkeypatch.setattr("apps.analytics.tasks.publish_generated_asset", lambda **kwargs: asset)

    result = run_export_job(str(job.id))

    job.refresh_from_db()
    assert result["published"] is False
    assert job.status == "pending" and job.result_asset_id is None


@pytest.mark.django_db
def test_worker_does_not_publish_ready_after_job_expires(doctor, monkeypatch):
    from datetime import timedelta
    import uuid

    job = ExportJob.objects.create(
        creator=doctor.user, normalized_filters={}, selected_ids=[], rows_snapshot=[], snapshot_count=0,
        format="csv", expires_at=timezone.now() + timedelta(minutes=1),
        idempotency_key="expire-during-publish", request_fingerprint="f" * 64,
    )
    asset = MediaAsset.objects.create(
        owner_type="export", owner_id=job.id, media_type="export", backend="qiniu",
        object_key=f"test/export/{uuid.uuid4().hex}", mime="text/csv", size=3,
        etag="late-ready", status="ready", upload_expires_at=timezone.now() + timedelta(hours=1),
    )

    def publish(**kwargs):
        ExportJob.objects.filter(pk=job.id).update(expires_at=timezone.now() - timedelta(seconds=1))
        return asset

    monkeypatch.setattr("apps.analytics.tasks.export_rows", lambda rows, export_format: b"abc")
    monkeypatch.setattr("apps.analytics.tasks.publish_generated_asset", publish)

    result = run_export_job(str(job.id))

    job.refresh_from_db(); asset.refresh_from_db()
    assert result["published"] is False and job.status == "expired"
    assert asset.status == "pending_cleanup"
