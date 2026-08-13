from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("media", "0002_asset_owner_contract_and_receipt_constraints")]

    operations = [
        migrations.RenameIndex(model_name="mediaasset", new_name="media_media_patient_5ee947_idx", old_name="media_asset_patient_status_idx"),
        migrations.AlterField(model_name="mediaasset", name="backend", field=models.CharField(choices=[("local", "本地"), ("qiniu", "七牛")], max_length=16)),
        migrations.AlterField(model_name="mediaasset", name="media_type", field=models.CharField(choices=[("song_source", "song_source"), ("song_accompaniment", "song_accompaniment"), ("song_vocal", "song_vocal"), ("lyrics", "lyrics"), ("singing_audio", "singing_audio"), ("singing_video", "singing_video"), ("waveform", "waveform"), ("export", "export")], max_length=32)),
    ]
