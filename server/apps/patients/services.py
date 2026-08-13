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


def create_treatment_plan(*, patient, start_date, cycle_weeks, status=TreatmentPlan.Status.PENDING):
    """Lock order: DoctorProfile, PatientProfile, then TreatmentPlan creation."""
    with transaction.atomic():
        _lock_active_doctor(patient.primary_doctor_id)
        locked_patient = PatientProfile.objects.select_for_update().get(
            pk=patient.pk,
            deleted_at__isnull=True,
        )
        return TreatmentPlan.objects.create(
            patient=locked_patient,
            start_date=start_date,
            cycle_weeks=cycle_weeks,
            target_session_count=cycle_weeks * 3,
            status=status,
        )


def transition_treatment_plan_status(*, actor, plan, status, request_id: str):
    """Lock order: DoctorProfile, PatientProfile, then TreatmentPlan."""
    if status not in TreatmentPlan.Status.values:
        raise ValidationError({"status": "治疗计划状态无效"})
    doctor_id = plan.patient.primary_doctor_id
    with transaction.atomic():
        locked_doctor = _lock_doctor(doctor_id)
        if status in {TreatmentPlan.Status.PENDING, TreatmentPlan.Status.ACTIVE}:
            if (
                locked_doctor.deleted_at is not None
                or locked_doctor.user.role != Role.DOCTOR
                or not locked_doctor.user.is_active
                or locked_doctor.user.deleted_at is not None
            ):
                raise ValidationError({"primary_doctor": "主治医生不存在或不可用"})
        PatientProfile.objects.select_for_update().get(
            pk=plan.patient_id,
            primary_doctor=locked_doctor,
            deleted_at__isnull=True,
        )
        locked_plan = TreatmentPlan.objects.select_for_update().get(
            pk=plan.pk,
            patient_id=plan.patient_id,
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
    with transaction.atomic():
        requested_doctor = editable.get("primary_doctor")
        doctor_id = requested_doctor.pk if requested_doctor is not None else patient.primary_doctor_id
        locked_doctor = _lock_active_doctor(doctor_id)
        locked = PatientProfile.objects.select_for_update().get(pk=patient.pk, deleted_at__isnull=True)
        if "primary_doctor" in editable:
            editable["primary_doctor"] = locked_doctor
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


def soft_delete_patient(*, actor, patient, request_id: str):
    with transaction.atomic():
        # Global plan-write lock order: DoctorProfile -> PatientProfile -> TreatmentPlan.
        _lock_doctor(patient.primary_doctor_id)
        locked = PatientProfile.objects.select_for_update().select_related("user").get(
            pk=patient.pk,
            deleted_at__isnull=True,
        )
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
