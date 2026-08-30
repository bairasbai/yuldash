# -*- coding: utf-8 -*-
"""Разрешение такси проверяем в государственном реестре, а не на слово (580-ФЗ, 2026-08-29).

БЫЛО. Водитель вписывал номер разрешения руками, и мы верили. Закон при этом обязывает
службу заказа передавать заказы ТОЛЬКО тем, кто есть в реестре легковых такси, и проверять
это. Цена доверия: солидарная ответственность за вред пассажиру, если заказ ушёл нелегалу,
штраф до 50 000 ₽ для ИП и — при систематических нарушениях — исключение нас самих из
реестра служб заказа.

СТАЛО. Спрашиваем реестр по госномеру: при одобрении заявки и дальше раз в сутки фоновой
задачей. Ответ храним, на нём стоит гейт линии.

ГЛАВНОЕ РАЗЛИЧЕНИЕ, ради которого написана половина этих тестов: «реестр молчит» и
«разрешения нет» — разные вещи.

  • реестр ответил «нет»        → на линию не пускаем;
  • реестр не ответил           → пускаем: молчание чужого сервера это НАША проблема,
                                   и снимать человека с линии за наш таймаут нечестно;
  • не спрашивали ни разу       → пускаем, проверка идёт фоном;
  • ответ старше `fgis_stale_days` → пускаем, но переспрашиваем.

Выключенная проверка (нет флага или нет токена) не мешает никому и никогда: пустой токен
при включённом флаге означал бы, что каждый водитель получает «реестр не ответил», и на
проде это выглядело бы как массовый сбой.
"""
from datetime import date, timedelta

import pytest
from sqlmodel import Session, select

from app import fgis
from app import taxi as taxi_mod
from app.config import settings
from app.db import engine
from app.models import DriverProfile, TaxiApplication, TaxiApplicationStatus, UserRole
from app.timeutil import local_date, utcnow


class _Ответ:
    """Подставной ответ HTTP: реестр без сети."""

    def __init__(self, payload, status_code=200):
        self._payload, self.status_code = payload, status_code

    def json(self):
        return self._payload


class _Реестр:
    """Подставной клиент. `payload=None` — сервер лежит (исключение), как в жизни."""

    def __init__(self, payload, status_code=200):
        self.payload, self.status_code, self.calls = payload, status_code, 0

    def get(self, url, params=None, timeout=None):
        self.calls += 1
        if self.payload is None:
            raise RuntimeError("реестр недоступен")
        return _Ответ(self.payload, self.status_code)


@pytest.fixture
def реестр(request):
    """Включает проверку и подменяет клиента. Параметр — что «отвечает» реестр."""
    payload = getattr(request, "param", {"status": True})
    было = (settings.fgis_check_enabled, settings.fgis_api_token)
    settings.fgis_check_enabled, settings.fgis_api_token = True, "test-token"
    client = _Реестр(payload)
    fgis._client_override = client
    yield client
    fgis._client_override = None
    settings.fgis_check_enabled, settings.fgis_api_token = было


def _водитель(client, user_factory, name, plate="А123БВ102"):
    d = user_factory(name, role=UserRole.driver)
    with Session(engine) as s:
        profile = s.exec(select(DriverProfile).where(DriverProfile.user_id == d["id"])).first()
        if profile is None:
            profile = DriverProfile(user_id=d["id"])
        profile.car_plate = plate
        s.add(profile)
        s.commit()
    return d


def _заявка(user_id: int) -> TaxiApplication:
    with Session(engine) as s:
        return s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == user_id)).first()


def _обновить(user_id: int, **fields):
    with Session(engine) as s:
        app = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == user_id)).first()
        for k, v in fields.items():
            setattr(app, k, v)
        s.add(app)
        s.commit()


# ==================== 1. Реестр сказал «нет» ====================
def test_no_permit_in_the_registry_keeps_him_off_the_line(client, user_factory, реестр):
    """Реестр отвечает «разрешения нет» — на линию не пускаем."""
    d = _водитель(client, user_factory, "БезРазрешения")
    реестр.payload = {"status": False}

    with Session(engine) as s:
        app = _заявка(d["id"])
        assert taxi_mod.refresh_permit_from_registry(s, s.merge(app), force=True) is True
        assert taxi_mod.is_approved_taxi_driver(s, d["id"]) is False
        assert taxi_mod.taxi_permit_missing(s, d["id"]) is True


def test_the_refusal_shows_the_way_out(client, user_factory, реестр):
    """Отказ объясняет, что делать: разрешение бесплатное, попутка работает."""
    текст = taxi_mod.MSG_NO_PERMIT["ru"]
    assert "бесплатно" in текст, "человеку не сказали, что разрешение ничего не стоит"
    assert "Госуслуги" in текст, "не сказали, куда идти"
    assert "опутка" in текст, "не сказали, что можно возить попуткой прямо сейчас"
    assert taxi_mod.MSG_NO_PERMIT["ba"].strip(), "нет башкирского текста"


def test_an_expired_date_beats_a_cheerful_status(client, user_factory, реестр):
    """Срок в прошлом перевешивает «действует» в ответе: дата конкретнее слова.

    «Вчера» берём по МЕСТНОМУ календарю (Уфа), как и код. Возьми тест серверную дату —
    и он падал бы ровно те пять часов в сутки, когда сервер и Уфа живут в разных днях.
    Это уже записано правилом в `lessons.md`, и сюда оно тоже относится.
    """
    d = _водитель(client, user_factory, "СрокВышел")
    реестр.payload = {"status": True,
                      "permit_until": (local_date(utcnow()) - timedelta(days=1)).isoformat()}

    with Session(engine) as s:
        taxi_mod.refresh_permit_from_registry(s, s.merge(_заявка(d["id"])), force=True)
    assert _заявка(d["id"]).fgis_permit_ok is False


# ==================== 2. Реестр молчит — это не приговор ====================
def test_a_silent_registry_does_not_take_anyone_off_the_line(client, user_factory, реестр):
    """Сервер реестра лёг — водитель работает. Наш таймаут не его вина."""
    d = _водитель(client, user_factory, "РеестрМолчит")
    реестр.payload = None            # клиент бросит исключение

    with Session(engine) as s:
        assert taxi_mod.refresh_permit_from_registry(s, s.merge(_заявка(d["id"])), force=True) is False
        assert taxi_mod.is_approved_taxi_driver(s, d["id"]) is True


def test_a_silent_registry_does_not_erase_the_previous_answer(client, user_factory, реестр):
    """Молчание не стирает прошлый ответ: он всё ещё лучшее, что у нас есть."""
    d = _водитель(client, user_factory, "БылоДаСталоТихо")
    with Session(engine) as s:
        taxi_mod.refresh_permit_from_registry(s, s.merge(_заявка(d["id"])), force=True)
    assert _заявка(d["id"]).fgis_permit_ok is True

    реестр.payload = None
    with Session(engine) as s:
        taxi_mod.refresh_permit_from_registry(s, s.merge(_заявка(d["id"])), force=True)
    assert _заявка(d["id"]).fgis_permit_ok is True, "молчание реестра стёрло прошлый ответ"


def test_never_checked_means_not_blocked(client, user_factory, реестр):
    """Проверки ещё не было — не мешаем. Она идёт фоном, а человеку надо работать."""
    d = _водитель(client, user_factory, "ЕщёНеСпрашивали")
    assert _заявка(d["id"]).fgis_checked_at is None
    with Session(engine) as s:
        assert taxi_mod.is_approved_taxi_driver(s, d["id"]) is True


def test_a_stale_answer_is_not_a_verdict(client, user_factory, реестр):
    """Ответ старше `fgis_stale_days` не блокирует — это повод переспросить, а не наказать."""
    d = _водитель(client, user_factory, "ОтветПротух")
    _обновить(d["id"], fgis_permit_ok=False,
              fgis_checked_at=utcnow() - timedelta(days=settings.fgis_stale_days + 1))
    with Session(engine) as s:
        assert taxi_mod.is_approved_taxi_driver(s, d["id"]) is True


# ==================== 3. Выключено — значит выключено ====================
def test_disabled_check_blocks_nobody(client, user_factory):
    """Флаг выключен — реестр не спрашиваем и никого не трогаем."""
    d = _водитель(client, user_factory, "ПроверкаВыключена")
    _обновить(d["id"], fgis_permit_ok=False, fgis_checked_at=utcnow())
    with Session(engine) as s:
        assert taxi_mod.is_approved_taxi_driver(s, d["id"]) is True


def test_an_empty_token_is_the_same_as_disabled(client, user_factory):
    """Флаг включён, а ключа нет — это выключено, а не «всем отказать».

    Иначе первый же человек, который включит флаг раньше, чем заведёт токен, снял бы
    с линии всех водителей разом — и выглядело бы это как массовый сбой.
    """
    было = (settings.fgis_check_enabled, settings.fgis_api_token)
    settings.fgis_check_enabled, settings.fgis_api_token = True, "   "
    try:
        assert fgis.enabled() is False
        d = _водитель(client, user_factory, "ФлагБезКлюча")
        _обновить(d["id"], fgis_permit_ok=False, fgis_checked_at=utcnow())
        with Session(engine) as s:
            assert taxi_mod.is_approved_taxi_driver(s, d["id"]) is True
    finally:
        settings.fgis_check_enabled, settings.fgis_api_token = было


# ==================== 4. Деньги и данные ====================
def test_we_do_not_pay_twice_for_the_same_answer(client, user_factory, реестр):
    """Не спрашиваем чаще, чем раз в `fgis_recheck_hours`: каждый запрос стоит денег."""
    d = _водитель(client, user_factory, "НеПлатимДважды")
    with Session(engine) as s:
        taxi_mod.refresh_permit_from_registry(s, s.merge(_заявка(d["id"])), force=True)
    было = реестр.calls

    with Session(engine) as s:      # без force, сразу следом
        taxi_mod.refresh_permit_from_registry(s, s.merge(_заявка(d["id"])))
    assert реестр.calls == было, "заплатили за тот же ответ второй раз"


def test_we_do_not_keep_what_we_do_not_need(client, user_factory, реестр):
    """VIN, ИНН и ОГРН реестр отдаёт — мы их НЕ храним.

    Искать в ФГИС можно по госномеру, который у нас и так есть. Любой лишний идентификатор
    в базе — это то, что придётся защищать и что можно потерять.
    """
    d = _водитель(client, user_factory, "ЛишнегоНеБерём")
    свой_инн = _заявка(d["id"]).inn        # ИНН самозанятого водитель называет сам — он наш
    реестр.payload = {"status": True, "vin": "XTA21099", "inn": "999999999999",
                      "ogrn": "1027700132195", "brand": "Lada", "year": "2019"}
    with Session(engine) as s:
        taxi_mod.refresh_permit_from_registry(s, s.merge(_заявка(d["id"])), force=True)

    app = _заявка(d["id"])
    поля = {c for c in app.__dict__ if not c.startswith("_")}
    assert "vin" not in поля and "ogrn" not in поля, (
        "сохранили идентификаторы из реестра, которые нам не нужны"
    )
    assert app.inn == свой_инн, "ответ реестра перезаписал ИНН, который назвал сам водитель"
    assert app.fgis_permit_ok is True


# ==================== 5. Мелочи, на которых ломаются номера ====================
@pytest.mark.parametrize("написал,ожидаем", [
    ("а123бв 102", "А123БВ102"),
    ("А123БВ-102", "А123БВ102"),
    (" а 123 бв 102 ", "А123БВ102"),
    ("", ""),
    (None, ""),
])
def test_the_plate_is_normalised_before_asking(написал, ожидаем):
    """Люди пишут номер по-разному — для реестра это один номер."""
    assert fgis.normalize_plate(написал) == ожидаем


def test_a_broken_answer_is_treated_as_silence(client, user_factory, реестр):
    """Кривой JSON — это «не знаем», а не «разрешения нет»."""
    d = _водитель(client, user_factory, "КривойОтвет")
    реестр.payload = ["не", "объект"]
    with Session(engine) as s:
        assert taxi_mod.refresh_permit_from_registry(s, s.merge(_заявка(d["id"])), force=True) is False
        assert taxi_mod.is_approved_taxi_driver(s, d["id"]) is True


def test_a_driver_without_a_plate_is_not_asked_about(client, user_factory, реестр):
    """Госномера нет — в реестр не ходим: спрашивать не о чем, а запрос платный."""
    d = _водитель(client, user_factory, "БезНомера", plate="")
    with Session(engine) as s:
        assert taxi_mod.refresh_permit_from_registry(s, s.merge(_заявка(d["id"])), force=True) is False
    assert реестр.calls == 0
