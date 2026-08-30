# -*- coding: utf-8 -*-
"""Отказ банка не должен превращаться в «деньги отправлены» (волна 219).

Кошелёк — единственная дверь, где приложение говорит человеку про ЕГО деньги. Волны 215–217
научили класть их туда и списывать, но самой выплатой (`ledger.request_payout`) ни разу не
ходили пробой. Пошли — и нашли две вещи.

1. **Ключ идемпотентности переживает отказ банка.** Банк отказал → резерв возвращается на
   баланс, человеку говорят «не получилось, попробуй позже». Он жмёт ещё раз, клиент шлёт
   ТОТ ЖЕ ключ (так и задумано: `WalletScreen.kt` — «ключ НЕ сбрасываем»), сервер находит
   старую запись выплаты и отвечает `status=already`. Приложение считает это успехом:
   показывает «Готово», чистит поле суммы. Денег при этом не ушло НИ РАЗУ.

   Хуже всего, что баланс сходится: −500 (выплата) и +500 (возврат резерва) дают ноль. Ошибку
   не видно ни в кошельке, ни в сверке — видно только человеку, который ждёт денег на карте.

2. **Отказ выплаты говорит только по-русски.** `PayoutError.message` — одна строка, роутер
   отдаёт её `HTTPException(400, ...)`. Башкироязычный водитель вместо «Минимальная сумма
   вывода — 100 ₽» видит общее «нет доступа» — и это про деньги.
"""
import pytest
from sqlmodel import Session, select

from app import ledger, payments
from app.config import settings
from app.db import engine
from app.models import LedgerEntry, LedgerKind, User, UserRole


def _сессия() -> Session:
    return Session(engine, expire_on_commit=False)


def _положить(driver_id: int, kop: int, ext: str) -> None:
    """Живые деньги в кошельке: начисление, которое можно вывести."""
    with _сессия() as s:
        s.add(LedgerEntry(driver_id=driver_id, kind=LedgerKind.adj, amount_kop=kop,
                          ext_id=ext, note="проба"))
        s.commit()


@pytest.fixture
def банк_отказывает(monkeypatch):
    """Провайдер отвечает явным отказом — деньги НЕ ушли."""
    monkeypatch.setattr(payments, "create_payout",
                        lambda *a, **k: {"payout_id": "p1", "status": "canceled", "mock": True})
    # request_payout импортирует create_payout внутри функции — подменяем там, где он его берёт.
    monkeypatch.setattr("app.payments.create_payout",
                        lambda *a, **k: {"payout_id": "p1", "status": "canceled", "mock": True})


# ==================== 1. Отказ банка не превращается в «уже выплачено» ====================
def test_a_declined_payout_gives_the_money_back(user_factory, банк_отказывает):
    """Банк отказал → резерв вернулся, баланс целый. Это работало и раньше — проверяем опору."""
    d = user_factory("ОтказБанка", role=UserRole.driver)
    _положить(d["id"], 100_000, "seed:declined-back")
    with _сессия() as s:
        было = ledger.driver_balance(s, d["id"])
        with pytest.raises(ledger.PayoutError) as поймали:
            ledger.request_payout(s, d["id"], 50_000, payout_token="tok",
                                  card_last4="1234", idempotency_key="ключ-1")
        assert поймали.value.code == "provider"
        assert ledger.driver_balance(s, d["id"]) == было, "резерв не вернулся на баланс"


def test_retrying_a_declined_payout_does_not_report_success(user_factory, банк_отказывает):
    """Повтор той же попытки НЕ должен отвечать «уже выплачено»: денег не ушло ни разу.

    Это и есть находка. Клиент переиспользует ключ намеренно — чтобы двойной тап не списал
    дважды. Но после отказа банка тот же ключ означает «ничего не произошло», а сервер читал
    его как «всё уже сделано» и приложение рапортовало человеку об успехе.
    """
    d = user_factory("ПовторПослеОтказа", role=UserRole.driver)
    _положить(d["id"], 100_000, "seed:declined-retry")
    with _сессия() as s:
        with pytest.raises(ledger.PayoutError):
            ledger.request_payout(s, d["id"], 50_000, payout_token="tok",
                                  card_last4="1234", idempotency_key="ключ-2")
        # Второй заход с ТЕМ ЖЕ ключом.
        with pytest.raises(ledger.PayoutError) as поймали:
            ledger.request_payout(s, d["id"], 50_000, payout_token="tok",
                                  card_last4="1234", idempotency_key="ключ-2")
        assert поймали.value.code == "provider", (
            "сервер ответил «уже выплачено» по ключу, по которому банк отказал — "
            "приложение покажет человеку успех, а денег он не получит"
        )


def test_a_successful_payout_is_still_idempotent(user_factory, monkeypatch):
    """Опора на месте: удачная выплата по тому же ключу второй раз НЕ списывает."""
    monkeypatch.setattr("app.payments.create_payout",
                        lambda *a, **k: {"payout_id": "p2", "status": "succeeded", "mock": True})
    d = user_factory("ДваждыОдинКлюч", role=UserRole.driver)
    _положить(d["id"], 100_000, "seed:idem-ok")
    with _сессия() as s:
        первый = ledger.request_payout(s, d["id"], 30_000, payout_token="tok",
                                       card_last4="1234", idempotency_key="ключ-3")
        assert первый["status"] == "ok"
        после = ledger.driver_balance(s, d["id"])
        второй = ledger.request_payout(s, d["id"], 30_000, payout_token="tok",
                                       card_last4="1234", idempotency_key="ключ-3")
        assert второй["status"] == "already", "повтор удачной выплаты списал бы деньги дважды"
        assert ledger.driver_balance(s, d["id"]) == после


def test_after_a_decline_a_fresh_key_actually_pays(user_factory, monkeypatch):
    """Отказал банк — новая попытка (новый ключ) проходит и списывает ровно один раз."""
    состояния = iter(["canceled", "succeeded"])
    monkeypatch.setattr("app.payments.create_payout",
                        lambda *a, **k: {"payout_id": "p3", "status": next(состояния), "mock": True})
    d = user_factory("НовыйКлючПослеОтказа", role=UserRole.driver)
    _положить(d["id"], 100_000, "seed:fresh-key")
    with _сессия() as s:
        было = ledger.driver_balance(s, d["id"])
        with pytest.raises(ledger.PayoutError):
            ledger.request_payout(s, d["id"], 40_000, payout_token="tok",
                                  card_last4="1234", idempotency_key="ключ-4a")
        ок = ledger.request_payout(s, d["id"], 40_000, payout_token="tok",
                                   card_last4="1234", idempotency_key="ключ-4b")
        assert ок["status"] == "ok"
        assert ledger.driver_balance(s, d["id"]) == было - 40_000


# ==================== 2. Отказ выплаты говорит на двух языках ====================
@pytest.mark.parametrize("сумма, код", [
    (0, "amount"),
    (settings.payout_min_kop - 1, "min"),
    (settings.payout_max_kop + 1, "max"),
])
def test_every_payout_refusal_speaks_both_languages(user_factory, сумма, код):
    """Отказ про ДЕНЬГИ обязан быть понятен обоим — иначе клиент подменит его «нет доступа»."""
    d = user_factory(f"Отказ{код}", role=UserRole.driver)
    with _сессия() as s:
        with pytest.raises(ledger.PayoutError) as поймали:
            ledger.request_payout(s, d["id"], сумма, payout_token="tok",
                                  card_last4="1234", idempotency_key="ключ-язык")
    ошибка = поймали.value
    assert ошибка.code == код
    assert getattr(ошибка, "message_ba", ""), f"отказ «{код}» существует только по-русски"
    assert ошибка.message_ba != ошибка.message


def test_the_endpoint_hands_both_languages_to_the_client(client, user_factory, monkeypatch):
    """Ручка отдаёт detail словарём {ru, ba} — как все остальные отказы Юлдаша."""
    monkeypatch.setattr(settings, "payouts_enabled", True)   # payouts_ready — свойство, крутим его вход
    d = user_factory("ЯзыкиВРучке", role=UserRole.driver)
    client.post("/wallet/payout/requisite", headers=d["auth"],
                json={"card_number": "4111111111111111"})
    r = client.post("/wallet/payout", headers=d["auth"],
                    json={"amount_kop": 1, "idempotency_key": "ключ-ручка"})
    assert r.status_code == 400, r.text
    detail = r.json()["detail"]
    assert isinstance(detail, dict), f"отказ пришёл строкой, башкирский потеряется: {detail!r}"
    assert detail["ru"] and detail["ba"] and detail["ru"] != detail["ba"]


def test_the_client_is_told_the_attempt_is_finished(client, user_factory, monkeypatch):
    """После отказа банка ручка говорит клиенту, что попытка ЗАКРЫТА.

    Без этого приложение переиспользует ключ (так написано в `WalletScreen.kt`) и упирается
    в тот же отказ снова и снова. Машинный код в теле ответа — то, по чему клиент понимает,
    что пора начинать новую попытку.
    """
    monkeypatch.setattr(settings, "payouts_enabled", True)   # payouts_ready — свойство, крутим его вход
    monkeypatch.setattr("app.payments.create_payout",
                        lambda *a, **k: {"payout_id": "p4", "status": "canceled", "mock": True})
    d = user_factory("ЗакрытаяПопытка", role=UserRole.driver)
    _положить(d["id"], 100_000, "seed:attempt-over")
    client.post("/wallet/payout/requisite", headers=d["auth"],
                json={"card_number": "4111111111111111"})
    r = client.post("/wallet/payout", headers=d["auth"],
                    json={"amount_kop": 50_000, "idempotency_key": "ключ-закрыт"})
    assert r.status_code == 400, r.text
    assert r.json()["detail"].get("code") == "provider", (
        "клиент не узнает, что попытка закрыта, и будет слать тот же ключ"
    )


def test_the_register_marks_a_payout_that_never_left(client, user_factory, monkeypatch):
    """Реестр для сверки помечает отклонённую выплату — иначе админ считает её отправленной.

    Деньги задним числом не переписываем: запись payout остаётся. Но рядом с ней теперь видно,
    что резерв вернули и по этой строке не ушло ничего.
    """
    monkeypatch.setattr(settings, "payouts_enabled", True)
    monkeypatch.setattr("app.payments.create_payout",
                        lambda *a, **k: {"payout_id": "p5", "status": "canceled", "mock": True})
    d = user_factory("ВРеестре", role=UserRole.driver)
    админ = user_factory("АдминРеестра", role=UserRole.admin)
    _положить(d["id"], 100_000, "seed:register")
    client.post("/wallet/payout/requisite", headers=d["auth"],
                json={"card_number": "4111111111111111"})
    client.post("/wallet/payout", headers=d["auth"],
                json={"amount_kop": 50_000, "idempotency_key": "ключ-реестр"})

    r = client.get("/admin/payouts", headers=админ["auth"])
    assert r.status_code == 200, r.text
    строка = next(x for x in r.json() if x["driver_id"] == d["id"])
    assert строка["reversed"] is True, "отклонённая выплата выглядит в сводке как отправленная"


def test_the_register_does_not_cry_wolf(client, user_factory, monkeypatch):
    """Удачная выплата пометки НЕ получает — иначе сверка перестанет ей верить."""
    monkeypatch.setattr(settings, "payouts_enabled", True)
    monkeypatch.setattr("app.payments.create_payout",
                        lambda *a, **k: {"payout_id": "p6", "status": "succeeded", "mock": True})
    d = user_factory("ВРеестреУдачно", role=UserRole.driver)
    админ = user_factory("АдминРеестра2", role=UserRole.admin)
    _положить(d["id"], 100_000, "seed:register-ok")
    client.post("/wallet/payout/requisite", headers=d["auth"],
                json={"card_number": "4111111111111111"})
    client.post("/wallet/payout", headers=d["auth"],
                json={"amount_kop": 50_000, "idempotency_key": "ключ-реестр-ок"})

    r = client.get("/admin/payouts", headers=админ["auth"])
    строка = next(x for x in r.json() if x["driver_id"] == d["id"])
    assert строка["reversed"] is False


# ==================== 3. Отказ банка не должен портить денежный отчёт ====================
def test_a_returned_reserve_is_not_platform_spending(user_factory, monkeypatch):
    """Возврат резерва — не расход платформы, а чужие деньги, которые мы подержали минуту.

    `adj` — кухонный ящик кошелька: туда падают компенсация промо, возврат комиссии, ручная
    доплата админа. Волна 217 научила отчёт разбирать ящик ПО КЛЮЧУ, а не по знаку, — но один
    ключ пропустила. Отклонённая выплата возвращалась записью `adj` с плюсом, отчёт считал её
    «прочей доплатой админа» и вычитал из дохода платформы. Каждый отказ банка делал нас
    беднее на бумаге ровно на сумму выплаты, которой не было.
    """
    from datetime import timedelta
    from app.timeutil import utcnow

    monkeypatch.setattr("app.payments.create_payout",
                        lambda *a, **k: {"payout_id": "p7", "status": "canceled", "mock": True})
    d = user_factory("ОтчётНеВрёт", role=UserRole.driver)
    _положить(d["id"], 100_000, "seed:report")
    с, по = utcnow() - timedelta(days=1), utcnow() + timedelta(days=1)
    with _сессия() as s:
        до = ledger.reconcile(s, с, по)["adj_other_kop"]
        with pytest.raises(ledger.PayoutError):
            ledger.request_payout(s, d["id"], 50_000, payout_token="tok",
                                  card_last4="1234", idempotency_key="ключ-отчёт")
        после = ledger.reconcile(s, с, по)
    assert после["adj_other_kop"] == до, (
        "возврат резерва записался в расход платформы — отчёт занизил доход на сумму "
        "выплаты, которой не было"
    )
