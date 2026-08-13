from concurrent.futures import ThreadPoolExecutor

import pytest
from django.db import IntegrityError, transaction
from rest_framework.test import APIClient

from apps.accounts.models import RefreshToken, Role, User
from apps.accounts.tokens import issue_token_pair
from apps.audit.models import AuditLog
from apps.doctors.services import create_doctor
from apps.patients.models import PatientProfile, TreatmentPlan
from apps.patients.services import create_patient, soft_delete_patient


@pytest.fixture
def api_client():
    return APIClient()


@pytest.fixture
def admin_user(db):
    return User.objects.create_user(login_id="admin-patient", password="888888", role=Role.SYSTEM_ADMIN, must_change_password=False)


@pytest.fixture
def doctor_user(db):
    return User.objects.create_user(login_id="doctor-patient", password="888888", role=Role.DOCTOR, must_change_password=False)


@pytest.fixture
def doctor(db):
    return create_doctor(name="赵医生", gender="male", phone="13600000001", department="康复科", title="医师")


@pytest.fixture
def patient(doctor):
    return create_patient(
        name="患者甲", gender="female", enrollment_age=42, phone="13500000001", doctor=doctor,
        start_date="2026-01-01", cycle_weeks=6, notes="初诊记录",
    )


def patient_payload(doctor, **overrides):
    payload = {
        "name": "患者乙", "gender": "male", "enrollment_age": 31, "phone": "13500000002",
        "primary_doctor": str(doctor.id), "start_date": "2026-02-01", "cycle_weeks": 4, "notes": "",
    }
    payload.update(overrides)
    return payload


@pytest.mark.django_db
def test_create_patient_generates_medical_record_number_plan_target_and_audit(api_client, admin_user, doctor):
    api_client.force_authenticate(admin_user)

    response = api_client.post("/api/v1/admin/patients/", patient_payload(doctor), format="json", HTTP_X_REQUEST_ID="patient-create-1")

    assert response.status_code == 201
    patient = PatientProfile.objects.get(id=response.json()["data"]["id"])
    assert patient.medical_record_no == "P000001"
    assert patient.user.login_id == "P000001"
    assert patient.user.check_password("888888")
    assert patient.user.must_change_password is True
    plan = patient.treatment_plans.get()
    assert plan.target_session_count == 12
    assert plan.status == "pending"
    assert AuditLog.objects.filter(action="patient.create", target_id=patient.id, request_id="patient-create-1").exists()


@pytest.mark.django_db(transaction=True)
def test_concurrent_patient_number_generation_does_not_duplicate_numbers(doctor):
    def create(index):
        return create_patient(
            name=f"并发患者{index}", gender="male", enrollment_age=20 + index,
            phone=f"13700000{index:03d}", doctor=doctor, start_date="2026-01-01", cycle_weeks=1,
        ).medical_record_no

    with ThreadPoolExecutor(max_workers=4) as executor:
        numbers = list(executor.map(create, range(1, 5)))

    assert sorted(numbers) == ["P000001", "P000002", "P000003", "P000004"]


@pytest.mark.django_db
def test_system_admin_and_doctor_can_list_all_patients(api_client, admin_user, doctor_user, patient, doctor):
    create_patient(name="患者乙", gender="male", enrollment_age=33, phone="13500000003", doctor=doctor, start_date="2026-01-01", cycle_weeks=1)
    api_client.force_authenticate(admin_user)
    assert api_client.get("/api/v1/admin/patients/").json()["data"]["count"] == 2
    api_client.force_authenticate(doctor_user)
    response = api_client.get("/api/v1/admin/patients/")
    assert response.status_code == 200
    assert response.json()["data"]["count"] == 2


@pytest.mark.django_db
def test_patient_list_supports_filters_and_pagination(api_client, admin_user, patient, doctor):
    create_patient(name="患者乙", gender="male", enrollment_age=33, phone="13500000003", doctor=doctor, start_date="2026-01-01", cycle_weeks=1)
    api_client.force_authenticate(admin_user)

    response = api_client.get(f"/api/v1/admin/patients/?gender=male&primary_doctor={doctor.id}&page=1&page_size=1")

    assert response.status_code == 200
    body = response.json()["data"]
    assert body["count"] == 1
    assert body["results"][0]["name"] == "患者乙"
    assert body["page"] == 1
    assert body["page_size"] == 1


@pytest.mark.django_db
def test_patient_role_is_rejected_from_admin_patient_list(api_client, patient):
    patient.user.must_change_password = False
    patient.user.save(update_fields=["must_change_password"])
    api_client.force_authenticate(patient.user)

    assert api_client.get("/api/v1/admin/patients/").status_code == 403


@pytest.mark.django_db
def test_patient_primary_doctor_must_be_an_active_doctor_profile(api_client, admin_user):
    non_doctor = User.objects.create_user(login_id="not-a-doctor", password="888888", role=Role.DOCTOR, must_change_password=False)
    api_client.force_authenticate(admin_user)

    response = api_client.post("/api/v1/admin/patients/", patient_payload(type("Ref", (), {"id": non_doctor.id})()), format="json")

    assert response.status_code == 400
    assert response.json()["code"] == "validation_error"


@pytest.mark.django_db
def test_patient_update_rejects_deleted_primary_doctor(api_client, admin_user, patient, doctor):
    doctor.deleted_at = __import__("django.utils.timezone", fromlist=["now"]).now()
    doctor.save(update_fields=["deleted_at"])
    api_client.force_authenticate(admin_user)

    response = api_client.patch(f"/api/v1/admin/patients/{patient.id}/", {"primary_doctor": str(doctor.id)}, format="json")

    assert response.status_code == 400


@pytest.mark.django_db
def test_patient_has_at_most_one_active_treatment_plan(patient):
    existing = patient.treatment_plans.get()
    existing.status = "active"
    existing.save(update_fields=["status"])

    with pytest.raises(IntegrityError):
        with transaction.atomic():
            TreatmentPlan.objects.create(
                patient=patient, start_date="2026-03-01", cycle_weeks=4,
                target_session_count=12, status="active",
            )


@pytest.mark.django_db
def test_delete_patient_keeps_history_root_revokes_refresh_and_audits(api_client, doctor_user, patient):
    pair = issue_token_pair(patient.user)
    api_client.force_authenticate(doctor_user)

    response = api_client.delete(f"/api/v1/admin/patients/{patient.id}/", HTTP_X_REQUEST_ID="patient-delete-1")

    patient.refresh_from_db()
    patient.user.refresh_from_db()
    assert response.status_code == 204
    assert patient.deleted_at is not None
    assert patient.user.is_active is False
    assert patient.user.deleted_at is not None
    assert RefreshToken.objects.get(token_hash=RefreshToken.digest(pair.refresh)).revoked_at is not None
    assert AuditLog.objects.filter(action="patient.delete", target_id=patient.id, request_id="patient-delete-1").exists()


@pytest.mark.django_db
def test_patient_update_records_audit(api_client, doctor_user, patient):
    api_client.force_authenticate(doctor_user)

    response = api_client.patch(f"/api/v1/admin/patients/{patient.id}/", {"phone": "13500000999", "notes": "复诊记录"}, format="json", HTTP_X_REQUEST_ID="patient-update-1")

    assert response.status_code == 200
    patient.refresh_from_db()
    assert patient.phone == "13500000999"
    audit = AuditLog.objects.get(action="patient.update", target_id=patient.id)
    assert audit.changes["medical_notes"].startswith("[MEDICAL_CONTENT_REDACTED")
