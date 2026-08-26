import uuid
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeoutError
from threading import Event

import pytest
from django.db import DatabaseError, IntegrityError, connection, connections, transaction
from django.db.migrations.exceptions import IrreversibleError
from django.db.migrations.executor import MigrationExecutor
from django.db.migrations.recorder import MigrationRecorder
from django.utils import timezone

from apps.analysis.models import AnalysisTask
from apps.media.models import MediaAsset
from apps.songs.models import Song
from apps.singing.models import SingingSession

from .test_submission_idempotency import uploaded_session


@pytest.mark.django_db(transaction=True, databases="__all__")
def test_creation_idempotency_migration_is_reversible_and_enforces_patient_scoped_key():
    previous_target = [("singing", "0004_remove_analysistimeseries_generation")]

    try:
        executor = MigrationExecutor(connection)
        executor.migrate(previous_target)
        old_apps = executor.loader.project_state(previous_target).apps
        OldSingingSession = old_apps.get_model("singing", "SingingSession")
        assert "creation_idempotency_key" not in {
            field.name for field in OldSingingSession._meta.fields
        }

        executor = MigrationExecutor(connection)
        executor.migrate(executor.loader.graph.leaf_nodes())
        patient, existing = uploaded_session()
        session_values = {
            "song_id": existing.song_id,
            "treatment_plan_id": existing.treatment_plan_id,
            "patient_snapshot": existing.patient_snapshot,
            "song_snapshot": existing.song_snapshot,
            "treatment_plan_snapshot": existing.treatment_plan_snapshot,
            "created_source": existing.created_source,
        }
        SingingSession.objects.filter(pk=existing.id).update(
            creation_idempotency_key="migration-create-001",
        )
        with pytest.raises(IntegrityError), transaction.atomic():
            SingingSession.objects.create(
                patient_id=patient.id,
                creation_idempotency_key="migration-create-001",
                **session_values,
            )
    finally:
        executor = MigrationExecutor(connection)
        executor.migrate(executor.loader.graph.leaf_nodes())


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


@pytest.mark.postgresql
@pytest.mark.django_db(transaction=True, databases="__all__")
def test_postgresql_reverse_barrier_locks_before_check_and_blocks_new_singing_task():
    if connection.vendor != "postgresql":
        pytest.skip("需要真实 PostgreSQL")
    asset = MediaAsset.objects.create(
        owner_type="system",
        owner_id=uuid.uuid4(),
        media_type="waveform",
        backend="qiniu",
        object_key=f"test/waveform/{uuid.uuid4().hex}",
        mime="application/json",
        size=3,
        etag="barrier-source",
        status="ready",
        upload_expires_at=timezone.now(),
    )
    lock_acquired = Event()
    release_migration = Event()
    insert_entered = Event()

    def migrate_back():
        connections.close_all()
        thread_connection = connections["default"]

        def observe_lock(execute, sql, params, many, context):
            result = execute(sql, params, many, context)
            if "analysis_target_destructive_barrier" in sql:
                lock_acquired.set()
                assert release_migration.wait(10)
            return result

        try:
            with thread_connection.execute_wrapper(observe_lock):
                MigrationExecutor(thread_connection).migrate(
                    [("analysis", "0003_analysistask_next_dispatch_at")]
                )
        finally:
            thread_connection.close()

    def insert_singing_task():
        connections.close_all()
        thread_connection = connections["default"]

        def observe_insert(execute, sql, params, many, context):
            if 'INSERT INTO "analysis_analysistask"' in sql:
                insert_entered.set()
            return execute(sql, params, many, context)

        try:
            with thread_connection.execute_wrapper(observe_insert):
                AnalysisTask.objects.create(
                    target_type="singing_session",
                    target_id=uuid.uuid4(),
                    source_asset_id=asset.id,
                    task_type="singing_audio_metrics",
                    executor="mock_singing",
                    generation=0,
                    idempotency_key=f"barrier-writer:{uuid.uuid4()}",
                    input_snapshot={},
                )
        except DatabaseError:
            return "rejected-after-schema-change"
        finally:
            thread_connection.close()
        return "unexpectedly-created"

    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            migration_future = pool.submit(migrate_back)
            assert lock_acquired.wait(10), "回退必须先获得数据库表锁"
            writer_future = pool.submit(insert_singing_task)
            assert insert_entered.wait(10)
            with pytest.raises(FutureTimeoutError):
                writer_future.result(timeout=0.25)
            release_migration.set()
            migration_future.result(timeout=20)
            assert writer_future.result(timeout=20) == "rejected-after-schema-change"
        with connection.cursor() as cursor:
            assert "singing_singingsession" not in connection.introspection.table_names(cursor)
            columns = {
                column.name
                for column in connection.introspection.get_table_description(
                    cursor, "analysis_analysistask"
                )
            }
        assert "target_type" not in columns
    finally:
        release_migration.set()
        connections.close_all()
        executor = MigrationExecutor(connection)
        executor.migrate(executor.loader.graph.leaf_nodes())


@pytest.mark.django_db(transaction=True, databases="__all__")
def test_generation_cleanup_migration_preserves_existing_result_and_series_payloads():
    old_targets = [
        ("analysis", "0005_analysisresult_generation_analysistask_generation_and_more"),
        ("singing", "0003_analysistimeseries_generation_and_more"),
    ]
    try:
        executor = MigrationExecutor(connection)
        executor.migrate(old_targets)
        old_apps = executor.loader.project_state(old_targets).apps
        OldSingingSession = old_apps.get_model("singing", "SingingSession")
        patient, session = uploaded_session(session_model=OldSingingSession)
        audio = session.media_bindings.get(media_type="singing_audio").asset
        task = AnalysisTask.objects.create(
            target_type="singing_session",
            target_id=session.id,
            source_asset_id=audio.id,
            task_type="singing_audio_metrics",
            executor="mock_singing",
            generation=7,
            idempotency_key=f"migration-generation:{session.id}",
            input_snapshot={},
        )
        OldResult = old_apps.get_model("analysis", "AnalysisResult")
        OldSeries = old_apps.get_model("singing", "AnalysisTimeSeries")
        OldResult.objects.create(
            task_id=task.id,
            protocol_version="1.0",
            is_mock=True,
            generation=99,
            payload={"kept": "result"},
        )
        OldSeries.objects.create(
            session_id=session.id,
            task_id=task.id,
            metric_type="volume",
            generation=99,
            sample_interval_ms=1000,
            values=[0.1, 0.2],
        )

        executor = MigrationExecutor(connection)
        executor.migrate(executor.loader.graph.leaf_nodes())

        from apps.analysis.models import AnalysisResult
        from apps.singing.models import AnalysisTimeSeries

        assert AnalysisResult.objects.get(task_id=task.id).payload == {"kept": "result"}
        assert AnalysisTimeSeries.objects.get(task_id=task.id).values == [0.1, 0.2]
        assert "generation" not in {field.name for field in AnalysisResult._meta.fields}
        assert "generation" not in {field.name for field in AnalysisTimeSeries._meta.fields}
        assert AnalysisTask.objects.get(pk=task.id).generation == 7
    finally:
        executor = MigrationExecutor(connection)
        executor.migrate(executor.loader.graph.leaf_nodes())
