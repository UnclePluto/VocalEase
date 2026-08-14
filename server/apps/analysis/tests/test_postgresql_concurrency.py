import io
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from uuid import uuid4

import pytest
from django.db import connection, connections

from apps.analysis.executors import MockSongExecutor
from apps.analysis.models import AnalysisTask
from apps.analysis.services import create_song_analysis, run_analysis
from apps.media.services import (claim_local_upload, complete_local_asset,
                                 create_upload_grant, get_storage_backend,
                                 publish_local_upload)
from apps.songs.models import Song
from apps.songs.models import SongUploadIntent
from apps.songs.services import SongStateConflict, publish_song, update_song
from apps.analysis import services as analysis_services


@pytest.mark.postgresql
@pytest.mark.django_db(transaction=True)
def test_postgresql_concurrent_duplicate_run_executes_mock_once(tmp_path, settings, monkeypatch):
    """此用例只在真实 PostgreSQL 跑，证明不是 SQLite 的伪并发。"""
    if connection.vendor != "postgresql":
        pytest.skip("并发分析由真实 PostgreSQL 行锁测试证明")
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song_id = uuid4()
    content = b"source"
    asset, grant = create_upload_grant(owner_type="song", owner_id=song_id, media_type="song_source", mime="audio/mpeg", size=len(content))
    backend = get_storage_backend()
    nonce = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(content), mime="audio/mpeg", asset_id=asset.id)
    publish_local_upload(asset_id=asset.id, nonce=nonce, prepared=prepared, backend=backend)
    asset = complete_local_asset(asset=asset)
    song = Song.objects.create(id=song_id, title="并发曲目", artist="测试", genre="流行", language="中文", duration_seconds=1, source_asset=asset)
    task = create_song_analysis(song=song, source_asset=asset)

    started, release = Event(), Event()
    calls = []

    def execute(_self, _task):
        calls.append(_task.id)
        started.set()
        assert release.wait(timeout=5)
        return {"protocol_version": "1.0", "is_mock": True, "artifacts": [], "metrics": {}}

    monkeypatch.setattr(MockSongExecutor, "execute", execute)

    def run_from_thread():
        connections.close_all()
        try:
            return run_analysis(task.id).status
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(run_from_thread)
        assert started.wait(timeout=5)
        second = pool.submit(run_from_thread)
        assert second.result(timeout=5) == "processing"
        release.set()
        assert first.result(timeout=5) == "succeeded"

    assert calls == [task.id]


@pytest.mark.postgresql
@pytest.mark.django_db(transaction=True)
def test_postgresql_old_worker_cannot_overwrite_song_after_source_replacement(tmp_path, settings, monkeypatch):
    if connection.vendor != "postgresql":
        pytest.skip("换源与旧 worker 交错由真实 PostgreSQL 行锁证明")
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song_id = uuid4()
    old_asset, old_grant = create_upload_grant(owner_type="song", owner_id=song_id, media_type="song_source", mime="audio/mpeg", size=3)
    backend = get_storage_backend()
    nonce = claim_local_upload(asset=old_asset)
    prepared = backend.prepare_authorized_stream(object_key=old_asset.object_key, token=old_grant.upload_token, stream=io.BytesIO(b"old"), mime="audio/mpeg", asset_id=old_asset.id)
    publish_local_upload(asset_id=old_asset.id, nonce=nonce, prepared=prepared, backend=backend)
    old_asset = complete_local_asset(asset=old_asset)
    song = Song.objects.create(id=song_id, title="换源并发", artist="测试", genre="流行", language="中文", duration_seconds=1, source_asset=old_asset)
    old_task = create_song_analysis(song=song, source_asset=old_asset)

    started, release = Event(), Event()

    def paused_execute(_self, _task):
        started.set()
        assert release.wait(timeout=5)
        return {"protocol_version": "1.0", "is_mock": True, "artifacts": [], "metrics": {}}

    monkeypatch.setattr(MockSongExecutor, "execute", paused_execute)
    monkeypatch.setattr(analysis_services, "schedule_analysis_task", lambda _task_id: True)

    def run_old_worker():
        connections.close_all()
        try:
            return run_analysis(old_task.id).status
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=1) as pool:
        worker = pool.submit(run_old_worker)
        assert started.wait(timeout=5)
        new_asset, new_grant = create_upload_grant(owner_type="song", owner_id=song_id, media_type="song_source", mime="audio/mpeg", size=3)
        nonce = claim_local_upload(asset=new_asset)
        prepared = backend.prepare_authorized_stream(object_key=new_asset.object_key, token=new_grant.upload_token, stream=io.BytesIO(b"new"), mime="audio/mpeg", asset_id=new_asset.id)
        publish_local_upload(asset_id=new_asset.id, nonce=nonce, prepared=prepared, backend=backend)
        new_asset = complete_local_asset(asset=new_asset)
        SongUploadIntent.objects.update_or_create(song_id=song.id, defaults={"id": song.id, "asset": new_asset})
        update_song(actor=None, request_id="pg-replace", song=song, source_asset=new_asset.id)
        release.set()
        assert worker.result(timeout=5) == "superseded"

    song.refresh_from_db()
    old_task.refresh_from_db()
    assert old_task.status == "superseded"
    assert song.source_asset_id == new_asset.id
    assert song.analysis_status == "pending"
    assert song.publication_status == "draft"
    assert AnalysisTask.objects.filter(song=song, source_asset=new_asset, status="pending").count() == 1
    with pytest.raises(SongStateConflict):
        publish_song(actor=None, request_id="cannot-publish", song=song, publish=True)
