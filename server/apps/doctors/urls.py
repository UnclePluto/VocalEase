from django.urls import path

from .views import DoctorDetailView, DoctorListView, DoctorStatusView


urlpatterns = [
    path("", DoctorListView.as_view(), name="doctor-list"),
    path(
        "<uuid:doctor_id>/activate/",
        DoctorStatusView.as_view(),
        {"action": "activate"},
        name="doctor-activate",
    ),
    path(
        "<uuid:doctor_id>/deactivate/",
        DoctorStatusView.as_view(),
        {"action": "deactivate"},
        name="doctor-deactivate",
    ),
    path("<uuid:doctor_id>/", DoctorDetailView.as_view(), name="doctor-detail"),
]
