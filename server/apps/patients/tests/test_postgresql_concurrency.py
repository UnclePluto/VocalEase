from concurrent.futures import ThreadPoolExecutor
from threading import Event

import pytest
from django.db import connection, connections

from apps.accounts.models import Role, User
from apps.doctors.models import DoctorProfile
from apps.doctors.services import create_doctor, soft_delete_doctor
from apps.patients.models import PatientProfile
from apps.patients.services import create_patient, update_patient


pytestmark = [pytest.mark.django_db(transaction=True), pytest.mark.postgresql]


@pytest.fixture(autouse=True)
def require_postgresql():
    if connection.vendor != "postgresql":
        pytest.skip("仅用于真实 PostgreSQL 行锁验证")
    from apps.doctors.models import SequenceCounter

    SequenceCounter.objects.bulk_create(
        [SequenceCounter(prefix="D"), SequenceCounter(prefix="P")],
        ignore_conflicts=True,
    )


@pytest.fixture
def admin_user():
    return User.objects.create_user(
        login_id="pg-race-admin", password="888888", role=Role.SYSTEM_ADMIN,
        must_change_password=False,
    )


def _doctor(index):
    return create_doctor(
        name=f"竞态医生{index}", gender="male", phone=f"13890000{index:03d}",
        department="康复科", title="医师",
    )


def test_postgresql_sequence_rows_are_preseeded_and_concurrent_numbers_are_unique():
    from apps.doctors.models import SequenceCounter

    assert set(SequenceCounter.objects.values_list("prefix", flat=True)) == {"D", "P"}

    with ThreadPoolExecutor(max_workers=4) as executor:
        numbers = list(executor.map(lambda index: _doctor(index).employee_no, range(1, 5)))

    assert sorted(numbers) == ["D0001", "D0002", "D0003", "D0004"]

    doctor = DoctorProfile.objects.order_by("employee_no").first()
    with ThreadPoolExecutor(max_workers=4) as executor:
        patient_numbers = list(executor.map(
            lambda index: create_patient(
                name=f"编号患者{index}", gender="female", enrollment_age=20 + index,
                phone=f"13780000{index:03d}", doctor=doctor,
                start_date="2026-01-01", cycle_weeks=1,
            ).medical_record_no,
            range(1, 5),
        ))

    assert sorted(patient_numbers) == ["P000001", "P000002", "P000003", "P000004"]


def test_patient_creation_waits_for_doctor_delete_and_rejects_deleted_doctor(monkeypatch, admin_user):
    doctor = _doctor(1)
    delete_locked = Event()
    allow_delete = Event()
    original = __import__("apps.doctors.services", fromlist=["update_account_security_state"]).update_account_security_state

    def pause_after_doctor_lock(**kwargs):
        delete_locked.set()
        assert allow_delete.wait(timeout=5)
        return original(**kwargs)

    monkeypatch.setattr("apps.doctors.services.update_account_security_state", pause_after_doctor_lock)

    def delete():
        try:
            soft_delete_doctor(actor=admin_user, doctor=doctor, request_id="pg-delete-create-race")
        finally:
            connections.close_all()

    def create():
        try:
            return create_patient(
                name="竞态患者", gender="female", enrollment_age=30, phone="13790000001",
                doctor=doctor, start_date="2026-01-01", cycle_weeks=4,
            )
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as executor:
        deleting = executor.submit(delete)
        assert delete_locked.wait(timeout=5)
        creating = executor.submit(create)
        allow_delete.set()
        deleting.result(timeout=5)
        with pytest.raises(Exception):
            creating.result(timeout=5)

    assert not PatientProfile.objects.filter(primary_doctor=doctor).exists()


def test_patient_update_waits_for_doctor_delete_and_keeps_original_doctor(monkeypatch, admin_user):
    original_doctor = _doctor(1)
    deleting_doctor = _doctor(2)
    patient = create_patient(
        name="既有患者", gender="female", enrollment_age=30, phone="13790000002",
        doctor=original_doctor, start_date="2026-01-01", cycle_weeks=4,
    )
    delete_locked = Event()
    allow_delete = Event()
    original = __import__("apps.doctors.services", fromlist=["update_account_security_state"]).update_account_security_state

    def pause_after_doctor_lock(**kwargs):
        delete_locked.set()
        assert allow_delete.wait(timeout=5)
        return original(**kwargs)

    monkeypatch.setattr("apps.doctors.services.update_account_security_state", pause_after_doctor_lock)

    def delete():
        try:
            return soft_delete_doctor(
                actor=admin_user,
                doctor=deleting_doctor,
                request_id="pg-delete-update-race",
            )
        finally:
            connections.close_all()

    def update():
        try:
            return update_patient(
                actor=admin_user,
                patient=patient,
                request_id="pg-patient-update-race",
                primary_doctor=deleting_doctor,
            )
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as executor:
        deleting = executor.submit(delete)
        assert delete_locked.wait(timeout=5)
        updating = executor.submit(update)
        allow_delete.set()
        deleting.result(timeout=5)
        with pytest.raises(Exception):
            updating.result(timeout=5)

    patient.refresh_from_db()
    assert patient.primary_doctor_id == original_doctor.id
