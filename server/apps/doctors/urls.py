from django.urls import path

from .views import DoctorDetailView, DoctorListView


urlpatterns = [
    path("", DoctorListView.as_view(), name="doctor-list"),
    path("<uuid:doctor_id>/", DoctorDetailView.as_view(), name="doctor-detail"),
]
