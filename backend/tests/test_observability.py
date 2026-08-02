"""Тесты наблюдаемости: /health компоненты, /health/ready гейт,
Sentry no-op без DSN, алерт при всплеске 5xx."""
import app.routers.health as health_mod
from app import services
from app.observability import init_sentry, sentry_enabled


# ----------------------------- /health -----------------------------
def test_health_reports_components(client):
    body = client.get("/health").json()
    # Совместимость со старым парсингом (monitor.sh / старые тесты).
    assert body["status"] == "ok"
    assert body["db"] == "ok"
    # Новые поля компонентов.
    assert body["redis"] in ("ok", "fail", "off")
    assert body["fcm"] in ("configured", "not")
    assert body["components"]["db"] == "ok"
    assert set(body["components"]) == {"db", "redis", "fcm"}


def test_health_redis_off_by_default(client):
    # В тестах REDIS_URL не задан → компонент помечен 'off', проба не падает.
    assert client.get("/health").json()["redis"] == "off"


# --------------------------- /health/ready ---------------------------
def test_ready_ok_when_db_up(client):
    r = client.get("/health/ready")
    assert r.status_code == 200
    assert r.json()["ready"] is True


def test_ready_503_when_db_down(client, monkeypatch):
    # Подменяем проверку БД на «упала» → гейт должен вернуть 503 (не пускать трафик).
    monkeypatch.setattr(health_mod, "_check_db", lambda: False)
    r = client.get("/health/ready")
    assert r.status_code == 503
    assert r.json()["ready"] is False


# ----------------------------- Sentry -----------------------------
def test_sentry_noop_without_dsn(client):
    # Без SENTRY_DSN init — полный no-op: возвращает False, приложение работает.
    from app.config import settings
    saved = settings.sentry_dsn
    settings.sentry_dsn = ""
    try:
        assert init_sentry() is False
        assert sentry_enabled() is False
        # Приложение живо и отвечает как раньше.
        assert client.get("/health").json()["status"] == "ok"
    finally:
        settings.sentry_dsn = saved


# ------------------- Алерт при всплеске 5xx -------------------
def test_error_alert_fires_once_on_threshold(monkeypatch):
    from app.config import settings
    calls = {"n": 0}
    monkeypatch.setattr(services, "notify_admin_telegram",
                        lambda *a, **k: calls.__setitem__("n", calls["n"] + 1))
    saved_thr = settings.error_alert_threshold
    saved_cd = settings.error_alert_cooldown_sec
    settings.error_alert_threshold = 3
    settings.error_alert_cooldown_sec = 900
    services.reset_error_counter()
    try:
        services.record_server_error("/rides")   # 1 — тихо
        services.record_server_error("/rides")   # 2 — тихо
        assert calls["n"] == 0
        services.record_server_error("/rides")   # 3 — порог → один алерт
        assert calls["n"] == 1
        # Ещё ошибки в пределах cooldown — новых сообщений нет (анти-спам).
        for _ in range(5):
            services.record_server_error("/rides")
        assert calls["n"] == 1
    finally:
        settings.error_alert_threshold = saved_thr
        settings.error_alert_cooldown_sec = saved_cd
        services.reset_error_counter()


def test_error_alert_silent_below_threshold(monkeypatch):
    from app.config import settings
    calls = {"n": 0}
    monkeypatch.setattr(services, "notify_admin_telegram",
                        lambda *a, **k: calls.__setitem__("n", calls["n"] + 1))
    saved_thr = settings.error_alert_threshold
    settings.error_alert_threshold = 10
    services.reset_error_counter()
    try:
        for _ in range(9):
            services.record_server_error("/x")
        assert calls["n"] == 0   # порог не достигнут → тишина
    finally:
        settings.error_alert_threshold = saved_thr
        services.reset_error_counter()


# ------------------- утечка токена live-ссылки в алерт -------------------
def test_error_alert_does_not_leak_live_link_token(monkeypatch):
    """Регресс: путь /t/{token} — секрет. Всплеск 5xx шлётся админу в Telegram
    с «последним путём»; если туда попадёт сырой путь, ссылка на живую поездку
    утечёт третьей стороне. Обработчик обязан отдавать замаскированный путь."""
    import asyncio
    from types import SimpleNamespace
    from app.config import settings
    from app.middleware import unhandled_exception_handler

    sent: list[str] = []
    monkeypatch.setattr(services, "notify_admin_telegram", lambda text, *a, **k: sent.append(text))
    saved_thr = settings.error_alert_threshold
    settings.error_alert_threshold = 1          # первый же 5xx → алерт
    services.reset_error_counter()
    try:
        secret = "s3cr3t-live-token"
        request = SimpleNamespace(method="GET", url=SimpleNamespace(path=f"/t/{secret}"))
        asyncio.run(unhandled_exception_handler(request, RuntimeError("boom")))

        assert sent, "алерт о всплеске 5xx не ушёл — тест бессмысленен"
        assert secret not in sent[0], f"токен live-ссылки утёк в Telegram: {sent[0]}"
        assert "/t/***" in sent[0], f"путь должен быть замаскирован, а пришло: {sent[0]}"
    finally:
        settings.error_alert_threshold = saved_thr
        services.reset_error_counter()
