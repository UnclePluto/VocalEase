from drf_spectacular.utils import extend_schema, OpenApiParameter
from rest_framework.views import APIView
from apps.accounts.views import api_response
from apps.songs.views import PatientCatalogPermission
from common.api.permissions import IsAdminNamespaceUser, MustChangePasswordPermission
from common.api.schema import ApiEnvelopeSerializer
from .playback import authorize_session_song
from .schema import SongPlaybackGrantEnvelopeSerializer


class PatientSessionSongPlaybackView(APIView):
    permission_classes=[PatientCatalogPermission]

    @extend_schema(request=None, parameters=[OpenApiParameter(name='track',type=str,enum=['source','accompaniment'])],responses={200:SongPlaybackGrantEnvelopeSerializer})
    def post(self,request,session_id):
        return api_response(data=authorize_session_song(actor=request.user,session_id=session_id,track=request.query_params.get('track','accompaniment'),request_id=request.request_id),request_id=request.request_id)


class AdminSessionAccompanimentView(APIView):
    permission_classes=[IsAdminNamespaceUser, MustChangePasswordPermission]

    @extend_schema(request=None,parameters=[OpenApiParameter(name='preview',type=bool),OpenApiParameter(name='expected_asset_id',type=str)],responses={200:SongPlaybackGrantEnvelopeSerializer})
    def post(self,request,session_id):
        return api_response(data=authorize_session_song(actor=request.user,session_id=session_id,track='accompaniment',request_id=request.request_id,preview=request.query_params.get('preview')=='true',expected_asset_id=request.query_params.get('expected_asset_id')),request_id=request.request_id)
