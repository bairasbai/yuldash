"""Служебные пути сервера: пробы здоровья, ночные задачи, разбор мусора.

Общее у всего, что здесь проверяется: **это работает без человека и падает молча**.
Ночные задачи крутятся по таймеру в четыре утра, пробы здоровья дёргает мониторинг раз
в минуту. Если что-то из этого сломается, никто не заметит — до дня, когда понадобится.

Что здесь закрыто:

* **Пробы здоровья.** Мониторинг решает по ним, будить ли Александра ночью. Проба обязана
  ответить даже когда база лежит: «жив, но база отвалилась» — это осмысленный ответ,
  а падение пробы с 500 мониторинг прочитает как «сервер мёртв» и разбудит зря.
  А `/health/ready` наоборот обязан вернуть 503 без базы: он решает, пускать ли трафик
  на свежевыкаченный сервер.
* **Ночные задачи** (авто-подбор, воркер такси, напоминание об оценке, чистка). Запускаются
  из командной строки по таймеру. Выключенная задача обязана честно сказать, что выключена,
  а не притвориться сделавшей работу.
* **Дневная сводка.** Отправляется один раз в сутки, но воркеров пять. Второй воркер не
  должен слать вторую сводку.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session

from app import cleanup, digest, rate_reminder, taxi_worker
from app.config import settings
from app.db import engine
from app.routers import health


# ---------- Пробы здоровья ----------

def test_проба_отвечает_даже_когда_база_лежит(client, monkeypatch):
    monkeypatch.setattr(health, "_check_db", lambda: False)
    r = client.get("/health")
    assert r.status_code == 200, "падение пробы мониторинг прочитает как «сервер мёртв»"
    body = r.json()
    assert body["status"] == "degraded"
    assert body["db"] == "fail"
    assert body["components"]["db"] == "fail"


def test_проба_готовности_без_базы_не_пускает_трафик(client, monkeypatch):
    monkeypatch.setattr(health, "_check_db", lambda: False)
    r = client.get("/health/ready")
    assert r.status_code == 503, "иначе выкатка переключит людей на сервер без базы"
    assert r.json()["ready"] is False


def test_проба_готовности_с_базой_пускает(client):
    r = client.get("/health/ready")
    assert r.status_code == 200
    assert r.json()["ready"] is True


def test_проверка_базы_не_бросает_исключение(monkeypatch):
    # Настоящая проверка ходит в базу. Если база отвалилась, функция обязана вернуть False,
    # а не пробросить исключение наружу — иначе вся проба ответит 500.
    class BrokenEngine:
        def connect(self):
            raise RuntimeError("база недоступна")

    monkeypatch.setattr(health, "engine", BrokenEngine())
    assert health._check_db() is False


def test_redis_не_настроен_так_и_говорим(monkeypatch):
    monkeypatch.setattr(settings, "redis_url", "", raising=False)
    assert health._check_redis() == "off"


def test_настроенный_но_мёртвый_redis_отмечается_как_сбой(monkeypatch):
    monkeypatch.setattr(settings, "redis_url", "redis://127.0.0.1:1/0", raising=False)
    # Порт 1 никто не слушает → ping не пройдёт. Проба обязана сказать «fail», а не упасть.
    assert health._check_redis() == "fail"


def test_ключ_firebase_не_задан(monkeypatch):
    monkeypatch.setattr(settings, "firebase_credentials", "", raising=False)
    assert health._check_fcm() == "not"


def test_путь_к_ключу_firebase_задан_но_файла_нет(monkeypatch):
    monkeypatch.setattr(settings, "firebase_credentials", "/нет/такого/файла.json", raising=False)
    assert health._check_fcm() == "not", "путь есть, а файла нет — пуши не поедут, это не «configured»"


def test_ключ_firebase_на_месте(monkeypatch, tmp_path):
    key = tmp_path / "firebase.json"
    key.write_text("{}", encoding="utf-8")
    monkeypatch.setattr(settings, "firebase_credentials", str(key), raising=False)
    assert health._check_fcm() == "configured"


def test_версия_и_минимальная_версия_отдаются(client):
    assert client.get("/version").json()["version"]
    body = client.get("/version/min").json()
    assert "min_version_code" in body
    assert body["message"]["ru"] and body["message"]["ba"], "текст обновления обязан быть на двух языках"


# ---------- Ночные задачи из командной строки ----------

def test_воркер_такси_выключен_и_честно_об_этом_говорит(monkeypatch, capsys):
    monkeypatch.setattr(taxi_worker.sys, "argv", ["taxi_worker", "--dry-run"], raising=False)
    monkeypatch.setattr(settings, "taxi_worker_enabled", False, raising=False)
    taxi_worker.main()
    assert "выключен" in capsys.readouterr().out


def test_воркер_такси_печатает_итог_по_каждому_виду_работы(monkeypatch, capsys, client):
    monkeypatch.setattr(taxi_worker.sys, "argv", ["taxi_worker", "--dry-run"], raising=False)
    monkeypatch.setattr(settings, "taxi_worker_enabled", True, raising=False)
    taxi_worker.main()
    out = capsys.readouterr().out
    # Итог читает человек в логе таймера: непонятный вывод = задача, за которой не следят.
    for label in ("предзаказов запущено", "зависших закрыто", "зависших посылок разобрано"):
        assert label in out


def test_напоминание_об_оценке_выключено(monkeypatch, capsys):
    monkeypatch.setattr(rate_reminder.sys, "argv", ["rate_reminder", "--dry-run"], raising=False)
    monkeypatch.setattr(settings, "rate_reminder_enabled", False, raising=False)
    rate_reminder.main()
    assert "выключено" in capsys.readouterr().out


def test_напоминание_об_оценке_печатает_итог(monkeypatch, capsys, client):
    monkeypatch.setattr(rate_reminder.sys, "argv", ["rate_reminder", "--dry-run"], raising=False)
    monkeypatch.setattr(settings, "rate_reminder_enabled", True, raising=False)
    rate_reminder.main()
    assert "Итог" in capsys.readouterr().out


def test_сухой_прогон_напоминаний_ничего_не_меняет(client):
    with Session(engine) as s:
        first = rate_reminder.rate_reminder_once(s, dry_run=True)
        second = rate_reminder.rate_reminder_once(s, dry_run=True)
    assert first == second, "сухой прогон не имеет права помечать поездки как «напомнили»"


# ---------- Чистка старого ----------

def test_чистка_медиа_не_трогает_файлы_без_списка_живых_ссылок(monkeypatch, capsys):
    # Не смогли спросить у базы, какие файлы ещё нужны → удалять НЕЛЬЗЯ: снесём аватары.
    def boom():
        raise RuntimeError("база недоступна")

    monkeypatch.setattr(cleanup, "_referenced_media_keys", boom)
    cleanup._clean_media()
    assert "пропуск" in capsys.readouterr().out


def test_чистка_медиа_переживает_недоступное_хранилище(monkeypatch, capsys):
    from app.storage import StorageError

    monkeypatch.setattr(cleanup, "_referenced_media_keys", lambda: set())

    class BrokenStorage:
        def iter_old(self, *a, **kw):
            raise StorageError("облако недоступно")

    monkeypatch.setattr(cleanup, "get_storage", lambda: BrokenStorage())
    cleanup._clean_media()
    assert "пропуск" in capsys.readouterr().out


# ---------- Дневная сводка ----------

def test_сводка_уходит_один_раз_в_сутки(client, monkeypatch):
    from datetime import datetime

    sent = []
    monkeypatch.setattr(settings, "daily_digest_enabled", True, raising=False)
    monkeypatch.setattr(digest, "daily_digest", lambda s, d: sent.append(d))
    monkeypatch.setattr(digest, "_memo_sent_day", None, raising=False)
    # Время в базе — UTC, а порог часа считается по местному (Уфа = UTC+5).
    # 17:00 UTC = 22:00 в Сибае: день кончился, итоги подводить пора.
    вечер = datetime(2026, 8, 4, 17, 0, 0)

    with Session(engine) as s:
        assert digest.maybe_send_daily_digest(s, вечер) is True
        # Второй воркер в том же процессе — память процесса уже помнит, что слали.
        assert digest.maybe_send_daily_digest(s, вечер) is False

    assert len(sent) == 1, "пять воркеров не должны прислать пять одинаковых сводок"


def test_днём_сводка_не_уходит(client, monkeypatch):
    from datetime import datetime

    monkeypatch.setattr(settings, "daily_digest_enabled", True, raising=False)
    monkeypatch.setattr(digest, "_memo_sent_day", None, raising=False)
    with Session(engine) as s:
        # Полдень: день ещё не кончился, подводить итоги рано.
        assert digest.maybe_send_daily_digest(s, datetime(2026, 8, 4, 6, 0, 0)) is False


def test_выключенная_сводка_молчит(client, monkeypatch):
    from datetime import datetime

    monkeypatch.setattr(settings, "daily_digest_enabled", False, raising=False)
    monkeypatch.setattr(digest, "_memo_sent_day", None, raising=False)
    with Session(engine) as s:
        assert digest.maybe_send_daily_digest(s, datetime(2026, 8, 4, 17, 0, 0)) is False


def test_чужой_воркер_уже_отправил_сводку(client, monkeypatch):
    from datetime import date, datetime

    from app.models import DailyDigestLog

    sent = []
    monkeypatch.setattr(settings, "daily_digest_enabled", True, raising=False)
    monkeypatch.setattr(digest, "daily_digest", lambda s, d: sent.append(d))
    monkeypatch.setattr(digest, "_memo_sent_day", None, raising=False)
    day = date(2026, 8, 3)
    with Session(engine) as s:
        s.add(DailyDigestLog(day=day))
        s.commit()
        # Запись в базе от другого процесса → молчим, даже если своя память пуста.
        assert digest.maybe_send_daily_digest(s, datetime(2026, 8, 3, 17, 0, 0)) is False
    assert sent == []


def test_ошибка_в_сводке_не_ломает_запросы(monkeypatch):
    # Сводка запускается в фоне на обычном запросе человека. Её падение не должно
    # долететь до этого запроса ни при каких условиях.
    def boom(_s):
        raise RuntimeError("телеграм недоступен")

    monkeypatch.setattr(digest, "maybe_send_daily_digest", boom)
    digest._run_check()   # не должно бросить


@pytest.mark.parametrize("enabled", [True, False])
def test_слой_сводки_пропускает_запрос_дальше(enabled, monkeypatch):
    """Middleware стоит на КАЖДОМ запросе: его поломка = поломка всего сервера."""
    import asyncio

    monkeypatch.setattr(settings, "daily_digest_enabled", enabled, raising=False)
    passed = []

    async def fake_app(scope, receive, send):
        passed.append(scope["type"])

    mw = digest.DailyDigestMiddleware(fake_app)
    asyncio.run(mw({"type": "http"}, None, None))
    asyncio.run(mw({"type": "lifespan"}, None, None))
    assert passed == ["http", "lifespan"], "запрос обязан дойти до приложения в любом случае"
