from rest_framework.exceptions import PermissionDenied
from rest_framework.permissions import BasePermission

from apps.accounts.models import Role


class MustChangePasswordPermission(BasePermission):
    message = "首次登录后必须修改密码"

    def has_permission(self, request, view):
        user = request.user
        if not user or not user.is_authenticated:
            return True
        if not user.must_change_password or getattr(view, "allows_password_change", False):
            return True
        raise PermissionDenied(self.message, code="password_change_required")


class SystemAdminPermission(BasePermission):
    message = "仅系统管理员可访问"

    def has_permission(self, request, view):
        return bool(
            request.user
            and request.user.is_authenticated
            and request.user.role == Role.SYSTEM_ADMIN
        )
