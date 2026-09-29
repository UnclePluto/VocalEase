from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("media", "0006_owner_matrix_constraints")]
    operations = [migrations.AddConstraint(model_name="mediaasset", constraint=models.CheckConstraint(condition=models.Q(("media_type__in", ["song_source", "song_accompaniment", "song_vocal", "lyrics", "singing_audio", "singing_video", "waveform", "export"])), name="media_asset_media_type_valid"))]
