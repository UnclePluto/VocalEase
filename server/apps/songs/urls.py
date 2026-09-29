from django.urls import path
from .resource_views import AdminSongLyricsView, AdminSongResourcesView

from .views import (AdminSongAnalysisStatusView, AdminSongDetailView, AdminSongListView,
                    AdminSongPreviewView, AdminSongPublishView, AdminSongReanalyzeView,
                    AdminSongUnpublishView, PatientSongDetailView, PatientSongListView,
                    PatientSongPreviewView, SongUploadGrantView)


admin_urlpatterns = [
    path("upload-grants/", SongUploadGrantView.as_view(), name="song-upload-grant"),
    path("", AdminSongListView.as_view(), name="song-list"),
    path("<uuid:song_id>/", AdminSongDetailView.as_view(), name="song-detail"),
    path("<uuid:song_id>/publish/", AdminSongPublishView.as_view(), name="song-publish"),
    path("<uuid:song_id>/unpublish/", AdminSongUnpublishView.as_view(), name="song-unpublish"),
    path("<uuid:song_id>/preview/", AdminSongPreviewView.as_view(), name="song-preview"),
    path("<uuid:song_id>/resources/", AdminSongResourcesView.as_view(), name="song-resources"),
    path("<uuid:song_id>/lyrics/", AdminSongLyricsView.as_view(), name="song-lyrics"),
    path("<uuid:song_id>/reanalyze/", AdminSongReanalyzeView.as_view(), name="song-reanalyze"),
    path("<uuid:song_id>/analysis/", AdminSongAnalysisStatusView.as_view(), name="song-analysis-status"),
]

patient_urlpatterns = [
    path("", PatientSongListView.as_view(), name="patient-song-list"),
    path("<uuid:song_id>/", PatientSongDetailView.as_view(), name="patient-song-detail"),
    path("<uuid:song_id>/preview/", PatientSongPreviewView.as_view(), name="patient-song-preview"),
]
