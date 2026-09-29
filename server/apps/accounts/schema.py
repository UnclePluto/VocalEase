from drf_spectacular.extensions import OpenApiAuthenticationExtension
from rest_framework import serializers

from common.api.schema import ApiEnvelopeSerializer

from .serializers import AccountSnapshotSerializer


class LoginDataSerializer(serializers.Serializer):
    access = serializers.CharField()
    refresh = serializers.CharField(required=False)
    refresh_expires_at = serializers.DateTimeField()
    user = AccountSnapshotSerializer()


class RefreshDataSerializer(serializers.Serializer):
    access = serializers.CharField()
    refresh = serializers.CharField(required=False)
    refresh_expires_at = serializers.DateTimeField()
    user = AccountSnapshotSerializer()


class LoginEnvelopeSerializer(ApiEnvelopeSerializer):
    data = LoginDataSerializer()


class RefreshEnvelopeSerializer(ApiEnvelopeSerializer):
    data = RefreshDataSerializer()


class ActiveUserJWTAuthenticationScheme(OpenApiAuthenticationExtension):
    target_class = "apps.accounts.tokens.ActiveUserJWTAuthentication"
    name = "bearerAuth"

    def get_security_definition(self, auto_schema):
        return {
            "type": "http",
            "scheme": "bearer",
            "bearerFormat": "JWT",
        }


class LogoutJWTAuthenticationScheme(ActiveUserJWTAuthenticationScheme):
    target_class = "apps.accounts.views.LogoutJWTAuthentication"
    name = "logoutBearerAuth"
