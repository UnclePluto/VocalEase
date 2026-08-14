import pytest
from django.db import connection
from django.db.migrations.exceptions import IrreversibleError
from django.db.migrations.executor import MigrationExecutor
from django.db.migrations.recorder import MigrationRecorder
from django.utils import timezone

from apps.analysis.models import AnalysisTask
from apps.media.models import MediaAsset
from apps.songs.models import Song

from .test_submission_idempotency import uploaded_session


def _singing_schema_snapshot():
    probe = connection.copy(alias="default")
    try:
        probe.ensure_connection()
        with probe.cursor() as cursor:
            tables = set(probe.introspection.table_names(cursor))
            cursor.execute('SELECT "id", "status" FROM "singing_singingsession" ORDER BY "id"')
            sessions = cursor.fetchall()
            cursor.execute(
                'SELECT "id", "target_type", "target_id" FROM "analysis_analysistask" '
                'ORDER BY "id"'
            )
            tasks = cursor.fetchall()
        return {
            "applied": set(MigrationRecorder(probe).applied_migrations()),
            "tables": tables,
            "sessions": sessions,
            "tasks": tasks,
        }
    finally:
        probe.close()


@pytest.mark.django_db(transaction=True, databases="__all__")
def test_singing_reverse_barrier_stops_before_schema_or_data_changes():
    uploaded_session()
    before = _singing_schema_snapshot()

    try:
        with pytest.raises(IrreversibleError):
            MigrationExecutor(connection).migrate([("analysis", "0003_analysistask_next_dispatch_at")])
        assert _singing_schema_snapshot() == before
    finally:
        executor = MigrationExecutor(connection)
        executor.migrate(executor.loader.graph.leaf_nodes())


def _compatible_song_task():
    import uuid

    song_id = uuid.uuid4()
    asset = MediaAsset.objects.create(
        owner_type="song",
        owner_id=song_id,
        media_type="song_source",
        backend="qiniu",
        object_key=f"test/song_source/{song_id.hex}",
        mime="audio/mpeg",
        size=3,
        etag="song-etag",
        status="ready",
        upload_expires_at=timezone.now(),
    )
    song = Song.objects.create(
        id=song_id,
        title="兼容歌曲",
        artist="歌手",
        genre="流行",
        language="中文",
        duration_seconds=60,
        source_asset=asset,
    )
    return AnalysisTask.objects.create(
        target_type="song",
        target_id=song.id,
        song=song,
        source_asset=asset,
        task_type="vocal_separation",
        executor="mock_song",
        idempotency_key=f"song-compatible:{song.id}",
        input_snapshot={},
    )


@pytest.mark.parametrize("contents", ["empty", "song_task"])
@pytest.mark.django_db(transaction=True, databases="__all__")
def test_singing_reverse_barrier_allows_empty_or_song_only_downgrade(contents):
    task = _compatible_song_task() if contents == "song_task" else None

    try:
        MigrationExecutor(connection).migrate([("analysis", "0003_analysistask_next_dispatch_at")])
        with connection.cursor() as cursor:
            assert "singing_singingsession" not in connection.introspection.table_names(cursor)
        if task is not None:
            with connection.cursor() as cursor:
                cursor.execute(
                    'SELECT "song_id", "task_type" FROM "analysis_analysistask" WHERE "id" = %s',
                    [task.id.hex],
                )
                song_id, task_type = cursor.fetchone()
                assert str(song_id).replace("-", "") == task.song_id.hex
                assert task_type == "vocal_separation"
    finally:
        executor = MigrationExecutor(connection)
        executor.migrate(executor.loader.graph.leaf_nodes())
