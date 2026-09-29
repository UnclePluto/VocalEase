import importlib.util
import json
import os
from pathlib import Path
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("production_validator", ROOT / "deploy/validate-production.py")
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


def production_environment():
    env = validator.read_env(ROOT / "deploy/production.env.example")
    env.update(DJANGO_SECRET_KEY="s" * 64, POSTGRES_PASSWORD="p" * 64, QINIU_ACCESS_KEY="test-ak", QINIU_SECRET_KEY="test-sk", QINIU_BUCKET="test-private")
    return env


def images():
    return {name + "_IMAGE": validator.REGISTRY + "@sha256:" + "a" * 64 for name in ("WEB", "SERVER", "POSTGRES", "REDIS")}


def test_production_compose_isolates_data_services_and_limits_every_container(tmp_path):
    env = production_environment()
    env_path = tmp_path / ".env"
    env_path.write_text("\n".join(k + "=" + v for k, v in env.items()))
    process_env = {**os.environ, **env, **images(), "APP_ENV_FILE": str(env_path)}
    result = subprocess.run(["docker", "compose", "-f", str(ROOT / "deploy/compose.prod.yaml"), "config", "--format", "json"], env=process_env, check=True, capture_output=True, text=True)
    config = json.loads(result.stdout)
    services = config["services"]
    assert config["name"] == "vocaease-prod"
    assert config["networks"]["private"]["internal"] is True
    assert {n for n, service in services.items() if "edge" in service["networks"]} == {"web"}
    for name in ("postgres", "redis"):
        assert set(services[name]["networks"]) == {"private"}
    for name, service in services.items():
        assert float(service["cpus"]) > 0 and int(service["mem_limit"]) > 0
        assert "build" not in service
        assert service["logging"]["options"]["max-size"] == "10m"
        if name != "web":
            assert not service.get("ports")
    assert services["web"]["ports"][0]["host_ip"] == "127.0.0.1"
    assert services["web"]["ports"][0]["published"] == "19080"


@pytest.mark.parametrize("key,value", [("MEDIA_ENVIRONMENT", "production"), ("POSTGRES_HOST", "motioncare-postgres"), ("DJANGO_DEBUG", "true"), ("QINIU_CALLBACK_URL", "https://mcare-api.whestsun.com/callback/")])
def test_preflight_rejects_shared_or_unsafe_environment(key, value):
    env = production_environment()
    env[key] = value
    with pytest.raises(ValueError):
        validator.validate(env, images())


def test_preflight_requires_immutable_images_and_upload_csp_allowlist():
    env = production_environment()
    validator.validate(env, images())
    mutable = images()
    mutable["SERVER_IMAGE"] = validator.REGISTRY + ":latest"
    with pytest.raises(ValueError):
        validator.validate(env, mutable)
    env["WEB_MEDIA_ORIGIN"] = env["QINIU_DOMAIN"]
    with pytest.raises(ValueError):
        validator.validate(env, images())
