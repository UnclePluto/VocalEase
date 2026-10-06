from uuid import UUID
from django.shortcuts import get_object_or_404
from drf_spectacular.utils import extend_schema
from rest_framework.exceptions import ValidationError
from rest_framework.views import APIView
from apps.accounts.views import api_response
from common.api.permissions import IsAdminNamespaceUser, MustChangePasswordPermission
from common.api.schema import ApiEnvelopeSerializer
from .models import Song
from .reference_pitch_services import import_reference_pitch, read_reference_pitch
from .views import PatientCatalogPermission


class PatientReferencePitchView(APIView):
    permission_classes = [PatientCatalogPermission]

    @extend_schema(responses={200: ApiEnvelopeSerializer})
    def get(self, request, song_id):
        version = request.query_params.get('version')
        try:
            version = UUID(version) if version else None
        except ValueError:
            raise ValidationError({'version': '版本标识无效'})
        # 旧会话可读取固定版本，即使歌曲后来被删除。
        if version is None:
            get_object_or_404(Song, pk=song_id, deleted_at__isnull=True)
        return api_response(data=read_reference_pitch(song_id=song_id, version=version), request_id=request.request_id)


class AdminReferencePitchView(APIView):
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]

    @extend_schema(request=ApiEnvelopeSerializer, responses={200: ApiEnvelopeSerializer})
    def post(self, request, song_id):
        if not isinstance(request.data, dict) or 'expected_fingerprint' not in request.data:
            raise ValidationError({'expected_fingerprint': '须提供预期输入指纹'})
        pitch = import_reference_pitch(actor=request.user, song_id=song_id, document=request.data.get('document'), expected_fingerprint=request.data['expected_fingerprint'])
        return api_response(data=read_reference_pitch(song_id=song_id, version=pitch.id), request_id=request.request_id)


class AdminGenerateReferencePitchView(APIView):
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]

    @extend_schema(request=ApiEnvelopeSerializer, responses={202: ApiEnvelopeSerializer})
    def post(self, request, song_id):
        from .reference_pitch_services import request_reference_pitch
        if not isinstance(request.data, dict) or not isinstance(request.data.get('expected_fingerprint'), str):
            raise ValidationError({'expected_fingerprint': '须提供预期人声音轨指纹'})
        pitch = request_reference_pitch(actor=request.user, song_id=song_id, expected_fingerprint=request.data['expected_fingerprint'])
        return api_response(data={'version':str(pitch.id), 'status':pitch.status}, request_id=request.request_id, status_code=202)
