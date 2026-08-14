from uuid import UUID

from celery import shared_task

from .contracts import TransientAnalysisError
from .services import run_analysis


@shared_task(bind=True, autoretry_for=(TransientAnalysisError,), retry_backoff=True, max_retries=3)
def run_analysis_task(self, task_id: str):
    """队列消息只携带任务 UUID，输入及授权均由数据库重建。"""
    return str(run_analysis(UUID(task_id)).id)
