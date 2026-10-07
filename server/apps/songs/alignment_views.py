from rest_framework.views import APIView
from rest_framework import serializers
from drf_spectacular.utils import extend_schema
from common.api.schema import ApiEnvelopeSerializer
from rest_framework.exceptions import ValidationError
from common.api.permissions import IsAdminNamespaceUser,MustChangePasswordPermission
from apps.accounts.views import api_response
from .alignment import verify_track_alignment

class TrackAlignmentRequestSerializer(serializers.Serializer):
    source_marker_ms=serializers.IntegerField(min_value=0)
    accompaniment_marker_ms=serializers.IntegerField(min_value=0)
    evidence=serializers.CharField(min_length=5,max_length=1000)
    expected_source_fingerprint=serializers.CharField(min_length=64,max_length=64)
    expected_accompaniment_fingerprint=serializers.CharField(min_length=64,max_length=64)

class AdminTrackAlignmentView(APIView):
    permission_classes=[IsAdminNamespaceUser,MustChangePasswordPermission]
    @extend_schema(request=TrackAlignmentRequestSerializer,responses={200:ApiEnvelopeSerializer})
    def post(self,request,song_id):
        keys={'source_marker_ms','accompaniment_marker_ms','evidence','expected_source_fingerprint','expected_accompaniment_fingerprint'}
        if not isinstance(request.data,dict) or set(request.data)!=keys:
            raise ValidationError({'alignment':'核验字段不完整'})
        value=verify_track_alignment(actor=request.user,song_id=song_id,**request.data)
        return api_response(data=value,request_id=request.request_id)
