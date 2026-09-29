from django.contrib.admin import AdminSite
from django.http import HttpResponseForbidden

from apps.accounts.models import Role


class SystemAdminSite(AdminSite):
    site_header = "VocaEase 管理后台"

    def has_permission(self, request):
        return bool(
            request.user.is_authenticated
            and request.user.role == Role.SYSTEM_ADMIN
            and request.user.is_active
            and request.user.deleted_at is None
            and not request.user.must_change_password
        )

    def admin_view(self, view, cacheable=False):
        wrapped = super().admin_view(view, cacheable=cacheable)

        def protected_view(request, *args, **kwargs):
            if not self.has_permission(request):
                return HttpResponseForbidden("仅系统管理员可访问")
            return wrapped(request, *args, **kwargs)

        return protected_view


system_admin_site = SystemAdminSite(name="system_admin")
