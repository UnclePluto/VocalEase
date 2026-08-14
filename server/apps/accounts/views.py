import secrets
from urllib.parse import urlsplit

from django.conf import settings
from django.db import transaction
from django.shortcuts import get_object_or_404
from rest_framework import status
from rest_framework.exceptions import (
    AuthenticationFailed,
    NotAuthenticated,
    PermissionDenied,
    ValidationError,
)
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from apps.audit.services import record
from common.api.permissions import (
    IsAdminNamespaceUser,
    MustChangePasswordPermission,
    SystemAdminPermission,
)

from .serializers import (
    AccountSnapshotSerializer,
    ChangePasswordSerializer,
    LoginSerializer,
    LogoutSerializer,
    RefreshSerializer,
)
from .models import Role, User
from .services import change_password, login, reset_password
from .tokens import ActiveUserJWTAuthentication, revoke_refresh_token, rotate_refresh_token


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
    response.set_cookie(
        settings.AUTH_REFRESH_CSRF_COOKIE_NAME,
        secrets.token_urlsafe(32),
        httponly=False,
        secure=settings.AUTH_REFRESH_COOKIE_SECURE,
        samesite="Lax",
        path="/",
    )


def clear_refresh_cookies(response: Response) -> None:
    response.delete_cookie(
        settings.AUTH_REFRESH_COOKIE_NAME,
        path="/api/v1/auth/",
        samesite="Lax",
    )
    response.delete_cookie(
        settings.AUTH_REFRESH_CSRF_COOKIE_NAME,
        path="/",
        samesite="Lax",
    )


def get_refresh(request, serializer) -> str | None:
    client_kind = serializer.validated_data["client_kind"]
    body_refresh = serializer.validated_data.get("refresh")
    cookie_refresh = request.COOKIES.get(settings.AUTH_REFRESH_COOKIE_NAME)
    if client_kind == "web":
        if body_refresh:
            raise ValidationError({"refresh": "Web 客户端只能通过 Cookie 提交刷新令牌"})
        return cookie_refresh
    if cookie_refresh:
        raise ValidationError({"refresh": "Android 客户端不能携带 Web 刷新 Cookie"})
    return body_refresh


def _request_origin(request) -> str:
    source = request.headers.get("Origin") or request.headers.get("Referer", "")
    parsed = urlsplit(source)
    return f"{parsed.scheme}://{parsed.netloc}" if parsed.scheme and parsed.netloc else ""


def validate_web_origin(request) -> None:
    if _request_origin(request) not in settings.AUTH_WEB_ALLOWED_ORIGINS:
        raise PermissionDenied("请求来源不受信任", code="origin_not_allowed")


def validate_web_csrf(request) -> None:
    cookie_token = request.COOKIES.get(settings.AUTH_REFRESH_CSRF_COOKIE_NAME, "")
    header_token = request.headers.get("X-CSRFToken", "")
    if not cookie_token or not header_token or not secrets.compare_digest(cookie_token, header_token):
        raise PermissionDenied("CSRF 校验失败", code="csrf_failed")


def validate_web_refresh_request(request) -> None:
    validate_web_origin(request)
    validate_web_csrf(request)


class LogoutJWTAuthentication(ActiveUserJWTAuthentication):
    """Web 退出只信任 Refresh Cookie；Android 保持 Bearer 认证。"""

    def authenticate(self, request):
        if request.data.get("client_kind") == "web":
            return None
        return super().authenticate(request)


class LoginView(APIView):
    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "auth_login"

    def post(self, request):
        serializer = LoginSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        with transaction.atomic():
            user, pair = login(
                login_id=serializer.validated_data["login_id"],
                password=serializer.validated_data["password"],
                client_kind=serializer.validated_data["client_kind"],
                remember_me=serializer.validated_data["remember_me"],
            )
            record(
                actor=user,
                action="auth.login",
                target=user,
                changes={
                    "client_kind": serializer.validated_data["client_kind"],
                    "remember_me": serializer.validated_data["remember_me"],
                },
                request_id=request.request_id,
            )
        data = {
            "access": pair.access,
            "refresh_expires_at": pair.refresh_expires_at.isoformat(),
            "user": AccountSnapshotSerializer(user).data,
        }
        response = api_response(data=data, request_id=request.request_id)
        if serializer.validated_data["client_kind"] == "web":
            set_refresh_cookie(response, pair.refresh)
        else:
            data["refresh"] = pair.refresh
        return response


class RefreshView(APIView):
    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "auth_refresh"

    def post(self, request):
        serializer = RefreshSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        client_kind = serializer.validated_data["client_kind"]
        if client_kind == "web":
            validate_web_refresh_request(request)
        raw_refresh = get_refresh(request, serializer)
        if not raw_refresh:
            raise AuthenticationFailed("缺少刷新令牌", code="authentication_failed")
        def record_refresh(rotated_user):
            record(
                actor=rotated_user,
                action="auth.refresh",
                target=rotated_user,
                changes={},
                request_id=request.request_id,
            )

        user, pair = rotate_refresh_token(raw_refresh, on_success=record_refresh)
        response = api_response(
            data={
                "access": pair.access,
                "refresh_expires_at": pair.refresh_expires_at.isoformat(),
                "user": AccountSnapshotSerializer(user).data,
            },
            request_id=request.request_id,
        )
        if client_kind == "web":
            set_refresh_cookie(response, pair.refresh)
        else:
            response.data["data"]["refresh"] = pair.refresh
        return response


class ChangePasswordView(APIView):
    permission_classes = [IsAuthenticated, MustChangePasswordPermission]
    allows_password_change = True
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "auth_change_password"

    def post(self, request):
        serializer = ChangePasswordSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        change_password(
            user=request.user,
            request_id=request.request_id,
            **serializer.validated_data,
        )
        return api_response(data={}, request_id=request.request_id)


class LogoutView(APIView):
    authentication_classes = [LogoutJWTAuthentication]
    permission_classes = [AllowAny]
    allows_password_change = True
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "auth_logout"

    def post(self, request):
        serializer = LogoutSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        client_kind = serializer.validated_data["client_kind"]
        raw_refresh = get_refresh(request, serializer)
        if client_kind == "web":
            validate_web_origin(request)
            if not raw_refresh:
                response = api_response(data={}, request_id=request.request_id)
                clear_refresh_cookies(response)
                return response
            validate_web_csrf(request)
            actor = None
        else:
            if not request.user or not request.user.is_authenticated:
                raise NotAuthenticated("需要登录后退出", code="not_authenticated")
            actor = request.user
        with transaction.atomic():
            if raw_refresh:
                revoked_user = revoke_refresh_token(raw_refresh)
                if client_kind == "web":
                    actor = revoked_user
            if actor is not None:
                record(
                    actor=actor,
                    action="auth.logout",
                    target=actor,
                    changes={},
                    request_id=request.request_id,
                )
        response = api_response(data={}, request_id=request.request_id)
        if client_kind == "web":
            clear_refresh_cookies(response)
        return response


class AdminMeView(APIView):
    permission_classes = [IsAuthenticated, MustChangePasswordPermission, SystemAdminPermission]

    def get(self, request):
        return api_response(
            data={"login_id": request.user.login_id, "role": request.user.role},
            request_id=request.request_id,
        )


class AdminResetPasswordView(APIView):
    permission_classes = [IsAuthenticated, MustChangePasswordPermission, IsAdminNamespaceUser]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "auth_reset_password"

    def post(self, request, user_id):
        target = get_object_or_404(User, pk=user_id, deleted_at__isnull=True)
        if request.user.role == Role.DOCTOR:
            if target.pk == request.user.pk:
                raise PermissionDenied(
                    "医生不能通过后台为自己重置密码",
                    code="password_reset_self_forbidden",
                )
            if target.role == Role.SYSTEM_ADMIN:
                raise PermissionDenied(
                    "医生不能重置系统管理员密码",
                    code="password_reset_target_forbidden",
                )
        reset_password(
            actor=request.user,
            user=target,
            new_password="888888",
            request_id=request.request_id,
        )
        return api_response(data={}, request_id=request.request_id)
