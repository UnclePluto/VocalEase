import io
import math
import struct
import wave
from datetime import timedelta
from uuid import uuid4
import pytest
from django.core.management import call_command
from django.utils import timezone
from apps.songs.models import Song


def test_generated_pitch_uses_verified_vocal():
    from apps.songs.reference_pitch_audio import decode_vocal, extract_pitch_notes
    output = io.BytesIO()
    with wave.open(output, 'wb') as wav:
        wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(8000)
        wav.writeframes(b''.join(struct.pack('<h', int(15000*math.sin(2*math.pi*220*i/8000))) for i in range(8000)))
    pcm = decode_vocal(output.getvalue(), duration_ms=1000)
    notes = extract_pitch_notes(pcm, sample_rate=8000, duration_ms=1000)
    assert notes and abs(notes[0]['midi_note'] - 57) < .5
    assert extract_pitch_notes([0.0]*8000, sample_rate=8000, duration_ms=1000) == []

@pytest.mark.django_db
def test_stale_generation_cannot_publish():
    from apps.songs.models import SongReferencePitch
    from apps.songs.reference_pitch_services import generate_reference_pitch
    song = Song.objects.create(title='a', artist='a', genre='a', language='a', duration_seconds=1)
    row = SongReferencePitch.objects.create(song=song, input_fingerprint='old')
    generate_reference_pitch(song_id=song.id, expected_fingerprint='old', version=row.id)
    row.refresh_from_db()
    assert row.status == 'stale'

@pytest.mark.django_db
def test_retry_and_worker_lease(monkeypatch, django_capture_on_commit_callbacks):
    from apps.songs.models import SongReferencePitch
    from apps.songs.tasks import recover_reference_pitch_tasks, generate_reference_pitch_task
    song = Song.objects.create(title='a', artist='a', genre='a', language='a', duration_seconds=1)
    row = SongReferencePitch.objects.create(song=song, status='processing', lease_token=uuid4(), lease_until=timezone.now()-timedelta(seconds=1), attempt=1)
    calls=[]
    monkeypatch.setattr(generate_reference_pitch_task, 'delay', lambda **kwargs: calls.append(kwargs))
    with django_capture_on_commit_callbacks(execute=True):
        assert recover_reference_pitch_tasks() == 1
    row.refresh_from_db()
    assert row.status == 'pending' and calls[0]['version'] == str(row.id)
    row.status='processing';row.lease_until=timezone.now()-timedelta(seconds=1);row.attempt=3;row.save()
    recover_reference_pitch_tasks();row.refresh_from_db()
    assert row.status == 'failed'

@pytest.mark.django_db
def test_readiness_lists_missing_tracks():
    song = Song.objects.create(title='缺少音轨', artist='a', genre='a', language='a', duration_seconds=1)
    out=io.StringIO()
    call_command('check_reference_pitch_readiness', song_id=str(song.id), stdout=out)
    assert str(song.id) in out.getvalue() and 'missing' in out.getvalue()
