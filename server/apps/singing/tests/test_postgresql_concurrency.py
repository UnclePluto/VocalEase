from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Event, Lock

import pytest
from django.db import connection, connections

from apps.analysis.models import AnalysisResult, AnalysisTask
from apps.analysis.services import run_analysis
from apps.singing.executors import mock_singing_result
from apps.singing.models import SessionMedia, SingingSession
from apps.singing.services import SingingMediaConflict

from .test_analysis_execution import _add_ready_video
from .test_submission_idempotency import uploaded_session


def _session_lock_wrapper(barrier, entered):
    def wrapper(execute, sql, params, many, context):
        lowered = sql.lower()
        if not entered[0] and "singing_singingsession" in lowered and "for update" in lowered:
            entered[0] = True
            barrier.wait(timeout=5)
        return execute(sql, params, many, context)

    return wrapper


def _disable_external_dispatch(monkeypatch):
    from apps.analysis.tasks import run_analysis_task

    monkeypatch.setattr(run_analysis_task, "delay", lambda task_id: None)


@pytest.mark.django_db(transaction=True)
@pytest.mark.postgresql
def test_postgresql_concurrent_same_key_submit_waits_on_real_session_row_lock(monkeypatch):
    if connection.vendor != "postgresql":
        pytest.skip("提交并发由真实 PostgreSQL 行锁测试证明")
    patient, session = uploaded_session()
    from apps.singing import services
    _disable_external_dispatch(monkeypatch)

    before_lock = Barrier(2)
    lock_held = Event()
    release_lock = Event()
    first_call = [True]
    call_guard = Lock()
    original_create = services._create_generation_tasks_locked

    def hold_first_transaction(*args, **kwargs):
        with call_guard:
            should_hold = first_call[0]
            first_call[0] = False
        if should_hold:
            lock_held.set()
            assert release_lock.wait(timeout=5)
        return original_create(*args, **kwargs)

    monkeypatch.setattr(services, "_create_generation_tasks_locked", hold_first_transaction)

    def submit():
        connections.close_all()
        entered = [False]
        try:
            with connections["default"].execute_wrapper(
                _session_lock_wrapper(before_lock, entered),
            ):
                result = services.submit_session(
                    session_id=session.id,
                    patient_id=patient.id,
                    idempotency_key="parallel-submit",
                )
            return result.created, result.task_ids
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(submit)
        second = pool.submit(submit)
        assert lock_held.wait(timeout=5)
        assert not first.done() and not second.done()
        release_lock.set()
        results = [first.result(timeout=5), second.result(timeout=5)]

    assert sorted(created for created, _ids in results) == [False, True]
    assert results[0][1] == results[1][1]
    assert AnalysisTask.objects.filter(
        target_type="singing_session",
        target_id=session.id,
        generation=0,
    ).count() == 1


def _created_session_without_media():
    patient, session = uploaded_session()
    SessionMedia.objects.filter(session=session).delete()
    SingingSession.objects.filter(pk=session.id).update(status="created")
    session.refresh_from_db()
    return patient, session


@pytest.mark.django_db(transaction=True)
@pytest.mark.postgresql
def test_postgresql_same_and_different_upload_keys_are_serialized_by_session_lock():
    if connection.vendor != "postgresql":
        pytest.skip("上传 intent 并发由真实 PostgreSQL 行锁测试证明")
    from apps.singing.services import issue_session_upload_grant

    patient, same_session = _created_session_without_media()

    def issue(session, key, barrier):
        connections.close_all()
        entered = [False]
        try:
            with connections["default"].execute_wrapper(
                _session_lock_wrapper(barrier, entered),
            ):
                result = issue_session_upload_grant(
                    session_id=session.id,
                    patient_id=patient.id,
                    media_type="singing_audio",
                    mime="audio/mpeg",
                    size=6,
                    idempotency_key=key,
                )
            return "ok", result.created, result.asset.id
        except SingingMediaConflict as exc:
            return "conflict", exc.default_code, None
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as pool:
        barrier = Barrier(2)
        same = list(pool.map(lambda _index: issue(same_session, "same-upload", barrier), range(2)))
    assert sorted(row[1] for row in same) == [False, True]
    assert same[0][2] == same[1][2]

    different_session = SingingSession.objects.create_from_snapshots(
        patient=patient,
        song=same_session.song,
    )
    with ThreadPoolExecutor(max_workers=2) as pool:
        barrier = Barrier(2)
        different = list(pool.map(
            lambda index: issue(different_session, f"different-{index}", barrier),
            range(2),
        ))
    assert sorted(row[0] for row in different) == ["conflict", "ok"]
    assert SessionMedia.objects.filter(session=different_session).count() == 1


@pytest.mark.django_db(transaction=True)
@pytest.mark.postgresql
def test_postgresql_failure_retry_interleaving_fences_old_generation(monkeypatch):
    if connection.vendor != "postgresql":
        pytest.skip("失败重试交错由真实 PostgreSQL 行锁测试证明")
    patient, session = uploaded_session()
    _add_ready_video(patient, session)
    from apps.singing import services
    _disable_external_dispatch(monkeypatch)

    submitted = services.submit_session(
        session_id=session.id,
        patient_id=patient.id,
        idempotency_key="pg-generation-zero",
    )
    audio = AnalysisTask.objects.get(
        target_id=session.id,
        generation=0,
        task_type="singing_audio_metrics",
    )
    face = AnalysisTask.objects.get(
        target_id=session.id,
        generation=0,
        task_type="face_landmarks",
    )
    audio_claim = services.claim_singing_analysis_task(audio.id)
    face_claim = services.claim_singing_analysis_task(face.id)
    failure_written = Event()
    release_failure = Event()
    retry_entered = Event()
    original_fail = services._fail_singing_generation_locked

    def hold_failed_generation(*args, **kwargs):
        result = original_fail(*args, **kwargs)
        failure_written.set()
        assert release_failure.wait(timeout=5)
        return result

    monkeypatch.setattr(services, "_fail_singing_generation_locked", hold_failed_generation)

    def fail_face():
        connections.close_all()
        try:
            return services.finalize_singing_failure(
                face.id,
                face_claim.claim_token,
                code="analysis_result_invalid",
                summary="面部结果无效",
            )
        finally:
            connections.close_all()

    def retry_after_failure():
        connections.close_all()

        def mark_retry_entry(execute, sql, params, many, context):
            lowered = sql.lower()
            if "singing_singingsession" in lowered and "for update" in lowered:
                retry_entered.set()
            return execute(sql, params, many, context)

        try:
            with connections["default"].execute_wrapper(mark_retry_entry):
                return services.retry_session(
                    session_id=session.id,
                    patient_id=patient.id,
                    idempotency_key="pg-generation-one",
                )
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as pool:
        failed = pool.submit(fail_face)
        assert failure_written.wait(timeout=5)
        retried = pool.submit(retry_after_failure)
        assert retry_entered.wait(timeout=5)
        assert not retried.done()
        release_failure.set()
        failed.result(timeout=5)
        retry = retried.result(timeout=5)

    services.finalize_singing_success(
        audio.id,
        audio_claim.claim_token,
        mock_singing_result(audio.id, 60),
    )
    assert set(submitted.task_ids).isdisjoint(retry.task_ids)
    assert not AnalysisResult.objects.filter(task__generation=0, task__target_id=session.id).exists()
    assert AnalysisTask.objects.filter(
        target_id=session.id,
        generation=0,
        status="failed",
    ).count() == 2
    monkeypatch.undo()
    for task_id in retry.task_ids:
        run_analysis(task_id)
    session.refresh_from_db()
    assert session.status == "completed" and session.analysis_generation == 1
