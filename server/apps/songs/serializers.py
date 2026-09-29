from rest_framework import serializers

from common.api.pagination import PaginationQuerySerializer

from .models import Song


class SongWriteSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    title = serializers.CharField(max_length=200)
    artist = serializers.CharField(max_length=200)
    genre = serializers.CharField(max_length=64)
    language = serializers.CharField(max_length=64)
    duration_seconds = serializers.IntegerField(min_value=1, max_value=24 * 60 * 60)
    source_asset = serializers.UUIDField()
    ingestion_mode = serializers.ChoiceField(choices=Song.IngestionMode.choices, required=False, default="existing")
    vocal_asset = serializers.UUIDField(required=False)
    accompaniment_asset = serializers.UUIDField(required=False)
    lyrics_asset = serializers.UUIDField(required=False)
    auto_analyze = serializers.BooleanField(required=False, default=False, write_only=True)


class SongReadSerializer(serializers.ModelSerializer):
    source_asset = serializers.UUIDField(source="source_asset_id", allow_null=True, read_only=True)
    uploaded_at = serializers.DateTimeField(source="created_at", read_only=True)
    vocal_asset = serializers.UUIDField(source="vocal_asset_id", allow_null=True, read_only=True)
    accompaniment_asset = serializers.UUIDField(source="accompaniment_asset_id", allow_null=True, read_only=True)
    lyrics_asset = serializers.UUIDField(source="lyrics_asset_id", allow_null=True, read_only=True)
    artifacts = serializers.SerializerMethodField()

    def get_artifacts(self, song):
        return {kind: bool(getattr(song, f"{field}_id") and getattr(song, field).status == "ready" and getattr(song, field).deleted_at is None)
                for kind, field in (("source", "source_asset"), ("vocal", "vocal_asset"), ("accompaniment", "accompaniment_asset"), ("lyrics", "lyrics_asset"))}

    class Meta:
        model = Song
        fields = ("id", "title", "artist", "genre", "language", "duration_seconds", "source_asset", "vocal_asset", "accompaniment_asset", "lyrics_asset", "ingestion_mode", "artifacts", "analysis_status", "publication_status", "uploaded_at")


class PatientSongReadSerializer(SongReadSerializer):
    class Meta(SongReadSerializer.Meta):
        fields = ("id", "title", "artist", "genre", "language", "duration_seconds", "analysis_status", "publication_status", "uploaded_at")


class SongListQuerySerializer(PaginationQuerySerializer):
    keyword = serializers.CharField(required=False, allow_blank=True, default="")
    genre = serializers.CharField(required=False, allow_blank=True, default="")
    language = serializers.CharField(required=False, allow_blank=True, default="")
    analysis_status = serializers.ChoiceField(choices=Song.AnalysisStatus.choices, required=False, allow_blank=True, default="")
    publication_status = serializers.ChoiceField(choices=Song.PublicationStatus.choices, required=False, allow_blank=True, default="")
    sort = serializers.ChoiceField(choices=("created_at", "-created_at", "title", "-title", "artist", "-artist", "duration_seconds", "-duration_seconds"), required=False, default="-created_at")


class PatientSongListQuerySerializer(PaginationQuerySerializer):
    keyword = serializers.CharField(required=False, allow_blank=True, default="")
    sort = serializers.ChoiceField(choices=("created_at", "-created_at", "title", "-title", "artist", "-artist", "duration_seconds", "-duration_seconds"), required=False, default="-created_at")


class SongUploadGrantSerializer(serializers.Serializer):
    mime = serializers.ChoiceField(choices=("audio/mpeg", "audio/wav", "audio/flac"))
    size = serializers.IntegerField(min_value=1, max_value=50 * 1024 * 1024)
    song_id = serializers.UUIDField(required=False)
    media_type = serializers.ChoiceField(choices=("song_source", "song_vocal", "song_accompaniment", "lyrics"), required=False, default="song_source")


class ReanalyzeSerializer(serializers.Serializer):
    task_type = serializers.ChoiceField(choices=("vocal_separation", "accompaniment_generation", "lyrics_recognition"), default="vocal_separation")
    idempotency_key = serializers.CharField(max_length=128, required=False, allow_blank=False)
