from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import date
from uuid import UUID
from decimal import Decimal

from rest_framework import serializers

from common.api.pagination import PaginationQuerySerializer


TREATMENT_STATUSES = ("active", "pending", "completed", "cancelled", "none")


@dataclass(frozen=True)
class PatientMetrics:
    patient_id: UUID
    completed_count: int
    total_duration_seconds: int
    treatment_progress: Decimal | None
    average_score: Decimal | None
    score_trend: Decimal | None
    burp_improvement: Decimal | None
    is_mock: bool


@dataclass(frozen=True)
class DashboardMetrics:
    active_patient_count: int
    completed_session_count: int
    average_score: Decimal | None
    average_burp_count: Decimal | None
    is_mock: bool


@dataclass(frozen=True)
class PatientMetricFilters:
    name: str = ""
    medical_record_no: str = ""
    treatment_status: str = ""
    primary_doctor: UUID | None = None
    created_from: date | None = None
    created_to: date | None = None

    def as_json(self) -> dict[str, str]:
        values = asdict(self)
        return {key: value.isoformat() if hasattr(value, "isoformat") else str(value) if value is not None else "" for key, value in values.items()}

    @classmethod
    def from_validated(cls, values):
        return cls(**{field: values.get(field) for field in cls.__dataclass_fields__})


class PatientMetricQuerySerializer(PaginationQuerySerializer):
    name = serializers.CharField(required=False, allow_blank=True, default="", max_length=64, trim_whitespace=True)
    medical_record_no = serializers.CharField(required=False, allow_blank=True, default="", max_length=16, trim_whitespace=True)
    treatment_status = serializers.ChoiceField(required=False, allow_blank=True, default="", choices=TREATMENT_STATUSES)
    primary_doctor = serializers.UUIDField(required=False, allow_null=True, default=None)
    created_from = serializers.DateField(required=False, allow_null=True, default=None)
    created_to = serializers.DateField(required=False, allow_null=True, default=None)

    def validate(self, attrs):
        if attrs.get("created_from") and attrs.get("created_to") and attrs["created_from"] > attrs["created_to"]:
            raise serializers.ValidationError({"created_to": "结束日期不能早于开始日期"})
        return attrs


class ExportFiltersSerializer(serializers.Serializer):
    name = serializers.CharField(required=False, allow_blank=True, default="", max_length=64, trim_whitespace=True)
    medical_record_no = serializers.CharField(required=False, allow_blank=True, default="", max_length=16, trim_whitespace=True)
    treatment_status = serializers.ChoiceField(required=False, allow_blank=True, default="", choices=TREATMENT_STATUSES)
    primary_doctor = serializers.UUIDField(required=False, allow_null=True, default=None)
    created_from = serializers.DateField(required=False, allow_null=True, default=None)
    created_to = serializers.DateField(required=False, allow_null=True, default=None)

    def to_internal_value(self, data):
        unexpected = set(data) - set(self.fields)
        if unexpected:
            raise serializers.ValidationError({key: "不支持的筛选参数" for key in sorted(unexpected)})
        return super().to_internal_value(data)

    def validate(self, attrs):
        if attrs.get("created_from") and attrs.get("created_to") and attrs["created_from"] > attrs["created_to"]:
            raise serializers.ValidationError({"created_to": "结束日期不能早于开始日期"})
        return attrs


class ExportRequestSerializer(serializers.Serializer):
    format = serializers.ChoiceField(choices=("csv", "xlsx"))
    filters = ExportFiltersSerializer(required=False, default=dict)
    selected_ids = serializers.ListField(child=serializers.UUIDField(), required=False, default=list, max_length=10000)
    idempotency_key = serializers.CharField(max_length=128)

    def to_internal_value(self, data):
        unexpected = set(data) - set(self.fields)
        if unexpected:
            raise serializers.ValidationError({key: "不支持的请求参数" for key in sorted(unexpected)})
        return super().to_internal_value(data)

    def validate_idempotency_key(self, value):
        if not value.strip():
            raise serializers.ValidationError("幂等键不能为空")
        return value.strip()
