"""生产 WSGI 入口；环境配置由部署端注入。"""

import os

from django.core.wsgi import get_wsgi_application

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "vocaease.settings.base")
application = get_wsgi_application()
