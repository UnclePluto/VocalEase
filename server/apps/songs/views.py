from uuid import UUID

from django.conf import settings
from django.shortcuts import get_object_or_404
from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import status
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.permissions import BasePermission
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from apps.accounts.models import Role
from apps.accounts.views import api_response
from apps.analysis.models import AnalysisTask
from apps.analysis.services import request_song_analysis
from apps.audit.services import record
from common.api.pagination import paginated_data, validated_query
from common.api.permissions import IsAdminNamespaceUser, MustChangePasswordPermission
from common.api.schema import ApiEnvelopeSerializer
from apps.singing.schema import (
    PatientSongEnvelopeSerializer,
    PatientSongPageEnvelopeSerializer,
    PrivateUrlEnvelopeSerializer,
)

from .models import Song
from .selectors import songs_for_admin, songs_for_patient
from .serializers import (PatientSongListQuerySerializer, PatientSongReadSerializer,
                          ReanalyzeSerializer, SongListQuerySerializer,
                          SongReadSerializer, SongUploadGrantSerializer,
                          SongWriteSerializer, SongPreviewSerializer)
from .services import (SourceAssetInvalid, issue_song_upload_grant,
                       publish_song, soft_delete_song, update_song, create_song,
                       validate_source_asset)


class PatientCatalogPermission(BasePermission):
    message = "仅患者可访问患者曲库"

    def has_permission(self, request, view):
        user = request.user
        return bool(user and user.is_authenticated and user.role == Role.PATIENT and user.is_active and user.deleted_at is None and not user.must_change_password)


def _validated_song_query(request, serializer_class):
    unexpected = set(request.query_params) - set(serializer_class().fields)
    if unexpected:
        raise ValidationError({key: "不支持的查询参数" for key in sorted(unexpected)})
    return validated_query(request, serializer_class)


class SongUploadGrantView(APIView):
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"

    @extend_schema(request=SongUploadGrantSerializer, responses={201: ApiEnvelopeSerializer})
    def post(self, request):
        serializer = SongUploadGrantSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        values = serializer.validated_data
        media_type = values.pop("media_type")
        if media_type == "song_source":
            song_id, asset, grant = issue_song_upload_grant(actor=request.user, request_id=request.request_id, **values)
        else:
            from .resources import issue_optional_song_upload_grant
            song_id, asset, grant = issue_optional_song_upload_grant(actor=request.user, request_id=request.request_id, media_type=media_type, **values)
        data = {
            "song_id": str(song_id), "owner_id": str(song_id), "asset_id": str(asset.id),
            "object_key": grant.object_key, "expires_at": grant.expires_at.isoformat(),
            "upload_url": grant.upload_url, "upload_token": grant.upload_token, "fields": grant.fields or {},
        }
        if asset.backend == "local":
            data.update(upload_url=f"/api/v1/media/local-upload/{asset.id}/?signature={grant.upload_token}", upload_token="")
        return api_response(data=data, request_id=request.request_id, status_code=status.HTTP_201_CREATED)


class AdminSongListView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    def get(self, request):
        query = _validated_song_query(request, SongListQuerySerializer)
        queryset = songs_for_admin(**{key: query[key] for key in ("keyword", "genre", "language", "analysis_status", "publication_status")}, ordering=query["sort"])
        data = paginated_data(queryset, page=query["page"], page_size=query["page_size"])
        data["results"] = SongReadSerializer(data["results"], many=True).data
        return api_response(data=data, request_id=request.request_id)

    @extend_schema(request=SongWriteSerializer, responses={201: ApiEnvelopeSerializer})
    def post(self, request):
        serializer = SongWriteSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        values = serializer.validated_data.copy()
        song_id = values.pop("id", None)
        source_asset = values.pop("source_asset", None)
        auto_analyze = values.pop("auto_analyze", False)
        song = create_song(actor=request.user, request_id=request.request_id, song_id=song_id, source_asset=source_asset, auto_analyze=auto_analyze, **values)
        return api_response(data=SongReadSerializer(song).data, request_id=request.request_id, status_code=status.HTTP_201_CREATED)


class AdminSongDetailView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    def get_object(self, song_id):
        return get_object_or_404(songs_for_admin(), pk=song_id)

    def get(self, request, song_id):
        return api_response(data=SongReadSerializer(self.get_object(song_id)).data, request_id=request.request_id)

    @extend_schema(request=SongWriteSerializer, responses={200: ApiEnvelopeSerializer})
    def patch(self, request, song_id):
        if any(key in request.data for key in ("ingestion_mode", "vocal_asset", "accompaniment_asset", "lyrics_asset")):
            from rest_framework.exceptions import ValidationError
            raise ValidationError({"resources": "请使用歌曲资源管理接口修改资源"})
        serializer = SongWriteSerializer(data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        values = serializer.validated_data.copy()
        values.pop("id", None)
        values.pop("auto_analyze", None)
        song = update_song(actor=request.user, request_id=request.request_id, song=self.get_object(song_id), source_asset=values.pop("source_asset", None), **values)
        return api_response(data=SongReadSerializer(song).data, request_id=request.request_id)

    @extend_schema(request=None, responses={204: None})
    def delete(self, request, song_id):
        # DELETE 对同一 UUID 幂等：已软删仍返回 204；未知 UUID 返回 404。
        if soft_delete_song(actor=request.user, request_id=request.request_id, song_id=song_id) is None:
            return Response(status=status.HTTP_404_NOT_FOUND)
        return Response(status=status.HTTP_204_NO_CONTENT)


class AdminSongPublishView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    @extend_schema(request=None, responses={200: ApiEnvelopeSerializer})
    def post(self, request, song_id):
        song = publish_song(actor=request.user, request_id=request.request_id, song=get_object_or_404(songs_for_admin(), pk=song_id), publish=True)
        return api_response(data=SongReadSerializer(song).data, request_id=request.request_id)


class AdminSongUnpublishView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    @extend_schema(request=None, responses={200: ApiEnvelopeSerializer})
    def post(self, request, song_id):
        song = publish_song(actor=request.user, request_id=request.request_id, song=get_object_or_404(songs_for_admin(), pk=song_id), publish=False)
        return api_response(data=SongReadSerializer(song).data, request_id=request.request_id)


class AdminSongPreviewView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    @extend_schema(request=SongPreviewSerializer, responses={200: ApiEnvelopeSerializer})
    def post(self, request, song_id):
        serializer = SongPreviewSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        from .resources import preview_song_resource
        private_url = preview_song_resource(actor=request.user, request_id=request.request_id, song=get_object_or_404(songs_for_admin(), pk=song_id), **serializer.validated_data)
        return api_response(data={"url": private_url.url, "expires_at": private_url.expires_at.isoformat()}, request_id=request.request_id)


class AdminSongReanalyzeView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    @extend_schema(
        request=ReanalyzeSerializer,
        responses={202: ApiEnvelopeSerializer, 409: ApiEnvelopeSerializer},
    )
    def post(self, request, song_id):
        serializer = ReanalyzeSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        song = get_object_or_404(songs_for_admin().select_related("source_asset"), pk=song_id)
        if song.ingestion_mode == Song.IngestionMode.MANUAL:
            from .services import SongStateConflict
            raise SongStateConflict("人工歌曲不支持重新分析", code="manual_song_no_analysis")
        if not song.source_asset:
            return Response({"code": "song_source_invalid", "message": "歌曲源媒体不可用或验证失败", "data": None, "request_id": request.request_id}, status=status.HTTP_409_CONFLICT)
        task = request_song_analysis(song=song, source_asset=song.source_asset, **serializer.validated_data)
        record(actor=request.user, action="song.reanalyze", target=song, changes={"task_id": str(task.id), "task_type": task.task_type}, request_id=request.request_id)
        return api_response(data={"task_id": str(task.id), "status": task.status, "is_mock": True}, request_id=request.request_id, status_code=status.HTTP_202_ACCEPTED)


class AdminSongAnalysisStatusView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    def get(self, request, song_id):
        get_object_or_404(songs_for_admin(), pk=song_id)
        tasks = AnalysisTask.objects.filter(song_id=song_id).select_related("analysis_result").order_by("-created_at")
        data = [{"id": str(task.id), "task_type": task.task_type, "protocol_version": task.protocol_version, "executor": task.executor, "status": task.status, "attempt": task.attempt, "result": task.result if task.status == "succeeded" else {}, "error_code": task.error_code, "error_summary": task.error_summary, "created_at": task.created_at.isoformat(), "completed_at": task.completed_at.isoformat() if task.completed_at else None} for task in tasks]
        return api_response(data={"results": data}, request_id=request.request_id)


class PatientSongListView(APIView):
    permission_classes = [PatientCatalogPermission, MustChangePasswordPermission]

    @extend_schema(responses={200: PatientSongPageEnvelopeSerializer})
    def get(self, request):
        query = _validated_song_query(request, PatientSongListQuerySerializer)
        queryset = songs_for_patient(keyword=query["keyword"], ordering=query["sort"])
        data = paginated_data(queryset, page=query["page"], page_size=query["page_size"])
        data["results"] = PatientSongReadSerializer(data["results"], many=True).data
        return api_response(data=data, request_id=request.request_id)


class PatientSongDetailView(APIView):
    permission_classes = [PatientCatalogPermission, MustChangePasswordPermission]

    def get_object(self, song_id):
        # 详情以实时存储复核为准，并可修复 Beat 尚未回填的可信快照；列表仍只查索引。
        song = get_object_or_404(
            songs_for_admin(),
            pk=song_id,
        )
        if not song.source_asset_id:
            from django.http import Http404
            raise Http404
        try:
            validate_source_asset(song=song, asset=song.source_asset)
            from .resources import validate_singing_accompaniment
            validate_singing_accompaniment(song=song)
        except SourceAssetInvalid:
            from django.http import Http404
            raise Http404
        return song

    @extend_schema(responses={200: PatientSongEnvelopeSerializer})
    def get(self, request, song_id):
        return api_response(data=PatientSongReadSerializer(self.get_object(song_id)).data, request_id=request.request_id)


class PatientSongPreviewView(APIView):
    permission_classes = [PatientCatalogPermission, MustChangePasswordPermission]

    @extend_schema(
        request=None, responses={200: PrivateUrlEnvelopeSerializer},
        parameters=[OpenApiParameter("track", str, enum=["source", "accompaniment"], default="source")],
    )
    def post(self, request, song_id):
        from .resources import preview_song_resource
        track = request.query_params.get("track", "source")
        if track not in {"source", "accompaniment"}:
            raise ValidationError({"track": "不支持的音轨"})
        serializer = SongPreviewSerializer(data={"track": track})
        serializer.is_valid(raise_exception=True)
        from .alignment import current_alignment
        song=PatientSongDetailView().get_object(song_id)
        alignment=current_alignment(song)
        if track=='accompaniment' and alignment is None:
            raise SongStateConflict('伴奏起点尚未核验')
        private_url = preview_song_resource(
            actor=request.user, request_id=request.request_id,
            song=song,
            track=serializer.validated_data["track"],
        )
        return api_response(data={"url": private_url.url, "expires_at": private_url.expires_at.isoformat(), "alignment_verified":bool(alignment), "accompaniment_offset_ms":alignment["offset_ms"] if alignment else None}, request_id=request.request_id)
