from django.urls import path

from .views import PatientDetailView, PatientListView


urlpatterns = [
    path("", PatientListView.as_view(), name="patient-list"),
    path("<uuid:patient_id>/", PatientDetailView.as_view(), name="patient-detail"),
]
