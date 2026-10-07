import base64
import json
from io import BytesIO
from urllib.parse import urlencode

import pytest
from rest_framework.test import APIClient

from apps.accounts.models import Role, User
from apps.media.backends.qiniu import QiniuStorageBackend
from apps.media.contracts import ObjectMetadata
from apps.media.models import MediaAsset
from apps.media.readers import _qiniu_etag
from apps.media.services import STORAGE_BACKEND_FACTORIES
from apps.songs.models import Song


@pytest.mark.django_db
@pytest.mark.parametrize("content,valid", [
    ("[00:01.00]成都\n[00:02.50]第二句".encode(), True),
    (b"plain lyrics without timestamps", False),
    (b"\xff\x00binary", False),
])
def test_qiniu_lrc_upload_callback_binding_and_content_validation(settings, monkeypatch, content, valid):
    settings.MEDIA_BACKEND = "qiniu"
    objects = {}
    backend = QiniuStorageBackend(
        access_key="ak", secret_key="sk", bucket="private-bucket", domain="https://cdn.example.test",
        callback_url="http://testserver/api/v1/media/qiniu/callback/", environment="test",
        stat_transport=lambda key: objects[key],
    )
    monkeypatch.setitem(STORAGE_BACKEND_FACTORIES, "qiniu", lambda: backend)
    admin = User.objects.create_user(login_id="qiniu-lyrics-admin", password="888888", role=Role.SYSTEM_ADMIN, must_change_password=False)
    song = Song.objects.create(title="成都", artist="赵雷", genre="流行", language="中文", duration_seconds=60, ingestion_mode="manual")
    client = APIClient()
    client.force_authenticate(admin)
    response = client.post("/api/v1/admin/songs/upload-grants/", {
        "song_id": str(song.id), "media_type": "lyrics", "mime": "text/plain", "size": len(content),
    }, format="json")
    assert response.status_code == 201, response.content
    grant = response.json()["data"]
    policy = json.loads(base64.urlsafe_b64decode(grant["upload_token"].split(":")[-1] + "=="))
    # 重现线上存储边界：正文被识别为二进制，但浏览器发送的文件类型为 text/plain。
    assert "application/octet-stream" in policy["mimeLimit"].split(";")
    key, etag = grant["object_key"], _qiniu_etag(content)
    # 实际七牛回调和 stat 都记录 octet-stream，不能假设其沿用客户端声明类型。
    objects[key] = ObjectMetadata(key, len(content), "application/octet-stream", etag=etag)
    body = urlencode({"key": key, "hash": etag, "fsize": len(content), "mime": "application/octet-stream"}).encode()
    authorization = f"QBox {backend.auth.token_of_request(backend.callback_url, body.decode(), 'application/x-www-form-urlencoded')}"
    callback = APIClient().post("/api/v1/media/qiniu/callback/", body, content_type="application/x-www-form-urlencoded", HTTP_AUTHORIZATION=authorization)
    assert callback.status_code == 200, callback.content
    assert callback.json()["data"]["status"] == "ready"

    class Download:
        status_code = 200

        def __enter__(self):
            self.raw = BytesIO(content)
            return self

        def __exit__(self, *args):
            self.raw.close()

    monkeypatch.setattr("apps.media.readers.requests.get", lambda *args, **kwargs: Download())
    linked = client.patch(f"/api/v1/admin/songs/{song.id}/resources/", {
        "updates": {"lyrics_asset": grant["asset_id"]}, "expected": {"lyrics_asset": None},
    }, format="json")
    song.refresh_from_db()
    if valid:
        assert linked.status_code == 200, linked.content
        assert str(song.lyrics_asset_id) == grant["asset_id"]
        lines = client.get(f"/api/v1/admin/songs/{song.id}/lyrics/")
        assert lines.status_code == 200, lines.content
        assert lines.json()["data"]["lines"] == [{"time_ms": 1000, "text": "成都"}, {"time_ms": 2500, "text": "第二句"}]
    else:
        assert linked.status_code == 400, linked.content
        assert song.lyrics_asset_id is None
        assert "lyrics" in str(linked.json())
    assert MediaAsset.objects.get(pk=grant["asset_id"]).mime == "application/octet-stream"
