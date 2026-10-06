from django.db.models import Prefetch

from apps.analysis.models import AnalysisTask

from .models import SessionMedia, SingingSession


def sessions_for_patient(*, patient_id, created_from=None, created_to=None, include_media=False):
    queryset = SingingSession.objects.filter(patient_id=patient_id).select_related(
        "patient", "song", "song__accompaniment_asset", "playback_accompaniment_asset", "treatment_plan"
    )
    if include_media:
        queryset = queryset.prefetch_related(Prefetch(
            "media_bindings",
            queryset=SessionMedia.objects.select_related("asset").order_by("media_type"),
        ))
    if created_from:
        queryset = queryset.filter(created_at__date__gte=created_from)
    if created_to:
        queryset = queryset.filter(created_at__date__lte=created_to)
    return queryset


def sessions_for_admin(*, patient_id=None, status="", created_from=None, created_to=None, include_media=False):
    queryset = SingingSession.objects.select_related(
        "patient", "song", "song__accompaniment_asset", "playback_accompaniment_asset", "treatment_plan"
    )
    if include_media:
        queryset = queryset.prefetch_related(Prefetch(
            "media_bindings",
            queryset=SessionMedia.objects.select_related("asset").order_by("media_type"),
        ))
    if patient_id:
        queryset = queryset.filter(patient_id=patient_id)
    if status:
        queryset = queryset.filter(status=status)
    if created_from:
        queryset = queryset.filter(created_at__date__gte=created_from)
    if created_to:
        queryset = queryset.filter(created_at__date__lte=created_to)
    return queryset


def attach_analysis_details(session: SingingSession) -> SingingSession:
    session.prefetched_analysis_tasks = list(AnalysisTask.objects.filter(
        target_type=AnalysisTask.TargetType.SINGING_SESSION,
        target_id=session.id,
        generation=session.analysis_generation,
    ).select_related("analysis_result").prefetch_related("time_series").order_by("task_type"))
    return session
