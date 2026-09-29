from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("media", "0001_initial")]

    operations = [
        migrations.RemoveIndex(model_name="mediaasset", name="media_media_owner_i_f272bb_idx"),
        migrations.RenameField(model_name="mediaasset", old_name="owner", new_name="patient_owner"),
        migrations.AddField(model_name="mediaasset", name="owner_id", field=models.UUIDField(null=True)),
        migrations.AddField(model_name="mediaasset", name="etag", field=models.CharField(blank=True, max_length=128)),
        migrations.RunSQL("UPDATE media_mediaasset SET owner_id = patient_owner_id WHERE owner_id IS NULL", migrations.RunSQL.noop),
        migrations.AlterField(model_name="mediaasset", name="patient_owner", field=models.ForeignKey(blank=True, null=True, on_delete=models.PROTECT, related_name="media_assets", to="patients.patientprofile")),
        migrations.AlterField(model_name="mediaasset", name="owner_id", field=models.UUIDField()),
        migrations.AlterField(model_name="mediaasset", name="owner_type", field=models.CharField(choices=[("patient", "患者"), ("song", "歌曲"), ("system", "系统"), ("export", "导出")], max_length=16)),
        migrations.AddIndex(model_name="mediaasset", index=models.Index(fields=["patient_owner", "status"], name="media_media_patient_5ee947_idx")),
        migrations.AlterField(model_name="mediaasset", name="backend", field=models.CharField(choices=[("local", "本地"), ("qiniu", "七牛")], max_length=16)),
        migrations.AlterField(model_name="mediaasset", name="media_type", field=models.CharField(choices=[("song_source", "song_source"), ("song_accompaniment", "song_accompaniment"), ("song_vocal", "song_vocal"), ("lyrics", "lyrics"), ("singing_audio", "singing_audio"), ("singing_video", "singing_video"), ("waveform", "waveform"), ("export", "export")], max_length=32)),
    ]
