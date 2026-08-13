from django.db import transaction
from django.utils import timezone
from rest_framework.exceptions import APIException, ValidationError

from apps.accounts.models import Role, User
from apps.accounts.services import update_account_security_state
from apps.audit.services import record
from apps.doctors.models import DoctorProfile
from apps.doctors.services import next_sequence, run_with_database_retry

from .models import PatientProfile, TreatmentPlan


class PatientHasNoCurrentTreatmentPlan(APIException):
    status_code = 409
    default_detail = "患者没有可更新的当前治疗计划"
    default_code = "patient_has_no_current_treatment_plan"


class PatientDoctorAssociationChanged(Exception):
    """Internal retry signal; never exposed outside the service transaction."""


def _lock_doctor(doctor_id):
    try:
        return DoctorProfile.objects.select_for_update().select_related("user").get(pk=doctor_id)
    except DoctorProfile.DoesNotExist:
        raise ValidationError({"primary_doctor": "主治医生不存在或不可用"})


def _lock_active_doctor(doctor_id):
    doctor = _lock_doctor(doctor_id)
    user = doctor.user
    if (
        doctor.deleted_at is not None
        or user.role != Role.DOCTOR
        or not user.is_active
        or user.deleted_at is not None
    ):
        raise ValidationError({"primary_doctor": "主治医生不存在或不可用"})
    return doctor


def _validate_active_doctor(doctor):
    user = doctor.user
    if (
        doctor.deleted_at is not None
        or user.role != Role.DOCTOR
        or not user.is_active
        or user.deleted_at is not None
    ):
        raise ValidationError({"primary_doctor": "主治医生不存在或不可用"})


def _with_patient_doctor_locks(
    *,
    patient_id,
    operation,
    requested_doctor_id=None,
    require_current_doctor_active=True,
    attempts=3,
):
    """Optimistic read, deterministic doctor locks, patient lock, then recheck.

    Only a changed patient->doctor association is retried. Database errors are
    deliberately not caught here.
    """
    for attempt in range(attempts):
        try:
            with transaction.atomic():
                observed_doctor_id = PatientProfile.objects.values_list(
                    "primary_doctor_id", flat=True
                ).get(pk=patient_id, deleted_at__isnull=True)
                doctor_ids = {observed_doctor_id}
                if requested_doctor_id is not None:
                    doctor_ids.add(requested_doctor_id)
                locked_doctors = {
                    doctor.pk: doctor
                    for doctor in DoctorProfile.objects.select_for_update()
                    .select_related("user")
                    .filter(pk__in=doctor_ids)
                    .order_by("id")
                }
                if len(locked_doctors) != len(doctor_ids):
                    raise ValidationError({"primary_doctor": "主治医生不存在或不可用"})
                locked_patient = PatientProfile.objects.select_for_update().get(
                    pk=patient_id,
                    deleted_at__isnull=True,
                )
                if locked_patient.primary_doctor_id != observed_doctor_id:
                    raise PatientDoctorAssociationChanged
                current_doctor = locked_doctors[observed_doctor_id]
                if require_current_doctor_active:
                    _validate_active_doctor(current_doctor)
                requested_doctor = None
                if requested_doctor_id is not None:
                    requested_doctor = locked_doctors[requested_doctor_id]
                    _validate_active_doctor(requested_doctor)
                return operation(locked_patient, current_doctor, requested_doctor)
        except PatientDoctorAssociationChanged:
            if attempt == attempts - 1:
                raise ValidationError({"primary_doctor": "主治医生关联已变化，请重试"})
    raise RuntimeError("患者主治医生锁定失败")


def create_treatment_plan(*, patient, start_date, cycle_weeks, status=TreatmentPlan.Status.PENDING):
    """Use the database's current doctor relation; never trust patient cache."""

    def create(locked_patient, current_doctor, requested_doctor):
        return TreatmentPlan.objects.create(
            patient=locked_patient,
            start_date=start_date,
            cycle_weeks=cycle_weeks,
            target_session_count=cycle_weeks * 3,
            status=status,
        )

    return _with_patient_doctor_locks(
        patient_id=patient.pk,
        operation=create,
        require_current_doctor_active=True,
    )


def _update_locked_plan_status(*, actor, plan, status, request_id, locked_patient):
    locked_plan = TreatmentPlan.objects.select_for_update().get(
        pk=plan.pk,
        patient=locked_patient,
        deleted_at__isnull=True,
    )
    previous = locked_plan.status
    locked_plan.status = status
    locked_plan.save(update_fields=["status", "updated_at"])
    record(
        actor=actor,
        action="treatment_plan.status_changed",
        target=locked_plan,
        changes={"status": {"from": previous, "to": status}},
        request_id=request_id,
    )
    return locked_plan


def transition_treatment_plan_status(*, actor, plan, status, request_id: str):
    """Lock current database doctor, patient, then the plan."""
    if status not in TreatmentPlan.Status.values:
        raise ValidationError({"status": "治疗计划状态无效"})
    require_active = status in {TreatmentPlan.Status.PENDING, TreatmentPlan.Status.ACTIVE}
    return _with_patient_doctor_locks(
        patient_id=plan.patient_id,
        require_current_doctor_active=require_active,
        operation=lambda locked_patient, current_doctor, requested_doctor: _update_locked_plan_status(
            actor=actor,
            plan=plan,
            status=status,
            request_id=request_id,
            locked_patient=locked_patient,
        )
    )


def _create_patient_once(*, name, gender, enrollment_age, phone, doctor, start_date, cycle_weeks, notes, actor, request_id):
    with transaction.atomic():
        locked_doctor = _lock_active_doctor(doctor.pk)
        medical_record_no = next_sequence("P", width=6)
        user = User.objects.create_user(login_id=medical_record_no, role=Role.PATIENT, password="888888")
        patient = PatientProfile.objects.create(
            user=user,
            medical_record_no=medical_record_no,
            name=name,
            gender=gender,
            enrollment_age=enrollment_age,
            phone=phone,
            primary_doctor=locked_doctor,
            notes=notes,
        )
        create_treatment_plan(
            patient=patient,
            start_date=start_date,
            cycle_weeks=cycle_weeks,
            status=TreatmentPlan.Status.PENDING,
        )
        record(
            actor=actor,
            action="patient.create",
            target=patient,
            changes={"medical_record_no": medical_record_no, "name": name, "gender": gender, "enrollment_age": enrollment_age, "phone": phone, "primary_doctor": str(locked_doctor.id), "medical_notes": notes},
            request_id=request_id,
        )
        return patient


def create_patient(*, name, gender, enrollment_age, phone, doctor, start_date, cycle_weeks, notes="", actor=None, request_id=""):
    return run_with_database_retry(
        lambda: _create_patient_once(
                name=name, gender=gender, enrollment_age=enrollment_age, phone=phone, doctor=doctor,
                start_date=start_date, cycle_weeks=cycle_weeks, notes=notes, actor=actor, request_id=request_id,
        )
    )


def update_patient(*, actor, patient, request_id: str, **changes):
    editable = {key: value for key, value in changes.items() if key in {"name", "gender", "enrollment_age", "phone", "primary_doctor", "notes"}}
    plan_changes = {key: value for key, value in changes.items() if key in {"start_date", "cycle_weeks"}}
    if not editable and not plan_changes:
        return patient
    requested_doctor = editable.get("primary_doctor")

    def update(locked, current_doctor, locked_requested_doctor):
        if "primary_doctor" in editable:
            editable["primary_doctor"] = locked_requested_doctor
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
        if audit_changes:
            record(actor=actor, action="patient.update", target=locked, changes=audit_changes, request_id=request_id)
        if plan_changes:
            plan = TreatmentPlan.objects.select_for_update().filter(
                patient=locked,
                deleted_at__isnull=True,
                status__in=[TreatmentPlan.Status.PENDING, TreatmentPlan.Status.ACTIVE],
            ).order_by("-start_date", "-created_at").first()
            if plan is None:
                raise PatientHasNoCurrentTreatmentPlan()
            plan_before = {key: getattr(plan, key) for key in plan_changes}
            for key, value in plan_changes.items():
                setattr(plan, key, value)
            update_fields = [*plan_changes.keys(), "updated_at"]
            if "cycle_weeks" in plan_changes:
                plan.target_session_count = plan_changes["cycle_weeks"] * 3
                update_fields.append("target_session_count")
            plan.save(update_fields=update_fields)
            record(
                actor=actor,
                action="treatment_plan.update",
                target=plan,
                changes={key: {"from": str(plan_before[key]), "to": str(plan_changes[key])} for key in plan_changes},
                request_id=request_id,
            )
        return locked

    return _with_patient_doctor_locks(
        patient_id=patient.pk,
        requested_doctor_id=requested_doctor.pk if requested_doctor is not None else None,
        require_current_doctor_active=requested_doctor is None,
        operation=update,
    )


def soft_delete_patient(*, actor, patient, request_id: str):
    def delete(locked, current_doctor, requested_doctor):
        locked = PatientProfile.objects.select_related("user").get(pk=locked.pk)
        current_plans = list(
            TreatmentPlan.objects.select_for_update().filter(
                patient=locked,
                deleted_at__isnull=True,
                status__in=[TreatmentPlan.Status.PENDING, TreatmentPlan.Status.ACTIVE],
            )
        )
        update_account_security_state(actor=actor, target=locked.user, is_active=False, deleted=True, request_id=request_id)
        deleted_at = timezone.now()
        for plan in current_plans:
            plan.deleted_at = deleted_at
            plan.save(update_fields=["deleted_at"])
            record(
                actor=actor,
                action="treatment_plan.delete",
                target=plan,
                changes={"deleted": True},
                request_id=request_id,
            )
        locked.deleted_at = deleted_at
        locked.save(update_fields=["deleted_at"])
        record(actor=actor, action="patient.delete", target=locked, changes={"deleted": True}, request_id=request_id)
        return locked

    return _with_patient_doctor_locks(
        patient_id=patient.pk,
        operation=delete,
        require_current_doctor_active=False,
    )
