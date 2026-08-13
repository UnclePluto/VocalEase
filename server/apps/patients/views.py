from django.shortcuts import get_object_or_404
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.accounts.views import api_response
from common.api.permissions import IsAdminNamespaceUser

from .selectors import patients_for_list
from .serializers import PatientReadSerializer, PatientWriteSerializer
from .services import create_patient, soft_delete_patient, update_patient


def _page(request, queryset):
    page = max(int(request.query_params.get("page", 1)), 1)
    page_size = min(max(int(request.query_params.get("page_size", 20)), 1), 100)
    count = queryset.count()
    return {"count": count, "page": page, "page_size": page_size, "results": queryset[(page - 1) * page_size: page * page_size]}


class PatientListView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    def get(self, request):
        queryset = patients_for_list(
            keyword=request.query_params.get("keyword", ""),
            gender=request.query_params.get("gender", ""),
            primary_doctor=request.query_params.get("primary_doctor", ""),
        )
        data = _page(request, queryset)
        data["results"] = PatientReadSerializer(data["results"], many=True).data
        return api_response(data=data, request_id=request.request_id)

    def post(self, request):
        serializer = PatientWriteSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        values = serializer.validated_data.copy()
        doctor = values.pop("primary_doctor")
        patient = create_patient(actor=request.user, request_id=request.request_id, doctor=doctor, **values)
        return api_response(data=PatientReadSerializer(patient).data, request_id=request.request_id, status_code=status.HTTP_201_CREATED)


class PatientDetailView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    def get_object(self, patient_id):
        return get_object_or_404(patients_for_list(), pk=patient_id)

    def get(self, request, patient_id):
        return api_response(data=PatientReadSerializer(self.get_object(patient_id)).data, request_id=request.request_id)

    def patch(self, request, patient_id):
        patient = self.get_object(patient_id)
        serializer = PatientWriteSerializer(data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        updated = update_patient(actor=request.user, patient=patient, request_id=request.request_id, **serializer.validated_data)
        return api_response(data=PatientReadSerializer(updated).data, request_id=request.request_id)

    def delete(self, request, patient_id):
        soft_delete_patient(actor=request.user, patient=self.get_object(patient_id), request_id=request.request_id)
        return Response(status=status.HTTP_204_NO_CONTENT)
