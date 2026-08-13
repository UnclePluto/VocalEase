from concurrent.futures import ThreadPoolExecutor

import pytest
from rest_framework.test import APIClient

from apps.accounts.models import RefreshToken, Role, User
from apps.accounts.tokens import issue_token_pair
from apps.audit.models import AuditLog
from apps.doctors.models import DoctorProfile
from apps.doctors.services import create_doctor, soft_delete_doctor
from apps.patients.services import create_patient


@pytest.fixture
def api_client():
    return APIClient()


@pytest.fixture
def admin_user(db):
    return User.objects.create_user(
        login_id="admin-domain", password="888888", role=Role.SYSTEM_ADMIN,
        must_change_password=False,
    )


@pytest.fixture
def doctor_user(db):
    return User.objects.create_user(
        login_id="doctor-domain", password="888888", role=Role.DOCTOR,
        must_change_password=False,
    )


@pytest.fixture
def doctor(db):
    return create_doctor(
        name="王医生", gender="male", phone="13800000001", department="康复科", title="主治医师",
    )


def doctor_payload(**overrides):
    payload = {
        "name": "李医生", "gender": "female", "phone": "13800000002",
        "department": "神经科", "title": "副主任医师",
    }
    payload.update(overrides)
    return payload


@pytest.mark.django_db
def test_create_doctor_generates_non_reusable_employee_number_initial_password_and_audit(
    api_client, admin_user
):
    api_client.force_authenticate(admin_user)

    response = api_client.post("/api/v1/admin/doctors/", doctor_payload(), format="json", HTTP_X_REQUEST_ID="doctor-create-1")

    assert response.status_code == 201
    created = DoctorProfile.objects.get(id=response.json()["data"]["id"])
    assert created.employee_no == "D0001"
    assert created.user.login_id == "D0001"
    assert created.user.check_password("888888")
    assert created.user.must_change_password is True
    assert AuditLog.objects.filter(action="doctor.create", target_id=created.id, request_id="doctor-create-1").exists()


@pytest.mark.django_db(transaction=True)
def test_concurrent_doctor_number_generation_does_not_duplicate_numbers():
    def create(index):
        return create_doctor(
            name=f"并发医生{index}", gender="male", phone=f"13900000{index:03d}", department="康复科", title="医师",
        ).employee_no

    with ThreadPoolExecutor(max_workers=4) as executor:
        numbers = list(executor.map(create, range(1, 5)))

    assert sorted(numbers) == ["D0001", "D0002", "D0003", "D0004"]


@pytest.mark.django_db
def test_doctor_list_supports_keyword_filter_and_pagination(api_client, admin_user, doctor):
    create_doctor(name="李医生", gender="female", phone="13800000003", department="神经科", title="医师")
    create_doctor(name="李护士", gender="female", phone="13800000004", department="神经科", title="护士")
    api_client.force_authenticate(admin_user)

    response = api_client.get("/api/v1/admin/doctors/?keyword=李&department=神经科&page=1&page_size=1")

    assert response.status_code == 200
    body = response.json()["data"]
    assert body["count"] == 2
    assert len(body["results"]) == 1
    assert body["page"] == 1
    assert body["page_size"] == 1


@pytest.mark.django_db
def test_doctor_and_admin_can_access_doctor_management_but_patient_is_rejected(api_client, admin_user, doctor_user, doctor):
    api_client.force_authenticate(admin_user)
    assert api_client.get("/api/v1/admin/doctors/").status_code == 200
    api_client.force_authenticate(doctor_user)
    assert api_client.get("/api/v1/admin/doctors/").status_code == 200
    patient = User.objects.create_user(login_id="patient-domain", password="888888", role=Role.PATIENT, must_change_password=False)
    api_client.force_authenticate(patient)
    assert api_client.get("/api/v1/admin/doctors/").status_code == 403


@pytest.mark.django_db
def test_delete_doctor_revokes_refresh_soft_deletes_user_and_writes_audit(admin_user, doctor):
    pair = issue_token_pair(doctor.user)

    soft_delete_doctor(actor=admin_user, doctor=doctor, request_id="doctor-delete-1")

    doctor.refresh_from_db()
    doctor.user.refresh_from_db()
    assert doctor.deleted_at is not None
    assert doctor.user.deleted_at is not None
    assert doctor.user.is_active is False
    assert RefreshToken.objects.get(token_hash=RefreshToken.digest(pair.refresh)).revoked_at is not None
    assert AuditLog.objects.filter(action="doctor.delete", target_id=doctor.id, request_id="doctor-delete-1").exists()


@pytest.mark.django_db
def test_delete_doctor_with_active_patients_returns_stable_error(api_client, admin_user, doctor):
    patient = create_patient(
        name="在治患者", gender="male", enrollment_age=30, phone="13700000001", doctor=doctor,
        start_date="2026-01-01", cycle_weeks=4,
    )
    patient.treatment_plans.update(status="active")
    api_client.force_authenticate(admin_user)

    response = api_client.delete(f"/api/v1/admin/doctors/{doctor.id}/")

    assert response.status_code == 409
    assert response.json()["code"] == "doctor_has_active_patients"
    doctor.refresh_from_db()
    assert doctor.deleted_at is None
