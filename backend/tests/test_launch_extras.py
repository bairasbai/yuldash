"""B9b «Предзапусковые мелочи»: force-update (/version/min), пуши пассажиру о ходе
такси-заказа, дневная сводка админу в Telegram, тестовый аккаунт для модерации сторов."""
from app.config import settings


# ============================ Force-update: /version/min (B9b-1) ============================
def test_version_min_disabled_by_default(client):
    """По умолчанию min_app_version_code=0 → клиент никого не блокирует."""
    r = client.get("/version/min")
    assert r.status_code == 200, r.text
    data = r.json()
    assert data["min_version_code"] == 0
    assert data["store_url"] == ""
    # Сообщение всегда двуязычное (RU/BA), даже когда проверка выключена.
    assert data["message"]["ru"] and data["message"]["ba"]


def test_version_min_enabled_via_config(client, monkeypatch):
    """Конфиг включён → ручка отдаёт порог и ссылку на стор (без пересборки клиента)."""
    monkeypatch.setattr(settings, "min_app_version_code", 5)
    monkeypatch.setattr(settings, "app_store_url", "https://example.com/yuldash")
    data = client.get("/version/min").json()
    assert data["min_version_code"] == 5
    assert data["store_url"] == "https://example.com/yuldash"


def test_version_min_no_auth_required(client):
    """Ручка публичная: клиент проверяет версию ДО входа (на сплэше)."""
    assert client.get("/version/min").status_code == 200
    assert client.get("/api/v1/version/min").status_code == 200
