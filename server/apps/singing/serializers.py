from rest_framework import serializers

from common.api.pagination import PaginationQuerySerializer

from .models import SessionMedia, SingingSession


class CreateSessionSerializer(serializers.Serializer):
    song_id = serializers.UUIDField()


class SessionUploadGrantSerializer(serializers.Serializer):
    media_type = serializers.ChoiceField(choices=("singing_audio", "singing_video"))
    mime = serializers.CharField(max_length=127)
    size = serializers.IntegerField(min_value=1)


class ConfirmSessionMediaSerializer(serializers.Serializer):
    asset_id = serializers.UUIDField(required=False)
    object_key = serializers.CharField(max_length=255, required=False, allow_blank=False)

    def validate(self, attrs):
        if not attrs.get("asset_id") and not attrs.get("object_key"):
            raise serializers.ValidationError("asset_id 或 object_key 至少提供一个")
        return attrs


class SessionListQuerySerializer(PaginationQuerySerializer):
    status = serializers.ChoiceField(choices=SingingSession.Status.choices, required=False, allow_blank=True, default="")
    created_from = serializers.DateField(required=False, allow_null=True, default=None)
    created_to = serializers.DateField(required=False, allow_null=True, default=None)

    def validate(self, attrs):
        if attrs.get("created_from") and attrs.get("created_to") and attrs["created_from"] > attrs["created_to"]:
            raise serializers.ValidationError({"created_to": "结束日期不能早于开始日期"})
        return attrs


class AdminSessionListQuerySerializer(SessionListQuerySerializer):
    patient_id = serializers.UUIDField(required=False, allow_null=True, default=None)


class SessionMediaReadSerializer(serializers.ModelSerializer):
    asset_id = serializers.UUIDField(read_only=True)
    status = serializers.CharField(source="asset.status", read_only=True)
    mime = serializers.CharField(source="asset.mime", read_only=True)
    size = serializers.IntegerField(source="asset.size", read_only=True)

    class Meta:
        model = SessionMedia
        fields = ("asset_id", "media_type", "status", "mime", "size", "confirmed_at")


class SingingSessionReadSerializer(serializers.ModelSerializer):
    patient = serializers.JSONField(source="patient_snapshot", read_only=True)
    song = serializers.JSONField(source="song_snapshot", read_only=True)
    treatment_plan = serializers.JSONField(source="treatment_plan_snapshot", read_only=True)
    media = SessionMediaReadSerializer(source="media_bindings", many=True, read_only=True)
    analysis_task_ids = serializers.SerializerMethodField()
    analysis_results = serializers.SerializerMethodField()

    class Meta:
        model = SingingSession
        fields = (
            "id", "patient", "song", "treatment_plan", "status", "score", "burp_count",
            "duration_seconds", "is_mock", "created_source", "media", "analysis_task_ids",
            "analysis_results",
            "submitted_at", "completed_at", "created_at", "updated_at",
        )

    def get_analysis_task_ids(self, obj):
        from apps.analysis.models import AnalysisTask
        return [
            str(value) for value in AnalysisTask.objects.filter(
                target_type="singing_session", target_id=obj.id,
            ).order_by("task_type").values_list("id", flat=True)
        ]

    def get_analysis_results(self, obj):
        from apps.analysis.models import AnalysisTask
        tasks = AnalysisTask.objects.filter(
            target_type="singing_session", target_id=obj.id,
        ).select_related("analysis_result").prefetch_related("time_series").order_by("task_type")
        rows = []
        for task in tasks:
            result = getattr(task, "analysis_result", None)
            series = {
                item.metric_type: {
                    "sample_interval_ms": item.sample_interval_ms,
                    "values": item.values,
                }
                for item in task.time_series.all()
            }
            rows.append({
                "id": str(task.id), "task_type": task.task_type, "status": task.status,
                "protocol_version": task.protocol_version,
                "is_mock": result.is_mock if result else None,
                "payload": result.payload if result else None,
                "time_series": series,
                "error_code": task.error_code, "error_summary": task.error_summary,
                "attempt": task.attempt,
            })
        return rows
