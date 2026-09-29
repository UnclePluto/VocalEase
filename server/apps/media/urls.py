from django.urls import path

from .views import (
    AdminUploadGrantView,
    LocalPrivateDownloadView,
    LocalUploadView,
    AdminCompleteView,
    PatientUploadGrantView,
    AdminPrivateUrlView,
    PatientCompleteView,
    PatientPrivateUrlView,
    QiniuCallbackView,
)


patient_urlpatterns = [
    path("upload-grants/", PatientUploadGrantView.as_view(), name="patient-media-upload-grant"),
    path("<uuid:asset_id>/complete/", PatientCompleteView.as_view(), name="patient-media-complete"),
    path("<uuid:asset_id>/private-url/", PatientPrivateUrlView.as_view(), name="patient-media-private-url"),
]

admin_urlpatterns = [
    path("upload-grants/", AdminUploadGrantView.as_view(), name="admin-media-upload-grant"),
    path("<uuid:asset_id>/complete/", AdminCompleteView.as_view(), name="admin-media-complete"),
    path("<uuid:asset_id>/private-url/", AdminPrivateUrlView.as_view(), name="admin-media-private-url"),
]

public_urlpatterns = [
    path("local-upload/<uuid:asset_id>/", LocalUploadView.as_view(), name="media-local-upload"),
    path("private/<path:object_key>", LocalPrivateDownloadView.as_view(), name="media-private-download"),
    path("qiniu/callback/", QiniuCallbackView.as_view(), name="qiniu-callback"),
]
