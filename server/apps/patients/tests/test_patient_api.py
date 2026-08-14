from concurrent.futures import ThreadPoolExecutor

import pytest
from django.db import IntegrityError, connection, connections, transaction
from rest_framework.exceptions import ValidationError
from rest_framework.test import APIClient

from apps.accounts.models import RefreshToken, Role, User
from apps.accounts.tokens import issue_token_pair
from apps.audit.models import AuditLog
from apps.doctors.models import SequenceCounter
from apps.doctors.services import create_doctor, soft_delete_doctor
from apps.patients.models import PatientProfile, TreatmentPlan
from apps.patients.selectors import patients_for_list
from apps.patients.serializers import PatientReadSerializer
from apps.patients.services import (
    create_patient,
    create_treatment_plan,
    soft_delete_patient,
    transition_treatment_plan_status,
    update_patient,
)


@pytest.fixture
def api_client():
    return APIClient()


@pytest.fixture(autouse=True)
def ensure_sequence_counters(db):
    SequenceCounter.objects.bulk_create(
        [SequenceCounter(prefix="D"), SequenceCounter(prefix="P")],
        ignore_conflicts=True,
    )


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
@pytest.mark.postgresql
def test_concurrent_patient_number_generation_does_not_duplicate_numbers(doctor):
    if connection.vendor != "postgresql":
        pytest.skip("并发编号由真实 PostgreSQL 行锁测试证明")
    def create(index):
        try:
            return create_patient(
                name=f"并发患者{index}", gender="male", enrollment_age=20 + index,
                phone=f"13700000{index:03d}", doctor=doctor, start_date="2026-01-01", cycle_weeks=1,
            ).medical_record_no
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=4) as executor:
        numbers = list(executor.map(create, range(1, 5)))

    assert sorted(numbers) == ["P000001", "P000002", "P000003", "P000004"]


@pytest.mark.django_db
def test_deleted_patient_number_is_not_reused(admin_user, doctor):
    first = create_patient(
        name="一号患者", gender="male", enrollment_age=20, phone="13510000001", doctor=doctor,
        start_date="2026-01-01", cycle_weeks=1,
    )
    soft_delete_patient(actor=admin_user, patient=first, request_id="delete-first-patient")

    second = create_patient(
        name="二号患者", gender="female", enrollment_age=21, phone="13510000002", doctor=doctor,
        start_date="2026-01-02", cycle_weeks=1,
    )

    assert first.medical_record_no == "P000001"
    assert second.medical_record_no == "P000002"


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
def test_patient_list_filters_real_treatment_status(api_client, admin_user, patient, doctor):
    patient.treatment_plans.update(status=TreatmentPlan.Status.ACTIVE)
    create_patient(
        name="待开始患者", gender="male", enrollment_age=33, phone="13500000004",
        doctor=doctor, start_date="2026-01-01", cycle_weeks=1,
    )
    api_client.force_authenticate(admin_user)

    response = api_client.get("/api/v1/admin/patients/?status=active")

    body = response.json()["data"]
    assert body["count"] == 1
    assert body["results"][0]["id"] == str(patient.id)
    assert body["results"][0]["user_id"] == str(patient.user_id)
    assert body["results"][0]["primary_doctor_name"] == doctor.name
    assert body["results"][0]["treatment_plan"]["status"] == "active"


@pytest.mark.django_db
def test_patient_status_filter_matches_the_current_plan(api_client, admin_user, patient):
    patient.treatment_plans.update(status=TreatmentPlan.Status.ACTIVE)
    create_treatment_plan(
        patient=patient,
        start_date="2026-03-01",
        cycle_weeks=4,
        status=TreatmentPlan.Status.PENDING,
    )
    api_client.force_authenticate(admin_user)

    active = api_client.get("/api/v1/admin/patients/?status=active")
    pending = api_client.get("/api/v1/admin/patients/?status=pending")

    assert active.status_code == 200
    assert active.json()["data"]["count"] == 0
    assert pending.status_code == 200
    assert pending.json()["data"]["count"] == 1
    assert pending.json()["data"]["results"][0]["treatment_plan"]["status"] == "pending"


@pytest.mark.django_db
def test_patient_list_serialization_uses_prefetched_plans_without_n_plus_one(
    doctor, patient, django_assert_num_queries
):
    create_patient(
        name="患者乙", gender="male", enrollment_age=33, phone="13500000004",
        doctor=doctor, start_date="2026-02-01", cycle_weeks=2,
    )
    patients = list(patients_for_list())

    with django_assert_num_queries(0):
        data = PatientReadSerializer(patients, many=True).data

    assert len(data) == 2


@pytest.mark.django_db
@pytest.mark.parametrize(
    ("query", "field"),
    [("page=bad", "page"), ("page_size=101", "page_size"), ("primary_doctor=bad-uuid", "primary_doctor")],
)
def test_patient_list_rejects_invalid_query_values(api_client, admin_user, query, field):
    api_client.force_authenticate(admin_user)

    response = api_client.get(f"/api/v1/admin/patients/?{query}")

    assert response.status_code == 400
    assert response.json()["code"] == "validation_error"
    assert field in response.json()["data"]


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
@pytest.mark.parametrize(("unsafe_state", "value"), [("is_active", False), ("role", Role.PATIENT)])
def test_patient_service_revalidates_primary_doctor_inside_transaction(patient, doctor, unsafe_state, value):
    setattr(doctor.user, unsafe_state, value)
    doctor.user.save(update_fields=[unsafe_state])

    with pytest.raises(ValidationError) as exc_info:
        update_patient(actor=None, patient=patient, request_id="unsafe-doctor", primary_doctor=doctor)

    assert exc_info.value.get_codes()["primary_doctor"] == "invalid"
    patient.refresh_from_db()
    assert patient.primary_doctor_id == doctor.id


@pytest.mark.django_db
def test_patient_patch_updates_current_plan_and_recalculates_target(api_client, doctor_user, patient):
    plan = patient.treatment_plans.get()
    plan.status = "active"
    plan.save(update_fields=["status"])
    api_client.force_authenticate(doctor_user)

    response = api_client.patch(
        f"/api/v1/admin/patients/{patient.id}/",
        {"start_date": "2026-04-01", "cycle_weeks": 8},
        format="json",
        HTTP_X_REQUEST_ID="plan-update-1",
    )

    assert response.status_code == 200
    plan.refresh_from_db()
    assert str(plan.start_date) == "2026-04-01"
    assert plan.cycle_weeks == 8
    assert plan.target_session_count == 24
    assert AuditLog.objects.filter(action="treatment_plan.update", target_id=plan.id, request_id="plan-update-1").exists()


@pytest.mark.django_db
def test_patient_patch_plan_fields_returns_stable_error_without_current_plan(api_client, doctor_user, patient):
    patient.treatment_plans.update(status="completed")
    api_client.force_authenticate(doctor_user)

    response = api_client.patch(
        f"/api/v1/admin/patients/{patient.id}/", {"cycle_weeks": 8}, format="json"
    )

    assert response.status_code == 409
    assert response.json()["code"] == "patient_has_no_current_treatment_plan"


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
def test_treatment_plan_activation_rejects_deleted_doctor(patient, doctor):
    plan = patient.treatment_plans.get()
    doctor.deleted_at = __import__("django.utils.timezone", fromlist=["now"]).now()
    doctor.save(update_fields=["deleted_at"])

    with pytest.raises(ValidationError) as exc_info:
        transition_treatment_plan_status(
            actor=None,
            plan=plan,
            status=TreatmentPlan.Status.ACTIVE,
            request_id="activate-deleted-doctor",
        )

    assert exc_info.value.get_codes()["primary_doctor"] == "invalid"
    plan.refresh_from_db()
    assert plan.status == TreatmentPlan.Status.PENDING


@pytest.mark.django_db
def test_create_plan_uses_current_database_doctor_not_stale_patient_relation(admin_user, patient):
    stale_patient = PatientProfile.objects.get(pk=patient.pk)
    current_doctor = create_doctor(
        name="现主治医生", gender="female", phone="13600000009", department="康复科", title="医师"
    )
    PatientProfile.objects.filter(pk=patient.pk).update(primary_doctor=current_doctor)
    soft_delete_doctor(
        actor=admin_user,
        doctor=current_doctor,
        request_id="delete-current-doctor-before-plan-create",
    )

    with pytest.raises(ValidationError) as exc_info:
        create_treatment_plan(
            patient=stale_patient,
            start_date="2026-05-01",
            cycle_weeks=2,
            status=TreatmentPlan.Status.ACTIVE,
        )

    assert exc_info.value.get_codes()["primary_doctor"] == "invalid"
    assert patient.treatment_plans.count() == 1


@pytest.mark.django_db
def test_patient_update_without_reassignment_uses_current_database_doctor(admin_user, patient):
    stale_patient = PatientProfile.objects.get(pk=patient.pk)
    current_doctor = create_doctor(
        name="已删现主治", gender="male", phone="13600000008", department="康复科", title="医师"
    )
    PatientProfile.objects.filter(pk=patient.pk).update(primary_doctor=current_doctor)
    soft_delete_doctor(
        actor=admin_user,
        doctor=current_doctor,
        request_id="delete-current-doctor-before-patient-patch",
    )

    with pytest.raises(ValidationError) as exc_info:
        update_patient(
            actor=admin_user,
            patient=stale_patient,
            request_id="stale-patient-patch",
            phone="13500000888",
        )

    assert exc_info.value.get_codes()["primary_doctor"] == "invalid"
    patient.refresh_from_db()
    assert patient.phone == "13500000001"


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
    plan = patient.treatment_plans.get()
    assert plan.deleted_at is not None
    assert not TreatmentPlan.objects.filter(
        pk=plan.pk,
        deleted_at__isnull=True,
        status__in=[TreatmentPlan.Status.PENDING, TreatmentPlan.Status.ACTIVE],
    ).exists()
    assert RefreshToken.objects.get(token_hash=RefreshToken.digest(pair.refresh)).revoked_at is not None
    assert AuditLog.objects.filter(action="patient.delete", target_id=patient.id, request_id="patient-delete-1").exists()


@pytest.mark.django_db
def test_patient_deletion_closes_current_plans_and_allows_doctor_deletion(admin_user, patient, doctor):
    plan = patient.treatment_plans.get()
    transition_treatment_plan_status(
        actor=admin_user,
        plan=plan,
        status=TreatmentPlan.Status.ACTIVE,
        request_id="activate-before-patient-delete",
    )

    soft_delete_patient(actor=admin_user, patient=patient, request_id="patient-delete-before-doctor")
    soft_delete_doctor(actor=admin_user, doctor=doctor, request_id="doctor-delete-after-patient")

    plan.refresh_from_db()
    doctor.refresh_from_db()
    assert plan.deleted_at is not None
    assert doctor.deleted_at is not None


@pytest.mark.django_db
def test_patient_with_pending_plan_can_be_deleted_after_doctor_deletion(admin_user, patient, doctor):
    soft_delete_doctor(actor=admin_user, doctor=doctor, request_id="doctor-delete-before-patient")

    soft_delete_patient(actor=admin_user, patient=patient, request_id="patient-delete-after-doctor")

    patient.refresh_from_db()
    assert patient.deleted_at is not None
    assert patient.treatment_plans.get().deleted_at is not None


@pytest.mark.django_db
def test_patient_update_records_audit(api_client, doctor_user, patient):
    api_client.force_authenticate(doctor_user)

    response = api_client.patch(f"/api/v1/admin/patients/{patient.id}/", {"phone": "13500000999", "notes": "复诊记录"}, format="json", HTTP_X_REQUEST_ID="patient-update-1")

    assert response.status_code == 200
    patient.refresh_from_db()
    assert patient.phone == "13500000999"
    audit = AuditLog.objects.get(action="patient.update", target_id=patient.id)
    assert audit.changes["medical_notes"].startswith("[MEDICAL_CONTENT_REDACTED")
