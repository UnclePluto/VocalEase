from django.core.management.base import BaseCommand
from apps.songs.models import Song
from apps.songs.reference_pitch_services import read_reference_pitch


class Command(BaseCommand):
    help = '列出歌曲真实参考音高就绪状态；不输出私有地址'

    def add_arguments(self, parser):
        parser.add_argument('--song-id')

    def handle(self, *args, **options):
        songs = Song.objects.filter(deleted_at__isnull=True)
        if options['song_id']:
            songs = songs.filter(pk=options['song_id'])
        for song in songs:
            self.stdout.write(f"{song.id} {read_reference_pitch(song_id=song.id)['status']}")
