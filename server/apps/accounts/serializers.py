from rest_framework import serializers

from .models import User


class AccountSnapshotSerializer(serializers.ModelSerializer):
    class Meta:
        model = User
        fields = ("login_id", "role", "must_change_password")
        read_only_fields = fields


class LoginSerializer(serializers.Serializer):
    login_id = serializers.CharField(max_length=32)
    password = serializers.CharField(trim_whitespace=False)
    client_kind = serializers.ChoiceField(choices=("web", "android"))
    remember_me = serializers.BooleanField(default=False)


class RefreshSerializer(serializers.Serializer):
    client_kind = serializers.ChoiceField(choices=("web", "android"))
    refresh = serializers.CharField(required=False, allow_blank=False)


class ChangePasswordSerializer(serializers.Serializer):
    old_password = serializers.CharField(trim_whitespace=False)
    new_password = serializers.CharField(min_length=6, trim_whitespace=False)


class LogoutSerializer(serializers.Serializer):
    client_kind = serializers.ChoiceField(choices=("web", "android"))
    refresh = serializers.CharField(required=False, allow_blank=False)
