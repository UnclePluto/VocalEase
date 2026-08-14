from uuid import UUID

from django.conf import settings
from django.shortcuts import get_object_or_404
from rest_framework import status
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.permissions import BasePermission
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.accounts.models import Role
from apps.accounts.views import api_response
from apps.analysis.models import AnalysisTask
from apps.analysis.services import create_song_analysis
from apps.analysis.tasks import run_analysis_task
from apps.audit.services import record
from common.api.pagination import paginated_data, validated_query
from common.api.permissions import IsAdminNamespaceUser, MustChangePasswordPermission

from .models import Song
from .selectors import songs_for_admin, songs_for_patient
from .serializers import (PatientSongListQuerySerializer, PatientSongReadSerializer,
                          ReanalyzeSerializer, SongListQuerySerializer,
                          SongReadSerializer, SongUploadGrantSerializer,
                          SongWriteSerializer)
from .services import (SourceAssetInvalid, issue_song_upload_grant, preview_source,
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

    def post(self, request):
        serializer = SongUploadGrantSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        values = serializer.validated_data
        song_id, asset, grant = issue_song_upload_grant(actor=request.user, request_id=request.request_id, **values)
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

    def patch(self, request, song_id):
        serializer = SongWriteSerializer(data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        values = serializer.validated_data.copy()
        values.pop("id", None)
        values.pop("auto_analyze", None)
        song = update_song(actor=request.user, request_id=request.request_id, song=self.get_object(song_id), source_asset=values.pop("source_asset", None), **values)
        return api_response(data=SongReadSerializer(song).data, request_id=request.request_id)

    def delete(self, request, song_id):
        # DELETE 对同一 UUID 幂等：已软删仍返回 204；未知 UUID 返回 404。
        if soft_delete_song(actor=request.user, request_id=request.request_id, song_id=song_id) is None:
            return Response(status=status.HTTP_404_NOT_FOUND)
        return Response(status=status.HTTP_204_NO_CONTENT)


class AdminSongPublishView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    def post(self, request, song_id):
        song = publish_song(actor=request.user, request_id=request.request_id, song=get_object_or_404(songs_for_admin(), pk=song_id), publish=True)
        return api_response(data=SongReadSerializer(song).data, request_id=request.request_id)


class AdminSongUnpublishView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    def post(self, request, song_id):
        song = publish_song(actor=request.user, request_id=request.request_id, song=get_object_or_404(songs_for_admin(), pk=song_id), publish=False)
        return api_response(data=SongReadSerializer(song).data, request_id=request.request_id)


class AdminSongPreviewView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    def post(self, request, song_id):
        private_url = preview_source(actor=request.user, request_id=request.request_id, song=get_object_or_404(songs_for_admin(), pk=song_id))
        return api_response(data={"url": private_url.url, "expires_at": private_url.expires_at.isoformat()}, request_id=request.request_id)


class AdminSongReanalyzeView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    def post(self, request, song_id):
        serializer = ReanalyzeSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        song = get_object_or_404(songs_for_admin().select_related("source_asset"), pk=song_id)
        if not song.source_asset:
            return Response({"code": "song_source_invalid", "message": "歌曲源媒体不可用或验证失败", "data": None, "request_id": request.request_id}, status=status.HTTP_409_CONFLICT)
        task = create_song_analysis(song=song, source_asset=song.source_asset, **serializer.validated_data)
        record(actor=request.user, action="song.reanalyze", target=song, changes={"task_id": str(task.id), "task_type": task.task_type}, request_id=request.request_id)
        # 测试环境显式 eager；生产只发送 JSON UUID，不传媒体地址或输入内容。
        run_analysis_task.delay(str(task.id))
        return api_response(data={"task_id": str(task.id), "status": task.status, "is_mock": True}, request_id=request.request_id, status_code=status.HTTP_202_ACCEPTED)


class AdminSongAnalysisStatusView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    def get(self, request, song_id):
        get_object_or_404(songs_for_admin(), pk=song_id)
        tasks = AnalysisTask.objects.filter(song_id=song_id).order_by("-created_at")
        data = [{"id": str(task.id), "task_type": task.task_type, "protocol_version": task.protocol_version, "executor": task.executor, "status": task.status, "attempt": task.attempt, "result": task.result if task.status == "succeeded" else {}, "error_code": task.error_code, "error_summary": task.error_summary, "created_at": task.created_at.isoformat(), "completed_at": task.completed_at.isoformat() if task.completed_at else None} for task in tasks]
        return api_response(data={"results": data}, request_id=request.request_id)


class PatientSongListView(APIView):
    permission_classes = [PatientCatalogPermission, MustChangePasswordPermission]

    def get(self, request):
        query = _validated_song_query(request, PatientSongListQuerySerializer)
        # 发布状态是必要条件，但不以 DB 的 ready 标记冒充真实对象可用性。
        candidates = songs_for_patient(keyword=query["keyword"], ordering=query["sort"])
        available = []
        for song in candidates:
            try:
                validate_source_asset(song=song, asset=song.source_asset)
            except SourceAssetInvalid:
                continue
            available.append(song)
        start = (query["page"] - 1) * query["page_size"]
        data = {"count": len(available), "page": query["page"], "page_size": query["page_size"], "results": PatientSongReadSerializer(available[start:start + query["page_size"]], many=True).data}
        return api_response(data=data, request_id=request.request_id)


class PatientSongDetailView(APIView):
    permission_classes = [PatientCatalogPermission, MustChangePasswordPermission]

    def get_object(self, song_id):
        song = get_object_or_404(songs_for_patient(), pk=song_id)
        try:
            validate_source_asset(song=song, asset=song.source_asset)
        except SourceAssetInvalid:
            from django.http import Http404
            raise Http404
        return song

    def get(self, request, song_id):
        return api_response(data=PatientSongReadSerializer(self.get_object(song_id)).data, request_id=request.request_id)


class PatientSongPreviewView(APIView):
    permission_classes = [PatientCatalogPermission, MustChangePasswordPermission]

    def post(self, request, song_id):
        private_url = preview_source(actor=request.user, request_id=request.request_id, song=PatientSongDetailView().get_object(song_id))
        return api_response(data={"url": private_url.url, "expires_at": private_url.expires_at.isoformat()}, request_id=request.request_id)
