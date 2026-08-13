import time

from django.db import OperationalError, transaction
from django.utils import timezone

from apps.accounts.models import Role, User
from apps.accounts.services import update_account_security_state
from apps.audit.services import record
from apps.doctors.services import next_sequence

from .models import PatientProfile, TreatmentPlan


def _create_patient_once(*, name, gender, enrollment_age, phone, doctor, start_date, cycle_weeks, notes, actor, request_id):
    with transaction.atomic():
        medical_record_no = next_sequence("P", width=6)
        user = User.objects.create_user(login_id=medical_record_no, role=Role.PATIENT, password="888888")
        patient = PatientProfile.objects.create(
            user=user,
            medical_record_no=medical_record_no,
            name=name,
            gender=gender,
            enrollment_age=enrollment_age,
            phone=phone,
            primary_doctor=doctor,
            notes=notes,
        )
        TreatmentPlan.objects.create(
            patient=patient,
            start_date=start_date,
            cycle_weeks=cycle_weeks,
            target_session_count=cycle_weeks * 3,
            status=TreatmentPlan.Status.PENDING,
        )
        record(
            actor=actor,
            action="patient.create",
            target=patient,
            changes={"medical_record_no": medical_record_no, "name": name, "gender": gender, "enrollment_age": enrollment_age, "phone": phone, "primary_doctor": str(doctor.id), "medical_notes": notes},
            request_id=request_id,
        )
        return patient


def create_patient(*, name, gender, enrollment_age, phone, doctor, start_date, cycle_weeks, notes="", actor=None, request_id=""):
    for attempt in range(20):
        try:
            return _create_patient_once(
                name=name, gender=gender, enrollment_age=enrollment_age, phone=phone, doctor=doctor,
                start_date=start_date, cycle_weeks=cycle_weeks, notes=notes, actor=actor, request_id=request_id,
            )
        except OperationalError:
            if attempt == 19:
                raise
            time.sleep(0.01 * (attempt + 1))
    raise RuntimeError("患者创建失败")


def update_patient(*, actor, patient, request_id: str, **changes):
    editable = {key: value for key, value in changes.items() if key in {"name", "gender", "enrollment_age", "phone", "primary_doctor", "notes"}}
    if not editable:
        return patient
    with transaction.atomic():
        locked = PatientProfile.objects.select_for_update().get(pk=patient.pk, deleted_at__isnull=True)
        before = {key: str(getattr(locked, f"{key}_id")) if key == "primary_doctor" else getattr(locked, key) for key in editable}
        for key, value in editable.items():
            setattr(locked, key, value)
        locked.save(update_fields=[*editable.keys(), "updated_at"])
        audit_changes = {
            key: {"from": before[key], "to": str(editable[key].id) if key == "primary_doctor" else editable[key]}
            for key in editable
            if key != "notes"
        }
        if "notes" in editable:
            audit_changes["medical_notes"] = {"from": before["notes"], "to": editable["notes"]}
        record(actor=actor, action="patient.update", target=locked, changes=audit_changes, request_id=request_id)
        return locked


def soft_delete_patient(*, actor, patient, request_id: str):
    with transaction.atomic():
        locked = PatientProfile.objects.select_for_update().select_related("user").get(pk=patient.pk, deleted_at__isnull=True)
        update_account_security_state(actor=actor, target=locked.user, is_active=False, deleted=True, request_id=request_id)
        locked.deleted_at = timezone.now()
        locked.save(update_fields=["deleted_at"])
        record(actor=actor, action="patient.delete", target=locked, changes={"deleted": True}, request_id=request_id)
        return locked
