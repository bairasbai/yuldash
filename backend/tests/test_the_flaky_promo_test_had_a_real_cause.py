"""Волна 206: разбор плавающего `test_remainder_goes_to_driver_wallet`.

СИМПТОМ. Один раз в полном прогоне (4058 тестов) тест про промо-компенсацию упал с ошибкой
SQLAlchemy. По отдельности зелёный, повторные полные прогоны зелёные. Классический
«плавающий».

ЧТО ДОКАЗАНО ЗАПУСКОМ.

1. Номера заказов в SQLite ПЕРЕИСПОЛЬЗУЮТСЯ. У таблицы обычный `INTEGER PRIMARY KEY` без
   `AUTOINCREMENT`: удалили последние строки — следующая вставка получает тот же номер
   (`test_sqlite_reuses_deleted_ids`). А заказы у нас реально удаляют: удаление аккаунта
   и ретеншен-чистка.

2. Пять тестовых файлов клали в кошелёк запись с `ext_id="promo:<номер заказа>"` — просто
   чтобы у водителя были деньги. Это ЧУЖОЕ пространство имён: на нём стоит частичный
   уникальный индекс `uq_ledgerentry_promo_comp`, и по нему же работает идемпотентность
   настоящей компенсации.

   Вместе 1 и 2 дают мину: номер освободился, достался новому заказу — и настоящая
   компенсация по нему видит «уже начислено» и возвращает ЧУЖУЮ запись. Деньги до водителя
   не доходят, ошибки нет, балансы в прогоне зависят от того, кто отработал раньше
   (`test_reused_order_id_would_steal_someone_elses_compensation`).

3. Мой собственный тест волны 205 заводил в базе ОБЩИЙ городской тариф, если не находил
   готовый. `active_tariff` берёт первую активную строку зоны — то есть тест начинал
   определять цены всем, кто идёт после него. Исправлено: теперь он заводит свой
   неактивный тариф.

ЧЕГО ДОКАЗАТЬ НЕ УДАЛОСЬ — и я не буду делать вид, что удалось.

Точную ошибку того единственного падения воспроизвести не получилось: четыре полных прогона
(в том числе с фиксированным порядком и с полными трассировками) прошли чисто, а краткий
отчёт первого прогона обрезал класс исключения до «sqlal…». Поэтому связь между найденными
минами и ТЕМ падением — вероятная, а не доказанная.

Проверялась и отвергнута ещё одна версия: будто `session.rollback()` внутри
`post_promo_compensation` стирает работу вызывающего. Оба вызывающих (`done`, `finish_early`)
коммитят статус заказа ДО начисления, так что терять нечего. Версия не подтвердилась.
"""
from __future__ import annotations

from sqlmodel import Session, select

from app import ledger
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, LedgerEntry, LedgerKind, UserRole
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


def _заказ(s: Session, driver_id: int, passenger_id: int) -> int:
    o = InstantOrder(passenger_id=passenger_id, driver_id=driver_id,
                     from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                     status=S.done, price_estimate=300, price_final=300, done_at=utcnow())
    s.add(o)
    s.commit()
    s.refresh(o)
    return o.id


def test_sqlite_reuses_deleted_ids(client, user_factory):
    """Опора всего разбора: номер удалённого заказа достаётся следующему.

    Если однажды это перестанет быть правдой (включат AUTOINCREMENT), тест покраснеет
    и напомнит, что разбор ниже держался на этом факте.
    """
    d = user_factory("ПовторВод", role=UserRole.driver)
    p = user_factory("ПовторПас")
    with Session(engine) as s:
        первый = _заказ(s, d["id"], p["id"])
        s.delete(s.get(InstantOrder, первый))
        s.commit()
        второй = _заказ(s, d["id"], p["id"])

    if engine.dialect.name == "postgresql":
        assert второй > первый, "PostgreSQL sequence must not reuse the deleted order ID"
        return
    assert второй == первый, (
        "номер удалённого заказа больше не переиспользуется — хорошо, но разбор волны 206 "
        "держался на обратном, перечитай его"
    )


def test_reused_order_id_would_steal_someone_elses_compensation(client, user_factory):
    """Что на самом деле делает занятый `ext_id`: деньги не доходят до водителя.

    Проверка идемпотентности в `post_promo_compensation` видит запись с таким `ext_id`
    и возвращает ЕЁ — не заводя своей. Если запись чужая (номер заказа переиспользован
    после удаления), настоящий водитель не получает компенсацию вовсе, и никто об этом
    не узнаёт: ошибки нет, функция вернула «уже начислено».

    Отсюда и брались плавающие падения в общем прогоне: балансы зависели от того, какие
    тесты отработали раньше.
    """
    чужой = user_factory("ЧужойВод", role=UserRole.driver)
    наш = user_factory("НашВод", role=UserRole.driver)
    p = user_factory("ПереиспользованиеПас")

    with Session(engine) as s:
        занятый = _заказ(s, чужой["id"], p["id"])
        s.add(LedgerEntry(driver_id=чужой["id"], kind=LedgerKind.adj, amount_kop=30_000,
                          ext_id=ledger.promo_comp_ext_id(занятый), note="чужая компенсация"))
        s.commit()
        s.delete(s.get(InstantOrder, занятый))     # номер освободился
        s.commit()
        новый_заказ = _заказ(s, наш["id"], p["id"])
        if engine.dialect.name == "postgresql":
            assert новый_заказ > занятый, "PostgreSQL must allocate a fresh order ID"
        else:
            assert новый_заказ == занятый, "проба не собралась: номер не переиспользовался"

        было = ledger.driver_balance(s, наш["id"])
        ledger.post_promo_compensation(s, наш["id"], новый_заказ, 5_000)
        стало = ledger.driver_balance(s, наш["id"])

    # Это ФИКСАЦИЯ поведения, а не одобрение: пока номер занят чужой записью, деньги
    # не дойдут. Защита от такой ситуации — сторож ниже: тесты не занимают `promo:` руками,
    # а на боевом Postgres номера заказов не переиспользуются вовсе.
    if engine.dialect.name == "postgresql":
        assert стало - было == 5_000, "fresh PostgreSQL order must receive its own compensation"
        return
    assert стало == было, (
        "поведение изменилось: теперь по занятому `ext_id` деньги всё-таки начисляются. "
        "Перечитай разбор волны 206 — сторож ниже писался под прежнее поведение"
    )


def test_compensation_is_still_idempotent(client, user_factory):
    """Защита не сломана: повторный вызов не начисляет второй раз."""
    d = user_factory("ИдемпотентВод", role=UserRole.driver)
    p = user_factory("ИдемпотентПас")
    with Session(engine) as s:
        oid = _заказ(s, d["id"], p["id"])
        было = ledger.driver_balance(s, d["id"])
        первая = ledger.post_promo_compensation(s, d["id"], oid, 4_200)
        вторая = ledger.post_promo_compensation(s, d["id"], oid, 4_200)
        стало = ledger.driver_balance(s, d["id"])

    assert первая is not None and вторая is not None
    assert первая.id == вторая.id, "завели вторую запись вместо возврата первой"
    assert стало - было == 4_200, f"начислили {(стало - было) / 100:g} ₽ вместо 42 ₽"


def test_compensation_actually_credits_the_wallet(client, user_factory):
    """И основное поведение на месте: деньги доходят до кошелька."""
    d = user_factory("НачислениеВод", role=UserRole.driver)
    p = user_factory("НачислениеПас")
    with Session(engine) as s:
        oid = _заказ(s, d["id"], p["id"])
        было = ledger.driver_balance(s, d["id"])
        ledger.post_promo_compensation(s, d["id"], oid, 7_700)
        стало = ledger.driver_balance(s, d["id"])
        запись = s.exec(select(LedgerEntry).where(
            LedgerEntry.ext_id == ledger.promo_comp_ext_id(oid))).first()

    assert стало - было == 7_700
    assert запись is not None and запись.kind == LedgerKind.adj


def test_no_test_plants_a_promo_ledger_entry_by_hand():
    """Сторож: тесты не занимают пространство имён `promo:` вручную.

    Так мина и была заложена. Тест кладёт в кошелёк запись `promo:<номер>` просто чтобы
    у водителя были деньги, потом другой тест удаляет заказы, номер достаётся новому заказу —
    и настоящая компенсация по нему падает на уникальном индексе. Между тестами это не видно
    никак: каждый по отдельности зелёный.

    Нужен баланс — клади запись БЕЗ `ext_id` или с любым другим префиксом.
    """
    import pathlib
    import re

    свои = pathlib.Path(__file__).name
    нарушители = []
    for файл in sorted(pathlib.Path(".").glob("tests/*.py")):
        if файл.name == свои:
            continue
        for номер, строка in enumerate(файл.read_text(encoding="utf-8").splitlines(), 1):
            if re.search(r'ext_id\s*=\s*f?"promo:', строка):
                нарушители.append(f"{файл.name}:{номер}")

    assert not нарушители, (
        "тест вручную занимает `ext_id` промо-компенсации — после переиспользования номера "
        "заказа настоящая компенсация упадёт на уникальном индексе:\n  "
        + "\n  ".join(нарушители)
    )
