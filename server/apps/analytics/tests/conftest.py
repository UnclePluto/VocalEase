import pytest

from apps.doctors.models import SequenceCounter
from apps.doctors.services import create_doctor
from apps.patients.services import create_patient, transition_treatment_plan_status


@pytest.fixture
def doctor(db):
    SequenceCounter.objects.bulk_create(
        [SequenceCounter(prefix="D"), SequenceCounter(prefix="P")],
        ignore_conflicts=True,
    )
    profile = create_doctor(
        name="统计医生", gender="female", phone="13600000901",
        department="康复科", title="医师",
    )
    profile.user.must_change_password = False
    profile.user.save(update_fields=["must_change_password"])
    return profile


def _patient(*, doctor, name, phone):
    profile = create_patient(
        name=name, gender="male", enrollment_age=36, phone=phone,
        doctor=doctor, start_date="2026-08-01", cycle_weeks=4,
    )
    profile.user.must_change_password = False
    profile.user.save(update_fields=["must_change_password"])
    plan = profile.treatment_plans.get()
    transition_treatment_plan_status(
        actor=doctor.user, plan=plan, status="active", request_id=f"activate-{profile.id}",
    )
    return profile


@pytest.fixture
def patient(doctor):
    return _patient(doctor=doctor, name="患者甲", phone="13500000901")


@pytest.fixture
def other_patient(doctor):
    return _patient(doctor=doctor, name="患者乙", phone="13500000902")
