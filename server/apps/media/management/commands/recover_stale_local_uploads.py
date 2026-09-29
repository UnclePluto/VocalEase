import json

from django.core.management.base import BaseCommand

from apps.media.services import recover_stale_local_uploads


class Command(BaseCommand):
    help = "恢复过期本地上传租约并清理有可信 marker 归属的残留文件"

    def handle(self, *args, **options):
        stats = recover_stale_local_uploads()
        self.stdout.write(json.dumps(stats, ensure_ascii=False, sort_keys=True))
