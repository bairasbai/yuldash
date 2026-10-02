"""leaf-1.3 — деньги: именные промокоды и кампании (app/routers/promo.py).

Фокус группы «Деньги»:
  R1 — общий лимит кампании (limit_total) не превышается, даже когда два РАЗНЫХ человека
       одновременно применяют последний доступный код (PostgreSQL);
  R2 — владелец кампании не может активировать свой же код (бесплатный бонус самому себе);
  R3 — бонус-«поднятия» (kind=boost) не выдаётся сверх общего потолка бонусов на руках
       (MAX_REFERRAL_CREDITS), даже если perk_value кампании больше остатка до потолка;
  R4 — срок кампании: дата без времени (форма админа шлёт именно так) = конец суток по Уфе,
       а не начало — та же дыра и то же исправление, что у купонов (B-3, найдено ревью).
  R5 — начисление boost-бонуса не теряет параллельное реферальное начисление тому же
       человеку (атомарный UPDATE вместо «прочитал-прибавил-записал», найдено ревью круга 2);
  R6 — след «этот номер уже брал промокод» (PromoClaimLog) хранит HMAC номера, а не сам
       номер цифрами (найдено независимым ревью leaf-1.3, круг 3 — docstring модели обещал
       это и раньше, но хранил ровно обратное);
  R7 — миграция `promo_claim_hmac` переводит СТАРЫЕ (до круга 3) открытые ключи в HMAC, не
       трогает уже-HMAC строки и безопасно переживает повторный прогон (круг 4);
  R8 — пока не все окружения прогнали эту миграцию (или на случай одной проскочившей
       строки), `promo_apply` обязан ловить повтор И по старому открытому, И по новому
       HMAC-ключу — иначе для всех, кто брал код ДО круга 3, лимит «один код на номер»
       молча обнулился бы (найдено ведущим при ревью круга 3, почина — круг 4).
"""
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path

import pytest
from sqlalchemy import text
from sqlmodel import Session, select

from app.config import _phone_key
from app.db import engine
from app.models import PromoClaimLog, PromoCode, User, UserRole
from app.routers import promo as promo_router
from app.routers import referral as ref
from app.routers.promo import MAX_REFERRAL_CREDITS, ApplyIn

_MIGRATION_PATH = Path(__file__).parents[3] / "alembic" / "versions" / "promo_claim_hmac_20261002.py"
_HEX64 = re.compile(r"^[0-9a-f]{64}$")


def _load_hmac_migration(tag: str):
    """Грузит файл миграции как обычный модуль — тот же приём, что у
    test_refresh_replay_postgres.py::test_recovery_migration_on_postgres_preserves_legacy_row."""
    import importlib.util
    spec = importlib.util.spec_from_file_location(tag, _MIGRATION_PATH)
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)
    return migration


def _pg_session():
    # SET LOCAL — без LOCAL настройка осталась бы на соединении и после commit внутри
    # вызванной функции утекла бы в СЛЕДУЮЩИЙ тест, взявший то же соединение из пула.
    s = Session(engine)
    s.execute(text("SET LOCAL lock_timeout = '4s'"))
    s.execute(text("SET LOCAL statement_timeout = '8s'"))
    return s


# ============================ R1: общий лимит кампании (гонка, PostgreSQL) ============================
@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
def test_concurrent_apply_respects_total_limit(client, user_factory, monkeypatch):
    """Кампания с limit_total=1: два РАЗНЫХ человека одновременно жмут «применить» —
    должен выиграть ровно один, счётчик активаций — вырасти ровно на 1.

    Голого Barrier перед вызовом недостаточно для надёжной проверки: после барьера оба потока
    ещё должны реально ПЕРЕСЕЧЬСЯ внутри критического участка (после чтения, до записи), а
    планировщик Windows может целиком прогнать один поток раньше, чем ОС вообще переключится
    на второй — тогда поломка не поймается не потому что защита есть, а просто по везению.
    Поэтому дополнительно задерживаем обоих ПРЯМО ПЕРЕД атомарным обновлением счётчика
    (единственная точка, которую зовут обе ветки — welcome и boost): это гарантирует, что
    оба запроса окажутся внутри гонки одновременно, независимо от того, как их планирует ОС.
    """
    admin = user_factory("ГонкаПромоАдмин", role=UserRole.admin)
    r = client.post("/admin/promo", headers=admin["auth"], json={
        "code": "PGPROMOLIMIT", "kind": "welcome", "limit_total": 1,
    })
    assert r.status_code == 200, r.text
    alice = user_factory("ГонкаПромоАлиса")
    bob = user_factory("ГонкаПромоБоб")

    original_granted_kop = promo_router.promo_ride.granted_kop

    def delayed_granted_kop(promo):
        time.sleep(0.2)   # окно, в котором оба потока гарантированно уже прочитали старое состояние
        return original_granted_kop(promo)

    monkeypatch.setattr(promo_router.promo_ride, "granted_kop", delayed_granted_kop)

    barrier = threading.Barrier(2)

    def apply(user_id):
        with _pg_session() as s:
            u = s.get(User, user_id)
            barrier.wait(timeout=10)
            try:
                return promo_router.promo_apply(ApplyIn(code="PGPROMOLIMIT"), user=u, session=s, x_device_id="")
            except Exception as exc:
                return exc

    with ThreadPoolExecutor(max_workers=2) as ex:
        futures = [ex.submit(apply, uid) for uid in (alice["id"], bob["id"])]
        results = [f.result(timeout=20) for f in futures]

    oks = [r for r in results if isinstance(r, dict)]
    fails = [r for r in results if not isinstance(r, dict)]
    assert len(oks) == 1, f"код с лимитом 1 не должен был достаться обоим: {results}"
    assert len(fails) == 1
    assert getattr(fails[0], "status_code", None) == 409
    assert fails[0].detail["ru"] and fails[0].detail["ba"]

    with Session(engine) as s:
        promo = s.exec(select(PromoCode).where(PromoCode.code == "PGPROMOLIMIT")).first()
        assert promo.redeemed_count == 1, "счётчик активаций кампании не должен задваиваться"


# ============================ R2: нельзя активировать свой же код ============================
def test_owner_cannot_apply_own_code(client, user_factory):
    blogger = user_factory("СвойКодБлогер")
    admin = user_factory("СвойКодАдмин", role=UserRole.admin)
    r = client.post("/admin/promo", headers=admin["auth"], json={"code": "OWNCODE1", "kind": "welcome"})
    assert r.status_code == 200, r.text
    # owner_phone ищется по User.phone — у user_factory телефон синтетический ("tg-test-N"),
    # поэтому проставим владельца напрямую в БД (ровно то же поле, что и ручка админа).
    with Session(engine) as s:
        promo = s.exec(select(PromoCode).where(PromoCode.code == "OWNCODE1")).first()
        promo.owner_id = blogger["id"]
        s.add(promo)
        s.commit()

    resp = client.post("/promo/apply", headers=blogger["auth"], json={"code": "OWNCODE1"})
    assert resp.status_code == 409, resp.text
    detail = resp.json()["detail"]
    assert detail["ru"] and detail["ba"]

    with Session(engine) as s:
        promo = s.exec(select(PromoCode).where(PromoCode.code == "OWNCODE1")).first()
        assert promo.redeemed_count == 0, "свой код не должен был засчитаться как активация"


# ============================ R3: boost не выдаётся сверх потолка на руках ============================
def test_boost_grant_capped_at_wallet_limit(client, user_factory):
    admin = user_factory("БустПотолокАдмин", role=UserRole.admin)
    pax = user_factory("БустПотолокПас")
    with Session(engine) as s:
        u = s.get(User, pax["id"])
        u.referral_credits = MAX_REFERRAL_CREDITS - 2
        s.add(u)
        s.commit()

    r = client.post("/admin/promo", headers=admin["auth"], json={
        "code": "HUGEBOOST1", "kind": "boost", "perk_value": 1000,
    })
    assert r.status_code == 200, r.text

    resp = client.post("/promo/apply", headers=pax["auth"], json={"code": "HUGEBOOST1"})
    assert resp.status_code == 200, resp.text

    with Session(engine) as s:
        u = s.get(User, pax["id"])
        assert u.referral_credits == MAX_REFERRAL_CREDITS, \
            "бонус boost не должен перелиться через общий потолок бонусов на руках"


# ============================ R4 (B-3): дата без времени = конец суток по Уфе ============================
def test_promo_valid_until_treats_date_only_midnight_as_end_of_day():
    naive_midnight = datetime(2026, 10, 31, 0, 0, 0)
    stored = promo_router._promo_valid_until(naive_midnight)
    assert stored == datetime(2026, 10, 31, 18, 59, 59)


def test_promo_valid_until_leaves_explicit_time_alone():
    naive_evening = datetime(2026, 10, 31, 20, 0, 0)
    stored = promo_router._promo_valid_until(naive_evening)
    assert stored == datetime(2026, 10, 31, 15, 0, 0)


def test_promo_deadline_survives_repeated_date_only_resaves(client, user_factory):
    """Тот же круговой путь, что у купонов: форма админа при правке подставляет в поле только
    дату — повторное сохранение той же даты не должно сдвигать срок кампании."""
    admin = user_factory("СрокПромоАдмин", role=UserRole.admin)
    r = client.post("/admin/promo", headers=admin["auth"],
                    json={"code": "DEADLINE1", "kind": "welcome", "valid_until": "2026-10-31"})
    assert r.status_code == 200, r.text
    first = r.json()["valid_until"]
    assert first[:10] == "2026-10-31", f"дата уже съехала на первом сохранении: {first}"

    r2 = client.post(f"/admin/promo/{r.json()['id']}", headers=admin["auth"],
                     json={"valid_until": first[:10]})
    assert r2.status_code == 200, r2.text
    second = r2.json()["valid_until"]
    assert second == first, f"срок сдвинулся после повторного сохранения той же даты: {first} -> {second}"


# ============================ R5: потерянное обновление boost-кредита ============================
def test_boost_credit_survives_concurrent_referral_grant(user_factory):
    """Два сеанса читают ОДНОГО И ТОГО ЖЕ человека ДО того, как любой из них запишет: один
    вот-вот применит boost-промокод, другой вот-вот начислит реферальный бонус (как будто кто-то
    только что ввёл его код). Старое «прочитал—прибавил—записал» считало новое число от
    устаревшего прочитанного и молча стирало параллельное начисление (найдено независимым
    ревью leaf-1.3, круг 2). Доказываем той же техникой «две сессии, оба читают до коммита
    любой из них», что и существующий test_lifetime_cap_counts_every_grant — она не требует
    настоящих потоков: дело не в блокировке строки, а в том, СЧИТАЕТ ли UPDATE новое значение
    на сервере БД (атомарно) или присылает его уже готовым из устаревшего Python-объекта."""
    admin = user_factory("ПотерянноеАдмин", role=UserRole.admin)
    x = user_factory("ПотерянноеX")
    with Session(engine) as s:
        u = s.get(User, x["id"])
        u.referral_credits = 5
        s.add(u)
        s.commit()

    with Session(engine) as s:
        admin_user = s.get(User, admin["id"])
        created = promo_router.admin_promo_create(
            promo_router.AdminPromoIn(code="LOSTUPDATE1", kind="boost", perk_value=3),
            user=admin_user, session=s,
        )
        assert created["code"] == "LOSTUPDATE1"

    # Оба сеанса читают "x" ДО того, как любой что-то запишет — ровно момент гонки.
    with Session(engine) as s1, Session(engine) as s2:
        x_for_promo = s1.exec(select(User).where(User.id == x["id"])).one()
        x_for_referral = s2.exec(select(User).where(User.id == x["id"])).one()
        assert x_for_promo.referral_credits == 5 and x_for_referral.referral_credits == 5

        # 1) Параллельное реферальное начисление коммитится ПЕРВЫМ.
        assert ref.grant_referral_credit(s2, x_for_referral) is True
        s2.commit()

        # 2) Промокод применяется ВТОРЫМ, но его сессия s1 прочитала x ДО этого начисления.
        result = promo_router.promo_apply(
            promo_router.ApplyIn(code="LOSTUPDATE1"), user=x_for_promo, session=s1, x_device_id="",
        )
        assert result["ok"] is True

    with Session(engine) as s:
        final = s.get(User, x["id"]).referral_credits
    assert final == 5 + 1 + 3, (
        f"итог {final} вместо 9 — промокод затёр параллельное реферальное начисление "
        "устаревшим прочитанным числом"
    )


# ============================ R6: ключ телефона в PromoClaimLog — не сам номер ============================
def test_phone_claim_key_is_not_reversible_to_the_number():
    """`PromoClaimLog` обещает в docstring «по ключу человека не найти, если не знать номер
    заранее» — раньше ключом был голый `_phone_key(phone)` (просто нормализованные цифры
    номера, без всякого хэширования), то есть обещание было неправдой: у ключа в базе и
    самого номера совпадали цифры (найдено независимым ревью leaf-1.3, круг 3)."""
    phone = "+79991234567"
    digits = "79991234567"
    key = promo_router._phone_claim_key(phone)

    assert digits not in key, f"ключ содержит цифры номера в явном виде: {key}"
    assert key != digits
    assert len(key) == 64 and all(c in "0123456789abcdef" for c in key), (
        "ожидаем шестнадцатеричный HMAC-SHA256"
    )
    # Детерминированность: тот же номер → тот же ключ — иначе повтор тем же номером нечем ловить.
    assert promo_router._phone_claim_key(phone) == key
    # Разные написания одного российского номера сводятся к одному ключу (как и раньше).
    assert promo_router._phone_claim_key("89991234567") == key
    # Разные номера — разные ключи.
    assert promo_router._phone_claim_key("+79997654321") != key


# ============================ R7: миграция переводит старые открытые ключи в HMAC ============================
@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
def test_hmac_migration_converts_legacy_keys_and_is_idempotent():
    """Старая (до круга 3) строка хранила голые цифры номера как есть — миграция обязана
    перевести их в HMAC, не трогая уже-HMAC строки, и не портить данные при повторном
    прогоне или откате (идемпотентность; HMAC необратим — downgrade честный no-op)."""
    migration = _load_hmac_migration("pg_promo_claim_hmac_migration_unit")
    legacy_digits = "79995550199"
    already_hmac = promo_router._phone_claim_key("+79995550299")

    with engine.begin() as connection:
        # Временная таблица той же формы — подменяет promoclaimlog ТОЛЬКО на этом соединении,
        # общая таблица приложения не тронута (приём из test_refresh_replay_postgres.py).
        connection.execute(text(
            "CREATE TEMP TABLE promoclaimlog (id INTEGER PRIMARY KEY, promo_id INTEGER NOT NULL, "
            "phone_key VARCHAR NOT NULL, device_id VARCHAR NOT NULL) ON COMMIT DROP"
        ))
        connection.execute(text(
            "INSERT INTO promoclaimlog (id, promo_id, phone_key, device_id) VALUES "
            "(1, 1, :legacy, ''), (2, 1, :hmac, '')"
        ), {"legacy": legacy_digits, "hmac": already_hmac})

        from alembic.migration import MigrationContext
        from alembic.operations import Operations
        with Operations.context(MigrationContext.configure(connection)):
            migration.upgrade()
            rows = dict(connection.execute(text("SELECT id, phone_key FROM promoclaimlog")).all())
            assert rows[1] == promo_router._phone_claim_key(legacy_digits), (
                "старый открытый ключ не перевёлся в HMAC"
            )
            assert rows[2] == already_hmac, "уже-HMAC строку миграция не должна трогать повторно"

            migration.upgrade()   # второй прогон подряд — идемпотентность
            rows_again = dict(connection.execute(text("SELECT id, phone_key FROM promoclaimlog")).all())
            assert rows_again == rows, "повторный прогон миграции изменил уже сконвертированные строки"

            migration.downgrade()  # безопасный no-op — HMAC необратим
            rows_after_down = dict(connection.execute(text("SELECT id, phone_key FROM promoclaimlog")).all())
            assert rows_after_down == rows, "downgrade не должен менять данные — HMAC необратим"


# ============================ R8: переходный период — ловим повтор по ОБОИМ видам ключа ============================
@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
def test_legacy_plaintext_claim_still_blocks_reapply_before_and_after_migration(client, user_factory):
    """Строка с ключом ДО круга 3 (голые цифры номера, как могло лежать на проде) обязана
    продолжать ловить «тот же номер снова» — и пока миграция `promo_claim_hmac` ещё не
    прогналась на этом окружении (проверка в `promo_apply` ищет оба вида ключа), и после
    (ключ уже HMAC, ищется как обычно)."""
    НОМЕР = "+79995559911"
    admin = user_factory("HmacГраницаАдмин", role=UserRole.admin)
    r = client.post("/admin/promo", headers=admin["auth"], json={"code": "HMACBORDER", "kind": "welcome"})
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        promo = s.exec(select(PromoCode).where(PromoCode.code == "HMACBORDER")).one()
        pid = promo.id
        # Строка «как будто с прода до круга 3» — ключ голыми цифрами, без HMAC.
        s.add(PromoClaimLog(promo_id=pid, phone_key=_phone_key(НОМЕР), device_id=""))
        s.commit()

    def _человек_с_номером(имя):
        ч = user_factory(имя)
        with Session(engine) as s:
            u = s.get(User, ч["id"])
            u.phone = НОМЕР
            s.add(u)
            s.commit()
        return ч

    # ДО миграции: прикладная проверка обязана найти старую строку по legacy-ключу.
    до = _человек_с_номером("HmacГраницаДо")
    r1 = client.post("/promo/apply", headers=до["auth"], json={"code": "HMACBORDER"})
    assert r1.status_code == 409, (
        f"старая (открытая) строка не поймала повтор ДО миграции: {r1.status_code} {r1.text}"
    )
    # Телефон уникален на аккаунт (ix_user_phone) — следующий «тот же номер» должен сначала
    # освободиться удалением аккаунта, ровно как в сценарии из её docstring (круг аферы).
    client.post("/me/delete", headers=до["auth"])

    migration = _load_hmac_migration("pg_promo_claim_hmac_migration_e2e")
    from alembic.migration import MigrationContext
    from alembic.operations import Operations
    with engine.begin() as connection:
        with Operations.context(MigrationContext.configure(connection)):
            migration.upgrade()

    with Session(engine) as s:
        keys = [row[0] for row in s.execute(text("SELECT phone_key FROM promoclaimlog")).all() if row[0]]
    assert all(_HEX64.match(k) for k in keys), "после миграции в таблице остались не-HMAC ключи"

    # ПОСЛЕ миграции: та же строка теперь лежит с HMAC-ключом — обязана продолжать ловить повтор.
    после = _человек_с_номером("HmacГраницаПосле")
    r2 = client.post("/promo/apply", headers=после["auth"], json={"code": "HMACBORDER"})
    assert r2.status_code == 409, (
        f"та же строка перестала ловить повтор ПОСЛЕ миграции: {r2.status_code} {r2.text}"
    )

    # Миграция дважды подряд (в т.ч. на боевых данных, не только на синтетике R7) — без ошибок.
    with engine.begin() as connection:
        with Operations.context(MigrationContext.configure(connection)):
            migration.upgrade()
