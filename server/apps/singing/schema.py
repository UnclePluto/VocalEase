from drf_spectacular.utils import extend_schema_field
from rest_framework import serializers

from apps.analysis.models import AnalysisTask
from apps.doctors.models import Gender
from apps.patients.serializers import TreatmentPlanReadSerializer
from apps.songs.serializers import PatientSongReadSerializer
from common.api.schema import ApiEnvelopeSerializer

from .models import SingingSession
from .serializers import (
    PatientSingingSummarySerializer,
    PatientTreatmentProgressSerializer,
    SingingSessionReadSerializer,
    SingingSessionSummarySerializer,
)


@extend_schema_field(
    {
        "type": "string",
        "enum": [value for value, _label in SingingSession.Status.choices],
    },
    component_name="SingingSessionStatus",
)
class SingingSessionStatusField(serializers.ChoiceField):
    def __init__(self, **kwargs):
        super().__init__(choices=SingingSession.Status.choices, **kwargs)


class PatientPrimaryDoctorSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    name = serializers.CharField()


class PatientMeDataSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    medical_record_no = serializers.CharField()
    name = serializers.CharField()
    gender = serializers.ChoiceField(choices=Gender.choices)
    enrollment_age = serializers.IntegerField(min_value=0)
    phone = serializers.CharField()
    notes = serializers.CharField(allow_blank=True)
    primary_doctor = PatientPrimaryDoctorSerializer()
    active_treatment_plan = TreatmentPlanReadSerializer(allow_null=True)
    treatment_progress = PatientTreatmentProgressSerializer(allow_null=True)
    singing_summary = PatientSingingSummarySerializer()


class PatientMeEnvelopeSerializer(ApiEnvelopeSerializer):
    data = PatientMeDataSerializer()


class PaginationDataSerializer(serializers.Serializer):
    count = serializers.IntegerField(min_value=0)
    page = serializers.IntegerField(min_value=1)
    page_size = serializers.IntegerField(min_value=1)


class PatientSongPageDataSerializer(PaginationDataSerializer):
    results = PatientSongReadSerializer(many=True)


class PatientSongPageEnvelopeSerializer(ApiEnvelopeSerializer):
    data = PatientSongPageDataSerializer()


class PatientSongEnvelopeSerializer(ApiEnvelopeSerializer):
    data = PatientSongReadSerializer()


class PrivateUrlDataSerializer(serializers.Serializer):
    url = serializers.CharField()
    expires_at = serializers.DateTimeField()


class PrivateUrlEnvelopeSerializer(ApiEnvelopeSerializer):
    data = PrivateUrlDataSerializer()


class AnalysisTimeSeriesDataSerializer(serializers.Serializer):
    sample_interval_ms = serializers.IntegerField(min_value=1)
    values = serializers.ListField(child=serializers.FloatField())


class SingingAnalysisResultSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    task_type = serializers.ChoiceField(choices=AnalysisTask.TaskType.choices)
    status = serializers.ChoiceField(choices=AnalysisTask.Status.choices)
    generation = serializers.IntegerField(min_value=0)
    protocol_version = serializers.CharField()
    is_mock = serializers.BooleanField(allow_null=True)
    payload = serializers.JSONField(allow_null=True)
    time_series = serializers.DictField(child=AnalysisTimeSeriesDataSerializer())
    error_code = serializers.CharField(allow_blank=True)
    error_summary = serializers.CharField(allow_blank=True)
    attempt = serializers.IntegerField(min_value=0)


class PatientSingingSessionReadSerializer(SingingSessionReadSerializer):
    status = SingingSessionStatusField(read_only=True)
    analysis_task_ids = serializers.ListField(
        child=serializers.UUIDField(), read_only=True,
    )
    analysis_results = SingingAnalysisResultSerializer(many=True, read_only=True)


class PatientSingingSessionSummarySerializer(SingingSessionSummarySerializer):
    status = SingingSessionStatusField(read_only=True)


class SingingSessionPageDataSerializer(PaginationDataSerializer):
    results = PatientSingingSessionSummarySerializer(many=True)


class SingingSessionPageEnvelopeSerializer(ApiEnvelopeSerializer):
    data = SingingSessionPageDataSerializer()


class SingingSessionEnvelopeSerializer(ApiEnvelopeSerializer):
    data = PatientSingingSessionReadSerializer()


class SessionUploadGrantDataSerializer(serializers.Serializer):
    session_id = serializers.UUIDField()
    asset_id = serializers.UUIDField()
    object_key = serializers.CharField()
    expires_at = serializers.DateTimeField()
    upload_url = serializers.CharField()
    upload_token = serializers.CharField(allow_blank=True)
    fields = serializers.DictField()


class SessionUploadGrantEnvelopeSerializer(ApiEnvelopeSerializer):
    data = SessionUploadGrantDataSerializer()


class PatientMediaUploadGrantDataSerializer(serializers.Serializer):
    asset_id = serializers.UUIDField()
    object_key = serializers.CharField()
    expires_at = serializers.DateTimeField()
    upload_url = serializers.CharField()
    upload_token = serializers.CharField(allow_blank=True)
    fields = serializers.DictField()


class PatientMediaUploadGrantEnvelopeSerializer(ApiEnvelopeSerializer):
    data = PatientMediaUploadGrantDataSerializer()


class SessionMutationDataSerializer(serializers.Serializer):
    session_id = serializers.UUIDField()
    status = SingingSessionStatusField()
    analysis_task_ids = serializers.ListField(child=serializers.UUIDField())


class SessionMutationEnvelopeSerializer(ApiEnvelopeSerializer):
    data = SessionMutationDataSerializer()


class SongPlaybackGrantSerializer(PrivateUrlDataSerializer):
    asset_id = serializers.UUIDField()


class SongPlaybackGrantEnvelopeSerializer(ApiEnvelopeSerializer):
    data = SongPlaybackGrantSerializer()


class ReferencePitchDataSerializer(serializers.Serializer):
    status = serializers.CharField()
    version = serializers.UUIDField(allow_null=True)
    schema_version = serializers.IntegerField()
    notes = serializers.ListField(child=serializers.DictField())
    origin = serializers.DictField(required=False)


class ReferencePitchEnvelopeSerializer(ApiEnvelopeSerializer):
    data = ReferencePitchDataSerializer()
