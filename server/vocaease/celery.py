import os

from celery import Celery

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "vocaease.settings.local")

app = Celery("vocaease")
app.config_from_object("django.conf:settings", namespace="CELERY")
app.autodiscover_tasks()
