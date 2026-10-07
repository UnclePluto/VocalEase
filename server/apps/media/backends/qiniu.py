from __future__ import annotations

import json
import logging
from datetime import datetime, timedelta, timezone as datetime_timezone
from typing import Any, Callable, Mapping
from urllib.parse import quote, urlsplit
from uuid import UUID

from django.conf import settings
from django.utils import timezone
from qiniu import Auth, BucketManager, put_data, put_stream

from apps.media.contracts import ObjectMetadata, PrivateUrl, StorageValidationError, UploadGrant, UploadReceipt, build_object_key, validate_media_request


logger = logging.getLogger(__name__)


class QiniuStorageBackend:
    """七牛 Kodo 官方 QBox/PutPolicy/RS stat 协议适配器。"""

    # 七牛官方回调鉴权仅对 application/x-www-form-urlencoded 将原始 body 纳入 QBox 签名。
    callback_content_type = "application/x-www-form-urlencoded"

    def __init__(self, *, access_key: str, secret_key: str, bucket: str, domain: str, callback_url: str, environment: str, stat_transport=None, auth: Auth | None = None, bucket_manager: BucketManager | None = None):
        if not all((access_key, secret_key, bucket, domain, callback_url)):
            raise StorageValidationError("七牛私有空间配置不完整")
        self.access_key, self.secret_key, self.bucket = access_key, secret_key, bucket
        self.domain, self.callback_url, self.environment = domain.rstrip("/"), callback_url, environment
        self.auth = auth or Auth(access_key, secret_key)
        self.bucket_manager = bucket_manager or BucketManager(self.auth)
        self.stat_transport = stat_transport

    @classmethod
    def from_settings(cls, **kwargs):
        return cls(access_key=settings.QINIU_ACCESS_KEY, secret_key=settings.QINIU_SECRET_KEY, bucket=settings.QINIU_BUCKET, domain=settings.QINIU_DOMAIN, callback_url=settings.QINIU_CALLBACK_URL, environment=settings.MEDIA_ENVIRONMENT, **kwargs)

    @staticmethod
    def _upload_mime_policy(*, media_type: str, mime: str) -> dict[str, Any]:
        if media_type == "lyrics" and mime == "text/plain":
            # 七牛可能把 LRC 正文识别为二进制；回调据可信 stat 保存实际类型，
            # 绑定歌曲前仍严格解析 UTF-8 LRC，不能仅凭 MIME 判断歌词有效。
            return {"mimeLimit": "text/plain;application/octet-stream", "detectMime": 1}
        return {"mimeLimit": mime, "detectMime": 1}

    def create_upload_grant(self, *, owner_id: UUID, media_type: str, mime: str, size: int) -> UploadGrant:
        validate_media_request(media_type=media_type, mime=mime, size=size)
        object_key = build_object_key(self.environment, media_type)
        deadline = int((timezone.now() + timedelta(seconds=settings.MEDIA_UPLOAD_GRANT_TTL_SECONDS)).timestamp())
        policy = {"scope": f"{self.bucket}:{object_key}", "insertOnly": 1, "fsizeLimit": size, **self._upload_mime_policy(media_type=media_type, mime=mime), "callbackUrl": self.callback_url, "callbackBodyType": self.callback_content_type, "callbackBody": "key=$(key)&hash=$(etag)&fsize=$(fsize)&mime=$(mimeType)"}
        self.last_policy = policy
        token = self.auth.upload_token(self.bucket, object_key, expires=settings.MEDIA_UPLOAD_GRANT_TTL_SECONDS, policy=policy.copy(), strict_policy=True)
        return UploadGrant(object_key=object_key, expires_at=datetime.fromtimestamp(deadline, tz=datetime_timezone.utc), upload_url=settings.QINIU_UPLOAD_URL, upload_token=token, fields={"key": object_key, "token": token})

    def reissue_upload_grant(self, *, object_key: str, owner_id: UUID, media_type: str, mime: str, size: int, expires_at) -> UploadGrant:
        del owner_id
        validate_media_request(media_type=media_type, mime=mime, size=size)
        issued_at = timezone.now()
        remaining_seconds = (expires_at - issued_at).total_seconds()
        if remaining_seconds <= 0:
            raise StorageValidationError("上传凭证已过期")
        deadline = int(expires_at.timestamp())
        if deadline <= int(issued_at.timestamp()):
            raise StorageValidationError("上传凭证剩余时间不足")
        policy = {
            "scope": f"{self.bucket}:{object_key}", "insertOnly": 1, "fsizeLimit": size,
            **self._upload_mime_policy(media_type=media_type, mime=mime), "callbackUrl": self.callback_url,
            "callbackBodyType": self.callback_content_type,
            "callbackBody": "key=$(key)&hash=$(etag)&fsize=$(fsize)&mime=$(mimeType)",
            "deadline": deadline,
        }
        # 七牛 upload_token 接收的是相对 TTL，内部会再次读取系统时间。重签必须以
        # 数据库截止时间为唯一真相，因此用 SDK 官方低层签名原语签标准 PutPolicy。
        token = self.auth.token_with_data(json.dumps(policy, separators=(",", ":")))
        return UploadGrant(
            object_key=object_key, expires_at=expires_at,
            upload_url=settings.QINIU_UPLOAD_URL, upload_token=token, fields={"key": object_key, "token": token},
        )

    def stat(self, object_key: str) -> ObjectMetadata:
        try:
            raw = self.stat_transport(object_key) if self.stat_transport else self.bucket_manager.stat(self.bucket, object_key)[0]
            if isinstance(raw, ObjectMetadata):
                return ObjectMetadata(object_key=object_key, size=raw.size, mime=raw.mime, sha256=raw.sha256, etag=raw.etag)
            data = json.loads(raw.decode()) if isinstance(raw, bytes) else raw
        except Exception as exc:
            raise StorageValidationError("七牛对象状态查询失败") from exc
        try:
            metadata = ObjectMetadata(object_key=object_key, size=int(data["fsize"]), mime=str(data["mimeType"]), etag=str(data["hash"]))
        except (KeyError, TypeError, ValueError) as exc:
            raise StorageValidationError("七牛对象状态响应不完整") from exc
        if not metadata.mime or not metadata.etag or metadata.size < 0:
            raise StorageValidationError("七牛对象状态响应不合法")
        return metadata

    def verify_completion(self, object_key: str, payload: Mapping[str, Any] | None = None) -> UploadReceipt:
        remote = self.stat(object_key)
        if payload is not None and (payload.get("key") != object_key or int(payload.get("fsize", -1)) != remote.size or str(payload.get("mime", "")) != remote.mime or str(payload.get("hash", "")) != remote.etag):
            raise StorageValidationError("七牛回调元数据与可信对象不一致")
        return UploadReceipt(**remote.__dict__)

    def upload_generated(self, *, grant: UploadGrant, content: bytes, mime: str) -> UploadReceipt:
        """服务端生成物仍使用受限 PutPolicy，并以 Kodo stat 作为可信完成事实。"""
        try:
            result, info = put_data(
                grant.upload_token,
                grant.object_key,
                content,
                mime_type=mime,
                check_crc=True,
            )
        except Exception as exc:
            raise StorageValidationError("七牛生成物上传失败") from exc
        if getattr(info, "status_code", 0) != 200 or not isinstance(result, dict) or not result.get("hash"):
            raise StorageValidationError("七牛生成物上传响应无效")
        receipt = self.verify_completion(grant.object_key)
        if receipt.size != len(content) or receipt.mime != mime or receipt.etag != str(result["hash"]):
            raise StorageValidationError("七牛生成物可信回执不一致")
        return receipt

    def upload_generated_stream(self, *, grant: UploadGrant, stream, size: int, mime: str) -> UploadReceipt:
        """以官方分片流接口上传服务端生成物，并用 Kodo stat 校验完成事实。"""
        try:
            result, info = put_stream(
                grant.upload_token,
                grant.object_key,
                stream,
                grant.object_key.rsplit("/", 1)[-1],
                size,
                mime_type=mime,
                bucket_name=self.bucket,
            )
        except Exception as exc:
            raise StorageValidationError("七牛生成物流式上传失败") from exc
        if getattr(info, "status_code", 0) != 200 or not isinstance(result, dict) or not result.get("hash"):
            raise StorageValidationError("七牛生成物上传响应无效")
        receipt = self.verify_completion(grant.object_key)
        if receipt.size != size or receipt.mime != mime or receipt.etag != str(result["hash"]):
            raise StorageValidationError("七牛生成物可信回执不一致")
        return receipt

    def verify_callback_signature(self, *, authorization: str, content_type: str, raw_path_query: str | None = None, callback_url: str | None = None, body: bytes) -> bool:
        if content_type.split(";", 1)[0].strip().lower() != self.callback_content_type:
            return False
        raw_path_query = raw_path_query or (urlsplit(callback_url or "").path + (f"?{urlsplit(callback_url or '').query}" if urlsplit(callback_url or "").query else ""))
        configured = urlsplit(self.callback_url)
        configured_path_query = configured.path + (f"?{configured.query}" if configured.query else "")
        if raw_path_query != configured_path_query:
            return False
        try:
            callback_body = body.decode("utf-8")
        except UnicodeDecodeError:
            return False
        return self.auth.verify_callback(authorization, self.callback_url, callback_body, content_type=content_type, method="POST")

    def sign_callback_for_test(self, raw_path_query: str, body: bytes, content_type: str = "application/x-www-form-urlencoded") -> str:
        return f"QBox {self.auth.token_of_request(self.callback_url, body.decode('utf-8'), content_type)}"

    def create_private_url(self, object_key: str, *, ttl_seconds: int) -> PrivateUrl:
        expires_at = timezone.now() + timedelta(seconds=ttl_seconds)
        encoded_key = "/".join(quote(part, safe="") for part in object_key.split("/"))
        try:
            url = self.auth.private_download_url(f"{self.domain}/{encoded_key}", expires=ttl_seconds)
        except Exception as exc:
            # SDK 异常文本可能携带完整 URL 或 token；这里只记录固定类别和异常类型。
            logger.warning(
                "qiniu_request_failed request_kind=private_download exception=%s",
                exc.__class__.__name__,
            )
            raise StorageValidationError("七牛私有下载地址签发失败") from exc
        return PrivateUrl(url=url, expires_at=expires_at, token="")

    def mark_for_cleanup(self, object_key: str) -> None:
        # 删除交给未来受限的 Kodo 管理操作；该标记接口幂等且不在上传请求中删除历史对象。
        return None
