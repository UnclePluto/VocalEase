from django.shortcuts import get_object_or_404
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.accounts.views import api_response
from common.api.permissions import IsAdminNamespaceUser

from .selectors import doctors_for_list
from .serializers import DoctorReadSerializer, DoctorWriteSerializer
from .services import DoctorHasActivePatients, create_doctor, soft_delete_doctor, update_doctor


def _page(request, queryset):
    page = max(int(request.query_params.get("page", 1)), 1)
    page_size = min(max(int(request.query_params.get("page_size", 20)), 1), 100)
    count = queryset.count()
    return {"count": count, "page": page, "page_size": page_size, "results": queryset[(page - 1) * page_size: page * page_size]}


class DoctorListView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    def get(self, request):
        queryset = doctors_for_list(keyword=request.query_params.get("keyword", ""), department=request.query_params.get("department", ""))
        data = _page(request, queryset)
        data["results"] = DoctorReadSerializer(data["results"], many=True).data
        return api_response(data=data, request_id=request.request_id)

    def post(self, request):
        serializer = DoctorWriteSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        doctor = create_doctor(actor=request.user, request_id=request.request_id, **serializer.validated_data)
        return api_response(data=DoctorReadSerializer(doctor).data, request_id=request.request_id, status_code=status.HTTP_201_CREATED)


class DoctorDetailView(APIView):
    permission_classes = [IsAdminNamespaceUser]

    def get_object(self, doctor_id):
        return get_object_or_404(doctors_for_list(), pk=doctor_id)

    def get(self, request, doctor_id):
        return api_response(data=DoctorReadSerializer(self.get_object(doctor_id)).data, request_id=request.request_id)

    def patch(self, request, doctor_id):
        doctor = self.get_object(doctor_id)
        serializer = DoctorWriteSerializer(data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        updated = update_doctor(actor=request.user, doctor=doctor, request_id=request.request_id, **serializer.validated_data)
        return api_response(data=DoctorReadSerializer(updated).data, request_id=request.request_id)

    def delete(self, request, doctor_id):
        try:
            soft_delete_doctor(actor=request.user, doctor=self.get_object(doctor_id), request_id=request.request_id)
        except DoctorHasActivePatients as exc:
            return Response({"code": exc.default_code, "message": exc.default_detail, "data": None, "request_id": request.request_id}, status=exc.status_code)
        return Response(status=status.HTTP_204_NO_CONTENT)
