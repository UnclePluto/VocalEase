import uuid

from django.db import models


class UUIDSoftDeleteModel(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    deleted_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        abstract = True
