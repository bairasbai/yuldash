"""Гейт такси (волна 2): глобальный/городской флаг + проверка таксиста (580-ФЗ).

Два независимых гейта для режима ТАКСИ (instant):
  (a) доступность по месту: settings.taxi_enabled=False → выключено везде;
      True и TaxiCity пуста → включено везде; True и есть записи → только города
      с enabled=True. Город определяем ближайшим из CITY_COORDS в радиусе
      taxi_city_radius_km; дальше/без координат → «город неизвестен» → в city-режиме выкл.
  (b) онбординг таксиста: возить такси может только водитель с approved TaxiApplication.

ПОПУТКА (плановые Ride/Booking) этими гейтами НЕ затрагивается — отдельный поток.
Приватность: координаты не логируем.
"""
from datetime import date
from typing import Optional

from sqlmodel import Session, select

from .config import settings
from .models import DriverProfile, TaxiApplication, TaxiApplicationStatus, TaxiCity
from .services import CITY_COORDS, haversine_km
from .timeutil import utcnow
from .workday import local_now

# Сообщения гейта — двуязычно (RU + черновой BA, финал башкирского — за Александром).
MSG_GLOBAL_OFF = {
    "ru": "Такси Юлдаш скоро запустится! А пока поезжай попуткой 🚗",
    "ba": "Юлдаш таксиы тиҙҙән асыла! Ә әлегә юлдаш булып бар 🚗",
}
MSG_CITY_OFF = {
    "ru": "Такси скоро в твоём городе 🚕 А пока поезжай попуткой",
    "ba": "Тиҙҙән таксиы һинең ҡалаңда ла булыр 🚕 Ә әлегә юлдаш булып бар",
}
# Документы просрочены: причина отказа отличается от «заявка не одобрена» — человек должен
# понимать, что дело не в модерации, а в сроке ОСАГО/разрешения (пуш об этом шлёт doc_check.py).
# С 29.08 просроченные документы закрывают И платную доставку: полис у машины один, и
# «ОСАГО кончилось, но коробки вози» — это та же машина на той же дороге. Текст обязан
# называть обе двери, иначе человек упрётся в закрытую и не поймёт, почему.
MSG_DOCS_EXPIRED = {
    "ru": "Истёк срок документов (ОСАГО или разрешение). Обнови их — и такси с доставкой "
          "снова откроются. Попутка работает.",
    "ba": "Документтарҙың ваҡыты сыҡҡан (ОСАГО йәки рөхсәт). Яңырт — такси ла, илтеү ҙә "
          "кире асыла. Юлдаш эшләй.",
}
MSG_OK = {
    "ru": "Такси доступно",
    "ba": "Такси эшләй",
}

# Понятные 403 гейтов — ОБА языка (волна 177). Раньше здесь лежала русская строка с пометкой
# «UI локализует сам»: на главном экране так и есть, но тот же текст уходит в 403 с ручек
# зоны и спроса. Приложение, увидев одноязычный detail, показывает башкироязычному общую
# заглушку «Был эшкә рөхсәт юҡ» — то есть человек узнаёт, что нельзя, но не узнаёт почему.
TAXI_UNAVAILABLE_MSG = "Такси скоро в твоём городе. А пока поезжай попуткой 🚗"
TAXI_UNAVAILABLE_MSG_BA = "Такси тиҙҙән һинең ҡалала. Хәҙергә юлдаш менән бар 🚗"
TAXI_NOT_APPROVED_MSG = "Пройди проверку таксиста, чтобы возить такси"
TAXI_NOT_APPROVED_MSG_BA = "Такси йөрөтөр өсөн таксист тикшереүен үт"


def _nearest_city(lat: float, lng: float) -> Optional[str]:
    """Ближайший известный город (CITY_COORDS) в радиусе taxi_city_radius_km, иначе None.
    У города может быть два имени (RU/BA, напр. Баймак/Баймаҡ) — возвращаем найденный ключ,
    сравнение со списком TaxiCity идёт по всем именам той же точки (см. availability)."""
    best_name, best_km = None, None
    for name, (clat, clng) in CITY_COORDS.items():
        km = haversine_km(lat, lng, clat, clng)
        if best_km is None or km < best_km:
            best_name, best_km = name, km
    if best_name is not None and best_km is not None and best_km <= settings.taxi_city_radius_km:
        return best_name
    return None


def _city_aliases(name: str) -> set[str]:
    """Все имена одной точки CITY_COORDS (RU/BA-варианты), в нижнем регистре."""
    coords = CITY_COORDS.get(name)
    if coords is None:
        return {name.casefold()}
    return {n.casefold() for n, c in CITY_COORDS.items() if c == coords}


def launch_promo_state() -> dict:
    """Идёт ли ещё набор в промо запуска «первым водителям — 0% комиссии».

    Зачем в ответе гейта: приложение зовёт водителей строкой «0% комиссии первые 3 месяца»,
    а промо по умолчанию ВЫКЛЮЧЕНО (`launch_promo_until` пуста) и в любом случае кончается
    датой. Обещание, которого сервер не выполнит, — не маркетинг, а обман: экран должен
    показывать эту строку только когда набор реально идёт (аудит 2026-09-02).
    """
    raw = (settings.launch_promo_until or "").strip()
    if not raw:
        return {"on": False, "percent": settings.launch_promo_percent, "days": settings.launch_promo_days}
    try:
        until = date.fromisoformat(raw)
    except ValueError:                      # кривая дата в .env → промо считаем выключенным
        return {"on": False, "percent": settings.launch_promo_percent, "days": settings.launch_promo_days}
    return {
        "on": local_now().date() <= until,
        "percent": settings.launch_promo_percent,
        "days": settings.launch_promo_days,
        "until": raw,
    }


def availability(session: Session, lat: Optional[float] = None, lng: Optional[float] = None) -> dict:
    """Доступно ли такси в точке (lat, lng). Ответ единый для API и внутренних гейтов:
    {"enabled": bool, "reason": "global_off"|"city_off"|"ok", "message": {"ru","ba"}, "city": str|None}.
    city — ближайший известный город (для предзаполнения формы листа ожидания, §11);
    None = город не определён. Аддитивное поле, старые клиенты его игнорируют."""
    near = _nearest_city(lat, lng) if (lat is not None and lng is not None) else None
    promo = launch_promo_state()            # аддитивное поле: старые клиенты игнорируют
    if not settings.taxi_enabled:
        return {"enabled": False, "reason": "global_off", "message": MSG_GLOBAL_OFF, "city": near,
                "launch_promo": promo}
    cities = session.exec(select(TaxiCity)).all()
    if not cities:
        return {"enabled": True, "reason": "ok", "message": MSG_OK, "city": near,
                "launch_promo": promo}
    enabled_names: set[str] = set()
    for c in cities:
        if c.enabled:
            enabled_names |= _city_aliases(c.city.strip())
    if lat is None or lng is None:
        return {"enabled": False, "reason": "city_off", "message": MSG_CITY_OFF, "city": None,
                "launch_promo": promo}
    if near is not None and _city_aliases(near) & enabled_names:
        return {"enabled": True, "reason": "ok", "message": MSG_OK, "city": near,
                "launch_promo": promo}
    return {"enabled": False, "reason": "city_off", "message": MSG_CITY_OFF, "city": near,
            "launch_promo": promo}


def my_application(session: Session, user_id: int) -> Optional[TaxiApplication]:
    return session.exec(select(TaxiApplication).where(TaxiApplication.user_id == user_id)).first()


def is_approved_taxi_driver(session: Session, user_id: int) -> bool:
    """Гейт (b): есть ли одобренная заявка таксиста (580-ФЗ) с ДЕЙСТВУЮЩИМИ документами.

    Раньше проверка была разовой: одобрили в июле — человек считался годным вечно и возил
    с просроченным ОСАГО в декабре, а мы «проверенная служба» (аудит 2026-07-26). Тогда завели
    фоновую задачу app/doc_check.py: она ставит `docs_expired`, когда срок истёк, и допуск
    снимается до обновления документа. ПОПУТКА продолжает работать — она не требует разрешения.

    Но гейт смотрел ТОЛЬКО на флаг, то есть держался на том, что задача успела отработать.
    Водитель с ОСАГО, истёкшим вчера, выходил на линию и вёз людей до её ближайшего запуска
    (проверено запросом — 200; аудит 2026-08-14, волна 66). А если systemd-таймер на сервере
    не настроен или упал, окно не «до утра», а навсегда — и никто этого не заметит.

    Теперь решаем по ДАТАМ, а флаг остаётся быстрым индексом и поводом для уведомления.
    Считает одна функция `doc_check.overdue_docs` — та же, которой пользуется задача."""
    app = my_application(session, user_id)
    if app is None or app.status != TaxiApplicationStatus.approved:
        return False
    # Машина из стоп-списка (решение 30.08). Стоит первым: это самый простой факт из всех —
    # он не про сроки и не про наши очереди, а про то, на чём человек собрался возить людей.
    if car_retired(session, user_id):
        return False
    if _docs_expired_now(app):
        return False
    # Государственный реестр — последнее слово (580-ФЗ). Наше одобрение говорит «мы его
    # проверили», разрешение в реестре — «государство разрешило ему возить людей». Второе
    # мы не выдаём и отменить не можем, поэтому оно и стоит последним.
    if permit_missing(app):
        return False
    # Фотоконтроль машины: последняя ступень мягкой лестницы (30.08). Первую неделю
    # просрочки человек только получает напоминания и падает в подборе — сюда доходит
    # тот, кто не показал машину больше недели. Попутки это не касается.
    from . import carphoto as cp_mod
    return not cp_mod.blocked(session, user_id, cp_mod.TAXI)


def approved_taxi_driver_ids(session: Session, driver_ids: list, now=None) -> set:
    """Кто из этих водителей ДОПУЩЕН возить такси — одним кругом запросов (волна 221).

    Нужна подбору такси. Волна 60 донесла до него все четыре НАКАЗАНИЯ водителя (пауза
    «Справедливости», пауза качества, отдых, долг), а про ДОПУСК не вспомнила: гейт линии
    спрашивает ещё и `is_approved_taxi_driver`. Разница важна тем, что допуск истекает САМ,
    по календарю, посреди смены: вышел в 22:00 со свежим ОСАГО, в полночь оно кончилось,
    а присутствие на линии живёт своим сроком — водитель остался `online`.

    Подбор продолжал слать ему офферы. В пуше оффера едет АДРЕС ПОДАЧИ пассажира, то есть
    адрес уходил тому, кому мы сами запретили работать; принять заказ он всё равно не мог
    (`accept` идёт через тот же гейт и отвечает 403), оффер сгорал по таймауту, и подбор
    шёл к следующему. Ночью в райцентре следующего может не быть.

    Решения принимают ТЕ ЖЕ функции, что и одиночный гейт (`retired_model`,
    `_docs_expired_now`, `permit_missing`, фотоконтроль), — иначе гейт и фильтр разъедутся,
    а такие пары разъезжаются всегда.
    """
    if not driver_ids:
        return set()
    from . import car_class as cc
    from . import carphoto as cp_mod

    ids = list(driver_ids)
    заявки = {a.user_id: a for a in session.exec(
        select(TaxiApplication).where(TaxiApplication.user_id.in_(ids))).all()}
    профили = {p.user_id: p for p in session.exec(
        select(DriverProfile).where(DriverProfile.user_id.in_(ids))).all()}
    без_фото = cp_mod.blocked_user_ids(session, ids, cp_mod.TAXI, now)

    годные = set()
    for did in ids:
        app = заявки.get(did)
        if app is None or app.status != TaxiApplicationStatus.approved:
            continue
        p = профили.get(did)
        # Профиля нет — машину не с чем сверять; одиночный `car_retired` в этом случае тоже
        # отвечает «всё в порядке».
        if p is not None and cc.retired_model(p.car_make, p.car_model):
            continue
        if _docs_expired_now(app):
            continue
        if permit_missing(app, now):
            continue
        if did in без_фото:
            continue
        годные.add(did)
    return годные


MSG_CAR_RETIRED = {
    "ru": "На этой машине такси возить нельзя: пассажир платит за поездку и вправе "
          "рассчитывать на машину, в которой безопасно и не тесно. Попутка работает как "
          "обычно — там требований к машине нет.",
    "ba": "Был машинала такси йөрөтөп булмай: юлаусы сәфәр өсөн түләй һәм именлек менән "
          "иркенлеккә хаҡлы. Юлдаш ғәҙәттәгесә эшләй — унда машинаға талап юҡ.",
}


def car_retired(session: Session, user_id: int) -> str:
    """Машина водителя в стоп-списке такси. Возврат — что совпало ("" = всё в порядке).

    Смотрим марку и модель из профиля. После проверки в государственном реестре они приходят
    из ФГИС, а не со слов человека, — переименовать «копейку» в анкете не поможет.

    ПОПУТКА ЭТИМ НЕ ЗАТРАГИВАЕТСЯ. Там требований к машине нет и не будет: сосед везёт соседа
    на том, что у него есть, и это нормально. Правило только про такси — работу за деньги,
    где пассажир выбирал не человека, а услугу.
    """
    from . import car_class as cc
    profile = session.exec(select(DriverProfile).where(
        DriverProfile.user_id == user_id)).first()
    if profile is None:
        return ""
    return cc.retired_model(profile.car_make, profile.car_model)


MSG_NO_PERMIT = {
    "ru": "В реестре такси нет действующего разрешения на эту машину. Получить его можно "
          "бесплатно через Госуслуги за 5–20 рабочих дней — мы подскажем шаги. Попутка "
          "работает и без разрешения.",
    "ba": "Такси реестрында был машинаға ғәмәлдәге рөхсәт юҡ. Уны Госуслуги аша 5–20 эш "
          "көнөндә бушлай алып була — аҙымдарын әйтербеҙ. Юлдаш рөхсәтһеҙ ҙә эшләй.",
}


def refresh_permit_from_registry(session: Session, app: TaxiApplication, *,
                                 force: bool = False) -> bool:
    """Спросить государственный реестр про машину этой заявки и запомнить ответ.

    Возврат — True, если реестр ответил (неважно, «да» или «нет»); False — если спросить
    не удалось. Прежний ответ при неудаче НЕ трогаем: он всё ещё лучшее, что у нас есть.

    `force=True` — спросить не глядя на давность (подача заявки, кнопка модератора).
    Иначе спрашиваем не чаще `fgis_recheck_hours`: каждый запрос стоит денег, а разрешение
    не меняется по десять раз в сутки.
    """
    from . import fgis
    if not fgis.enabled() or app is None:
        return False
    profile = session.exec(select(DriverProfile).where(
        DriverProfile.user_id == app.user_id)).first()
    plate = getattr(profile, "car_plate", "") if profile else ""
    if not fgis.normalize_plate(plate):
        return False
    now = utcnow()
    if not force and app.fgis_checked_at is not None:
        свежесть = (now - app.fgis_checked_at).total_seconds() / 3600.0
        if свежесть < float(settings.fgis_recheck_hours):
            return True        # спрашивали недавно — не платим за тот же ответ дважды
    ответ = fgis.lookup(plate)
    if ответ is None:
        return False           # реестр промолчал: старый ответ остаётся в силе
    app.fgis_checked_at = now
    app.fgis_permit_ok = bool(ответ["permit_ok"])
    app.fgis_permit_until = ответ["permit_until"]
    session.add(app)
    session.commit()
    return True


def permit_missing(app: Optional[TaxiApplication], now=None) -> bool:
    """Реестр ТОЧНО сказал, что действующего разрешения нет.

    Тонкость, ради которой функция и написана отдельно: «реестр не ответил» и «разрешения
    нет» — разные вещи, и смешивать их нельзя. Молчание чужого сервера это НАША проблема:
    снимать человека с линии из-за собственного таймаута нечестно, и он даже не поймёт, за
    что. Поэтому:

      • ответа никогда не было         → не мешаем (проверка идёт фоном);
      • ответ «разрешение есть»        → не мешаем;
      • ответ «разрешения нет»         → на линию не пускаем;
      • ответ есть, но старше          → не мешаем, но перепроверяем: жить вечно на
        `fgis_stale_days`                прошлогоднем «да» тоже нельзя.

    Выключенная проверка (нет флага или токена) не мешает никому и никогда.
    """
    from . import fgis
    if app is None or not fgis.enabled():
        return False
    if app.fgis_checked_at is None:
        return False
    now = now or utcnow()
    возраст_дней = (now - app.fgis_checked_at).days
    if возраст_дней > int(settings.fgis_stale_days):
        return False           # ответ протух — это не приговор, а повод спросить заново
    return not bool(app.fgis_permit_ok)


def _docs_expired_now(app: TaxiApplication) -> bool:
    """Флаг ИЛИ факт по датам. Импорт локальный: doc_check тянет модели и конфиг."""
    if getattr(app, "docs_expired", False):
        return True
    from .doc_check import overdue_docs
    return bool(overdue_docs(app))


def taxi_permit_missing(session: Session, user_id: int) -> bool:
    """Реестр сказал «разрешения нет» — для честного текста отказа (MSG_NO_PERMIT).
    Отвечает так же, как гейт выше, чтобы человек не гадал, почему его не пускают."""
    app = my_application(session, user_id)
    return (app is not None and app.status == TaxiApplicationStatus.approved
            and permit_missing(app))


def taxi_car_photo_blocked(session: Session, user_id: int) -> bool:
    """Пауза из-за фотоконтроля — для честного текста отказа (`carphoto.MSG_BLOCKED`).
    Отвечает так же, как гейт: одна функция на обоих концах (урок волны 60)."""
    from . import carphoto as cp_mod
    app = my_application(session, user_id)
    return (app is not None and app.status == TaxiApplicationStatus.approved
            and cp_mod.blocked(session, user_id, cp_mod.TAXI))


def taxi_docs_expired(session: Session, user_id: int) -> bool:
    """Заявка одобрена, но документы просрочены — для честного текста отказа (MSG_DOCS_EXPIRED).
    Отвечает так же, как гейт: по флагу И по датам (волна 66)."""
    app = my_application(session, user_id)
    return (app is not None and app.status == TaxiApplicationStatus.approved
            and _docs_expired_now(app))
