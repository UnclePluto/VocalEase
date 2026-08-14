from rest_framework import serializers

from common.api.pagination import PaginationQuerySerializer
from common.privacy import mask_phone, normalize_phone

from .models import DoctorProfile, Gender


class DoctorWriteSerializer(serializers.Serializer):
    name = serializers.CharField(max_length=64)
    gender = serializers.ChoiceField(choices=Gender.choices)
    phone = serializers.CharField(max_length=32)
    department = serializers.CharField(max_length=64)
    title = serializers.CharField(max_length=64)

    def validate_phone(self, value):
        phone = normalize_phone(value)
        queryset = DoctorProfile.objects.filter(phone=phone)
        doctor_id = self.context.get("doctor_id")
        if doctor_id:
            queryset = queryset.exclude(pk=doctor_id)
        if queryset.exists():
            raise serializers.ValidationError("手机号已存在")
        return phone


class DoctorReadSerializer(serializers.ModelSerializer):
    user_id = serializers.UUIDField(read_only=True)
    status = serializers.SerializerMethodField()

    class Meta:
        model = DoctorProfile
        fields = (
            "id",
            "user_id",
            "employee_no",
            "name",
            "gender",
            "phone",
            "department",
            "title",
            "status",
        )

    def get_status(self, obj):
        return "active" if obj.user.is_active else "inactive"


class DoctorListSerializer(DoctorReadSerializer):
    phone = serializers.SerializerMethodField()

    def get_phone(self, obj):
        return mask_phone(obj.phone)


class DoctorOptionSerializer(serializers.ModelSerializer):
    class Meta:
        model = DoctorProfile
        fields = ("id", "name", "employee_no")


class DoctorListQuerySerializer(PaginationQuerySerializer):
    keyword = serializers.CharField(required=False, allow_blank=True, default="")
    department = serializers.CharField(required=False, allow_blank=True, default="")
    status = serializers.ChoiceField(
        choices=("active", "inactive"), required=False, allow_blank=True, default=""
    )
