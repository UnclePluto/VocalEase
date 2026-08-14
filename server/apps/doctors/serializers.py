from rest_framework import serializers

from common.api.pagination import PaginationQuerySerializer

from .models import DoctorProfile, Gender


class DoctorWriteSerializer(serializers.Serializer):
    name = serializers.CharField(max_length=64)
    gender = serializers.ChoiceField(choices=Gender.choices)
    phone = serializers.CharField(max_length=32)
    department = serializers.CharField(max_length=64)
    title = serializers.CharField(max_length=64)


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


class DoctorListQuerySerializer(PaginationQuerySerializer):
    keyword = serializers.CharField(required=False, allow_blank=True, default="")
    department = serializers.CharField(required=False, allow_blank=True, default="")
    status = serializers.ChoiceField(
        choices=("active", "inactive"), required=False, allow_blank=True, default=""
    )
