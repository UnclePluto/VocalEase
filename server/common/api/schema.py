import re

from drf_spectacular.openapi import AutoSchema
from rest_framework import serializers
from rest_framework.generics import GenericAPIView
from rest_framework.views import APIView


class ApiEnvelopeSerializer(serializers.Serializer):
    code = serializers.CharField()
    message = serializers.CharField(allow_blank=True)
    data = serializers.JSONField()
    request_id = serializers.CharField(allow_blank=True)


class MediaUploadGrantRequestSerializer(serializers.Serializer):
    owner_type = serializers.CharField(required=False)
    owner_id = serializers.UUIDField(required=False)
    media_type = serializers.CharField()
    mime = serializers.CharField()
    size = serializers.IntegerField(min_value=1)


class QiniuCallbackRequestSerializer(serializers.Serializer):
    key = serializers.CharField()
    hash = serializers.CharField()
    fsize = serializers.IntegerField(min_value=1)
    mimeType = serializers.CharField()


class VocaEaseAutoSchema(AutoSchema):
    def _get_serializer(self):
        view = self.view
        if (
            isinstance(view, APIView)
            and not isinstance(view, GenericAPIView)
            and not callable(getattr(view, "get_serializer", None))
            and not callable(getattr(view, "get_serializer_class", None))
            and not hasattr(view, "serializer_class")
        ):
            return ApiEnvelopeSerializer()
        return super()._get_serializer()

    def get_operation_id(self) -> str:
        operation_id = super().get_operation_id()
        path_parameters = re.findall(r"\{([^}]+)\}", self.path)
        if path_parameters:
            operation_id += "_by_" + "_and_".join(path_parameters)
        return operation_id
