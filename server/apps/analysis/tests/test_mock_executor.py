import hashlib
import io
from uuid import uuid4

import pytest
from django.test import override_settings

from apps.analysis.services import create_song_analysis, run_analysis
from apps.analysis.tasks import run_analysis_task
from apps.media.models import MediaAsset
from apps.media.services import (claim_local_upload, complete_local_asset,
                                 create_upload_grant, get_storage_backend,
                                 publish_local_upload)
from apps.songs.models import Song


def _ready_source(tmp_path, song_id):
    content = b"source"
    asset, grant = create_upload_grant(owner_type="song", owner_id=song_id, media_type="song_source", mime="audio/mpeg", size=len(content))
    backend = get_storage_backend()
    nonce = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(content), mime="audio/mpeg", asset_id=asset.id)
    publish_local_upload(asset_id=asset.id, nonce=nonce, prepared=prepared, backend=backend)
    return complete_local_asset(asset=asset)


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_mock_song_analysis_is_explicitly_marked(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song_id = uuid4()
    source_asset = _ready_source(tmp_path, song_id)
    song = Song.objects.create(id=song_id, title="测试歌曲", artist="测试歌手", genre="流行", language="中文", duration_seconds=120, source_asset=source_asset)

    task = create_song_analysis(song=song, source_asset=source_asset, task_type="vocal_separation")
    run_analysis_task.apply(args=[str(task.id)]).get()
    task.refresh_from_db()

    assert task.status == "succeeded"
    assert task.result == {"protocol_version": "1.0", "is_mock": True, "artifacts": [], "metrics": {}}
    assert MediaAsset.objects.filter(owner_type="system").count() == 0


@pytest.mark.django_db
@override_settings(MEDIA_BACKEND="local")
def test_duplicate_idempotency_and_repeat_run_are_safe(tmp_path, settings):
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song_id = uuid4()
    source_asset = _ready_source(tmp_path, song_id)
    song = Song.objects.create(id=song_id, title="测试歌曲", artist="测试歌手", genre="流行", language="中文", duration_seconds=120, source_asset=source_asset)
    first = create_song_analysis(song=song, source_asset=source_asset, task_type="vocal_separation", idempotency_key="same-key")
    assert create_song_analysis(song=song, source_asset=source_asset, task_type="vocal_separation", idempotency_key="same-key").id == first.id
    run_analysis(first.id)
    assert run_analysis(first.id).status == "succeeded"
