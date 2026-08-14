from django.urls import include, path

from . import health
from .admin import system_admin_site
from apps.accounts.views import AdminMeView, AdminResetPasswordView
from apps.media.urls import admin_urlpatterns, patient_urlpatterns, public_urlpatterns
from apps.songs.urls import admin_urlpatterns as admin_song_urlpatterns, patient_urlpatterns as patient_song_urlpatterns

urlpatterns = [
    path("internal/admin/", system_admin_site.urls),
    path("api/v1/auth/", include("apps.accounts.urls")),
    path("api/v1/admin/doctors/", include("apps.doctors.urls")),
    path("api/v1/admin/patients/", include("apps.patients.urls")),
    path("api/v1/admin/songs/", include((admin_song_urlpatterns, "admin_songs"), namespace="admin_songs")),
    path("api/v1/patient/songs/", include((patient_song_urlpatterns, "patient_songs"), namespace="patient_songs")),
    path("api/v1/patient/media/", include((patient_urlpatterns, "patient_media"), namespace="patient_media")),
    path("api/v1/admin/media/", include((admin_urlpatterns, "admin_media"), namespace="admin_media")),
    path("api/v1/media/", include((public_urlpatterns, "public_media"), namespace="public_media")),
    path("api/v1/admin/me/", AdminMeView.as_view(), name="admin-me"),
    path(
        "api/v1/admin/users/<uuid:user_id>/reset-password/",
        AdminResetPasswordView.as_view(),
        name="admin-reset-password",
    ),
    path("health/live/", health.live),
    path("health/ready/", health.ready),
]
