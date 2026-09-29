from __future__ import annotations

import json
import logging
import os
import re
from urllib.parse import parse_qs

from django.conf import settings
from django.http import FileResponse, Http404, HttpResponse, StreamingHttpResponse
from django.shortcuts import get_object_or_404
from rest_framework import status
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import extend_schema

from apps.accounts.views import api_response
from apps.audit.services import record
from apps.media.backends.local import LocalStorageBackend
from apps.media.backends.qiniu import QiniuStorageBackend
from apps.media.contracts import PATIENT_MEDIA_TYPES, StorageValidationError
from apps.media.models import MediaAsset
from apps.media.services import backend_for_asset, claim_local_upload, complete_local_asset, complete_qiniu_callback, create_upload_grant, ensure_local_asset_layout, publish_local_upload, release_local_upload, storage_backend_for
from apps.patients.models import PatientProfile
from apps.singing.schema import (
    PatientMediaUploadGrantEnvelopeSerializer,
    PrivateUrlEnvelopeSerializer,
)
from common.api.permissions import IsAdminNamespaceUser, MustChangePasswordPermission
from common.api.schema import (
    ApiEnvelopeSerializer,
    MediaUploadGrantRequestSerializer,
    PatientMediaUploadGrantRequestSerializer,
    QiniuCallbackRequestSerializer,
)


logger = logging.getLogger(__name__)


def _single_byte_range(value: str, size: int) -> tuple[int, int]:
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value.strip())
    if not match or size <= 0 or not any(match.groups()):
        raise ValueError("无效的字节范围")
    first, last = match.groups()
    if first:
        start = int(first)
        end = int(last) if last else size - 1
        if start >= size or end < start:
            raise ValueError("字节范围超出文件")
        return start, min(end, size - 1)
    suffix = int(last)
    if suffix <= 0:
        raise ValueError("无效的字节范围")
    return max(0, size - suffix), size - 1


class _FileRange:
    def __init__(self, source, start: int, length: int):
        self.source = source
        self.remaining = length
        source.seek(start)

    def __iter__(self):
        return self

    def __next__(self):
        if not self.remaining:
            self.close()
            raise StopIteration
        chunk = self.source.read(min(64 * 1024, self.remaining))
        if not chunk:
            self.close()
            raise StopIteration
        self.remaining -= len(chunk)
        if not self.remaining:
            self.close()
        return chunk

    def close(self):
        self.source.close()


def _patient_for_user(user) -> PatientProfile:
    try:
        return PatientProfile.objects.get(user=user, deleted_at__isnull=True)
    except PatientProfile.DoesNotExist as exc:
        raise PermissionDenied("患者资料不存在或不可用", code="patient_profile_not_found") from exc


def _asset_data(asset):
    return {"id": str(asset.id), "owner_type": asset.owner_type, "owner_id": str(asset.owner_id), "media_type": asset.media_type, "mime": asset.mime, "size": asset.size, "status": asset.status, "created_at": asset.created_at.isoformat()}


class GrantMixin:
    def issue(self, request, *, owner_type, owner_id, validated_data=None):
        payload = request.data if validated_data is None else validated_data
        try:
            media_type, mime, size = str(payload["media_type"]), str(payload["mime"]), int(payload["size"])
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

    @extend_schema(
        request=PatientMediaUploadGrantRequestSerializer,
        responses={201: PatientMediaUploadGrantEnvelopeSerializer},
    )
    def post(self, request):
        patient = _patient_for_user(request.user)
        serializer = PatientMediaUploadGrantRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        payload = serializer.validated_data
        if payload["owner_id"] != patient.id:
            raise PermissionDenied("患者只能为本人申请上传凭证", code="media_owner_forbidden")
        if payload["media_type"] not in PATIENT_MEDIA_TYPES:
            raise PermissionDenied("患者仅可上传演唱音频或录像", code="media_type_forbidden")
        return self.issue(
            request,
            owner_type="patient",
            owner_id=patient.id,
            validated_data=payload,
        )


class AdminUploadGrantView(GrantMixin, APIView):
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"

    @extend_schema(request=MediaUploadGrantRequestSerializer, responses={201: ApiEnvelopeSerializer})
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

    @extend_schema(
        request={"application/octet-stream": OpenApiTypes.BINARY},
        responses={204: None},
    )
    def put(self, request, asset_id):
        asset = get_object_or_404(MediaAsset.objects.select_related("patient_owner__user"), pk=asset_id, deleted_at__isnull=True)
        is_owner = asset.owner_type == "patient" and asset.patient_owner and request.user == asset.patient_owner.user
        if not is_owner and not IsAdminNamespaceUser().has_permission(request, self):
            raise PermissionDenied("无权上传该媒体", code="media_owner_forbidden")
        backend = backend_for_asset(asset)
        if asset.backend != "local" or not isinstance(backend, LocalStorageBackend):
            raise PermissionDenied("上传凭证不可用", code="media_upload_not_available")
        nonce = claim_local_upload(asset=asset)
        try:
            prepared = backend.prepare_authorized_stream(object_key=asset.object_key, token=request.query_params.get("signature", ""), stream=request.stream, mime=request.content_type or "", asset_id=asset.id)
        except StorageValidationError as exc:
            release_local_upload(asset_id=asset.id, nonce=nonce, success=False)
            raise PermissionDenied(str(exc), code="media_upload_rejected") from exc
        except Exception:
            release_local_upload(asset_id=asset.id, nonce=nonce, success=False)
            raise
        publish_local_upload(asset_id=asset.id, nonce=nonce, prepared=prepared, backend=backend)
        return Response(status=status.HTTP_204_NO_CONTENT)


class PatientCompleteView(PatientAssetMixin, APIView):
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"
    @extend_schema(request=None, responses=ApiEnvelopeSerializer)
    def post(self, request, asset_id):
        asset = self.asset(request, asset_id)
        if asset.backend != "local":
            raise PermissionDenied("七牛媒体只能等待已验签回调", code="media_callback_required")
        completed = complete_local_asset(asset=asset)
        return api_response(data=_asset_data(completed), request_id=request.request_id)


class AdminCompleteView(AdminAssetMixin, APIView):
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"
    @extend_schema(request=None, responses=ApiEnvelopeSerializer)
    def post(self, request, asset_id):
        asset = self.asset(request, asset_id)
        if asset.backend != "local":
            raise PermissionDenied("七牛媒体只能等待已验签回调", code="media_callback_required")
        completed = complete_local_asset(asset=asset)
        return api_response(data=_asset_data(completed), request_id=request.request_id)


class PatientPrivateUrlView(PatientAssetMixin, APIView):
    @extend_schema(request=None, responses={200: PrivateUrlEnvelopeSerializer})
    def post(self, request, asset_id):
        return _private_url_response(request, self.asset(request, asset_id))


class AdminPrivateUrlView(AdminAssetMixin, APIView):
    @extend_schema(request=None, responses=ApiEnvelopeSerializer)
    def post(self, request, asset_id):
        return _private_url_response(request, self.asset(request, asset_id))


def _private_url_response(request, asset):
    if asset.status != MediaAsset.Status.READY:
        raise PermissionDenied("媒体尚不可访问", code="media_not_ready")
    backend = backend_for_asset(asset)
    if isinstance(backend, LocalStorageBackend):
        asset = ensure_local_asset_layout(asset=asset)
        if asset.status != MediaAsset.Status.READY:
            raise PermissionDenied("媒体尚不可访问", code="media_not_ready")
        backend = backend_for_asset(asset)
        private_url = backend.create_private_url(asset.object_key, ttl_seconds=settings.MEDIA_PRIVATE_URL_TTL_SECONDS, asset_id=asset.id, expected_generation=asset.manifest_generation)
    else:
        private_url = backend.create_private_url(asset.object_key, ttl_seconds=settings.MEDIA_PRIVATE_URL_TTL_SECONDS)
    record(actor=request.user, action="media.private_url", target=asset, changes={"media_type": asset.media_type, "backend": asset.backend}, request_id=request.request_id)
    return api_response(data={"url": private_url.url, "expires_at": private_url.expires_at.isoformat()}, request_id=request.request_id)


class LocalPrivateDownloadView(APIView):
    permission_classes = [AllowAny]
    @extend_schema(responses={(200, "application/octet-stream"): OpenApiTypes.BINARY})
    def get(self, request, object_key):
        try:
            backend = storage_backend_for("local")
            if not isinstance(backend, LocalStorageBackend):
                raise StorageValidationError("下载后端不可用")
            claim = backend.verify_private_token(request.query_params.get("signature", ""), object_key)
        except StorageValidationError as exc:
            raise PermissionDenied("私有下载凭证无效", code="media_private_url_invalid") from exc
        asset = get_object_or_404(
            MediaAsset, pk=claim["asset_id"], object_key=object_key, backend="local", deleted_at__isnull=True,
        )
        if asset.status != MediaAsset.Status.READY:
            raise Http404
        backend = backend_for_asset(asset)
        if not isinstance(backend, LocalStorageBackend):
            raise Http404
        try:
            source = backend.verify_and_open_private(request.query_params.get("signature", ""), object_key, asset_id=asset.id, expected_generation=asset.manifest_generation)
        except StorageValidationError as exc:
            raise PermissionDenied(str(exc), code="media_private_url_invalid") from exc
        size = os.fstat(source.fileno()).st_size
        byte_range = request.headers.get("Range")
        if byte_range:
            try:
                start, end = _single_byte_range(byte_range, size)
            except ValueError:
                source.close()
                response = HttpResponse(status=416)
                response["Content-Range"] = f"bytes */{size}"
                response["Accept-Ranges"] = "bytes"
                return response
            response = StreamingHttpResponse(_FileRange(source, start, end - start + 1), status=206, content_type=asset.mime)
            response["Content-Range"] = f"bytes {start}-{end}/{size}"
            response["Content-Length"] = str(end - start + 1)
        else:
            response = FileResponse(source, content_type=asset.mime, as_attachment=False)
        response["Accept-Ranges"] = "bytes"
        return response


class QiniuCallbackView(APIView):
    permission_classes = [AllowAny]
    authentication_classes = []
    @extend_schema(
        request={"application/x-www-form-urlencoded": QiniuCallbackRequestSerializer},
        responses=ApiEnvelopeSerializer,
        auth=[],
    )
    def post(self, request):
        raw_body = request.body
        raw_uri = request.META.get("RAW_URI") or request.get_full_path()
        raw_path_query = raw_uri.split("://", 1)[-1] if raw_uri.startswith(("http://", "https://")) else raw_uri
        if raw_path_query.startswith(request.get_host()):
            raw_path_query = raw_path_query[len(request.get_host()):]
        # 验签仅依赖七牛独立配置及原始请求，不解析 payload、不查询资产。
        verifier_failed = False
        try:
            backend = storage_backend_for("qiniu")
            signature_valid = isinstance(backend, QiniuStorageBackend) and backend.verify_callback_signature(authorization=request.headers.get("Authorization", ""), content_type=request.content_type or "", raw_path_query=raw_path_query, body=raw_body)
        except Exception as exc:
            verifier_failed = True
            logger.error(
                "qiniu_callback_verifier_unavailable request_id=%s exception=%s",
                getattr(request, "request_id", ""), exc.__class__.__name__,
            )
            signature_valid = False
        if not signature_valid:
            if not verifier_failed:
                logger.warning(
                    "qiniu_callback_signature_invalid request_id=%s",
                    getattr(request, "request_id", ""),
                )
            raise PermissionDenied("七牛回调验签失败", code="qiniu_callback_invalid")
        try:
            parsed = parse_qs(raw_body.decode("utf-8"), strict_parsing=True, keep_blank_values=True)
            payload = {key: values[0] for key, values in parsed.items() if len(values) == 1}
            if set(payload) != set(parsed):
                raise ValueError
            payload["fsize"] = int(payload["fsize"])
        except (UnicodeDecodeError, ValueError, KeyError) as exc:
            raise ValidationError({"callback": "七牛回调表单不合法"}) from exc
        asset = get_object_or_404(MediaAsset, object_key=payload["key"], backend="qiniu", deleted_at__isnull=True)
        completed = complete_qiniu_callback(payload=payload, backend=backend)
        record(actor=None, action="media.qiniu_callback", target=completed, changes={"media_type": completed.media_type, "size": completed.size, "status": completed.status}, request_id=request.request_id)
        return api_response(data={"asset_id": str(completed.id), "status": completed.status}, request_id=request.request_id)
