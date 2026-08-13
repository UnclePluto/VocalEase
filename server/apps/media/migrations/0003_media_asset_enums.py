from django.db import migrations
from django.db.migrations.exceptions import IrreversibleError


def backfill_qiniu_etag(apps, schema_editor):
    MediaAsset = apps.get_model("media", "MediaAsset")
    for asset in MediaAsset.objects.filter(backend="qiniu", status="ready", etag=""):
        legacy = (asset.metadata or {}).get("qiniu_etag", "")
        if legacy:
            asset.etag = legacy
            asset.save(update_fields=["etag"])


def reverse_backfill_qiniu_etag(apps, schema_editor):
    MediaAsset = apps.get_model("media", "MediaAsset")
    # 空库允许测试/部署中断后退回结构边界；任何真实媒体数据均拒绝伪回退。
    if MediaAsset.objects.exists():
        raise IrreversibleError("通用媒体所有权与可信回执无法无损降级")


class Migration(migrations.Migration):
    dependencies = [("media", "0002_asset_owner_contract_and_receipt_constraints")]

    operations = [
        migrations.RunPython(backfill_qiniu_etag, reverse_backfill_qiniu_etag),
    ]
