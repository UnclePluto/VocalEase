from __future__ import annotations

import json
from urllib.parse import parse_qs

from django.conf import settings
from django.http import FileResponse, Http404
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
from apps.media.contracts import PATIENT_MEDIA_TYPES, StorageValidationError
from apps.media.models import MediaAsset
from apps.media.services import backend_for_asset, claim_local_upload, complete_local_asset, complete_qiniu_callback, create_upload_grant, get_storage_backend, release_local_upload
from apps.patients.models import PatientProfile
from common.api.permissions import IsAdminNamespaceUser, MustChangePasswordPermission


def _patient_for_user(user) -> PatientProfile:
    try:
        return PatientProfile.objects.get(user=user, deleted_at__isnull=True)
    except PatientProfile.DoesNotExist as exc:
        raise PermissionDenied("患者资料不存在或不可用", code="patient_profile_not_found") from exc


def _asset_data(asset):
    return {"id": str(asset.id), "owner_type": asset.owner_type, "owner_id": str(asset.owner_id), "media_type": asset.media_type, "mime": asset.mime, "size": asset.size, "status": asset.status, "created_at": asset.created_at.isoformat()}


class GrantMixin:
    def issue(self, request, *, owner_type, owner_id):
        try:
            media_type, mime, size = str(request.data["media_type"]), str(request.data["mime"]), int(request.data["size"])
        except (KeyError, TypeError, ValueError) as exc:
            raise ValidationError({"media": "media_type、mime、size 均为必填项"}) from exc
        asset, grant = create_upload_grant(owner_type=owner_type, owner_id=owner_id, media_type=media_type, mime=mime, size=size)
        data = {"asset_id": str(asset.id), "object_key": grant.object_key, "expires_at": grant.expires_at.isoformat(), "upload_url": grant.upload_url, "upload_token": grant.upload_token, "fields": grant.fields or {}}
        if asset.backend == "local":
            data.update(upload_url=f"/api/v1/media/local-upload/{asset.id}/?signature={grant.upload_token}", upload_token="")
        record(actor=request.user, action="media.upload_grant", target=asset, changes={"media_type": asset.media_type, "size": asset.size, "backend": asset.backend}, request_id=request.request_id)
        return api_response(data=data, request_id=request.request_id, status_code=status.HTTP_201_CREATED)


class PatientUploadGrantView(GrantMixin, APIView):
    permission_classes = [IsAuthenticated, MustChangePasswordPermission]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"

    def post(self, request):
        patient = _patient_for_user(request.user)
        if str(request.data.get("owner_id")) != str(patient.id):
            raise PermissionDenied("患者只能为本人申请上传凭证", code="media_owner_forbidden")
        if request.data.get("media_type") not in PATIENT_MEDIA_TYPES:
            raise PermissionDenied("患者仅可上传演唱音频或录像", code="media_type_forbidden")
        return self.issue(request, owner_type="patient", owner_id=patient.id)


class AdminUploadGrantView(GrantMixin, APIView):
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"

    def post(self, request):
        owner_type, owner_id = request.data.get("owner_type"), request.data.get("owner_id")
        if not owner_type or not owner_id:
            raise ValidationError({"owner_type": "管理端必须明确指定 owner_type 和 owner_id"})
        if owner_type == "patient":
            get_object_or_404(PatientProfile, pk=owner_id, deleted_at__isnull=True)
        return self.issue(request, owner_type=owner_type, owner_id=owner_id)


class PatientAssetMixin:
    permission_classes = [IsAuthenticated, MustChangePasswordPermission]

    def asset(self, request, asset_id):
        asset = get_object_or_404(MediaAsset.objects.select_related("patient_owner__user"), pk=asset_id, deleted_at__isnull=True)
        if asset.owner_type != "patient" or asset.patient_owner_id != _patient_for_user(request.user).id:
            raise PermissionDenied("无权访问该媒体", code="media_owner_forbidden")
        return asset


class AdminAssetMixin:
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]

    def asset(self, request, asset_id):
        return get_object_or_404(MediaAsset, pk=asset_id, deleted_at__isnull=True)


class LocalUploadView(APIView):
    permission_classes = [IsAuthenticated, MustChangePasswordPermission]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"

    def put(self, request, asset_id):
        asset = get_object_or_404(MediaAsset.objects.select_related("patient_owner__user"), pk=asset_id, deleted_at__isnull=True)
        is_owner = asset.owner_type == "patient" and asset.patient_owner and request.user == asset.patient_owner.user
        if not is_owner and not IsAdminNamespaceUser().has_permission(request, self):
            raise PermissionDenied("无权上传该媒体", code="media_owner_forbidden")
        backend = backend_for_asset(asset)
        if asset.backend != "local" or asset.status != MediaAsset.Status.UPLOADING or not isinstance(backend, LocalStorageBackend):
            raise PermissionDenied("上传凭证不可用", code="media_upload_not_available")
        nonce = claim_local_upload(asset=asset)
        try:
            backend.write_authorized_stream(object_key=asset.object_key, token=request.query_params.get("signature", ""), stream=request.stream, mime=request.content_type or "")
            release_local_upload(asset_id=asset.id, nonce=nonce, success=True)
        except StorageValidationError as exc:
            release_local_upload(asset_id=asset.id, nonce=nonce, success=False)
            raise PermissionDenied(str(exc), code="media_upload_rejected") from exc
        except Exception:
            release_local_upload(asset_id=asset.id, nonce=nonce, success=False)
            raise
        return Response(status=status.HTTP_204_NO_CONTENT)


class PatientCompleteView(PatientAssetMixin, APIView):
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"
    def post(self, request, asset_id):
        asset = self.asset(request, asset_id)
        if asset.backend != "local":
            raise PermissionDenied("七牛媒体只能等待已验签回调", code="media_callback_required")
        completed = complete_local_asset(asset=asset)
        return api_response(data=_asset_data(completed), request_id=request.request_id)


class AdminCompleteView(AdminAssetMixin, APIView):
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"
    def post(self, request, asset_id):
        asset = self.asset(request, asset_id)
        if asset.backend != "local":
            raise PermissionDenied("七牛媒体只能等待已验签回调", code="media_callback_required")
        completed = complete_local_asset(asset=asset)
        return api_response(data=_asset_data(completed), request_id=request.request_id)


class PatientPrivateUrlView(PatientAssetMixin, APIView):
    def post(self, request, asset_id):
        return _private_url_response(request, self.asset(request, asset_id))


class AdminPrivateUrlView(AdminAssetMixin, APIView):
    def post(self, request, asset_id):
        return _private_url_response(request, self.asset(request, asset_id))


def _private_url_response(request, asset):
    if asset.status != MediaAsset.Status.READY:
        raise PermissionDenied("媒体尚不可访问", code="media_not_ready")
    private_url = backend_for_asset(asset).create_private_url(asset.object_key, ttl_seconds=settings.MEDIA_PRIVATE_URL_TTL_SECONDS)
    record(actor=request.user, action="media.private_url", target=asset, changes={"media_type": asset.media_type, "backend": asset.backend}, request_id=request.request_id)
    return api_response(data={"url": private_url.url, "expires_at": private_url.expires_at.isoformat()}, request_id=request.request_id)


class LocalPrivateDownloadView(APIView):
    permission_classes = [AllowAny]
    def get(self, request, object_key):
        asset = get_object_or_404(MediaAsset, object_key=object_key, backend="local", deleted_at__isnull=True)
        backend = backend_for_asset(asset)
        if asset.status != MediaAsset.Status.READY or not isinstance(backend, LocalStorageBackend):
            raise Http404
        try:
            path = backend.authorize_private(request.query_params.get("signature", ""), object_key)
        except StorageValidationError as exc:
            raise PermissionDenied(str(exc), code="media_private_url_invalid") from exc
        return FileResponse(path.open("rb"), content_type="application/octet-stream", as_attachment=False)


class QiniuCallbackView(APIView):
    permission_classes = [AllowAny]
    authentication_classes = []
    def post(self, request):
        raw_body = request.body
        raw_uri = request.META.get("RAW_URI") or request.get_full_path()
        raw_path_query = raw_uri.split("://", 1)[-1] if raw_uri.startswith(("http://", "https://")) else raw_uri
        if raw_path_query.startswith(request.get_host()):
            raw_path_query = raw_path_query[len(request.get_host()):]
        try:
            parsed = parse_qs(raw_body.decode("utf-8"), strict_parsing=True, keep_blank_values=True)
            payload = {key: values[0] for key, values in parsed.items() if len(values) == 1}
            if set(payload) != set(parsed):
                raise ValueError
            payload["fsize"] = int(payload["fsize"])
        except (UnicodeDecodeError, ValueError, KeyError) as exc:
            raise ValidationError({"callback": "七牛回调表单不合法"}) from exc
        asset = get_object_or_404(MediaAsset, object_key=payload["key"], backend="qiniu", deleted_at__isnull=True)
        backend = backend_for_asset(asset)
        if not isinstance(backend, QiniuStorageBackend) or not backend.verify_callback_signature(authorization=request.headers.get("Authorization", ""), content_type=request.content_type or "", raw_path_query=raw_path_query, body=raw_body):
            raise PermissionDenied("七牛回调验签失败", code="qiniu_callback_invalid")
        completed = complete_qiniu_callback(payload=payload, backend=backend)
        record(actor=None, action="media.qiniu_callback", target=completed, changes={"media_type": completed.media_type, "size": completed.size, "status": completed.status}, request_id=request.request_id)
        return api_response(data={"asset_id": str(completed.id), "status": completed.status}, request_id=request.request_id)
