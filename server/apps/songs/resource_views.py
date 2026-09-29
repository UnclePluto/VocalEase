from django.shortcuts import get_object_or_404
from drf_spectacular.utils import extend_schema
from rest_framework.views import APIView

from apps.accounts.views import api_response
from common.api.permissions import IsAdminNamespaceUser, MustChangePasswordPermission
from common.api.schema import ApiEnvelopeSerializer

from .resources import read_song_lyrics, update_song_resources
from .selectors import songs_for_admin
from .serializers import SongReadSerializer, SongResourcesUpdateSerializer


class AdminSongResourcesView(APIView):
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]

    @extend_schema(request=SongResourcesUpdateSerializer, responses={200: ApiEnvelopeSerializer})
    def patch(self, request, song_id):
        serializer = SongResourcesUpdateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        song = update_song_resources(actor=request.user, request_id=request.request_id, song=get_object_or_404(songs_for_admin(), pk=song_id), **serializer.validated_data)
        return api_response(data=SongReadSerializer(song).data, request_id=request.request_id)


class AdminSongLyricsView(APIView):
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]

    @extend_schema(responses={200: ApiEnvelopeSerializer})
    def get(self, request, song_id):
        song = get_object_or_404(songs_for_admin(), pk=song_id)
        return api_response(data={"lines": read_song_lyrics(song=song)}, request_id=request.request_id)
