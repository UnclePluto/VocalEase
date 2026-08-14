from datetime import timedelta

from django.utils import timezone

from apps.media.backends.qiniu import QiniuStorageBackend
from apps.media.contracts import UploadGrant


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
