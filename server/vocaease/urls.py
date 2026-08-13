from django.urls import include, path

from . import health
from .admin import system_admin_site
from apps.accounts.views import AdminMeView, AdminResetPasswordView

urlpatterns = [
    path("internal/admin/", system_admin_site.urls),
    path("api/v1/auth/", include("apps.accounts.urls")),
    path("api/v1/admin/doctors/", include("apps.doctors.urls")),
    path("api/v1/admin/patients/", include("apps.patients.urls")),
    path("api/v1/admin/me/", AdminMeView.as_view(), name="admin-me"),
    path(
        "api/v1/admin/users/<uuid:user_id>/reset-password/",
        AdminResetPasswordView.as_view(),
        name="admin-reset-password",
    ),
    path("health/live/", health.live),
    path("health/ready/", health.ready),
]
