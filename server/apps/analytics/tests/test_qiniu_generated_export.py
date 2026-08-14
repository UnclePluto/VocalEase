from datetime import timedelta
from io import BytesIO
from uuid import uuid4

import pytest
from django.utils import timezone

from apps.media.backends.qiniu import QiniuStorageBackend
from apps.media.contracts import UploadGrant
from apps.media.services import publish_generated_asset


def test_qiniu_generated_export_uses_official_put_and_trusted_stat(monkeypatch):
    calls = []

    class Info:
        status_code = 200

    monkeypatch.setattr(
        "apps.media.backends.qiniu.put_data",
        lambda token, key, content, **kwargs: (
            calls.append((token, key, content, kwargs)) or {"hash": "trusted-etag"}, Info(),
        ),
    )
    backend = QiniuStorageBackend(
        access_key="ak", secret_key="sk", bucket="private", domain="https://cdn.invalid",
        callback_url="https://api.invalid/callback", environment="test",
        stat_transport=lambda key: {"fsize": 3, "mimeType": "text/csv", "hash": "trusted-etag"},
    )
    grant = UploadGrant(
        object_key="test/export/2026/08/14/abc", upload_token="limited-token",
        expires_at=timezone.now() + timedelta(minutes=1),
    )

    receipt = backend.upload_generated(grant=grant, content=b"abc", mime="text/csv")

    assert receipt.etag == "trusted-etag" and receipt.size == 3 and receipt.mime == "text/csv"
    assert calls == [("limited-token", grant.object_key, b"abc", {"mime_type": "text/csv", "check_crc": True})]


def test_qiniu_large_generated_export_uses_official_stream_and_trusted_stat(monkeypatch):
    calls = []

    class Info:
        status_code = 200

    def put_stream(token, key, stream, file_name, size, **kwargs):
        calls.append((token, key, stream.read(), file_name, size, kwargs))
        return {"hash": "stream-etag"}, Info()

    monkeypatch.setattr("apps.media.backends.qiniu.put_stream", put_stream)
    backend = QiniuStorageBackend(
        access_key="ak", secret_key="sk", bucket="private", domain="https://cdn.invalid",
        callback_url="https://api.invalid/callback", environment="test",
        stat_transport=lambda key: {"fsize": 3, "mimeType": "text/csv", "hash": "stream-etag"},
    )
    grant = UploadGrant(
        object_key="test/export/2026/08/14/stream", upload_token="limited-token",
        expires_at=timezone.now() + timedelta(minutes=1),
    )

    receipt = backend.upload_generated_stream(
        grant=grant, stream=BytesIO(b"abc"), size=3, mime="text/csv",
    )

    assert receipt.etag == "stream-etag"
    assert calls == [(
        "limited-token", grant.object_key, b"abc", "stream", 3,
        {"mime_type": "text/csv", "bucket_name": "private"},
    )]


@pytest.mark.django_db
def test_server_generated_qiniu_stream_pulses_heartbeat_while_sdk_reads(settings, monkeypatch):
    heartbeat_calls = []

    class Info:
        status_code = 200

    def put_stream(token, key, stream, file_name, size, **kwargs):
        content = b""
        while chunk := stream.read(1):
            content += chunk
        assert content == b"abc"
        return {"hash": "heartbeat-etag"}, Info()

    settings.MEDIA_BACKEND = "qiniu"
    settings.QINIU_ACCESS_KEY = "ak"
    settings.QINIU_SECRET_KEY = "sk"
    settings.QINIU_BUCKET = "private"
    settings.QINIU_DOMAIN = "https://cdn.invalid"
    settings.QINIU_CALLBACK_URL = "https://api.invalid/callback"
    monkeypatch.setattr("apps.media.backends.qiniu.put_stream", put_stream)
    backend = QiniuStorageBackend(
        access_key="ak", secret_key="sk", bucket="private", domain="https://cdn.invalid",
        callback_url="https://api.invalid/callback", environment="test",
        stat_transport=lambda key: {"fsize": 3, "mimeType": "text/csv", "hash": "heartbeat-etag"},
    )
    monkeypatch.setitem(
        __import__("apps.media.services", fromlist=["STORAGE_BACKEND_FACTORIES"]).STORAGE_BACKEND_FACTORIES,
        "qiniu", lambda: backend,
    )

    asset = publish_generated_asset(
        owner_id=uuid4(), stream=BytesIO(b"abc"), size=3,
        content_sha256="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        mime="text/csv", heartbeat=lambda: heartbeat_calls.append(1),
    )

    assert asset.status == "ready" and asset.etag == "heartbeat-etag"
    assert len(heartbeat_calls) >= 6
