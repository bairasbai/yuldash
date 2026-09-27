"""Анти-фрод (батч B8) — прагматичный v1 без ML: устройство, вход, GPS, чат.

Принцип (утверждён): автоматика только ПОМЕЧАЕТ (флаги, сигналы, счётчики админу),
жёстко банит ЧЕЛОВЕК. Честного пользователя автоматика не наказывает.
Приватность: device_id, координаты и телефоны в логи открытым текстом НЕ пишем.
"""
import re
from datetime import timedelta, timezone
from typing import Optional

from fastapi import HTTPException

from .errors import herr
from sqlmodel import Session, select

from .logs import log
from .models import DeviceBan, User
from .timeutil import utcnow

# ------------------------------ устройство (B8-1) ------------------------------
MAX_DEVICE_ID_LEN = 64

# Текст 403 забаненному устройству: коротко, с путём в поддержку.
#
# ДВА ОТДЕЛЬНЫХ текста, а не один через точку. Человек читает это в минуту, когда не может
# войти вообще, — и башкироязычный не должен разбирать сначала русскую половину
# (docs/lessons.md, волна 20: любая надпись живёт на двух языках раздельно).
DEVICE_BANNED_RU = "Аккаунт заблокирован — напиши в поддержку."
DEVICE_BANNED_BA = "Аккаунт бикләнгән — ярҙам хеҙмәтенә яҙ."


def normalize_device_id(raw: Optional[str]) -> str:
    """X-Device-Id из заголовка: обрезаем мусор/длину. Пусто → '' (старый клиент без заголовка)."""
    return (raw or "").strip()[:MAX_DEVICE_ID_LEN]


def device_banned(session: Session, device_id: Optional[str]) -> bool:
    did = normalize_device_id(device_id)
    if not did:
        return False   # старый клиент без заголовка — не наказываем (не по кому проверять)
    return session.exec(select(DeviceBan).where(DeviceBan.device_id == did)).first() is not None


def guard_device_not_banned(session: Session, device_id: Optional[str]) -> None:
    """Гейт регистрации/логина: забаненное устройство → 403 (обход бана новым номером)."""
    if device_banned(session, device_id):
        raise herr(403, DEVICE_BANNED_RU, DEVICE_BANNED_BA)


def ban_device(session: Session, device_id: str, reason: str = "",
               user_id: Optional[int] = None) -> DeviceBan:
    """Забанить устройство (только админ, идемпотентно — повторный бан возвращает существующий)."""
    did = normalize_device_id(device_id)
    existing = session.exec(select(DeviceBan).where(DeviceBan.device_id == did)).first()
    if existing:
        return existing
    ban = DeviceBan(device_id=did, reason=(reason or "")[:300], user_id=user_id)
    session.add(ban)
    session.commit()
    session.refresh(ban)
    return ban


def unban_device(session: Session, device_id: str) -> bool:
    """Снять бан устройства. True — бан был и снят, False — бана не было."""
    did = normalize_device_id(device_id)
    rows = session.exec(select(DeviceBan).where(DeviceBan.device_id == did)).all()
    for r in rows:
        session.delete(r)
    session.commit()
    return bool(rows)


# ------------------------------ номер сменил владельца (волна 139) ------------------------------
# Через сколько дней молчания считаем, что номер мог перейти к другому человеку.
#
# Операторы в России забирают неиспользуемый номер и отдают его в продажу — обычно после
# полугода-года без активности. Берём 180 дней: раньше отвязывать нельзя (человек мог просто
# уехать на сезон), позже — уже поздно, номер успеет попасть к другому.
PHONE_RECYCLE_DAYS = 180


def phone_looks_recycled(user: User, device_id: Optional[str], now=None) -> bool:
    """Похоже ли, что номер перешёл к ДРУГОМУ человеку, а не хозяин сменил телефон.

    Вход у нас по номеру, а номер человеку не принадлежит. Проверено пробой (волна 139): новый
    владелец номера ставит приложение, входит по SMS и получает чужой аккаунт целиком — имя,
    историю поездок, переписку с водителями, доверенные контакты. Его сигнал SOS ушёл бы маме
    прежней хозяйки номера, а её близкие получили бы тревогу за постороннего.

    Признака ровно два, и нужны ОБА:
      • аккаунт молчал дольше `PHONE_RECYCLE_DAYS` — за это время оператор успевает передать номер;
      • вход идёт с ДРУГОГО устройства — тот же телефон означает того же человека.

    Почему не по одному признаку. Долгое молчание само по себе — это сезонный пассажир: ездил
    прошлым летом, вернулся этим. Новое устройство само по себе — обычная смена телефона,
    их меняют каждые два-три года. Опасно только сочетание.

    Устройство не назвали (старый клиент без заголовка) — не отвязываем: наказывать за
    неизвестность нельзя, слишком высока цена ошибки.
    """
    did = normalize_device_id(device_id)
    if not did or not user.last_device_id or did == user.last_device_id:
        return False
    молчал_с = user.last_seen_at or user.created_at
    if молчал_с is None:
        return False
    return (now or utcnow()) - молчал_с >= timedelta(days=PHONE_RECYCLE_DAYS)


def release_phone(session: Session, user: User, now=None) -> None:
    """Отвязать номер от аккаунта: он перешёл к другому человеку.

    Данные НЕ удаляем. Если это всё-таки был прежний владелец (уехал на год, сменил телефон),
    его поездки, отзывы и деньги целы — доступ вернёт поддержка. Удалить было бы проще и
    гораздо хуже: цена ошибки автоматики стала бы невосполнимой.

    Номер в поле заменяем на служебную пометку, а не стираем: колонка уникальная, а по пометке
    поддержка найдёт аккаунт, когда человек напишет «я не могу войти».
    """
    now = now or utcnow()
    user.phone = f"released:{user.phone}:{user.id}"[:64]
    user.phone_released_at = now
    user.last_device_id = None      # чужое устройство на этом аккаунте не оставляем
    session.add(user)
    session.commit()
    # Телефон в лог не пишем (правило модуля) — только факт и номер аккаунта.
    log.info("[ANTIFRAUD] номер отвязан от аккаунта user_id=%s: молчал > %s дней, вход с другого "
             "устройства", user.id, PHONE_RECYCLE_DAYS)
    # Прежнему владельцу — объяснение в Центре уведомлений. Если это всё-таки был он (уехал
    # на год, сменил телефон), он откроет приложение на старом устройстве и поймёт, почему
    # больше не входит по номеру, — вместо молчаливого «ничего не работает».
    #
    # Только запись, без SMS и пуша: номер уже принадлежит другому человеку, и сообщение
    # пришло бы постороннему — вместе с намёком, чей это был аккаунт.
    from .services import push_notification
    push_notification(
        session, user.id, "safety",
        "Номер откреплён от аккаунта", "Номер иҫәптән айырылды",
        "По этому номеру давно не заходили, и с него вошли с другого телефона. Твои поездки "
        "и отзывы целы — напиши в поддержку, вернём доступ.",
        "Был номер менән күптән инмәгәндәр, ә хәҙер башҡа телефондан ингәндәр. Сәфәрҙәрең "
        "һәм баһаларың һаҡлана — ярҙам хеҙмәтенә яҙ, инеү хоҡуғын ҡайтарабыҙ.",
        push=False,
    )


# ------------------------------ вход: фиксация устройства + сигнал (B8-1/B8-2) ------------------------------
def remember_login_device(session: Session, user: User, device_id: Optional[str]) -> None:
    """После успешного входа: фиксируем устройство на юзере. Вход с НОВОГО устройства
    (device_id ≠ последнего) → push + SMS «это не ты — смени номер / напиши в поддержку».
    Не блокируем — только сигнал (честный пользователь мог сменить телефон)."""
    did = normalize_device_id(device_id)
    if not did:
        return                          # старый клиент без заголовка — фиксировать нечего
    if user.last_device_id == did:
        return                          # то же устройство — тишина
    is_new_device = bool(user.last_device_id)   # первый вход (None/пусто) сигналом не считаем
    user.last_device_id = did
    session.add(user)
    session.commit()
    if not is_new_device:
        return
    # Сигнал (пункт 2): запись в Центре уведомлений + SMS. Локальный импорт — тесты патчат
    # app.services.
    #
    # Почему не голым пушем (аудит 2026-08-08, волна 21). Это единственное, из чего человек
    # узнаёт, что в его аккаунт вошли с чужого телефона — а там его поездки, номер и адреса.
    # Пуш ночью или на выключенном телефоне пропадает навсегда, и тревога вместе с ним. SMS
    # спасает не всех: у входа через Telegram телефона в базе нет (`tg…`), и канал остаётся
    # ровно один. Заодно языки разъехались: раньше оба ехали склеенными через « · ».
    from .services import push_notification, send_text
    ru = ("Вход в Юлдаш с нового устройства. Это не ты — смени номер и напиши в поддержку.")
    ba = ("Юлдашҡа яңы ҡоролмандан инеү. Был һин түгел икән — номерҙы алмаштыр һәм "
          "ярҙам хеҙмәтенә яҙ.")
    push_notification(session, user.id, "safety",
                      "Вход с нового устройства", "Яңы ҡоролмандан инеү", ru, ba)
    if user.phone and not user.phone.startswith("tg"):
        send_text(user.phone, f"Юлдаш: {ru} · {ba}")


# ------------------------------ GPS: анти-телепорт (B8-3) ------------------------------
# Скорость между последовательными точками выше физически разумной → точка фейковая
# (спуфинг/телепорт): игнорируем её и копим счётчик подозрительности. Честных не роняем:
# первая точка после паузы проходит сама (время выросло → скорость упала).
TELEPORT_MAX_KMH = 200.0
TELEPORT_FLAG_COUNT = 3          # 3+ телепорта за час → флаг в админ-пульс/лог
_TP_ANCHOR_TTL = 3600            # якорь «последняя честная точка» живёт час
_TP_COUNTER_TTL = 3600           # окно счётчика подозрительности — час
_TP_FLAG_TTL = 172800            # суточный набор подозрительных живёт 2 суток


def _tp_day_key(now=None) -> str:
    return f"af:tpflag:{(now or utcnow()).strftime('%Y%m%d')}"


def teleport_filter(r, user_id: int, lat: float, lng: float, now_ts: Optional[float] = None) -> bool:
    """True — точка честная (публикуем), False — телепорт (игнорируем, точку НЕ публикуем).

    Якорь — последняя ПРИНЯТАЯ точка: отвергнутая якорь не двигает (иначе два телепорта
    подряд «легализуются»). Без Redis — пропускаем всё (фильтр не роняет функциональность)."""
    if r is None:
        return True
    from .services import haversine_km   # локальный импорт: без циклов на старте
    # utcnow() — наивный UTC; timestamp() без tzinfo трактует его как местное
    # время процесса и ломает интервалы между разными workers и на смене DST.
    now_ts = now_ts if now_ts is not None else utcnow().replace(tzinfo=timezone.utc).timestamp()
    # Старый якорь мог содержать неизвестный сдвиг TZ. Не смешиваем поколения
    # при rolling restart: прежние ключи сами исчезнут по часовому TTL.
    key = f"af:pt:utc:v1:{user_id}"
    try:
        prev = r.get(key)
    except Exception:  # noqa: BLE001 — сбой Redis не роняет приём координат
        return True
    ok = True
    if prev:
        try:
            p_lat, p_lng, p_ts = (prev.decode() if isinstance(prev, bytes) else prev).split(",")
            dist_km = haversine_km(float(p_lat), float(p_lng), lat, lng)
            elapsed_h = max(now_ts - float(p_ts), 1.0) / 3600.0   # пол 1с — защита от деления на ~0
            ok = (dist_km / elapsed_h) <= TELEPORT_MAX_KMH
        except (ValueError, TypeError):
            ok = True   # битый якорь — не наказываем
    try:
        if ok:
            r.set(key, f"{lat},{lng},{now_ts}", ex=_TP_ANCHOR_TTL)
        else:
            _count_teleport(r, user_id)
    except Exception:  # noqa: BLE001
        return True
    return ok


def _count_teleport(r, user_id: int) -> None:
    """Счётчик телепортов за час; на TELEPORT_FLAG_COUNT — флаг в суточный набор + лог
    (БЕЗ координат — только id и факт, приватность)."""
    ckey = f"af:tpc:{user_id}"
    n = r.incr(ckey)
    r.expire(ckey, _TP_COUNTER_TTL)
    if int(n) == TELEPORT_FLAG_COUNT:
        dkey = _tp_day_key()
        r.sadd(dkey, str(user_id))
        r.expire(dkey, _TP_FLAG_TTL)
        log.warning(f"[ANTIFRAUD] gps-suspect user={user_id}: {TELEPORT_FLAG_COUNT}+ телепортов за час "
                    f"(точки игнорируются, решает админ)")


def gps_suspects_today(r) -> int:
    """Сколько пользователей сегодня помечено GPS-подозрительными (для админ-пульса)."""
    if r is None:
        return 0
    try:
        return int(r.scard(_tp_day_key()))
    except Exception:  # noqa: BLE001
        return 0


# ------------------------------ чат: анти-фишинг (B8-6) ------------------------------
# Сообщение НЕ блокируем (свобода честного разговора) — только помечаем flag="warn",
# клиент показывает получателю плашку «Никому не сообщай коды из SMS…».
# Паттерны узкие, чтобы не флажить честные сообщения (код посадки, «буду через 5 минут»):
#   1) просьба кода ИЗ SMS / кода подтверждения / кода для входа;
#   2) номер банковской карты (16 цифр, с пробелами/дефисами или слитно);
#   3) «переведи на другой номер / другую карту» (увод оплаты не тому человеку).
MESSAGE_FLAG_WARN = "warn"

_PHISHING_RES = (
    re.compile(r"код\w*[^.!?\n]{0,40}\b(?:смс|sms)\b", re.IGNORECASE),
    re.compile(r"\b(?:смс|sms)\b[^.!?\n]{0,40}код", re.IGNORECASE),
    re.compile(r"код\w*\s+(?:подтвержден\w*|для\s+входа|из\s+приложени\w*)", re.IGNORECASE),
    re.compile(r"\b\d{4}[ \-]?\d{4}[ \-]?\d{4}[ \-]?\d{4}\b"),
    re.compile(r"перевед\w*[^.!?\n]{0,30}на\s+друг(?:ой|ую)\s+(?:номер|карт\w*)", re.IGNORECASE),
)


def phishing_flag(text: Optional[str]) -> str:
    """'' — обычное сообщение, 'warn' — похоже на развод (см. паттерны выше)."""
    t = (text or "").strip()
    if not t:
        return ""
    return MESSAGE_FLAG_WARN if any(rx.search(t) for rx in _PHISHING_RES) else ""


# ------------------------------ модерация текста: контакты + мат ------------------------------
# Достройка к анти-фишингу выше. Принцип модуля не меняется: ПОМЕЧАЕМ, не блокируем и не баним.
# Текст всегда сохраняется и доставляется — ложное срабатывание на честном человеке
# («буду через 10 минут, подъезд 89») хуже пропущенного нарушителя.
#
# Три вида метки, приоритет сверху вниз (поле flag одно, а причина может совпасть):
#   warn    — фишинг: у человека уводят деньги прямо сейчас. Самое опасное.
#   contact — увод сделки мимо приложения: телефон/мессенджер там, где есть комиссия.
#   abuse   — грубость: бьёт по доверию, но не по деньгам.
MESSAGE_FLAG_CONTACT = "contact"
MESSAGE_FLAG_ABUSE = "abuse"

# --- телефоны и мессенджеры -----------------------------------------------------------------
# Регулярки НАРОЧНО узкие. Разделителями между цифрами считаем только пробел, дефис, точку и
# скобки — буква цепочку рвёт. Поэтому «цена 800 руб, 3 места, дом 5» не собирается в номер,
# а «8 917 123 45 67» собирается. Слева и справа требуем НЕ цифру: иначе 16-значный номер карты
# (его ловит анти-фишинг выше) отдал бы 11-значный кусок и получил бы вторую метку.
_SEP = r"[ \-–—.()]{0,3}"

_CONTACT_RES = (
    # +7 / 7 / 8 и ещё 10 цифр — полный российский номер в любом написании
    re.compile(rf"(?<!\d)(?:\+?7|8){_SEP}(?:\d{_SEP}){{9}}\d(?!\d)"),
    # 10 цифр с 9 — как часто пишут без кода страны: «917 123 45 67»
    re.compile(rf"(?<!\d)9(?:{_SEP}\d){{9}}(?!\d)"),
    # @ник телеграма. Слева не буква и не точка — иначе поймали бы хвост почты.
    re.compile(r"(?<![\w.])@[A-Za-z][A-Za-z0-9_]{4,31}\b"),
    # мессенджер САМ ПО СЕБЕ не улика: приложение и логинит через Telegram. Улика — намерение
    # увести разговор («напиши в вотсап», «скинь в тг»), поэтому требуем глагол/предлог рядом.
    re.compile(
        r"\b(?:напиш\w*|пиш\w*|скин\w*|кин\w*|добав\w*|найд\w*|мой|моя|давай)\s+"
        r"(?:мне\s+|тебе\s+)?(?:в\s+|на\s+)?"
        r"(?:вот\s?сап\w*|ват\s?сап\w*|вацап\w*|whats\s?app|вайбер\w*|viber|телеграм\w*|telegram|тг)\b",
        re.IGNORECASE,
    ),
)


def contact_flag(text: Optional[str]) -> str:
    """'' — обычный текст, 'contact' — в тексте телефон или увод в мессенджер."""
    t = (text or "").strip()
    if not t:
        return ""
    return MESSAGE_FLAG_CONTACT if any(rx.search(t) for rx in _CONTACT_RES) else ""


# --- грубость -------------------------------------------------------------------------------
# Обходы бывают двух видов: подмена похожих букв («хуй» латиницей/цифрами) и растягивание
# («х у й», «х.у.й»). Поэтому текст сначала приводим к одному виду, потом ищем корни, и ищем
# ДВАЖДЫ: по нормализованному тексту и по нему же без разделителей.
#
# Корни держим в одном месте — добавлять сюда, а не размазывать по роутерам.
# Башкирских корней здесь НЕТ намеренно: список составляет Александр (носитель). Модель их
# писать не должна — ошибётся и либо пропустит, либо забанит нормальное слово. См. docs/tasks.md.
_ABUSE_LOOKALIKE = str.maketrans({
    "a": "а", "e": "е", "o": "о", "p": "р", "c": "с", "x": "х", "y": "у", "k": "к",
    "m": "м", "t": "т", "b": "в", "h": "н", "u": "и", "n": "п", "i": "и", "g": "г",
    "0": "о", "3": "з", "4": "ч", "6": "б", "9": "д", "@": "а", "$": "с", "ё": "е",
})
_ABUSE_STRIP_RE = re.compile(r"[^а-я]")

# Корни с защитой от честных слов: «себя», «требовать», «употребить», «хлебать», «расхлебать»,
# «блик», «страховка». Именно они первыми ломают наивный фильтр по подстроке.
_ABUSE_RES = (
    re.compile(r"(?<![а-я])(?:о|у|за|на|по|до|при|разъ?|съ|подъ?)?ху[йеяю](?![а-я]*(?:тор|дожн|дож))"),
    re.compile(r"(?<![а-я])(?:о|у|за|на|по|до|при|раз|вы|под)?пизд"),
    re.compile(r"(?<![стрхл])(?<!употр)(?<!потр)е[бп](?:ат|ан|ну|ла|ло|ли|уч|ак|ал)"),
    re.compile(r"(?<![а-я])(?:за|на|по|у|при|от|до)?бл[яa]д"),
    re.compile(r"(?<![а-я])бля(?![а-я])"),
    re.compile(r"(?<![а-я])(?:за|на|по|у|при)?муд[аои](?:к|л|ч)"),
    re.compile(r"(?<![а-я])гандон|(?<![а-я])гнид[аыуе](?![а-я]*ник)"),
    re.compile(r"(?<![а-я])(?:до|за|на|при|об)?сос[иу](?:сь|тесь)?(?![а-я])"),
    re.compile(r"(?<![а-я])сук[аиуе](?![а-я])"),
    re.compile(r"(?<![а-я])(?:чмо|дебил|урод|тварь|ублюдок|скотин)"),
)


def _abuse_normalize(text: str) -> str:
    return text.lower().translate(_ABUSE_LOOKALIKE)


def abuse_flag(text: Optional[str]) -> str:
    """'' — обычный текст, 'abuse' — грубость (в т.ч. написанная в обход)."""
    t = (text or "").strip()
    if not t:
        return ""
    plain = _abuse_normalize(t)
    if any(rx.search(plain) for rx in _ABUSE_RES):
        return MESSAGE_FLAG_ABUSE
    # «х у й» / «х.у.й»: склеиваем и проверяем ещё раз. Склейка рвёт границы слов, поэтому
    # корни выше и написаны так, чтобы не срабатывать на стыках («себя», «требовать»).
    glued = _ABUSE_STRIP_RE.sub("", plain)
    if glued != plain and any(rx.search(glued) for rx in _ABUSE_RES):
        return MESSAGE_FLAG_ABUSE
    return ""


def moderate_text(text: Optional[str], *, check_contact: bool = True) -> str:
    """Одна проверка на все поля: '' / 'warn' / 'contact' / 'abuse'.

    Обычные регулярки, синхронно, до сохранения — доли миллисекунды, WS не притормаживает.
    `check_contact=False` — для попуток: соседи, едущие в один город, меняются номерами,
    это норма и суть «между своими». Комиссии там нет, мешать нельзя. В Такси и Курьере
    телефон помечаем: там увод сделки = прямая потеря денег.
    """
    t = (text or "").strip()
    if not t:
        return ""
    if phishing_flag(t):
        return MESSAGE_FLAG_WARN
    if check_contact and contact_flag(t):
        return MESSAGE_FLAG_CONTACT
    return abuse_flag(t)


# --- счётчик меток на пользователя (для админ-пульса) ----------------------------------------
# Зачем отдельно от чата: в открытых полях (комментарий заявки, отклик, отзыв) метку хранить
# негде — там нет колонки flag, а заводить её ради этого не стоит. Но телефон в комментарии
# заявки хуже, чем в личном чате: заявку видят ВСЕ, это объявление в обход приложения.
# Поэтому факт метки копим счётчиком на пользователя — тем же путём, что телепорты GPS.
# Решает по-прежнему ЧЕЛОВЕК: попадание в пульс — повод посмотреть, а не наказание.
_TEXT_COUNTER_TTL = 7 * 24 * 3600     # окно накопления — неделя
_TEXT_FLAG_TTL = 3 * 24 * 3600        # сколько держим пользователя в суточном наборе
TEXT_FLAG_COUNT = 3                   # столько меток за неделю → в пульс


def _text_day_key() -> str:
    return f"af:txtday:{utcnow():%Y%m%d}"


def count_text_flag(r, user_id: int, kind: str) -> None:
    """Метка в открытом поле → счётчик. Сам текст и телефон в лог НЕ пишем (§8): только id и вид."""
    if r is None or not kind:
        return
    try:
        ckey = f"af:txtc:{user_id}"
        n = r.incr(ckey)
        r.expire(ckey, _TEXT_COUNTER_TTL)
        if int(n) == TEXT_FLAG_COUNT:
            dkey = _text_day_key()
            r.sadd(dkey, str(user_id))
            r.expire(dkey, _TEXT_FLAG_TTL)
            log.warning(f"[ANTIFRAUD] text-suspect user={user_id}: {TEXT_FLAG_COUNT}+ помеченных "
                        f"текстов за неделю (последний вид: {kind}, решает админ)")
    except Exception:  # noqa: BLE001 — Redis лёг: модерация не должна ронять сохранение текста
        return


def log_text_flag(session, user_id: int, kind: str, place: str, ref_id: Optional[int]) -> None:
    """Запись в журнал для админа: кто · какая метка · где · id записи. Текст НЕ копируем.

    Ошибки глотаем полностью: журнал модерации не имеет права уронить сохранение самого текста.
    Пишем своей короткой транзакцией, чтобы не подмешиваться в чужую (у вызывающего роутера
    в этот момент может быть наполовину собранный объект, и commit за него делать нельзя).
    """
    if not kind or not user_id or session is None:
        return
    try:
        from .models import TextFlag
        session.add(TextFlag(user_id=user_id, kind=kind, place=place[:32], ref_id=ref_id))
        session.commit()
    except Exception:  # noqa: BLE001 — журнал best-effort
        try:
            session.rollback()
        except Exception:  # noqa: BLE001
            pass


def moderate_open_text(
    text: Optional[str],
    user_id: Optional[int],
    *,
    check_contact: bool = True,
    place: str = "",
    ref_id: Optional[int] = None,
    session=None,
) -> str:
    """Одна строка для роутеров: проверить открытое поле и, если помечено, посчитать и записать.

    Текст НЕ меняем и сохранение НЕ прерываем — вернуть вид метки полезно вызывающему,
    но игнорировать его тоже безопасно. Redis берём лениво: без него счётчик просто молчит,
    а проверка работает (регулярки ни от чего не зависят).

    `place`/`ref_id`/`session` — для журнала админа (см. models.TextFlag). Без session ведём
    себя как раньше: только счётчик. Так старые вызовы не ломаются, а новые дают админу
    возможность открыть саму запись и решить.
    """
    kind = moderate_text(text, check_contact=check_contact)
    if kind and user_id:
        try:
            from .instant_service import _redis
            count_text_flag(_redis(), user_id, kind)
        except Exception:  # noqa: BLE001 — счётчик не имеет права ронять сохранение
            pass
        if session is not None and place:
            log_text_flag(session, user_id, kind, place, ref_id)
    return kind


def text_suspects_today(r) -> int:
    """Сколько пользователей сегодня помечено по текстам (для админ-пульса)."""
    if r is None:
        return 0
    try:
        return int(r.scard(_text_day_key()))
    except Exception:  # noqa: BLE001
        return 0


class TrackGuard:
    """Анти-телепорт для WS-треков (пер-соединение): якорь — в памяти соединения (сокет и есть
    непрерывный поток одного клиента, Redis не нужен). Телепорт-кадр не ретранслируем, счётчик
    подозрительности копится в Redis (если он есть) — тем же путём, что presence."""

    def __init__(self, user_id: int):
        self.user_id = user_id
        self._anchor: Optional[tuple] = None   # (lat, lng, ts) последней ЧЕСТНОЙ точки

    def ok(self, lat: float, lng: float, now_ts: Optional[float] = None) -> bool:
        from .services import haversine_km
        now_ts = now_ts if now_ts is not None else utcnow().replace(tzinfo=timezone.utc).timestamp()
        if self._anchor is not None:
            p_lat, p_lng, p_ts = self._anchor
            elapsed_h = max(now_ts - p_ts, 1.0) / 3600.0
            if haversine_km(p_lat, p_lng, lat, lng) / elapsed_h > TELEPORT_MAX_KMH:
                self._note_teleport()
                return False              # якорь не двигаем: два телепорта подряд не «легализуются»
        self._anchor = (lat, lng, now_ts)
        return True

    def _note_teleport(self) -> None:
        from . import instant_service as isv   # локальный импорт: без циклов на старте
        r = isv._redis()
        if r is None:
            return
        try:
            _count_teleport(r, self.user_id)
        except Exception:  # noqa: BLE001 — счётчик не должен ронять сокет
            pass


# ------------------------------ внешние ссылки (волна 145) ------------------------------
# Куда человеку вообще можно предложить перейти из приложения.
#
# Зачем. У картинки объявления проверка была с самого начала: чужой адрес отбивался словами
# «Картинку нужно загрузить в приложении» — потому что чужой хост собирает IP, город и время
# просмотра всех наших людей. У ССЫЛКИ, по которой человек жмёт САМ, не было ничего: сервер
# принимал `javascript:`, `data:`, `intent://` и любой чужой адрес дословно и отдавал их
# в ленту, в том числе гостям без входа (аудит 2026-08-08, волна 145).
#
# `javascript:` и `data:` опасны ровно настолько, насколько клиент умеет их открывать, —
# и полагаться на то, что все наши клиенты (приложение, веб, будущие) одинаково аккуратны,
# нельзя. Это работа сервера.
#
# Чужие сайты при этом разрешены: реклама кафе ведёт на сайт кафе, и запрещать это бессмысленно.
# Мы отсекаем только то, что вообще не является «открыть страницу».
_URL_SCHEME = re.compile(r"^([a-zA-Z][a-zA-Z0-9+.\-]*):")
_SAFE_URL_SCHEMES = ("http", "https", "tel", "mailto")


def safe_link(url: str, kind: str = "ссылка") -> str:
    """Ссылка для перехода → она же, если по ней можно просто открыть страницу. Иначе 400.

    Пустая строка проходит: ссылка — необязательное поле, «нет ссылки» это не ошибка.
    Адрес без схемы («example.ru/sale») дополняем до https — человек пишет так постоянно,
    и отказывать ему из-за этого значит ломать работу на ровном месте.
    """
    t = (url or "").strip()
    if not t:
        return ""
    m = _URL_SCHEME.match(t)
    if m is None:
        return f"https://{t}"[:500]
    if m.group(1).lower() not in _SAFE_URL_SCHEMES:
        raise HTTPException(400, {
            "ru": f"Такую {kind} вставить нельзя — нужен обычный адрес сайта.",
            "ba": f"Бындай {kind} ҡуйып булмай — ғәҙәти сайт адресы кәрәк.",
        })
    return t[:500]
