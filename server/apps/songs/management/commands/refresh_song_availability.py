import json

from django.core.management.base import BaseCommand

from apps.songs.models import SongAvailabilityScanState
from apps.songs.services import start_song_availability_scan


class Command(BaseCommand):
    help = "通过单例扫描租约触发歌曲源媒体复核，或观察当前统计"

    def add_arguments(self, parser):
        parser.add_argument("--batch-size", type=int, default=100)
        parser.add_argument("--status", action="store_true")

    def handle(self, *args, **options):
        started = False if options["status"] else start_song_availability_scan(batch_size=options["batch_size"])
        state = SongAvailabilityScanState.objects.filter(pk=1).first()
        payload = {
            "started": started,
            "active": bool(state and state.claim_token),
            "cursor": str(state.cursor) if state and state.cursor else "",
            "stats": state.stats if state else {},
        }
        self.stdout.write(json.dumps(payload, ensure_ascii=False, sort_keys=True))
