from django.contrib import admin
from django.urls import path

from . import health

urlpatterns = [
    path("admin/", admin.site.urls),
    path("health/live/", health.live),
    path("health/ready/", health.ready),
]
