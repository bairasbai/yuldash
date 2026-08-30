# -*- coding: utf-8 -*-
"""Фотоконтроль машины: раз в две недели человек показывает, на чём он возит (580-ФЗ).

БЫЛО. Машину мы видели ОДИН раз — на фото при регистрации. Дальше о ней известно ровно то,
что человек сказал сам: перед выездом он ставит галочку «машина исправна» (`pretrip.py`),
и в базе разбитый бампер, лысая резина и салон, куда стыдно посадить ребёнка, выглядят
точно так же, как новая машина. Закон при этом требует от службы заказа контроля состояния
транспорта, а не честного слова.

СТАЛО. Раз в две недели человек присылает несколько кадров с телефона: такси — четыре
стороны кузова и салон, курьер — две стороны и багажник. Первые два контроля идут через
три дня: новичка надо посмотреть, пока он новичок.

ЧЕГО ЗДЕСЬ СОЗНАТЕЛЬНО НЕТ.
  • Документов в кадре. Их мы уже собрали при регистрации, и просить фотографировать
    паспорт «ещё разок» — это плодить копии чужих документов на ровном месте.
  • Геометок. Файл проходит общую дверь загрузки (`services._validate_upload`), а она
    срезает координаты: где человек живёт — не наше дело. Дату съёмки читаем ДО этого
    (`imagemeta.photo_probe`) и наружу не отдаём.
  • Жёсткой блокировки на следующий день после срока. Лестница мягкая, см. `stage()`.

ЛЕСТНИЦА ПРОСРОЧКИ (`stage`) — та же логика, что у документов: сначала напоминаем, потом
двигаем в подборе, и только потом останавливаем.

    1–3 дня   напоминание, работа не ограничена
    4–7 дней  приоритет в подборе вниз (`priority.py`)
    дальше    пауза до фото — но ПОПУТКА работает всегда: она не такси, ей контроль не нужен

АВТОМАТ И ЧЕЛОВЕК. Автомат отвечает на три вопроса, на которые можно ответить честно:
кадр не мелкий, не скриншот, не присланная второй раз старая фотография (`inspect`).
Грязь, вмятину и чехлы с рынка автомат не видит и не притворяется, что видит, — это
смотрит человек, и не у всех подряд: у новичков, у тех, на кого пожаловались, и каждый
пятый контроль выборкой. Пока контроль лежит у человека на просмотре, водитель НЕ
ограничен ничем: он своё сделал в срок, а наша очередь — это наша проблема.

ЗИМА. С ноября по апрель чистого кузова не требуем: в Башкирии его не удержать чистым и
час, а требование, которое невозможно выполнить, учит людей обходить правила. Целостность
(фары, бампер, ржавчина) и салон — круглый год.

ДВА ВЫКЛЮЧАТЕЛЯ. `car_photo_taxi_enabled` и `car_photo_courier_enabled` — раздельные:
такси и доставка запускаются в разное время. Выключено → модуль не мешает никому и нигде.
"""
from __future__ import annotations

import json
from datetime import datetime, timedelta
from typing import Optional

from sqlmodel import Session, select

from .config import settings
from .logs import log
from .models import (CarPhotoCheck, CourierApplication, Report, TaxiApplication,
                     TaxiApplicationStatus)
from .timeutil import local_date, utcnow

TAXI, COURIER = "taxi", "courier"

# Состояния контроля. `waiting` — ждём кадры; `review` — кадры пришли, смотрит человек;
# `passed`/`failed` — закрыт, остаётся историей.
WAITING, REVIEW, PASSED, FAILED = "waiting", "review", "passed", "failed"
OPEN = (WAITING, REVIEW)

# Вид контроля. Плановый двигает лестницу и может поставить паузу; требование по жалобе
# («пассажир написал, что было грязно») НЕ ограничивает работу само по себе — оно решает
# только одно: подтвердилась жалоба или нет.
PERIODIC, COMPLAINT = "periodic", "complaint"

# Какие кадры просим. Порядок — порядок съёмки: человек обходит машину по кругу и
# заканчивает внутри, чтобы не метаться вокруг неё с телефоном.
SLOTS = {
    TAXI: (
        {"code": "front", "ru": "Спереди", "ba": "Алдан",
         "hint_ru": "Весь перёд машины и номер", "hint_ba": "Машинаның алды һәм номеры"},
        {"code": "back", "ru": "Сзади", "ba": "Арттан",
         "hint_ru": "Весь зад машины и номер", "hint_ba": "Машинаның арты һәм номеры"},
        {"code": "left", "ru": "Левый бок", "ba": "Һул яҡ",
         "hint_ru": "Бок целиком, от фары до фонаря", "hint_ba": "Яҡ тулыһынса, фаранан фонарға тиклем"},
        {"code": "right", "ru": "Правый бок", "ba": "Уң яҡ",
         "hint_ru": "Бок целиком, от фары до фонаря", "hint_ba": "Яҡ тулыһынса, фаранан фонарға тиклем"},
        {"code": "salon", "ru": "Салон", "ba": "Салон",
         "hint_ru": "Задние сиденья и пол — там едет пассажир",
         "hint_ba": "Арттағы урындыҡтар һәм иҙән — юлаусы шунда бара"},
    ),
    COURIER: (
        {"code": "front", "ru": "Спереди", "ba": "Алдан",
         "hint_ru": "Весь перёд машины и номер", "hint_ba": "Машинаның алды һәм номеры"},
        {"code": "back", "ru": "Сзади", "ba": "Арттан",
         "hint_ru": "Весь зад машины и номер", "hint_ba": "Машинаның арты һәм номеры"},
        {"code": "trunk", "ru": "Багажник", "ba": "Багажник",
         "hint_ru": "Открытый и пустой — в нём поедет чужая посылка",
         "hint_ba": "Асыҡ һәм буш — унда кешенең бүләге бара"},
    ),
}

# Требование по жалобе: просим ровно то место, о котором написали. У такси это салон,
# у курьера — багажник: посылка едет там, и «грязная машина» для отправителя означает его.
COMPLAINT_SLOTS = {
    TAXI: ("salon",),
    COURIER: ("trunk",),
}

# Три пункта, по которым человек смотрит салон. Все три ВИДНЫ НА ФОТО — в этом весь смысл
# списка: требование, которое нельзя проверить, превращает разбор в спор о вкусах.
#
# Запаха здесь нет и не будет: по фотографии его не проверить никак. Честнее не обещать
# того, чего мы не делаем, чем держать красивый пункт, который ничего не значит.
CLEAN_RULES = (
    ("Нет мусора и личных вещей водителя", "Сүп һәм водителдең шәхси әйберҙәре юҡ"),
    ("Чехлы и обивка целые — без дыр и пятен", "Чехолдар һәм тышлыҡ бөтөн — тишекһеҙ, тапһыҙ"),
    ("На полу нет грязи слоем", "Иҙәндә ҡат-ҡат бысраҡ юҡ"),
)

# Сколько часов даём на фото по жалобе. Сутки — это «сегодня или завтра утром»: человек
# успевает, даже если жалоба пришла ночью, и при этом машину смотрят по свежим следам.
COMPLAINT_HOURS = 24

# Почему автомат не принял кадр. Текст объясняет, ЧТО СДЕЛАТЬ, а не выносит приговор:
# человек чаще всего ни в чём не виноват, он просто выбрал не тот файл в галерее.
REASON_TEXT = {
    "too_small": ("Кадр слишком мелкий — на нём ничего не разглядеть. Сними ещё раз камерой.",
                  "Кадр бик ваҡ — унда бер нәмә лә күренмәй. Камера менән яңынан төшөр."),
    "screenshot": ("Похоже на скриншот с экрана. Нужна фотография самой машины.",
                   "Экран һүрәтенә оҡшаған. Машинаның үҙен төшөрөргә кәрәк."),
    "stale": ("Снимок сделан давно. Покажи машину такой, какая она сейчас.",
              "Һүрәт күптән төшөрөлгән. Машинаны хәҙерге хәлендә күрһәт."),
    "duplicate": ("Это фото уже присылали раньше. Нужен новый снимок.",
                  "Был һүрәт элек ебәрелгән инде. Яңы кадр кәрәк."),
    "broken": ("Файл не открылся. Попробуй ещё раз.",
               "Файл асылманы. Тағы бер тапҡыр ҡабатла."),
}

# Отказ на линии. Не обвинение, а путь: что сделать и что при этом работает.
MSG_BLOCKED = {
    TAXI: {"ru": "Пора показать машину: 5 фото в профиле — и заказы вернутся сразу. "
                 "Попутка работает как обычно.",
           "ba": "Машинаны күрһәтер ваҡыт: профилдә 5 фото — заказдар шунда уҡ ҡайта. "
                 "Юлдаш ғәҙәттәгесә эшләй."},
    COURIER: {"ru": "Пора показать машину: 3 фото в профиле — и заказы вернутся сразу. "
                    "Попутные посылки работают как обычно.",
              "ba": "Машинаны күрһәтер ваҡыт: профилдә 3 фото — заказдар шунда уҡ ҡайта. "
                    "Юл ыңғайы бүләктәр ғәҙәттәгесә эшләй."},
}


# --------------------------------------------------------------------------- включено ли
def enabled(mode: str) -> bool:
    """Включён ли фотоконтроль для этого режима. Такси и курьер — раздельно."""
    if mode == TAXI:
        return bool(settings.car_photo_taxi_enabled)
    if mode == COURIER:
        return bool(settings.car_photo_courier_enabled)
    return False


def winter_now(now: Optional[datetime] = None) -> bool:
    """Зимний ли сейчас контроль. Месяц — МЕСТНЫЙ (Уфа), как везде в проекте."""
    месяцы = {int(m) for m in str(settings.car_photo_winter_months).split(",") if m.strip().isdigit()}
    return local_date(now or utcnow()).month in месяцы


def slots(mode: str, kind: str = PERIODIC) -> tuple:
    """Кадры, которые просим. Плановый обход — весь набор, жалоба — только спорное место."""
    полный = SLOTS.get(mode, ())
    if kind != COMPLAINT:
        return полный
    нужные = COMPLAINT_SLOTS.get(mode, ())
    return tuple(с for с in полный if с["code"] in нужные)


def check_slots(check: "CarPhotoCheck") -> tuple:
    """Кадры конкретного контроля — по его режиму и виду."""
    return slots(check.mode, getattr(check, "kind", PERIODIC))


# --------------------------------------------------------------------------- чей контроль
def _approved_at(session: Session, user_id: int, mode: str) -> Optional[datetime]:
    """Когда человека одобрили в этом режиме. None — не одобрен, контроль ему не нужен."""
    if mode == TAXI:
        app = session.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == user_id,
            TaxiApplication.status == TaxiApplicationStatus.approved)).first()
    elif mode == COURIER:
        app = session.exec(select(CourierApplication).where(
            CourierApplication.user_id == user_id,
            CourierApplication.status == "approved").order_by(
                CourierApplication.id.desc())).first()
    else:
        return None
    if app is None:
        return None
    return app.reviewed_at or app.created_at


def current(session: Session, user_id: int, mode: str) -> Optional[CarPhotoCheck]:
    """Открытый ПЛАНОВЫЙ контроль (ждём фото или смотрим). Один или ни одного.

    Требование по жалобе сюда не попадает намеренно: на нём стоит вся лестница и пауза
    линии, а жалоба сама по себе работы лишать не должна — иначе один тап постороннего
    выключал бы водителя на сутки.
    """
    return session.exec(select(CarPhotoCheck).where(
        CarPhotoCheck.user_id == user_id, CarPhotoCheck.mode == mode,
        CarPhotoCheck.kind == PERIODIC,
        CarPhotoCheck.status.in_(OPEN)).order_by(CarPhotoCheck.id.desc())).first()


def current_complaint(session: Session, user_id: int,
                      mode: Optional[str] = None) -> Optional[CarPhotoCheck]:
    """Открытое требование фото по жалобе. `mode=None` — в любом режиме."""
    условия = [CarPhotoCheck.user_id == user_id, CarPhotoCheck.kind == COMPLAINT,
               CarPhotoCheck.status.in_(OPEN)]
    if mode is not None:
        условия.append(CarPhotoCheck.mode == mode)
    return session.exec(select(CarPhotoCheck).where(*условия)
                        .order_by(CarPhotoCheck.id.desc())).first()


def last_closed(session: Session, user_id: int, mode: str) -> Optional[CarPhotoCheck]:
    """Последний закрытый ПЛАНОВЫЙ контроль — когда машину показывали в прошлый раз."""
    return session.exec(select(CarPhotoCheck).where(
        CarPhotoCheck.user_id == user_id, CarPhotoCheck.mode == mode,
        CarPhotoCheck.kind == PERIODIC,
        CarPhotoCheck.status.in_((PASSED, FAILED))).order_by(CarPhotoCheck.id.desc())).first()


def _period_after(seq_done: int) -> int:
    """Через сколько дней следующий контроль после пройденного номера `seq_done`."""
    if seq_done < int(settings.car_photo_intro_count):
        return int(settings.car_photo_intro_days)
    return int(settings.car_photo_period_days)


def _open_check(session: Session, user_id: int, mode: str, seq: int, due_at: datetime,
                now: datetime) -> CarPhotoCheck:
    """Завести контроль со сроком. Сам факт «человеку пора» решает вызывающий."""
    manual, reason = _needs_human(session, user_id, mode, seq, now)
    row = CarPhotoCheck(user_id=user_id, mode=mode, seq=seq, status=WAITING, due_at=due_at,
                        manual=manual, manual_reason=reason, winter=winter_now(now))
    session.add(row)
    session.commit()
    session.refresh(row)
    return row


def ensure(session: Session, user_id: int, mode: str,
           now: Optional[datetime] = None) -> Optional[CarPhotoCheck]:
    """Вернуть открытый контроль человека, а если его нет — завести ПЕРВЫЙ.

    Дальше цепочка держится сама: пройденный контроль сразу назначает следующий. Здесь
    закрывается только начало — тот момент, когда человека одобрили или когда мы включили
    фотоконтроль людям, которые работают давно.

    Срок первого контроля:
      • одобрен только что   → `car_photo_intro_days` от одобрения (3 дня);
      • работает давно       → `car_photo_grandfather_days` от сегодня (неделя). Включённый
        флаг не должен свалиться на человека ультиматумом посреди рабочего дня.
    """
    if not enabled(mode):
        return None
    now = now or utcnow()
    открытый = current(session, user_id, mode)
    if открытый is not None:
        return открытый
    if last_closed(session, user_id, mode) is not None:
        return None            # история есть, а открытого нет — цепочку ведёт `_close`
    одобрен = _approved_at(session, user_id, mode)
    if одобрен is None:
        return None
    новичок = (now - одобрен) <= timedelta(days=int(settings.car_photo_intro_days))
    срок = (одобрен + timedelta(days=int(settings.car_photo_intro_days)) if новичок
            else now + timedelta(days=int(settings.car_photo_grandfather_days)))
    return _open_check(session, user_id, mode, 1, срок, now)


def _needs_human(session: Session, user_id: int, mode: str, seq: int,
                 now: datetime) -> "tuple[bool, str]":
    """Смотреть ли этот контроль человеку. Возврат: (да/нет, почему).

    Три повода, и все три — про пользу, а не про недоверие:
      • новичок (первые контроли) — единственный шанс увидеть машину, пока о человеке
        ничего не известно;
      • на человека жаловались за грязную машину — жалоба без проверки ничего не стоит;
      • выборка (каждый N-й) — иначе автомат становится единственным судьёй, а он не видит
        ни грязи, ни вмятины, ни чехлов с рынка.

    Выборка считается ОТ НОМЕРА КОНТРОЛЯ, а не случайным числом: случайность невозможно
    воспроизвести в тесте и невозможно объяснить человеку, который спросит «почему я».
    """
    if seq <= int(settings.car_photo_intro_count):
        return True, "newbie"
    рубеж = now - timedelta(days=int(settings.car_photo_complaint_days))
    жаловались = session.exec(select(Report.id).where(
        Report.target_user_id == user_id, Report.category == "dirty_car",
        Report.created_at >= рубеж)).first()
    if жаловались is not None:
        return True, "complaint"
    каждый = max(int(settings.car_photo_manual_every), 1)
    if (int(user_id) * 7 + int(seq)) % каждый == 0:
        return True, "sample"
    return False, ""


# ------------------------------------------------------- требование по жалобе (24 часа)
def open_complaint(session: Session, report, mode: str,
                   now: Optional[datetime] = None) -> Optional[CarPhotoCheck]:
    """Пассажир написал «в машине грязно» → просим у водителя фото за сутки.

    ЗАЧЕМ ИМЕННО ТАК. Жалоба на грязь — слово против слова: пассажиру могло показаться,
    водитель мог только что кого-то довезти. Разбирать это «по справедливости» без фото
    невозможно, а наказывать по одному сообщению — значит дать любому кнопку «испортить
    соседу неделю». Фото снимает вопрос за минуту и в обе стороны.

    Работа при этом НЕ ограничивается ничем: пока идут сутки, человек возит как обычно.
    Одна жалоба не наказывает никогда — наказывает только молчание или подтверждённая
    фотографией грязь (`_settle_complaint`).

    Возврат: заведённое требование или None (режим выключен, требование уже открыто).
    """
    now = now or utcnow()
    if not enabled(mode) or report is None or report.target_user_id is None:
        return None
    # Одно открытое требование на человека: вторая жалоба за те же сутки не должна
    # превращаться в две фотосессии подряд.
    if current_complaint(session, report.target_user_id) is not None:
        return None
    строка = CarPhotoCheck(
        user_id=report.target_user_id, mode=mode, kind=COMPLAINT, seq=0, status=WAITING,
        due_at=now + timedelta(hours=COMPLAINT_HOURS), report_id=report.id,
        manual=True, manual_reason="complaint", winter=winter_now(now),
    )
    session.add(строка)
    session.commit()
    session.refresh(строка)
    место = "салона" if mode == TAXI else "багажника"
    место_ba = "салондың" if mode == TAXI else "багажниктың"
    _push(session, строка.user_id,
          "Пришли фото салона", "Салон фотоһын ебәр",
          f"Пассажир написал, что в машине было грязно. Пришли фото {место} за сутки — "
          f"и вопрос закрыт, без последствий. Работать можно как обычно.",
          f"Юлаусы машинала бысраҡ булған тип яҙған. Бер тәүлек эсендә {место_ba} фотоһын "
          f"ебәр — һорау ябыла, эҙемтәһеҙ. Эшләргә ғәҙәттәгесә була.")
    return строка


def expire_demands(session: Session, now: Optional[datetime] = None,
                   dry_run: bool = False) -> list:
    """Сутки прошли, фото не пришло → жалоба считается подтверждённой.

    Это единственное место, где молчание что-то стоит. Так и задумано: человек, которому
    нечего скрывать, тратит минуту и закрывает вопрос; человек, который не ответил сутки,
    получает обычную лестницу — ту же, что за любую подтверждённую жалобу.

    Возврат: id людей, которых коснулись.
    """
    now = now or utcnow()
    строки = session.exec(select(CarPhotoCheck).where(
        CarPhotoCheck.kind == COMPLAINT, CarPhotoCheck.status == WAITING,
        CarPhotoCheck.due_at <= now)).all()
    тронули = []
    for строка in строки:
        тронули.append(строка.user_id)
        if dry_run:
            continue
        try:
            _close(session, строка, False, now=now,
                   reason="Фото салона не пришло за сутки")
            _push(session, строка.user_id,
                  "Фото салона не пришло", "Салон фотоһы килмәне",
                  "Сутки прошли, фото мы не получили — жалобу пришлось засчитать. "
                  "Если считаешь это ошибкой, напиши в поддержку: разберём.",
                  "Бер тәүлек үтте, фото килмәне — ялыуҙы иҫәпкә алырға тура килде. "
                  "Хата тип уйлаһаң, ярҙам хеҙмәтенә яҙ: тикшерербеҙ.")
        except Exception as e:  # noqa: BLE001 — один человек не валит обход остальных
            log.warning(f"[carphoto] требование {строка.id}: {type(e).__name__}: {e}")
    return тронули


# --------------------------------------------------------------------------- лестница
def late_days(check: Optional[CarPhotoCheck], now: Optional[datetime] = None) -> int:
    """Сколько ПОЛНЫХ суток прошло после срока. Ноль — ещё не просрочен."""
    if check is None or check.status != WAITING:
        return 0
    now = now or utcnow()
    if now <= check.due_at:
        return 0
    return (now - check.due_at).days


def stage(check: Optional[CarPhotoCheck], now: Optional[datetime] = None) -> str:
    """На какой ступени лестницы человек: ok | remind | slow | blocked.

    Кадры отправлены и лежат у человека на просмотре (`review`) — это `ok`: свою часть
    он сделал в срок, а скорость нашего разбора не должна стоить ему заказов. Тот же
    принцип, что с государственным реестром: наше молчание — наша проблема.
    """
    if check is None or check.status != WAITING:
        return "ok"
    now = now or utcnow()
    if now <= check.due_at:
        return "ok"
    просрочка = (now - check.due_at).days          # полных суток после срока
    if просрочка < int(settings.car_photo_grace_days):
        return "remind"                            # первые трое суток — только напоминание
    if просрочка < int(settings.car_photo_soft_days):
        return "slow"                              # 4–7 сутки — вниз в подборе
    return "blocked"


def blocked(session: Session, user_id: int, mode: str, now: Optional[datetime] = None) -> bool:
    """Пауза до фото. Гейт линии спрашивает ЭТО, а не флаг в базе (урок волны 66:
    правило, живущее во флаге, ломается вместе с фоновой задачей)."""
    if not enabled(mode):
        return False
    return stage(current(session, user_id, mode), now) == "blocked"


def slow(session: Session, user_id: int, mode: str, now: Optional[datetime] = None) -> bool:
    """Средняя ступень: заказы идут, но первыми их видит тот, кто машину показал."""
    if not enabled(mode):
        return False
    return stage(current(session, user_id, mode), now) == "slow"


# --------------------------------------------------------------------------- автопроверка
def dhash(data: bytes) -> str:
    """Отпечаток картинки (dHash 8×9 → 64 бита) — чтобы узнать её, если пришлют второй раз.

    Считается по яркости соседних пикселей, поэтому переживает пережатие и смену размера,
    но НЕ переживает новую съёмку: два разных снимка одной машины дают разные отпечатки.
    Нет Pillow или файл не разобрался — пустая строка, и сравнивать просто не с чем.
    """
    try:
        import io as _io

        from PIL import Image
    except Exception:  # noqa: BLE001 — нет Pillow: живём без сравнения
        return ""
    try:
        with Image.open(_io.BytesIO(data)) as img:
            маленькая = img.convert("L").resize((9, 8))
            точки = маленькая.tobytes()          # 72 байта яркости, строка за строкой
        биты = 0
        for строка in range(8):
            for столбец in range(8):
                левый = точки[строка * 9 + столбец]
                правый = точки[строка * 9 + столбец + 1]
                биты = (биты << 1) | (1 if левый > правый else 0)
        return f"{биты:016x}"
    except Exception:  # noqa: BLE001 — не картинка: отпечатка нет
        return ""


def _distance(a: str, b: str) -> int:
    """На сколько битов различаются два отпечатка. 64 = «сравнивать не с чем»."""
    if not a or not b or len(a) != len(b):
        return 64
    try:
        return bin(int(a, 16) ^ int(b, 16)).count("1")
    except ValueError:
        return 64


#: Ближе этого — считаем, что прислали ту же самую фотографию. Порог намеренно жёсткий:
#: ошибиться здесь значит обвинить честного человека в обмане, а это дороже пропущенного.
SAME_PHOTO_BITS = 3


def known_hashes(session: Session, user_id: int, mode: str, limit: int = 12) -> list:
    """Отпечатки кадров прошлых контролей этого человека в этом режиме."""
    строки = session.exec(select(CarPhotoCheck).where(
        CarPhotoCheck.user_id == user_id, CarPhotoCheck.mode == mode,
        CarPhotoCheck.hashes_json != "").order_by(CarPhotoCheck.id.desc()).limit(limit)).all()
    отпечатки = []
    for строка in строки:
        for отпечаток in _load(строка.hashes_json).values():
            if отпечаток:
                отпечатки.append(отпечаток)
    return отпечатки


def _looks_like_screenshot(probe: dict) -> bool:
    """Похоже ли это на снимок экрана, а не на фотографию машины.

    Уверенно говорим «да» только в двух случаях: телефон сам написал это в файле, или
    кадр вытянут по вертикали сильнее, чем бывает у камеры (экран телефона — примерно
    2:1, камера — 4:3 или 16:9). Всё остальное — сомнение, а сомнение в пользу человека.
    """
    if "screenshot" in (probe.get("software") or "").lower():
        return True
    ш, в = probe.get("width"), probe.get("height")
    if not ш or not в:
        return False
    вытянутость = max(ш, в) / max(min(ш, в), 1)
    return вытянутость >= 1.95 and not (probe.get("make") or "")


def inspect(data: bytes, ext: str, *, now: Optional[datetime] = None,
            known=()) -> dict:
    """Что автомат может сказать про один кадр честно. Возврат: {ok, reason, hash}.

    Три вопроса, на которые есть ответ: кадр не мелкий; это не скриншот; это не та же
    самая фотография, что присылали раньше. Про грязь и вмятины автомат молчит — их
    смотрит человек.

    Даты съёмки в файле может не быть вообще (наше приложение пережимает фото и метаданные
    теряет само) — тогда «не знаем», а не «обманул». Наказывать за отсутствие того, что
    стёрли мы сами, нельзя.
    """
    from .imagemeta import photo_probe
    отпечаток = dhash(data)
    сведения = photo_probe(data, ext)
    ш, в = сведения.get("width"), сведения.get("height")
    if ш and в and min(ш, в) < int(settings.car_photo_min_side_px):
        return {"ok": False, "reason": "too_small", "hash": отпечаток}
    if _looks_like_screenshot(сведения):
        return {"ok": False, "reason": "screenshot", "hash": отпечаток}
    снято = сведения.get("shot_at")
    if isinstance(снято, datetime):
        сейчас = (now or utcnow()).replace(tzinfo=None)
        if снято < сейчас - timedelta(days=int(settings.car_photo_fresh_days)):
            return {"ok": False, "reason": "stale", "hash": отпечаток}
    if отпечаток:
        for прежний in known:
            if _distance(отпечаток, прежний) <= SAME_PHOTO_BITS:
                return {"ok": False, "reason": "duplicate", "hash": отпечаток}
    return {"ok": True, "reason": "", "hash": отпечаток}


# --------------------------------------------------------------------------- ход контроля
def _load(raw: str) -> dict:
    try:
        значение = json.loads(raw or "{}")
        return значение if isinstance(значение, dict) else {}
    except ValueError:
        return {}


def photos(check: CarPhotoCheck) -> dict:
    """Кадры контроля: {слот: ссылка}. Пустой контроль — пустой словарь."""
    return _load(check.photos_json)


def hashes(check: CarPhotoCheck) -> dict:
    """Отпечатки кадров этого контроля: {слот: отпечаток}."""
    return _load(check.hashes_json)


def attach(session: Session, check: CarPhotoCheck, slot: str, url: str, verdict: str,
           photo_hash: str) -> CarPhotoCheck:
    """Положить кадр в контроль. Пересъёмка того же кадра просто заменяет прежний."""
    кадры, оценки, отпечатки = (_load(check.photos_json), _load(check.checks_json),
                                _load(check.hashes_json))
    кадры[slot], оценки[slot] = url, verdict or "ok"
    отпечатки[slot] = photo_hash
    check.photos_json = json.dumps(кадры, ensure_ascii=False)
    check.checks_json = json.dumps(оценки, ensure_ascii=False)
    check.hashes_json = json.dumps(отпечатки, ensure_ascii=False)
    session.add(check)
    session.commit()
    session.refresh(check)
    return check


def missing_slots(check: CarPhotoCheck) -> list:
    """Каких кадров ещё нет (или они не приняты автоматом)."""
    кадры, оценки = _load(check.photos_json), _load(check.checks_json)
    нет = []
    for слот in check_slots(check):
        код = слот["code"]
        if not кадры.get(код) or оценки.get(код, "ok") != "ok":
            нет.append(код)
    return нет


def _close(session: Session, check: CarPhotoCheck, ok: bool, *, now: datetime,
           reason: str = "", admin_id: Optional[int] = None) -> CarPhotoCheck:
    """Закрыть контроль и сразу назначить следующий — цепочка не должна прерываться.

    Принят  → следующий по расписанию (3 дня для первых двух, дальше 14).
    Не принят → тот же номер заново, короткий срок на пересъёмку. Номер НЕ растёт: это
    один и тот же контроль, просто со второй попытки.

    У требования по жалобе цепочки нет: оно разовое, и вместо следующего контроля закрывает
    саму жалобу (`_settle_complaint`).
    """
    check.status = PASSED if ok else FAILED
    check.reviewed_at = now
    check.reviewed_by = admin_id
    check.reject_reason = "" if ok else (reason or "")[:200]
    session.add(check)
    session.commit()
    if getattr(check, "kind", PERIODIC) == COMPLAINT:
        _settle_complaint(session, check, ok, now=now)
        session.refresh(check)
        return check
    следующий_номер = check.seq + 1 if ok else check.seq
    дней = _period_after(check.seq) if ok else int(settings.car_photo_intro_days)
    _open_check(session, check.user_id, check.mode, следующий_номер,
                now + timedelta(days=дней), now)
    session.refresh(check)
    return check


def _settle_complaint(session: Session, check: CarPhotoCheck, clean: bool, *,
                      now: datetime) -> None:
    """Чем кончилось требование по жалобе «грязная машина».

    Салон чистый → жалоба ОТКЛОНЕНА: никакого наказания, ни следа в лестнице. Одна жалоба
    не наказывает никогда — наказывает только молчание или подтверждённая фотографией грязь.

    Салон грязный (или фото так и не пришло) → жалоба ПОДТВЕРЖДЕНА и дальше работает
    обычная лестница качества: три подтверждённые за месяц ставят такси на паузу. Отдельного
    наказания за грязь мы не придумываем — у нас уже есть общая, и она известна людям.
    """
    from . import quality
    from .models import Report
    жалоба = session.get(Report, check.report_id) if check.report_id else None
    if жалоба is None or жалоба.status in ("resolved", "rejected"):
        return
    жалоба.status = "rejected" if clean else "resolved"
    жалоба.resolved_at = now
    жалоба.resolution = ("Фото салона: чисто" if clean
                         else (check.reject_reason or "Фото салона: подтверждена грязь"))[:1000]
    session.add(жалоба)
    session.commit()
    try:
        quality.tell_report_decision(session, жалоба, confirmed=not clean)
        if not clean and жалоба.target_user_id is not None:
            quality.apply_ladder_after_resolve(session, жалоба.target_user_id, now)
    except Exception as e:  # noqa: BLE001 — письмо или лестница не должны отменять решение
        log.warning(f"[carphoto] разбор жалобы {жалоба.id}: {type(e).__name__}: {e}")


def submit(session: Session, check: CarPhotoCheck, *, now: Optional[datetime] = None) -> dict:
    """Человек отправил набор кадров. Возврат: {"ok", "status", "missing"}.

    Все кадры на месте и у автомата нет вопросов → либо сразу принято, либо уходит на
    просмотр человеку (новичок, жалоба, выборка). В обоих случаях человек своё сделал:
    пока контроль на просмотре, никаких ограничений на него не накладывается.
    """
    now = now or utcnow()
    нет = missing_slots(check)
    if нет:
        return {"ok": False, "status": check.status, "missing": нет}
    check.submitted_at = now
    if check.manual:
        check.status = REVIEW
        session.add(check)
        session.commit()
        session.refresh(check)
        return {"ok": True, "status": REVIEW, "missing": []}
    _close(session, check, True, now=now)
    return {"ok": True, "status": PASSED, "missing": []}


def decide(session: Session, check: CarPhotoCheck, ok: bool, *, reason: str = "",
           admin_id: Optional[int] = None, now: Optional[datetime] = None) -> CarPhotoCheck:
    """Решение человека по контролю, лежавшему на просмотре."""
    now = now or utcnow()
    жалоба = getattr(check, "kind", PERIODIC) == COMPLAINT
    строка = _close(session, check, ok, now=now, reason=reason, admin_id=admin_id)
    if жалоба:
        # Про снятое обвинение человеку говорит сам разбор жалобы (`_settle_complaint`
        # → `quality.tell_report_decision`). Второе письмо подряд про то же — это шум.
        if not ok:
            хвост = f" {reason}" if reason else ""
            хвост_ba = f" {reason}" if reason else ""
            _push(session, check.user_id,
                  "Жалоба подтвердилась", "Ялыу раҫланды",
                  f"На фото салон действительно грязный.{хвост} Приведи в порядок — "
                  f"и дальше работаем как обычно.",
                  f"Фотола салон ысынлап бысраҡ.{хвост_ba} Тәртипкә килтер — "
                  f"артабан ғәҙәттәгесә эшләйбеҙ.")
        return строка
    if ok:
        _push(session, check.user_id,
              "Машина принята", "Машина ҡабул ителде",
              "Спасибо, что показал. Следующий раз напомним заранее.",
              "Күрһәткәнең өсөн рәхмәт. Киләһе тапҡыр алдан иҫкә төшөрөрбөҙ.")
    else:
        хвост = f" Что поправить: {reason}" if reason else ""
        хвост_ba = f" Нимәне төҙәтергә: {reason}" if reason else ""
        _push(session, check.user_id,
              "Нужно переснять машину", "Машинаны яңынан төшөрөргә кәрәк",
              f"Кадры не подошли.{хвост} Пришли новые — и всё вернётся сразу.",
              f"Кадрҙар тура килмәне.{хвост_ba} Яңыларын ебәр — бөтәһе лә шунда уҡ ҡайта.")
    return строка


# --------------------------------------------------------------------------- для экрана
def payload(session: Session, user_id: int, mode: str,
            now: Optional[datetime] = None) -> dict:
    """Всё, что нужно экрану фотоконтроля: что снять, до какого числа и что сейчас с допуском."""
    now = now or utcnow()
    if not enabled(mode):
        return {"mode": mode, "enabled": False, "required": False}
    проверка = ensure(session, user_id, mode, now)
    кадры = _load(проверка.photos_json) if проверка else {}
    оценки = _load(проверка.checks_json) if проверка else {}
    список = [dict(слот, url=кадры.get(слот["code"]) or None,
                   verdict=оценки.get(слот["code"]) or "") for слот in slots(mode)]
    прошлый = last_closed(session, user_id, mode)
    ответ = {
        "mode": mode,
        "enabled": True,
        "required": проверка is not None,
        "slots": список,
        "demand": demand_payload(session, user_id, mode, now),
        "clean_rules": [{"ru": ru, "ba": ba} for ru, ba in CLEAN_RULES],
        "keep_days": int(settings.car_photo_keep_days),
        "last_passed_at": (прошлый.reviewed_at.isoformat()
                           if прошлый is not None and прошлый.status == PASSED
                           and прошлый.reviewed_at else None),
    }
    if проверка is None:
        return ответ
    ответ.update({
        "id": проверка.id,
        "seq": проверка.seq,
        "status": проверка.status,
        "due_at": проверка.due_at.isoformat(),
        "days_left": (проверка.due_at - now).days,
        "stage": stage(проверка, now),
        "late_days": late_days(проверка, now),
        "winter": проверка.winter,
        # Первый контроль: смотрим ещё и опознавательные знаки такси — фонарь и «шашечки».
        # Отдельного кадра не просим, они и так видны на снимках кузова.
        "check_signs": bool(проверка.seq == 1 and mode == TAXI),
        "manual": проверка.manual,
        "reject_reason": проверка.reject_reason,
        "missing": missing_slots(проверка),
    })
    return ответ


def demand_payload(session: Session, user_id: int, mode: str,
                   now: Optional[datetime] = None) -> Optional[dict]:
    """Открытое требование по жалобе для экрана. None — требования нет.

    Часы показываем целыми и вниз: «осталось 3 часа» при 3 часах 50 минутах честнее, чем
    «4 часа», — человек не должен опоздать из-за нашего округления.
    """
    now = now or utcnow()
    строка = current_complaint(session, user_id, mode)
    if строка is None:
        return None
    кадры, оценки = _load(строка.photos_json), _load(строка.checks_json)
    осталось = (строка.due_at - now).total_seconds() / 3600.0
    return {
        "id": строка.id,
        "status": строка.status,
        "due_at": строка.due_at.isoformat(),
        "hours_left": max(int(осталось), 0),
        "overdue": осталось <= 0,
        "slots": [dict(слот, url=кадры.get(слот["code"]) or None,
                       verdict=оценки.get(слот["code"]) or "")
                  for слот in slots(mode, COMPLAINT)],
        "missing": missing_slots(строка),
    }


# --------------------------------------------------------------------------- ночной обход
def _push(session: Session, user_id: int, title_ru: str, title_ba: str,
          body_ru: str, body_ba: str) -> None:
    """Уведомление. Вторично: падение пуша не должно валить обход."""
    try:
        from .services import push_notification
        push_notification(session, user_id, "docs", title_ru, title_ba, body_ru, body_ba)
    except Exception as e:  # noqa: BLE001
        log.warning(f"[carphoto] push failed user={user_id}: {e}")


def _mode_users(session: Session, mode: str) -> list:
    """Кому в этом режиме положен контроль: одобренные водители или курьеры."""
    if mode == TAXI:
        return list(session.exec(select(TaxiApplication.user_id).where(
            TaxiApplication.status == TaxiApplicationStatus.approved)).all())
    return list(session.exec(select(CourierApplication.user_id).where(
        CourierApplication.status == "approved")).all())


def _reminded_today(check: CarPhotoCheck, now: datetime) -> bool:
    return bool(check.reminded_at and (now - check.reminded_at) < timedelta(hours=20))


def remind(session: Session, check: CarPhotoCheck, now: Optional[datetime] = None) -> bool:
    """Напомнить про фото, если пора. Не чаще раза в сутки. Возврат: слали ли письмо.

    Текст зависит от ступени: сперва спокойная просьба, потом «заказы уходят другим», и
    только в конце — про паузу. Человек должен слышать приближение последствия, а не
    узнавать о нём в момент, когда уже не берёт заказы.
    """
    now = now or utcnow()
    if check.status != WAITING or _reminded_today(check, now):
        return False
    ступень = stage(check, now)
    осталось = (check.due_at - now).days
    if ступень == "ok" and осталось > 1:
        return False                       # рано: не дёргаем человека две недели подряд
    сколько = len(check_slots(check))
    if ступень == "ok":
        заголовок = ("Покажи машину", "Машинаны күрһәт")
        текст = (f"Завтра истекает срок фотоконтроля. {сколько} кадра с телефона — "
                 f"это пара минут.",
                 f"Иртәгә фотоконтроль ваҡыты бөтә. Телефондан {сколько} кадр — "
                 f"ике минутлыҡ эш.")
    elif ступень == "remind":
        заголовок = ("Фотоконтроль просрочен", "Фотоконтроль ваҡыты үткән")
        текст = ("Пока это ни на что не влияет — но через несколько дней заказы начнут "
                 "уходить другим. Пришли фото машины.",
                 "Хәҙергә был бер нәмәгә лә тәьҫир итмәй — әммә бер нисә көндән заказдар "
                 "башҡаларға китә башлай. Машина фотоһын ебәр.")
    elif ступень == "slow":
        заголовок = ("Заказы уходят другим", "Заказдар башҡаларға китә")
        текст = ("Фотоконтроль просрочен, и в подборе ты теперь ниже. Пришли фото — "
                 "вернёшься сразу.",
                 "Фотоконтроль ваҡыты үткән, һайлауҙа хәҙер түбәнерәк. Фотоны ебәр — "
                 "шунда уҡ ҡайтаһың.")
    else:
        заголовок = ("Нужно фото машины", "Машина фотоһы кәрәк")
        текст = (MSG_BLOCKED[check.mode]["ru"], MSG_BLOCKED[check.mode]["ba"])
    check.reminded_at = now
    session.add(check)
    session.commit()
    _push(session, check.user_id, заголовок[0], заголовок[1], текст[0], текст[1])
    return True


def run_once(session: Session, dry_run: bool = False) -> dict:
    """Один ночной обход: завести первые контроли и напомнить тем, чей срок подходит.

    Блокировку здесь НЕ проставляем: она считается по сроку в момент запроса (`stage`).
    Так гейт не зависит от того, отработал ли ночью таймер, — тот же урок, что с
    документами (волна 66).
    """
    итог = {}
    now = utcnow()
    for mode in (TAXI, COURIER):
        заведено, напомнили = [], []
        if not enabled(mode):
            итог[mode] = {"created": заведено, "reminded": напомнили}
            continue
        for user_id in _mode_users(session, mode):
            try:
                есть = current(session, user_id, mode)
                if dry_run:
                    if есть is None and last_closed(session, user_id, mode) is None:
                        заведено.append(user_id)
                    continue
                проверка = ensure(session, user_id, mode, now)
                if проверка is None:
                    continue
                if есть is None:
                    заведено.append(user_id)
                if remind(session, проверка, now):
                    напомнили.append(user_id)
            except Exception as e:  # noqa: BLE001 — один человек не валит обход остальных
                log.warning(f"[carphoto] {mode} user={user_id}: {type(e).__name__}: {e}")
        итог[mode] = {"created": заведено, "reminded": напомнили}
    # Требования по жалобам — отдельно от режимов: они разовые и живут сутками, а не днями.
    итог["demands"] = expire_demands(session, now, dry_run)
    return итог
