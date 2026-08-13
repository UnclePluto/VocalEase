from rest_framework import serializers


class LoginSerializer(serializers.Serializer):
    login_id = serializers.CharField(max_length=32)
    password = serializers.CharField(trim_whitespace=False)
    client_kind = serializers.ChoiceField(choices=("web", "android"))


class RefreshSerializer(serializers.Serializer):
    refresh = serializers.CharField(required=False, allow_blank=False)


class ChangePasswordSerializer(serializers.Serializer):
    old_password = serializers.CharField(trim_whitespace=False)
    new_password = serializers.CharField(min_length=6, trim_whitespace=False)


class LogoutSerializer(serializers.Serializer):
    refresh = serializers.CharField(required=False, allow_blank=False)
