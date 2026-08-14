from vocaease.settings import local


def test_local_web_origins_include_vite_and_compose_ports():
    assert {
        "http://localhost:3000",
        "http://127.0.0.1:3000",
        "http://localhost:5173",
        "http://127.0.0.1:5173",
    }.issubset(local.AUTH_WEB_ALLOWED_ORIGINS)
