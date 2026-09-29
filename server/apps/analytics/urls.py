from django.urls import path

from .views import DashboardView, ExportCreateView, ExportDetailView, ExportPrivateUrlView, PatientMetricsView


urlpatterns = [
    path("dashboard/", DashboardView.as_view(), name="analytics-dashboard"),
    path("patients/", PatientMetricsView.as_view(), name="analytics-patients"),
    path("exports/", ExportCreateView.as_view(), name="analytics-export-create"),
    path("exports/<uuid:job_id>/", ExportDetailView.as_view(), name="analytics-export-detail"),
    path("exports/<uuid:job_id>/private-url/", ExportPrivateUrlView.as_view(), name="analytics-export-private-url"),
]
