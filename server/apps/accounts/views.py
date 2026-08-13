from django.conf import settings
from rest_framework import status
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from apps.audit.services import record
from common.api.permissions import MustChangePasswordPermission, SystemAdminPermission

from .serializers import ChangePasswordSerializer, LoginSerializer, LogoutSerializer, RefreshSerializer
from .services import change_password, login
from .tokens import revoke_refresh_token, rotate_refresh_token


def api_response(*, data, request_id: str, status_code=status.HTTP_200_OK):
    return Response(
        {"code": "ok", "message": "", "data": data, "request_id": request_id},
        status=status_code,
    )


def set_refresh_cookie(response: Response, raw_refresh: str) -> None:
    response.set_cookie(
        settings.AUTH_REFRESH_COOKIE_NAME,
        raw_refresh,
        httponly=True,
        secure=settings.AUTH_REFRESH_COOKIE_SECURE,
        samesite="Lax",
        path="/api/v1/auth/",
    )


def get_refresh(request, serializer) -> str | None:
    return serializer.validated_data.get("refresh") or request.COOKIES.get(
        settings.AUTH_REFRESH_COOKIE_NAME
    )


class LoginView(APIView):
    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "auth_login"

    def post(self, request):
        serializer = LoginSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        user, pair = login(
            login_id=serializer.validated_data["login_id"],
            password=serializer.validated_data["password"],
        )
        data = {"access": pair.access, "refresh_expires_at": pair.refresh_expires_at.isoformat()}
        response = api_response(data=data, request_id=request.request_id)
        if serializer.validated_data["client_kind"] == "web":
            set_refresh_cookie(response, pair.refresh)
        else:
            data["refresh"] = pair.refresh
        record(
            actor=user,
            action="auth.login",
            target=user,
            changes={"client_kind": serializer.validated_data["client_kind"]},
            request_id=request.request_id,
        )
        return response


class RefreshView(APIView):
    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "auth_refresh"

    def post(self, request):
        serializer = RefreshSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        raw_refresh = get_refresh(request, serializer)
        if not raw_refresh:
            from rest_framework.exceptions import AuthenticationFailed

            raise AuthenticationFailed("缺少刷新令牌", code="authentication_failed")
        user, pair = rotate_refresh_token(raw_refresh)
        response = api_response(
            data={"access": pair.access, "refresh_expires_at": pair.refresh_expires_at.isoformat()},
            request_id=request.request_id,
        )
        if settings.AUTH_REFRESH_COOKIE_NAME in request.COOKIES:
            set_refresh_cookie(response, pair.refresh)
        else:
            response.data["data"]["refresh"] = pair.refresh
        record(
            actor=user,
            action="auth.refresh",
            target=user,
            changes={},
            request_id=request.request_id,
        )
        return response


class ChangePasswordView(APIView):
    permission_classes = [IsAuthenticated, MustChangePasswordPermission]
    allows_password_change = True
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "auth_change_password"

    def post(self, request):
        serializer = ChangePasswordSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        change_password(user=request.user, **serializer.validated_data)
        record(
            actor=request.user,
            action="auth.change_password",
            target=request.user,
            changes={"old_password": serializer.validated_data["old_password"], "new_password": serializer.validated_data["new_password"]},
            request_id=request.request_id,
        )
        return api_response(data={}, request_id=request.request_id)


class LogoutView(APIView):
    permission_classes = [IsAuthenticated, MustChangePasswordPermission]
    allows_password_change = True
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "auth_logout"

    def post(self, request):
        serializer = LogoutSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        raw_refresh = get_refresh(request, serializer)
        if raw_refresh:
            revoke_refresh_token(raw_refresh)
        record(
            actor=request.user,
            action="auth.logout",
            target=request.user,
            changes={},
            request_id=request.request_id,
        )
        response = api_response(data={}, request_id=request.request_id)
        response.delete_cookie(settings.AUTH_REFRESH_COOKIE_NAME, path="/api/v1/auth/", samesite="Lax")
        return response


class AdminMeView(APIView):
    permission_classes = [IsAuthenticated, MustChangePasswordPermission, SystemAdminPermission]

    def get(self, request):
        return api_response(
            data={"login_id": request.user.login_id, "role": request.user.role},
            request_id=request.request_id,
        )
