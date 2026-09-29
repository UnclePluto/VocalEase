from uuid import UUID

from celery import shared_task

from .services import (AnalysisRetryRequested, recover_analysis_tasks,
                       run_analysis)


@shared_task(bind=True, max_retries=3)
def run_analysis_task(self, task_id: str):
    """显式协调 DB retrying 状态与 Celery 的初次 + 3 次投递。"""
    task_uuid = UUID(task_id)
    try:
        return str(run_analysis(task_uuid).id)
    except AnalysisRetryRequested as exc:
        if exc.should_retry:
            # Celery 的 request.retries 仅用于退避；是否还能重试只由数据库 CAS 决定。
            countdown = min(2 ** min(self.request.retries, 5), 30)
            raise self.retry(exc=AnalysisRetryRequested(should_retry=True), countdown=countdown, max_retries=1000) from exc
        return str(task_uuid)


@shared_task
def recover_analysis_tasks_task(batch_size: int = 100):
    return recover_analysis_tasks(batch_size=batch_size)


redispatch_pending_analysis_tasks = recover_analysis_tasks_task
