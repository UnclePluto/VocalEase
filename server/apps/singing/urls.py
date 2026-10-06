from .playback_views import PatientSessionSongPlaybackView, AdminSessionAccompanimentView
from django.urls import path

from .views import (
    AdminSessionDetailView, AdminSessionListView, PatientSessionCancelView,
    PatientSessionConfirmUploadView, PatientSessionDetailView, PatientSessionListView,
    PatientSessionRetryView, PatientSessionSubmitView, PatientSessionUploadGrantView,
)


patient_urlpatterns = [
    path("<uuid:session_id>/song-playback/", PatientSessionSongPlaybackView.as_view()),
    path("", PatientSessionListView.as_view(), name="singing-session-list"),
    path("<uuid:session_id>/", PatientSessionDetailView.as_view(), name="singing-session-detail"),
    path("<uuid:session_id>/upload-grants/", PatientSessionUploadGrantView.as_view(), name="singing-session-upload-grant"),
    path("<uuid:session_id>/confirm-upload/", PatientSessionConfirmUploadView.as_view(), name="singing-session-confirm-upload"),
    path("<uuid:session_id>/submit/", PatientSessionSubmitView.as_view(), name="singing-session-submit"),
    path("<uuid:session_id>/cancel/", PatientSessionCancelView.as_view(), name="singing-session-cancel"),
    path("<uuid:session_id>/retry/", PatientSessionRetryView.as_view(), name="singing-session-retry"),
]

admin_urlpatterns = [
    path("<uuid:session_id>/playback-accompaniment/", AdminSessionAccompanimentView.as_view()),
    path("", AdminSessionListView.as_view(), name="singing-session-list"),
    path("<uuid:session_id>/", AdminSessionDetailView.as_view(), name="singing-session-detail"),
]
