from concurrent.futures import ThreadPoolExecutor
import json
from threading import Barrier

import pytest
from django.db import IntegrityError, OperationalError, connection, connections
from rest_framework.exceptions import ValidationError
from rest_framework.test import APIClient

from apps.accounts.models import RefreshToken, Role, User
from apps.accounts.tokens import issue_token_pair
from apps.audit.models import AuditLog
from apps.doctors.models import DoctorProfile, SequenceCounter
from apps.doctors.services import create_doctor, next_sequence, run_with_database_retry, soft_delete_doctor
from apps.patients.services import create_patient


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
@pytest.mark.postgresql
def test_concurrent_doctor_number_generation_does_not_duplicate_numbers():
    if connection.vendor != "postgresql":
        pytest.skip("并发编号由真实 PostgreSQL 行锁测试证明")
    def create(index):
        try:
            return create_doctor(
                name=f"并发医生{index}", gender="male", phone=f"13900000{index:03d}", department="康复科", title="医师",
            ).employee_no
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=4) as executor:
        numbers = list(executor.map(create, range(1, 5)))

    assert sorted(numbers) == ["D0001", "D0002", "D0003", "D0004"]


@pytest.mark.django_db
def test_deleted_doctor_number_is_not_reused(admin_user):
    first = create_doctor(name="一号医生", gender="male", phone="13810000001", department="康复科", title="医师")
    soft_delete_doctor(actor=admin_user, doctor=first, request_id="delete-first-doctor")

    second = create_doctor(name="二号医生", gender="female", phone="13810000002", department="康复科", title="医师")

    assert first.employee_no == "D0001"
    assert second.employee_no == "D0002"


@pytest.mark.django_db
@pytest.mark.parametrize(
    ("error", "message"),
    [
        (IntegrityError("counter constraint failed"), "counter constraint failed"),
        (OperationalError("disk I/O error"), "disk I/O error"),
    ],
)
def test_sequence_does_not_retry_non_retryable_database_errors(monkeypatch, error, message):
    calls = 0

    def fail_save(instance, *args, **kwargs):
        nonlocal calls
        calls += 1
        raise error

    monkeypatch.setattr(SequenceCounter, "save", fail_save)

    with pytest.raises(type(error), match=message):
        next_sequence("D", width=4)

    assert calls == 1


@pytest.mark.django_db
def test_database_retry_retries_the_exact_transient_sqlite_lock():
    if connection.vendor != "sqlite":
        pytest.skip("该回归只验证测试 SQLite 的精确锁重试分支")
    calls = 0

    def fail_once():
        nonlocal calls
        calls += 1
        if calls == 1:
            raise OperationalError("database is locked")
        return "ok"

    assert run_with_database_retry(fail_once) == "ok"
    assert calls == 2


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
def test_doctor_list_exposes_account_identity_and_filters_status(api_client, admin_user, doctor):
    inactive = create_doctor(
        name="停用医生", gender="female", phone="13800000005", department="康复科", title="医师"
    )
    inactive.user.is_active = False
    inactive.user.save(update_fields=["is_active"])
    api_client.force_authenticate(admin_user)

    response = api_client.get("/api/v1/admin/doctors/?status=inactive")

    assert response.status_code == 200
    body = response.json()["data"]
    assert body["count"] == 1
    assert body["results"] == [
        {
            "id": str(inactive.id),
            "user_id": str(inactive.user_id),
            "employee_no": inactive.employee_no,
            "name": "停用医生",
            "gender": "female",
            "phone": "138****0005",
            "department": "康复科",
            "title": "医师",
            "status": "inactive",
        }
    ]


@pytest.mark.django_db
def test_doctor_list_masks_phone_but_authorized_detail_returns_full_phone(
    api_client, admin_user, doctor
):
    api_client.force_authenticate(admin_user)

    listed = api_client.get("/api/v1/admin/doctors/")
    detailed = api_client.get(f"/api/v1/admin/doctors/{doctor.id}/")

    assert listed.status_code == detailed.status_code == 200
    assert listed.json()["data"]["results"][0]["phone"] == "138****0001"
    assert detailed.json()["data"]["phone"] == "13800000001"


@pytest.mark.django_db
def test_doctor_option_lookup_returns_only_label_fields_and_enforces_permissions(
    api_client, admin_user, doctor
):
    doctor.user.is_active = False
    doctor.user.save(update_fields=["is_active"])
    api_client.force_authenticate(admin_user)

    response = api_client.get(f"/api/v1/admin/doctors/{doctor.id}/option/")

    assert response.status_code == 200
    assert response.json()["data"] == {
        "id": str(doctor.id),
        "name": "王医生",
        "employee_no": doctor.employee_no,
    }

    patient = User.objects.create_user(
        login_id="patient-doctor-option",
        password="888888",
        role=Role.PATIENT,
        must_change_password=False,
    )
    api_client.force_authenticate(patient)
    assert api_client.get(f"/api/v1/admin/doctors/{doctor.id}/option/").status_code == 403

    doctor.deleted_at = __import__("django.utils.timezone", fromlist=["now"]).now()
    doctor.save(update_fields=["deleted_at"])
    api_client.force_authenticate(admin_user)
    assert api_client.get(f"/api/v1/admin/doctors/{doctor.id}/option/").status_code == 404


@pytest.mark.django_db
def test_doctor_phone_is_normalized_and_globally_unique_including_deleted_records(
    api_client, admin_user, doctor
):
    api_client.force_authenticate(admin_user)

    duplicate = api_client.post(
        "/api/v1/admin/doctors/",
        doctor_payload(phone=" 138-0000-0001 "),
        format="json",
    )

    assert duplicate.status_code == 400
    assert duplicate.json()["code"] == "validation_error"
    assert duplicate.json()["data"]["phone"] == ["手机号已存在"]
    doctor.deleted_at = __import__("django.utils.timezone", fromlist=["now"]).now()
    doctor.save(update_fields=["deleted_at"])
    still_duplicate = api_client.post(
        "/api/v1/admin/doctors/", doctor_payload(phone="13800000001"), format="json"
    )
    assert still_duplicate.status_code == 400
    assert still_duplicate.json()["data"]["phone"] == ["手机号已存在"]


@pytest.mark.django_db
def test_doctor_database_rejects_duplicate_phone_even_without_serializer(doctor):
    with pytest.raises(ValidationError) as exc_info:
        create_doctor(
            name="重复手机号医生",
            gender="female",
            phone="13800000001",
            department="康复科",
            title="医师",
        )

    assert exc_info.value.detail == {"phone": ["手机号已存在"]}


@pytest.mark.django_db(transaction=True)
@pytest.mark.postgresql
def test_postgresql_concurrent_same_normalized_doctor_phone_allows_only_one():
    if connection.vendor != "postgresql":
        pytest.skip("手机号并发唯一性由真实 PostgreSQL 约束证明")
    gate = Barrier(2)

    def create(index):
        try:
            gate.wait(timeout=5)
            return create_doctor(
                name=f"手机号竞态医生{index}",
                gender="male",
                phone="139-0000-0999" if index == 1 else "13900000999",
                department="康复科",
                title="医师",
            )
        except Exception as exc:  # outcome is asserted below
            return exc
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = list(executor.map(create, (1, 2)))

    assert sum(isinstance(value, DoctorProfile) for value in outcomes) == 1
    errors = [value for value in outcomes if not isinstance(value, DoctorProfile)]
    assert len(errors) == 1
    assert getattr(errors[0], "detail", None) == {"phone": ["手机号已存在"]}


@pytest.mark.django_db
def test_doctor_create_and_update_audit_never_store_full_phone(admin_user, doctor):
    created = create_doctor(
        actor=admin_user,
        request_id="doctor-private-create",
        name="隐私医生",
        gender="female",
        phone="13712345678",
        department="康复科",
        title="医师",
    )
    update_doctor = __import__("apps.doctors.services", fromlist=["update_doctor"]).update_doctor
    update_doctor(
        actor=admin_user,
        doctor=created,
        request_id="doctor-private-update",
        phone="13687654321",
    )

    payload = json.dumps(
        list(
            AuditLog.objects.filter(
                request_id__in=["doctor-private-create", "doctor-private-update"]
            ).values_list("changes", flat=True)
        ),
        ensure_ascii=False,
    )
    assert "13712345678" not in payload
    assert "13687654321" not in payload
    assert "changed_fields" in payload


@pytest.mark.django_db
@pytest.mark.parametrize(("query", "field"), [("page=not-a-number", "page"), ("page_size=0", "page_size")])
def test_doctor_list_rejects_invalid_pagination(api_client, admin_user, query, field):
    api_client.force_authenticate(admin_user)

    response = api_client.get(f"/api/v1/admin/doctors/?{query}")

    assert response.status_code == 400
    assert response.json()["code"] == "validation_error"
    assert field in response.json()["data"]


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
def test_admin_can_deactivate_and_activate_doctor_with_audit_and_session_revocation(
    api_client, admin_user, doctor
):
    pair = issue_token_pair(doctor.user)
    api_client.force_authenticate(admin_user)

    deactivated = api_client.post(
        f"/api/v1/admin/doctors/{doctor.id}/deactivate/",
        HTTP_X_REQUEST_ID="doctor-deactivate-1",
    )

    assert deactivated.status_code == 200
    assert deactivated.json()["data"]["status"] == "inactive"
    assert RefreshToken.objects.get(
        token_hash=RefreshToken.digest(pair.refresh)
    ).revoked_at is not None
    assert AuditLog.objects.filter(
        action="doctor.deactivate",
        target_id=doctor.id,
        request_id="doctor-deactivate-1",
    ).exists()

    activated = api_client.post(
        f"/api/v1/admin/doctors/{doctor.id}/activate/",
        HTTP_X_REQUEST_ID="doctor-activate-1",
    )

    assert activated.status_code == 200
    assert activated.json()["data"]["status"] == "active"
    assert AuditLog.objects.filter(
        action="doctor.activate",
        target_id=doctor.id,
        request_id="doctor-activate-1",
    ).exists()
    assert RefreshToken.objects.get(
        token_hash=RefreshToken.digest(pair.refresh)
    ).revoked_at is not None


@pytest.mark.django_db
def test_doctor_status_actions_are_idempotent_without_duplicate_audit(
    api_client, admin_user, doctor
):
    api_client.force_authenticate(admin_user)

    response = api_client.post(
        f"/api/v1/admin/doctors/{doctor.id}/activate/",
        HTTP_X_REQUEST_ID="doctor-activate-noop",
    )

    assert response.status_code == 200
    assert response.json()["data"]["status"] == "active"
    assert not AuditLog.objects.filter(
        action="doctor.activate", request_id="doctor-activate-noop"
    ).exists()

    changed = api_client.post(
        f"/api/v1/admin/doctors/{doctor.id}/deactivate/",
        HTTP_X_REQUEST_ID="doctor-deactivate-changed",
    )
    no_op = api_client.post(
        f"/api/v1/admin/doctors/{doctor.id}/deactivate/",
        HTTP_X_REQUEST_ID="doctor-deactivate-noop",
    )

    assert changed.status_code == 200
    assert no_op.status_code == 200
    assert no_op.json()["data"]["status"] == "inactive"
    assert AuditLog.objects.filter(
        action="doctor.deactivate", request_id="doctor-deactivate-changed"
    ).count() == 1
    assert not AuditLog.objects.filter(
        action="doctor.deactivate", request_id="doctor-deactivate-noop"
    ).exists()


@pytest.mark.django_db
def test_deactivate_doctor_with_active_patients_returns_same_stable_conflict(
    api_client, admin_user, doctor
):
    patient = create_patient(
        name="在治患者",
        gender="male",
        enrollment_age=30,
        phone="13700000011",
        doctor=doctor,
        start_date="2026-01-01",
        cycle_weeks=4,
    )
    patient.treatment_plans.update(status="active")
    api_client.force_authenticate(admin_user)

    response = api_client.post(f"/api/v1/admin/doctors/{doctor.id}/deactivate/")

    assert response.status_code == 409
    assert response.json()["code"] == "doctor_has_active_patients"
    assert response.json()["message"] == "该医生仍有进行中的患者，不能停用"
    doctor.user.refresh_from_db()
    assert doctor.user.is_active is True


@pytest.mark.django_db
def test_doctor_cannot_deactivate_self_or_system_admin_and_patient_is_rejected(
    api_client, doctor_user, doctor
):
    self_profile = DoctorProfile.objects.create(
        user=doctor_user,
        employee_no="D9001",
        name="当前医生",
        gender="male",
        phone="13800009991",
        department="康复科",
        title="医师",
    )
    admin_target = create_doctor(
        name="管理员档案",
        gender="female",
        phone="13800009992",
        department="管理科",
        title="管理员",
    )
    admin_target.user.role = Role.SYSTEM_ADMIN
    admin_target.user.save(update_fields=["role"])
    api_client.force_authenticate(doctor_user)

    self_response = api_client.post(
        f"/api/v1/admin/doctors/{self_profile.id}/deactivate/"
    )
    admin_response = api_client.post(
        f"/api/v1/admin/doctors/{admin_target.id}/deactivate/"
    )

    assert self_response.status_code == 403
    assert self_response.json()["code"] == "doctor_status_self_forbidden"
    assert admin_response.status_code == 403
    assert admin_response.json()["code"] == "doctor_status_target_forbidden"

    patient_user = User.objects.create_user(
        login_id="status-patient",
        password="888888",
        role=Role.PATIENT,
        must_change_password=False,
    )
    api_client.force_authenticate(patient_user)
    assert api_client.post(
        f"/api/v1/admin/doctors/{doctor.id}/deactivate/"
    ).status_code == 403


@pytest.mark.django_db
def test_status_action_validates_profile_user_is_really_a_doctor(
    api_client, admin_user
):
    invalid_target = create_doctor(
        name="异常患者档案",
        gender="female",
        phone="13800009993",
        department="康复科",
        title="医师",
    )
    invalid_target.user.role = Role.PATIENT
    invalid_target.user.save(update_fields=["role"])
    api_client.force_authenticate(admin_user)

    response = api_client.post(
        f"/api/v1/admin/doctors/{invalid_target.id}/deactivate/"
    )

    assert response.status_code == 403
    assert response.json()["code"] == "doctor_status_target_invalid"
    invalid_target.user.refresh_from_db()
    assert invalid_target.user.is_active is True


@pytest.mark.django_db
def test_deleted_doctor_cannot_be_activated(api_client, admin_user, doctor):
    soft_delete_doctor(
        actor=admin_user, doctor=doctor, request_id="doctor-delete-before-activate"
    )
    api_client.force_authenticate(admin_user)

    response = api_client.post(f"/api/v1/admin/doctors/{doctor.id}/activate/")

    assert response.status_code == 404


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


@pytest.mark.django_db
def test_deleted_active_plan_does_not_block_doctor_deletion(api_client, admin_user, doctor):
    patient = create_patient(
        name="历史患者", gender="male", enrollment_age=30, phone="13700000009", doctor=doctor,
        start_date="2026-01-01", cycle_weeks=4,
    )
    plan = patient.treatment_plans.get()
    plan.status = "active"
    plan.deleted_at = __import__("django.utils.timezone", fromlist=["now"]).now()
    plan.save(update_fields=["status", "deleted_at"])
    api_client.force_authenticate(admin_user)

    response = api_client.delete(f"/api/v1/admin/doctors/{doctor.id}/")

    assert response.status_code == 204


@pytest.mark.django_db
def test_deleted_patient_history_does_not_block_doctor_deletion(api_client, admin_user, doctor):
    patient = create_patient(
        name="已归档患者", gender="male", enrollment_age=30, phone="13700000010", doctor=doctor,
        start_date="2026-01-01", cycle_weeks=4,
    )
    patient.treatment_plans.update(status="active")
    patient.deleted_at = __import__("django.utils.timezone", fromlist=["now"]).now()
    patient.save(update_fields=["deleted_at"])
    api_client.force_authenticate(admin_user)

    response = api_client.delete(f"/api/v1/admin/doctors/{doctor.id}/")

    assert response.status_code == 204
