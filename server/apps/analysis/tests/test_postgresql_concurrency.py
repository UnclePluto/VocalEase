import io
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import timedelta
from time import sleep
from threading import Barrier, Event, Lock
from uuid import uuid4

import pytest
from django.db import connection, connections
from django.utils import timezone

from apps.analysis.executors import MockSongExecutor
from apps.analysis.models import AnalysisTask
from apps.analysis.services import create_song_analysis, run_analysis
from apps.media.services import (claim_local_upload, complete_local_asset,
                                 create_upload_grant, get_storage_backend,
                                 publish_local_upload)
from apps.songs.models import Song
from apps.songs.models import SongUploadIntent
from apps.songs.services import SongStateConflict, publish_song, update_song
from apps.songs import services as song_services
from apps.analysis import services as analysis_services


@contextmanager
def count_postgresql_song_updates(song_id):
    """用测试库触发器记录真实 UPDATE；跨 worker 连接可见。"""
    suffix = uuid4().hex
    table_name = f"test_song_update_audit_{suffix}"
    function_name = f"test_song_update_audit_fn_{suffix}"
    trigger_name = f"test_song_update_audit_trigger_{suffix}"
    with connection.cursor() as cursor:
        cursor.execute(f'CREATE TABLE "{table_name}" (song_id uuid NOT NULL)')
        cursor.execute(
            f'''CREATE FUNCTION "{function_name}"() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                INSERT INTO "{table_name}" (song_id) VALUES (NEW.id);
                RETURN NEW;
            END;
            $$'''
        )
        cursor.execute(
            f'''CREATE TRIGGER "{trigger_name}" AFTER UPDATE ON "songs_song"
            FOR EACH ROW WHEN (NEW.id = '{song_id}'::uuid)
            EXECUTE FUNCTION "{function_name}"()'''
        )

    def update_count():
        with connection.cursor() as cursor:
            cursor.execute(f'SELECT COUNT(*) FROM "{table_name}"')
            return cursor.fetchone()[0]

    try:
        yield update_count
    finally:
        with connection.cursor() as cursor:
            cursor.execute(f'DROP TRIGGER IF EXISTS "{trigger_name}" ON "songs_song"')
            cursor.execute(f'DROP FUNCTION IF EXISTS "{function_name}"()')
            cursor.execute(f'DROP TABLE IF EXISTS "{table_name}"')


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

    def execute(_self, _task, **_kwargs):
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

    def paused_execute(_self, _task, **_kwargs):
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


@pytest.mark.postgresql
@pytest.mark.django_db(transaction=True)
def test_postgresql_automatic_heartbeat_prevents_reclaim_during_long_executor(tmp_path, settings, monkeypatch):
    if connection.vendor != "postgresql":
        pytest.skip("自动心跳租约由真实 PostgreSQL 行锁测试证明")
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    settings.ANALYSIS_TASK_LEASE_SECONDS = 1
    song_id = uuid4()
    asset, grant = create_upload_grant(owner_type="song", owner_id=song_id, media_type="song_source", mime="audio/mpeg", size=4)
    backend = get_storage_backend()
    nonce = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(b"long"), mime="audio/mpeg", asset_id=asset.id)
    publish_local_upload(asset_id=asset.id, nonce=nonce, prepared=prepared, backend=backend)
    asset = complete_local_asset(asset=asset)
    song = Song.objects.create(id=song_id, title="心跳", artist="测试", genre="流行", language="中文", duration_seconds=1, source_asset=asset)
    task = create_song_analysis(song=song, source_asset=asset)
    started, release = Event(), Event()

    def long_execute(_self, _task, **_kwargs):
        started.set()
        assert release.wait(timeout=5)
        return {"protocol_version": "1.0", "is_mock": True, "artifacts": [], "metrics": {}}

    monkeypatch.setattr(MockSongExecutor, "execute", long_execute)

    def worker():
        connections.close_all()
        try:
            return run_analysis(task.id).status
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(worker)
        assert started.wait(timeout=5)
        sleep(1.4)
        assert analysis_services.claim_analysis_task(task.id) is None
        release.set()
        assert future.result(timeout=5) == "succeeded"
    task.refresh_from_db()
    assert task.attempt == 1


@pytest.mark.postgresql
@pytest.mark.django_db(transaction=True)
def test_postgresql_concurrent_idempotent_create_dispatches_once(tmp_path, settings, monkeypatch):
    if connection.vendor != "postgresql":
        pytest.skip("并发幂等投递由真实 PostgreSQL 唯一约束与提交回调证明")
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song_id = uuid4()
    asset, grant = create_upload_grant(owner_type="song", owner_id=song_id, media_type="song_source", mime="audio/mpeg", size=4)
    backend = get_storage_backend()
    nonce = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(b"same"), mime="audio/mpeg", asset_id=asset.id)
    publish_local_upload(asset_id=asset.id, nonce=nonce, prepared=prepared, backend=backend)
    asset = complete_local_asset(asset=asset)
    song = Song.objects.create(id=song_id, title="幂等", artist="测试", genre="流行", language="中文", duration_seconds=1, source_asset=asset)
    dispatched = []
    monkeypatch.setattr(analysis_services, "schedule_analysis_task", lambda task_id: dispatched.append(task_id) or True)

    def create_from_thread():
        connections.close_all()
        try:
            return analysis_services.request_song_analysis(
                song=Song.objects.get(pk=song.id), source_asset=asset,
                idempotency_key="pg-same",
            ).id
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as pool:
        task_ids = list(pool.map(lambda _index: create_from_thread(), range(2)))

    assert task_ids[0] == task_ids[1]
    assert dispatched == [task_ids[0]]


@pytest.mark.postgresql
@pytest.mark.django_db(transaction=True)
def test_postgresql_concurrent_availability_scan_starts_once():
    if connection.vendor != "postgresql":
        pytest.skip("扫描单例租约由真实 PostgreSQL 行锁测试证明")
    barrier = Barrier(2)
    dispatched = []

    def start_from_thread():
        connections.close_all()
        try:
            barrier.wait(timeout=5)
            return song_services.start_song_availability_scan(
                batch_size=1,
                dispatcher=lambda *args: dispatched.append(args),
            )
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as pool:
        started = list(pool.map(lambda _index: start_from_thread(), range(2)))

    assert sorted(started) == [False, True]
    assert len(dispatched) == 1


@pytest.mark.postgresql
@pytest.mark.django_db(transaction=True)
def test_postgresql_duplicate_scan_batch_commits_and_chains_once(tmp_path, settings, monkeypatch):
    if connection.vendor != "postgresql":
        pytest.skip("扫描批次 CAS 由真实 PostgreSQL 行锁测试证明")
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song_id = uuid4()
    content = b"duplicate-scan"
    asset, grant = create_upload_grant(
        owner_type="song", owner_id=song_id, media_type="song_source",
        mime="audio/mpeg", size=len(content),
    )
    backend = get_storage_backend()
    nonce = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(
        object_key=asset.object_key, token=grant.upload_token,
        stream=io.BytesIO(content), mime="audio/mpeg", asset_id=asset.id,
    )
    publish_local_upload(asset_id=asset.id, nonce=nonce, prepared=prepared, backend=backend)
    asset = complete_local_asset(asset=asset)
    song = Song.objects.create(
        id=song_id, title="重复批次", artist="测试", genre="流行",
        language="中文", duration_seconds=1, source_asset=asset,
        source_available=False,
    )
    evaluation_barrier = Barrier(2)
    evaluation_lock = Lock()
    evaluation_times = []
    dispatched = []
    real_evaluate_page = song_services.evaluate_song_availability_page

    def synchronized_real_evaluation(**kwargs):
        page = real_evaluate_page(**kwargs)
        with evaluation_lock:
            ordinal = len(evaluation_times) + 1
            distinct_time = timezone.now() + timedelta(microseconds=ordinal)
            evaluation_times.append(distinct_time)
        item = page.evaluations[0]
        snapshot = {**item.new_snapshot, "source_verified_at": distinct_time}
        page = replace(page, evaluations=(replace(item, new_snapshot=snapshot),))
        evaluation_barrier.wait(timeout=5)
        return page

    monkeypatch.setattr(song_services, "evaluate_song_availability_page", synchronized_real_evaluation)
    assert song_services.start_song_availability_scan(
        batch_size=1,
        dispatcher=lambda *args: dispatched.append(args),
    )
    initial = dispatched.pop()
    assert len(initial) == 4

    def run_duplicate():
        connections.close_all()
        try:
            token, batch_size, batch_token, batch_version = initial
            return song_services.run_song_availability_scan_batch(
                token, batch_size=batch_size, batch_token=batch_token,
                batch_version=batch_version,
                dispatcher=lambda *args: dispatched.append(args),
            )["status"]
        finally:
            connections.close_all()

    with count_postgresql_song_updates(song.id) as update_count:
        with ThreadPoolExecutor(max_workers=2) as pool:
            statuses = list(pool.map(lambda _index: run_duplicate(), range(2)))
        assert update_count() == 1

    assert sorted(statuses) == ["continued", "stale_batch"]
    state = song_services.SongAvailabilityScanState.objects.get(pk=1)
    song.refresh_from_db()
    assert song.source_available is True
    assert len(set(evaluation_times)) == 2
    assert song.source_verified_at in evaluation_times
    assert state.stats["processed"] == 1
    assert state.cursor == song.id
    assert len(dispatched) == 1


@pytest.mark.postgresql
@pytest.mark.django_db(transaction=True)
def test_postgresql_recovery_does_not_fail_active_fourth_attempt(tmp_path, settings, monkeypatch):
    if connection.vendor != "postgresql":
        pytest.skip("第 4 次有效 claim 与恢复调度交错由真实 PostgreSQL 行锁证明")
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    settings.ANALYSIS_TASK_LEASE_SECONDS = 60
    song_id = uuid4()
    asset, grant = create_upload_grant(owner_type="song", owner_id=song_id, media_type="song_source", mime="audio/mpeg", size=4)
    backend = get_storage_backend()
    nonce = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(object_key=asset.object_key, token=grant.upload_token, stream=io.BytesIO(b"last"), mime="audio/mpeg", asset_id=asset.id)
    publish_local_upload(asset_id=asset.id, nonce=nonce, prepared=prepared, backend=backend)
    asset = complete_local_asset(asset=asset)
    song = Song.objects.create(id=song_id, title="末次", artist="测试", genre="流行", language="中文", duration_seconds=1, source_asset=asset)
    task = create_song_analysis(song=song, source_asset=asset)
    AnalysisTask.objects.filter(pk=task.id).update(attempt=3)
    started, release = Event(), Event()

    def paused_fourth(_self, _task, **_kwargs):
        started.set()
        assert release.wait(timeout=5)
        return {"protocol_version": "1.0", "is_mock": True, "artifacts": [], "metrics": {}}

    monkeypatch.setattr(MockSongExecutor, "execute", paused_fourth)

    def worker():
        connections.close_all()
        try:
            return run_analysis(task.id).status
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(worker)
        assert started.wait(timeout=5)
        active = AnalysisTask.objects.get(pk=task.id)
        active_token = active.claim_token
        assert active.attempt == 4
        assert analysis_services.schedule_analysis_task(task.id) is False
        active.refresh_from_db()
        assert active.status == AnalysisTask.Status.PROCESSING
        assert active.claim_token == active_token
        release.set()
        assert future.result(timeout=5) == AnalysisTask.Status.SUCCEEDED


@pytest.mark.postgresql
@pytest.mark.django_db(transaction=True)
def test_postgresql_expired_scan_takeover_preserves_new_trusted_song_snapshot(tmp_path, settings, monkeypatch):
    if connection.vendor != "postgresql":
        pytest.skip("扫描接管后的旧批次业务写回由真实 PostgreSQL 行锁证明")
    settings.MEDIA_BACKEND = "local"
    settings.MEDIA_LOCAL_ROOT = str(tmp_path)
    song_id = uuid4()
    content = b"availability-source"
    asset, grant = create_upload_grant(
        owner_type="song", owner_id=song_id, media_type="song_source",
        mime="audio/mpeg", size=len(content),
    )
    backend = get_storage_backend()
    nonce = claim_local_upload(asset=asset)
    prepared = backend.prepare_authorized_stream(
        object_key=asset.object_key, token=grant.upload_token,
        stream=io.BytesIO(content), mime="audio/mpeg", asset_id=asset.id,
    )
    publish_local_upload(asset_id=asset.id, nonce=nonce, prepared=prepared, backend=backend)
    asset = complete_local_asset(asset=asset)
    song = Song.objects.create(
        id=song_id, title="扫描接管", artist="测试", genre="流行",
        language="中文", duration_seconds=1, source_asset=asset,
        source_available=False,
    )

    stat_finished, release = Event(), Event()
    old_messages = []
    new_messages = []

    class PauseAfterRealStat:
        def stat(self, object_key):
            metadata = backend.stat(object_key)
            if not stat_finished.is_set():
                stat_finished.set()
                assert release.wait(timeout=5)
            return metadata

    monkeypatch.setattr(song_services, "backend_for_asset", lambda _asset: PauseAfterRealStat())
    assert song_services.start_song_availability_scan(batch_size=100, dispatcher=lambda *args: old_messages.append(args))
    old = old_messages.pop()

    def old_worker():
        connections.close_all()
        try:
            token, batch_size, batch_token, batch_version = old
            return song_services.run_song_availability_scan_batch(
                token, batch_size=batch_size, batch_token=batch_token,
                batch_version=batch_version, dispatcher=lambda *args: old_messages.append(args),
            )["status"]
        finally:
            connections.close_all()

    with count_postgresql_song_updates(song.id) as update_count:
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(old_worker)
            assert stat_finished.wait(timeout=5)
            # 旧 worker 已完成真实 stat，但尚未返回；同一资产此时发布一个新的真实可信回执。
            replacement = b"availability-updated"
            prepared_replacement = backend._prepare_stream(
                object_key=asset.object_key, stream=io.BytesIO(replacement), mime=asset.mime,
                asset_id=asset.id, expected_size=len(replacement),
            )
            published_replacement = backend.publish_manifest(
                prepared_replacement, expected_generation=asset.manifest_generation,
            )
            backend.finalize_publish(published_replacement)
            asset.__class__.objects.filter(pk=asset.id).update(
                size=len(replacement), sha256=prepared_replacement.sha256,
                manifest_generation=prepared_replacement.generation,
            )
            song_services.SongAvailabilityScanState.objects.update(
                lease_expires_at=timezone.now() - timedelta(seconds=1),
            )
            assert song_services.start_song_availability_scan(
                batch_size=100, dispatcher=lambda *args: new_messages.append(args),
            )
            new = new_messages.pop()
            token, batch_size, batch_token, batch_version = new
            assert song_services.run_song_availability_scan_batch(
                token, batch_size=batch_size, batch_token=batch_token,
                batch_version=batch_version, dispatcher=lambda *args: new_messages.append(args),
            )["status"] == "completed"
            winner_updated_at = Song.objects.get(pk=song.id).updated_at
            release.set()
            assert future.result(timeout=5) == "stale_batch"
        assert update_count() == 1

    song.refresh_from_db()
    asset.refresh_from_db()
    state = song_services.SongAvailabilityScanState.objects.get(pk=1)
    assert song.source_available is True
    assert song.source_verified_asset_id == asset.id
    assert song.source_verified_generation == prepared_replacement.generation
    assert song.source_receipt_fingerprint == song_services.source_receipt_fingerprint(asset)
    assert song.updated_at == winner_updated_at
    assert state.stats["processed"] == 1
    assert state.stats["verified"] == 1
    assert state.cursor is None
    assert old_messages == []
    assert new_messages == []
