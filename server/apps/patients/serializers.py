from rest_framework import serializers

from apps.doctors.models import DoctorProfile, Gender
from common.api.pagination import PaginationQuerySerializer

from .models import PatientProfile, TreatmentPlan


class PatientWriteSerializer(serializers.Serializer):
    name = serializers.CharField(max_length=64, required=False)
    gender = serializers.ChoiceField(choices=Gender.choices, required=False)
    enrollment_age = serializers.IntegerField(
        min_value=0, max_value=150, required=False
    )
    phone = serializers.CharField(max_length=32, required=False)
    primary_doctor = serializers.UUIDField(required=False)
    start_date = serializers.DateField(required=False, write_only=True)
    cycle_weeks = serializers.IntegerField(
        min_value=1, max_value=520, required=False, write_only=True
    )
    notes = serializers.CharField(required=False, allow_blank=True)

    def validate_primary_doctor(self, value):
        try:
            return DoctorProfile.objects.select_related("user").get(
                pk=value,
                deleted_at__isnull=True,
                user__is_active=True,
                user__deleted_at__isnull=True,
                user__role="doctor",
            )
        except DoctorProfile.DoesNotExist:
            raise serializers.ValidationError("主治医生不存在或不可用")

    def validate(self, attrs):
        if not self.partial:
            required = {
                "name",
                "gender",
                "enrollment_age",
                "phone",
                "primary_doctor",
                "start_date",
                "cycle_weeks",
            }
            missing = required - attrs.keys()
            if missing:
                raise serializers.ValidationError(
                    {field: "该字段为必填项" for field in missing}
                )
        return attrs


class TreatmentPlanReadSerializer(serializers.ModelSerializer):
    class Meta:
        model = TreatmentPlan
        fields = ("id", "start_date", "cycle_weeks", "target_session_count", "status")


class PatientReadSerializer(serializers.ModelSerializer):
    user_id = serializers.UUIDField(read_only=True)
    primary_doctor = serializers.UUIDField(source="primary_doctor_id", read_only=True)
    treatment_plan = serializers.SerializerMethodField()

    class Meta:
        model = PatientProfile
        fields = (
            "id",
            "user_id",
            "medical_record_no",
            "name",
            "gender",
            "enrollment_age",
            "phone",
            "primary_doctor",
            "notes",
            "treatment_plan",
        )

    def get_treatment_plan(self, obj):
        prefetched = getattr(obj, "visible_treatment_plans", None)
        if prefetched is not None:
            plan = prefetched[0] if prefetched else None
            return TreatmentPlanReadSerializer(plan).data if plan else None
        plans = obj.treatment_plans.filter(deleted_at__isnull=True)
        plan = plans.filter(
            status__in=[TreatmentPlan.Status.ACTIVE, TreatmentPlan.Status.PENDING]
        ).first()
        if plan is None:
            plan = plans.first()
        return TreatmentPlanReadSerializer(plan).data if plan else None


class PatientListQuerySerializer(PaginationQuerySerializer):
    keyword = serializers.CharField(required=False, allow_blank=True, default="")
    gender = serializers.ChoiceField(
        choices=Gender.choices, required=False, allow_blank=True, default=""
    )
    primary_doctor = serializers.UUIDField(
        required=False, allow_null=True, default=None
    )
    status = serializers.ChoiceField(
        choices=TreatmentPlan.Status.choices,
        required=False,
        allow_blank=True,
        default="",
    )
