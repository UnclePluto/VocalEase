from rest_framework import serializers

from .models import DoctorProfile, Gender


class DoctorWriteSerializer(serializers.Serializer):
    name = serializers.CharField(max_length=64)
    gender = serializers.ChoiceField(choices=Gender.choices)
    phone = serializers.CharField(max_length=32)
    department = serializers.CharField(max_length=64)
    title = serializers.CharField(max_length=64)


class DoctorReadSerializer(serializers.ModelSerializer):
    class Meta:
        model = DoctorProfile
        fields = ("id", "employee_no", "name", "gender", "phone", "department", "title")
