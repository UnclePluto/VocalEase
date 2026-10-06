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

@pytest.mark.django_db
def test_generation_request_reuses_current_pending_and_ready(monkeypatch):
    from apps.accounts.models import Role, User
    from apps.media.models import MediaAsset
    from apps.songs.models import SongReferencePitch
    from apps.songs.reference_pitch_services import asset_fingerprint, request_reference_pitch
    actor=User.objects.create_user(login_id='generation-admin',password='888888',role=Role.SYSTEM_ADMIN,must_change_password=False)
    song=Song.objects.create(title='真实人声',artist='a',genre='a',language='中文',duration_seconds=10)
    asset=MediaAsset.objects.create(owner_type='song',owner_id=song.id,media_type='song_vocal',backend='local',object_key='voice.wav',mime='audio/wav',size=20,status='ready',sha256='a'*64,manifest_generation='b'*32,upload_expires_at=timezone.now()+timedelta(hours=1))
    song.vocal_asset=asset;song.save()
    monkeypatch.setattr('apps.songs.resources.validate_song_resource',lambda **kw:asset)
    fingerprint=asset_fingerprint(asset)
    first=request_reference_pitch(actor=actor,song_id=song.id,expected_fingerprint=fingerprint)
    second=request_reference_pitch(actor=actor,song_id=song.id,expected_fingerprint=fingerprint)
    assert second.id == first.id
    first.status='ready';first.document={'schema_version':1,'origin':{'type':'vocal_yin','fingerprint':fingerprint},'notes':[{'start_ms':0,'end_ms':1000,'midi_note':60,'confidence':1}]};first.save()
    assert request_reference_pitch(actor=actor,song_id=song.id,expected_fingerprint=fingerprint).id == first.id
    assert SongReferencePitch.objects.filter(song=song).count() == 1
    failed=SongReferencePitch.objects.create(song=song,input_asset=asset,input_fingerprint=fingerprint,status='failed')
    retry=request_reference_pitch(actor=actor,song_id=song.id,expected_fingerprint=fingerprint)
    assert retry.id not in (first.id,failed.id)
    assert retry.status == 'pending'
    first.refresh_from_db()
    assert first.status == 'ready'

def test_empty_vocal_cannot_be_published_as_ready():
    from apps.songs.reference_pitch import validate_pitch_document
    from rest_framework.exceptions import ValidationError
    with pytest.raises(ValidationError):
        validate_pitch_document({'schema_version':1,'origin':{'type':'vocal_yin','fingerprint':'real'},'notes':[]},duration_ms=1000)
