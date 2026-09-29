from __future__ import annotations

import hashlib
import json
from datetime import timedelta
from urllib.parse import quote

from django.conf import settings
from django.db import IntegrityError, transaction
from django.http import HttpResponse
from django.shortcuts import get_object_or_404
from django.utils import timezone
from rest_framework import status
from rest_framework.exceptions import APIException, ValidationError
from rest_framework.views import APIView
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import extend_schema

from apps.accounts.views import api_response
from apps.audit.services import record
from common.api.permissions import IsAdminNamespaceUser, MustChangePasswordPermission
from common.api.schema import ApiEnvelopeSerializer

from .calculations import METRIC_VERSION
from .assets import ExportAssetError, issue_export_private_url, resolve_export_asset
from .dto import ExportRequestSerializer, PatientMetricFilters, PatientMetricQuerySerializer
from .exporters import CsvExporter, XlsxExporter, export_rows
from .models import ExportJob, ExportJobItem
from .runtime import ExportRuntimeConfigurationError, get_export_runtime_config
from .selectors import dashboard_metrics, exportable_row, filtered_patients, patient_metric_rows
from .tasks import expire_export_job, request_export_cleanup, run_export_job_task


class ExportConflict(APIException):
    status_code = 409
    default_code = "export_idempotency_conflict"
    default_detail = "导出幂等键已用于其他请求"


class ExportAssetConflict(APIException):
    status_code = 409
    default_code = "export_asset_invalid"
    default_detail = "导出媒体状态不可信"


class ExportRuntimeUnavailable(APIException):
    status_code = 503
    default_code = "export_runtime_unavailable"
    default_detail = "导出服务配置不可用"


def _validate_query(request):
    serializer = PatientMetricQuerySerializer(data=request.query_params)
    unexpected = set(request.query_params) - set(serializer.fields)
    if unexpected:
        raise ValidationError({key: "不支持的查询参数" for key in sorted(unexpected)})
    serializer.is_valid(raise_exception=True)
    return serializer.validated_data


def _job_data(job):
    return {
        "id": str(job.id),
        "metric_version": METRIC_VERSION,
        "format": job.format,
        "status": job.status,
        "count": job.snapshot_count,
        "failure_reason": job.failure_reason,
        "expires_at": job.expires_at.isoformat(),
        "result_asset_id": str(job.result_asset_id) if job.result_asset_id else None,
    }


class DashboardView(APIView):
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]

    @extend_schema(responses=ApiEnvelopeSerializer)
    def get(self, request):
        if request.query_params:
            raise ValidationError({key: "不支持的查询参数" for key in sorted(request.query_params)})
        return api_response(data=dashboard_metrics(), request_id=request.request_id)


class PatientMetricsView(APIView):
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]

    @extend_schema(responses=ApiEnvelopeSerializer)
    def get(self, request):
        query = _validate_query(request)
        filters = PatientMetricFilters.from_validated(query)
        queryset = filtered_patients(filters)
        count = queryset.count()
        start = (query["page"] - 1) * query["page_size"]
        patients = queryset[start:start + query["page_size"]]
        return api_response(data={
            "metric_version": METRIC_VERSION,
            "count": count,
            "page": query["page"],
            "page_size": query["page_size"],
            "results": patient_metric_rows(patients),
        }, request_id=request.request_id)


def _fingerprint(*, export_format, filters, selected_ids):
    payload = json.dumps({
        "format": export_format,
        "filters": filters,
        "selected_ids": sorted(str(value) for value in selected_ids),
        "metric_version": METRIC_VERSION,
    }, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _content_disposition(extension):
    localized = f"VocaEase患者统计-{timezone.localdate().isoformat()}.{extension}"
    return f"attachment; filename=\"vocaease-patient-metrics.{extension}\"; filename*=UTF-8''{quote(localized, safe='')}"


def _snapshot_job_items(job, patient_queryset, *, batch_size=500):
    batch = []
    for position, patient_id in enumerate(
        patient_queryset.values_list("id", flat=True).iterator(chunk_size=batch_size)
    ):
        batch.append(ExportJobItem(job=job, patient_id=patient_id, position=position))
        if len(batch) == batch_size:
            ExportJobItem.objects.bulk_create(batch, batch_size=batch_size)
            batch.clear()
    if batch:
        ExportJobItem.objects.bulk_create(batch, batch_size=batch_size)


def _find_or_create_job(
    *, user, key, fingerprint, filters, selected_ids, patient_queryset,
    count, export_format, ttl_seconds,
):
    try:
        with transaction.atomic():
            existing = ExportJob.objects.select_for_update().filter(creator=user, idempotency_key=key).first()
            if existing:
                if existing.request_fingerprint != fingerprint:
                    raise ExportConflict()
                return existing, False
            job = ExportJob.objects.create(
                creator=user,
                normalized_filters=filters,
                selected_ids=[str(value) for value in selected_ids],
                snapshot_count=count,
                format=export_format,
                expires_at=timezone.now() + timedelta(seconds=ttl_seconds),
                idempotency_key=key,
                request_fingerprint=fingerprint,
            )
            _snapshot_job_items(job, patient_queryset)
            return job, True
    except IntegrityError:
        existing = ExportJob.objects.get(creator=user, idempotency_key=key)
        if existing.request_fingerprint != fingerprint:
            raise ExportConflict()
        return existing, False


class ExportCreateView(APIView):
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]

    @extend_schema(
        request=ExportRequestSerializer,
        responses={
            (200, "text/csv"): OpenApiTypes.BINARY,
            (
                200,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            ): OpenApiTypes.BINARY,
            202: ApiEnvelopeSerializer,
        },
    )
    def post(self, request):
        try:
            runtime = get_export_runtime_config()
        except ExportRuntimeConfigurationError as exc:
            raise ExportRuntimeUnavailable() from exc
        serializer = ExportRequestSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        values = serializer.validated_data
        filters = PatientMetricFilters.from_validated(values["filters"])
        selected_ids = tuple(dict.fromkeys(values["selected_ids"]))
        patient_queryset = filtered_patients(filters, selected_ids=selected_ids)
        count = patient_queryset.count()
        export_format = values["format"]
        exporter = CsvExporter() if export_format == "csv" else XlsxExporter()
        if count <= runtime.sync_limit:
            rows = [exportable_row(row) for row in patient_metric_rows(patient_queryset)]
            content = export_rows(rows, export_format)
            record(
                actor=request.user,
                action="analytics.export_sync",
                target=None,
                changes={"metric_version": METRIC_VERSION, "format": export_format, "count": count, "filters": filters.as_json()},
                request_id=request.request_id,
            )
            response = HttpResponse(content, content_type=exporter.mime)
            response["Content-Disposition"] = _content_disposition(exporter.extension)
            response["X-Metric-Version"] = METRIC_VERSION
            response["X-Export-Row-Count"] = str(count)
            return response
        normalized_filters = filters.as_json()
        fingerprint = _fingerprint(export_format=export_format, filters=normalized_filters, selected_ids=selected_ids)
        job, created = _find_or_create_job(
            user=request.user,
            key=values["idempotency_key"],
            fingerprint=fingerprint,
            filters=normalized_filters,
            selected_ids=selected_ids,
            patient_queryset=patient_queryset,
            count=count,
            export_format=export_format,
            ttl_seconds=runtime.ttl_seconds,
        )
        if created:
            record(
                actor=request.user,
                action="analytics.export_create",
                target=job,
                changes={"metric_version": METRIC_VERSION, "format": export_format, "count": count, "filters": normalized_filters},
                request_id=request.request_id,
            )
            if settings.ANALYTICS_AUTO_DISPATCH_EXPORTS:
                transaction.on_commit(lambda: run_export_job_task.delay(str(job.id)))
        return api_response(
            data=_job_data(job),
            request_id=request.request_id,
            status_code=status.HTTP_202_ACCEPTED if created else status.HTTP_200_OK,
        )


class ExportDetailView(APIView):
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]

    @extend_schema(responses=ApiEnvelopeSerializer)
    def get(self, request, job_id):
        job = get_object_or_404(ExportJob, pk=job_id)
        if job.expires_at <= timezone.now() and job.status != ExportJob.Status.EXPIRED:
            job = expire_export_job(job.id)
        elif job.status == ExportJob.Status.READY:
            try:
                resolve_export_asset(job)
            except ExportAssetError as exc:
                job = request_export_cleanup(
                    job.id, status=ExportJob.Status.FAILED, failure_reason=exc.message,
                )
        return api_response(data=_job_data(job), request_id=request.request_id)


class ExportPrivateUrlView(APIView):
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]

    @extend_schema(request=None, responses=ApiEnvelopeSerializer)
    def post(self, request, job_id):
        get_object_or_404(ExportJob, pk=job_id)
        try:
            job, asset, private = issue_export_private_url(job_id)
        except ExportRuntimeConfigurationError as exc:
            raise ExportRuntimeUnavailable() from exc
        except ExportAssetError as exc:
            if exc.code == "export_expired":
                expire_export_job(job_id)
            elif exc.code.startswith("export_asset_"):
                request_export_cleanup(job_id, status=ExportJob.Status.FAILED, failure_reason=exc.message)
            raise ExportAssetConflict(exc.message, code=exc.code) from exc
        record(
            actor=request.user,
            action="analytics.export_download",
            target=job,
            changes={"metric_version": METRIC_VERSION, "asset_id": str(asset.id), "format": job.format},
            request_id=request.request_id,
        )
        return api_response(data={
            "metric_version": METRIC_VERSION,
            "url": private.url,
            "expires_at": private.expires_at.isoformat(),
        }, request_id=request.request_id)
