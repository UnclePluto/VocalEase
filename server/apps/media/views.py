from __future__ import annotations

import json
from typing import Type

from django.conf import settings
from django.http import FileResponse, Http404, HttpResponse
from django.shortcuts import get_object_or_404
from rest_framework import status
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from apps.accounts.views import api_response
from apps.audit.services import record
from apps.media.backends.local import LocalStorageBackend
from apps.media.backends.qiniu import QiniuStorageBackend
from apps.media.contracts import StorageValidationError
from apps.media.models import MediaAsset
from apps.media.services import complete_asset, complete_qiniu_callback, create_upload_grant, get_storage_backend
from apps.patients.models import PatientProfile
from common.api.permissions import IsAdminNamespaceUser
from common.api.permissions import MustChangePasswordPermission


def _patient_for_user(user) -> PatientProfile:
    try:
        return PatientProfile.objects.get(user=user, deleted_at__isnull=True)
    except PatientProfile.DoesNotExist as exc:
        raise PermissionDenied("患者资料不存在或不可用", code="patient_profile_not_found") from exc


def _asset_data(asset: MediaAsset) -> dict:
    return {
        "id": str(asset.id), "owner_type": asset.owner_type, "owner_id": str(asset.owner_id), "media_type": asset.media_type,
        "mime": asset.mime, "size": asset.size, "status": asset.status, "created_at": asset.created_at.isoformat(),
    }


class UploadGrantInputMixin:
    def issue_grant(self, request, *, owner: PatientProfile):
        try:
            media_type = str(request.data["media_type"])
            mime = str(request.data["mime"])
            size = int(request.data["size"])
        except (KeyError, TypeError, ValueError) as exc:
            raise ValidationError({"media": "media_type、mime、size 均为必填项"}) from exc
        asset, grant = create_upload_grant(owner=owner, media_type=media_type, mime=mime, size=size)
        data = {
            "asset_id": str(asset.id), "object_key": grant.object_key, "expires_at": grant.expires_at.isoformat(),
            "upload_url": grant.upload_url, "upload_token": grant.upload_token, "fields": grant.fields or {},
        }
        if asset.backend == "local":
            data["upload_url"] = f"/api/v1/media/local-upload/{asset.id}/?signature={grant.upload_token}"
            data["upload_token"] = ""
        record(
            actor=request.user, action="media.upload_grant", target=asset,
            changes={"media_type": asset.media_type, "size": asset.size, "backend": asset.backend}, request_id=request.request_id,
        )
        return api_response(data=data, request_id=request.request_id, status_code=status.HTTP_201_CREATED)


class PatientUploadGrantView(UploadGrantInputMixin, APIView):
    permission_classes = [IsAuthenticated, MustChangePasswordPermission]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"

    def post(self, request):
        owner = _patient_for_user(request.user)
        owner_id = request.data.get("owner_id")
        if str(owner.id) != str(owner_id):
            raise PermissionDenied("患者只能为本人申请上传凭证", code="media_owner_forbidden")
        return self.issue_grant(request, owner=owner)


class AdminUploadGrantView(UploadGrantInputMixin, APIView):
    permission_classes = [IsAdminNamespaceUser]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"

    def post(self, request):
        owner = get_object_or_404(PatientProfile, pk=request.data.get("owner_id"), deleted_at__isnull=True)
        return self.issue_grant(request, owner=owner)


class LocalUploadView(APIView):
    permission_classes = [IsAuthenticated, MustChangePasswordPermission]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"

    def put(self, request, asset_id):
        asset = get_object_or_404(MediaAsset.objects.select_related("owner__user"), pk=asset_id, deleted_at__isnull=True)
        if request.user != asset.owner.user and not IsAdminNamespaceUser().has_permission(request, self):
            raise PermissionDenied("无权上传该媒体", code="media_owner_forbidden")
        if asset.backend != "local" or asset.status != MediaAsset.Status.UPLOADING:
            raise PermissionDenied("上传凭证不可用", code="media_upload_not_available")
        signature = request.query_params.get("signature", "")
        backend = get_storage_backend()
        if not isinstance(backend, LocalStorageBackend):
            raise PermissionDenied("本地上传未启用", code="media_upload_not_available")
        try:
            backend.write_authorized_upload(
                object_key=asset.object_key, token=signature, content=request.body, mime=request.content_type or ""
            )
        except StorageValidationError as exc:
            raise PermissionDenied(str(exc), code="media_upload_rejected") from exc
        return Response(status=status.HTTP_204_NO_CONTENT)


class AssetAccessMixin:
    permission_classes = [IsAuthenticated, MustChangePasswordPermission]

    def get_asset(self, request, asset_id):
        asset = get_object_or_404(MediaAsset.objects.select_related("owner__user"), pk=asset_id, deleted_at__isnull=True)
        if request.user == asset.owner.user:
            return asset
        if IsAdminNamespaceUser().has_permission(request, self):
            return asset
        raise PermissionDenied("无权访问该媒体", code="media_owner_forbidden")


class MediaCompleteView(AssetAccessMixin, APIView):
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"

    def post(self, request, asset_id):
        asset = self.get_asset(request, asset_id)
        completed = complete_asset(asset=asset, payload=request.data)
        record(
            actor=request.user, action="media.complete", target=completed,
            changes={"media_type": completed.media_type, "size": completed.size, "status": completed.status}, request_id=request.request_id,
        )
        return api_response(data=_asset_data(completed), request_id=request.request_id)


class PrivateUrlView(AssetAccessMixin, APIView):
    def post(self, request, asset_id):
        asset = self.get_asset(request, asset_id)
        if asset.status != MediaAsset.Status.READY:
            raise PermissionDenied("媒体尚不可访问", code="media_not_ready")
        private_url = get_storage_backend().create_private_url(
            asset.object_key, ttl_seconds=settings.MEDIA_PRIVATE_URL_TTL_SECONDS
        )
        record(
            actor=request.user, action="media.private_url", target=asset,
            changes={"media_type": asset.media_type, "backend": asset.backend}, request_id=request.request_id,
        )
        return api_response(
            data={"url": private_url.url, "expires_at": private_url.expires_at.isoformat()}, request_id=request.request_id
        )


class LocalPrivateDownloadView(APIView):
    permission_classes = [AllowAny]

    def get(self, request, object_key):
        backend = get_storage_backend()
        if not isinstance(backend, LocalStorageBackend):
            raise Http404
        try:
            content = backend.read_private(request.query_params.get("signature", ""))
            signed_key = backend._read_token(request.query_params.get("signature", "")).get("object_key")
        except StorageValidationError as exc:
            raise PermissionDenied(str(exc), code="media_private_url_invalid") from exc
        if signed_key != object_key:
            raise PermissionDenied("私有访问对象不匹配", code="media_private_url_invalid")
        return FileResponse(
            open(backend._path(object_key), "rb"), content_type="application/octet-stream", as_attachment=False
        )


class QiniuCallbackView(APIView):
    permission_classes = [AllowAny]
    authentication_classes = []

    def post(self, request):
        backend = get_storage_backend()
        if not isinstance(backend, QiniuStorageBackend):
            raise Http404
        raw_body = request.body
        if not backend.verify_callback_signature(
            authorization=request.headers.get("Authorization", ""), content_type=request.content_type or "",
            callback_url=backend.callback_url, body=raw_body,
        ):
            raise PermissionDenied("七牛回调验签失败", code="qiniu_callback_invalid")
        try:
            payload = json.loads(raw_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValidationError({"callback": "七牛回调 JSON 不合法"}) from exc
        completed = complete_qiniu_callback(payload=payload, backend=backend)
        record(
            actor=None, action="media.qiniu_callback", target=completed,
            changes={"media_type": completed.media_type, "size": completed.size, "status": completed.status}, request_id=request.request_id,
        )
        return api_response(data={"asset_id": str(completed.id), "status": completed.status}, request_id=request.request_id)
