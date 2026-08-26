from django.shortcuts import get_object_or_404
from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import status
from rest_framework.exceptions import ValidationError
from rest_framework.permissions import BasePermission
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from apps.accounts.models import Role
from apps.accounts.views import api_response
from apps.audit.services import record
from apps.patients.models import PatientProfile, TreatmentPlan
from apps.patients.selectors import patient_singing_summary, patient_treatment_progress
from apps.patients.serializers import TreatmentPlanReadSerializer
from common.api.pagination import paginated_data, validated_query
from common.api.permissions import IsAdminNamespaceUser, MustChangePasswordPermission
from common.api.schema import ApiEnvelopeSerializer

from .schema import (
    PatientMeEnvelopeSerializer,
    SessionMutationEnvelopeSerializer,
    SessionUploadGrantEnvelopeSerializer,
    SingingSessionEnvelopeSerializer,
    SingingSessionPageEnvelopeSerializer,
)
from .selectors import attach_analysis_details, sessions_for_admin, sessions_for_patient
from .serializers import (
    AdminSessionListQuerySerializer, ConfirmSessionMediaSerializer, CreateSessionSerializer,
    PatientSingingSummarySerializer, PatientTreatmentProgressSerializer,
    SessionListQuerySerializer, SessionUploadGrantSerializer, SingingSessionReadSerializer,
    SingingSessionSummarySerializer,
)
from .services import cancel_session, confirm_session_media, create_session, issue_session_upload_grant, retry_session, submit_session


def _validated_session_query(request, serializer_class):
    supported = set(serializer_class().fields)
    unexpected = set(request.query_params) - supported
    if unexpected:
        raise ValidationError({key: "不支持的查询参数" for key in sorted(unexpected)})
    return validated_query(request, serializer_class)


class IsPatientUser(BasePermission):
    message = "仅患者可访问患者服务"

    def has_permission(self, request, view):
        user = request.user
        return bool(
            user and user.is_authenticated and user.role == Role.PATIENT and user.is_active
            and user.deleted_at is None and not user.must_change_password
        )


def patient_for_request(request):
    return get_object_or_404(
        PatientProfile.objects.select_related("primary_doctor", "user"),
        user=request.user, deleted_at__isnull=True,
    )


class PatientMeView(APIView):
    permission_classes = [IsPatientUser, MustChangePasswordPermission]

    @extend_schema(responses={200: PatientMeEnvelopeSerializer})
    def get(self, request):
        patient = patient_for_request(request)
        plan = TreatmentPlan.objects.filter(
            patient=patient, status=TreatmentPlan.Status.ACTIVE, deleted_at__isnull=True,
        ).first()
        treatment_progress = patient_treatment_progress(patient=patient)
        singing_summary = patient_singing_summary(patient=patient)
        data = {
            "id": str(patient.id), "medical_record_no": patient.medical_record_no,
            "name": patient.name, "gender": patient.gender, "enrollment_age": patient.enrollment_age,
            "phone": patient.phone, "notes": patient.notes,
            "primary_doctor": {"id": str(patient.primary_doctor_id), "name": patient.primary_doctor.name},
            "active_treatment_plan": TreatmentPlanReadSerializer(plan).data if plan else None,
            "treatment_progress": (
                PatientTreatmentProgressSerializer(treatment_progress).data
                if treatment_progress else None
            ),
            "singing_summary": PatientSingingSummarySerializer(singing_summary).data,
        }
        return api_response(data=data, request_id=request.request_id)


class PatientSessionListView(APIView):
    permission_classes = [IsPatientUser, MustChangePasswordPermission]

    @extend_schema(responses={200: SingingSessionPageEnvelopeSerializer})
    def get(self, request):
        patient = patient_for_request(request)
        query = _validated_session_query(request, SessionListQuerySerializer)
        queryset = sessions_for_patient(
            patient_id=patient.id, created_from=query["created_from"], created_to=query["created_to"],
        )
        if query["status"]:
            queryset = queryset.filter(status=query["status"])
        data = paginated_data(queryset, page=query["page"], page_size=query["page_size"])
        data["results"] = SingingSessionSummarySerializer(data["results"], many=True).data
        return api_response(data=data, request_id=request.request_id)

    @extend_schema(
        request=CreateSessionSerializer,
        parameters=[
            OpenApiParameter(
                name="Idempotency-Key",
                location=OpenApiParameter.HEADER,
                required=True,
                type=str,
            ),
        ],
        responses={200: SingingSessionEnvelopeSerializer, 201: SingingSessionEnvelopeSerializer},
    )
    def post(self, request):
        patient = patient_for_request(request)
        serializer = CreateSessionSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        try:
            idempotency_key = request.headers["Idempotency-Key"]
        except KeyError as exc:
            raise ValidationError(
                {"idempotency_key": "必须提供 Idempotency-Key 请求头"},
            ) from exc
        result = create_session(
            patient_id=patient.id,
            idempotency_key=idempotency_key,
            **serializer.validated_data,
        )
        return api_response(
            data=SingingSessionReadSerializer(result.session).data,
            request_id=request.request_id,
            status_code=status.HTTP_201_CREATED if result.created else status.HTTP_200_OK,
        )


class PatientSessionMixin:
    permission_classes = [IsPatientUser, MustChangePasswordPermission]

    def get_session(self, request, session_id, *, include_details=False):
        patient = patient_for_request(request)
        session = get_object_or_404(
            sessions_for_patient(patient_id=patient.id, include_media=include_details),
            pk=session_id,
        )
        return attach_analysis_details(session) if include_details else session


class PatientSessionDetailView(PatientSessionMixin, APIView):
    @extend_schema(responses={200: SingingSessionEnvelopeSerializer})
    def get(self, request, session_id):
        return api_response(
            data=SingingSessionReadSerializer(
                self.get_session(request, session_id, include_details=True),
            ).data,
            request_id=request.request_id,
        )


class PatientSessionUploadGrantView(PatientSessionMixin, APIView):
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"

    @extend_schema(
        request=SessionUploadGrantSerializer,
        parameters=[
            OpenApiParameter(
                name="Idempotency-Key",
                location=OpenApiParameter.HEADER,
                required=False,
                type=str,
            ),
        ],
        responses={
            200: SessionUploadGrantEnvelopeSerializer,
            201: SessionUploadGrantEnvelopeSerializer,
        },
    )
    def post(self, request, session_id):
        session = self.get_session(request, session_id)
        serializer = SessionUploadGrantSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        result = issue_session_upload_grant(
            session_id=session.id, patient_id=session.patient_id,
            idempotency_key=request.headers.get("Idempotency-Key", ""),
            **serializer.validated_data,
        )
        session, asset, grant = result.session, result.asset, result.grant
        data = {
            "session_id": str(session.id), "asset_id": str(asset.id), "object_key": grant.object_key,
            "expires_at": grant.expires_at.isoformat(), "upload_url": grant.upload_url,
            "upload_token": grant.upload_token, "fields": grant.fields or {},
        }
        if asset.backend == "local" and grant.upload_token:
            data.update(upload_url=f"/api/v1/media/local-upload/{asset.id}/?signature={grant.upload_token}", upload_token="")
        record(
            actor=request.user, action="singing.media_upload_grant", target=asset,
            changes={"session_id": str(session.id), "media_type": asset.media_type, "reissued": not result.created},
            request_id=request.request_id,
        )
        return api_response(
            data=data, request_id=request.request_id,
            status_code=status.HTTP_201_CREATED if result.created else status.HTTP_200_OK,
        )


class PatientSessionConfirmUploadView(PatientSessionMixin, APIView):
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "credential_upload"

    @extend_schema(
        request=ConfirmSessionMediaSerializer,
        responses={200: SingingSessionEnvelopeSerializer},
    )
    def post(self, request, session_id):
        session = self.get_session(request, session_id)
        serializer = ConfirmSessionMediaSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        session, _binding = confirm_session_media(
            session_id=session.id, patient_id=session.patient_id, **serializer.validated_data,
        )
        return api_response(data=SingingSessionReadSerializer(session).data, request_id=request.request_id)


class PatientSessionSubmitView(PatientSessionMixin, APIView):
    @extend_schema(
        request=None,
        parameters=[
            OpenApiParameter(
                name="Idempotency-Key",
                location=OpenApiParameter.HEADER,
                required=True,
                type=str,
            ),
        ],
        responses={
            200: SessionMutationEnvelopeSerializer,
            202: SessionMutationEnvelopeSerializer,
        },
    )
    def post(self, request, session_id):
        session = self.get_session(request, session_id)
        result = submit_session(
            session_id=session.id, patient_id=session.patient_id,
            idempotency_key=request.headers.get("Idempotency-Key", ""),
        )
        return api_response(
            data={"session_id": str(result.session.id), "status": result.session.status, "analysis_task_ids": [str(value) for value in result.task_ids]},
            request_id=request.request_id,
            status_code=status.HTTP_202_ACCEPTED if result.created else status.HTTP_200_OK,
        )


class PatientSessionCancelView(PatientSessionMixin, APIView):
    @extend_schema(request=None, responses={200: SingingSessionEnvelopeSerializer})
    def post(self, request, session_id):
        session = self.get_session(request, session_id)
        session = cancel_session(session_id=session.id, patient_id=session.patient_id)
        return api_response(data=SingingSessionReadSerializer(session).data, request_id=request.request_id)


class PatientSessionRetryView(PatientSessionMixin, APIView):
    @extend_schema(
        request=None,
        parameters=[
            OpenApiParameter(
                name="Idempotency-Key",
                location=OpenApiParameter.HEADER,
                required=True,
                type=str,
            ),
        ],
        responses={
            200: SessionMutationEnvelopeSerializer,
            202: SessionMutationEnvelopeSerializer,
        },
    )
    def post(self, request, session_id):
        session = self.get_session(request, session_id)
        result = retry_session(
            session_id=session.id, patient_id=session.patient_id,
            idempotency_key=request.headers.get("Idempotency-Key", ""),
        )
        return api_response(
            data={"session_id": str(result.session.id), "status": result.session.status, "analysis_task_ids": [str(value) for value in result.task_ids]},
            request_id=request.request_id,
            status_code=status.HTTP_202_ACCEPTED if result.created else status.HTTP_200_OK,
        )


class AdminSessionListView(APIView):
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]

    def get(self, request):
        query = _validated_session_query(request, AdminSessionListQuerySerializer)
        queryset = sessions_for_admin(
            patient_id=query["patient_id"], status=query["status"],
            created_from=query["created_from"], created_to=query["created_to"],
        )
        data = paginated_data(queryset, page=query["page"], page_size=query["page_size"])
        data["results"] = SingingSessionSummarySerializer(data["results"], many=True).data
        return api_response(data=data, request_id=request.request_id)


class AdminSessionDetailView(APIView):
    permission_classes = [IsAdminNamespaceUser, MustChangePasswordPermission]

    def get(self, request, session_id):
        session = get_object_or_404(sessions_for_admin(include_media=True), pk=session_id)
        session = attach_analysis_details(session)
        return api_response(data=SingingSessionReadSerializer(session).data, request_id=request.request_id)
