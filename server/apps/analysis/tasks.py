from uuid import UUID

from celery import shared_task

from .contracts import TransientAnalysisError
from .services import (exhaust_analysis_retries, redispatch_pending_analyses,
                       run_analysis)


@shared_task(bind=True, max_retries=3)
def run_analysis_task(self, task_id: str):
    """显式协调 DB retrying 状态与 Celery 的初次 + 3 次投递。"""
    task_uuid = UUID(task_id)
    try:
        return str(run_analysis(task_uuid).id)
    except TransientAnalysisError as exc:
        if self.request.retries < self.max_retries:
            countdown = min(2 ** self.request.retries, 30)
            raise self.retry(
                exc=TransientAnalysisError("分析服务暂时不可用"),
                countdown=countdown,
            ) from exc
        return str(exhaust_analysis_retries(task_uuid).id)


@shared_task
def redispatch_pending_analysis_tasks(batch_size: int = 100):
    return redispatch_pending_analyses(batch_size=batch_size)
