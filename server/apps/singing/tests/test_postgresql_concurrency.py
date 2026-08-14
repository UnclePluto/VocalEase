from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

import pytest
from django.db import connection, connections

from apps.analysis.models import AnalysisTask
from apps.singing.services import submit_session

from .test_submission_idempotency import uploaded_session


@pytest.mark.django_db(transaction=True)
@pytest.mark.postgresql
def test_postgresql_concurrent_same_key_submit_creates_one_analysis_set():
    if connection.vendor != "postgresql":
        pytest.skip("提交并发由真实 PostgreSQL 行锁测试证明")
    patient, session = uploaded_session()
    barrier = Barrier(2)

    def submit():
        connections.close_all()
        try:
            barrier.wait(timeout=5)
            result = submit_session(
                session_id=session.id, patient_id=patient.id, idempotency_key="parallel-submit",
            )
            return result.created, result.task_ids
        finally:
            connections.close_all()

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _index: submit(), range(2)))

    assert sorted(created for created, _ids in results) == [False, True]
    assert results[0][1] == results[1][1]
    assert AnalysisTask.objects.filter(target_type="singing_session", target_id=session.id).count() == 1
