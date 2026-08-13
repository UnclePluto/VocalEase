from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("media", "0004_upload_lease")]
    operations = [
        migrations.AlterField(model_name="mediaasset", name="status", field=models.CharField(choices=[("uploading", "上传中"), ("receiving", "接收中"), ("ready", "可用"), ("failed", "失败"), ("pending_cleanup", "待清理")], default="uploading", max_length=24)),
    ]
