from django.db import migrations


def backfill_qiniu_etag(apps, schema_editor):
    MediaAsset = apps.get_model("media", "MediaAsset")
    for asset in MediaAsset.objects.filter(backend="qiniu", status="ready", etag=""):
        legacy = (asset.metadata or {}).get("qiniu_etag", "")
        if legacy:
            asset.etag = legacy
            asset.save(update_fields=["etag"])


def reverse_backfill_qiniu_etag(apps, schema_editor):
    MediaAsset = apps.get_model("media", "MediaAsset")
    for asset in MediaAsset.objects.filter(backend="qiniu").exclude(etag=""):
        metadata = dict(asset.metadata or {})
        metadata["qiniu_etag"] = asset.etag
        asset.metadata = metadata
        asset.save(update_fields=["metadata"])


class Migration(migrations.Migration):
    dependencies = [("media", "0002_asset_owner_contract_and_receipt_constraints")]

    operations = [
        migrations.RunPython(backfill_qiniu_etag, reverse_backfill_qiniu_etag),
    ]
