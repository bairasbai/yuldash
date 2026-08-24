"""Классы машин такси, авто-расчёт класса и опции салона (спека — docs/taxi-classes-2026-08.md).

Три правила, из которых растёт весь модуль:

1. **Класс НЕ выбирает водитель — класс считается по характеристикам машины.** Водитель
   заявляет год, места, кондиционер; класс выводится из них. Иначе любой поставит себе
   «Бизнес» и пассажир получит Гранту вместо Мерседеса.

2. **Каждый класс проверяется НЕЗАВИСИМО, иерархии нет.** Так же устроен классификатор
   Яндекса. Разница не теоретическая: седан Бизнеса НЕ проходит в «Минивэн» (мест мало),
   а старый минивэн проходит в «Минивэн», но НЕ в «Комфорт» (возраст). Проверка «всё, что
   ниже» оба случая обработала бы неверно.

3. **Из доступных классов водитель включает нужные сам** (`classes_enabled`). Оплата — по
   тарифу ЗАКАЗА, не по классу машины: взял эконом-заказ на Комфорте — получил по Экономе.
   Это его осознанный выбор, а не понижение.

Исторические имена: базовая категория заказа зовётся `standard` (Tariff.category,
InstantOrder.category), а тот же класс машины в DriverProfile.car_class — `economy`.
Переименовывать не стали (живая БД и тесты), расхождение снимают `class_to_category` /
`category_to_class`.
"""
from __future__ import annotations

from typing import Iterable, Optional

# --- Классы машин и категории заказа -------------------------------------------------
ECONOMY = "economy"
COMFORT = "comfort"
BUSINESS = "business"
MINIVAN = "minivan"

CLASSES: tuple[str, ...] = (ECONOMY, COMFORT, BUSINESS, MINIVAN)

# Категория заказа = класс машины, кроме исторического economy → standard.
STANDARD = "standard"
ORDER_CATEGORIES: tuple[str, ...] = (STANDARD, COMFORT, BUSINESS, MINIVAN)


def class_to_category(car_class: Optional[str]) -> str:
    """Класс машины → категория заказа/тарифа. NULL и мусор → базовая категория."""
    c = (car_class or ECONOMY).strip().lower()
    if c == ECONOMY:
        return STANDARD
    return c if c in ORDER_CATEGORIES else STANDARD


def category_to_class(category: Optional[str]) -> str:
    """Категория заказа → класс машины. NULL и мусор → economy."""
    c = (category or STANDARD).strip().lower()
    if c == STANDARD:
        return ECONOMY
    return c if c in CLASSES else ECONOMY


# --- Опции салона ---------------------------------------------------------------------
# Опция — НЕ класс. Одна машина не может стоять в двух классах, поэтому «детское кресло» и
# «помощь с коляской» сделаны галочками поверх любого класса: водитель на Комфорте с креслом
# остаётся в Комфорте и ловит оба типа заказов. Классом это ломалось бы — ему пришлось бы
# выбирать между «Комфортом» и «Детским» и терять половину заказов.
OPT_SEAT_0_1 = "seat_0_1"          # автолюлька, 0–1 год
OPT_SEAT_1_4 = "seat_1_4"          # кресло 1–4 года
OPT_SEAT_4_7 = "seat_4_7"          # кресло 4–7 лет
OPT_BOOSTER = "booster"            # бустер 7–12 лет
OPT_WHEELCHAIR = "wheelchair"      # помогу с инвалидной коляской (складная, в багажник)
OPT_GUIDE_DOG = "guide_dog"        # везу с собакой-проводником
OPT_STROLLER = "stroller"          # детская коляска — нужен свободный багажник
OPT_PETS = "pets"                  # можно с животным
OPT_BIG_LUGGAGE = "big_luggage"    # большой багаж

OPTIONS: tuple[str, ...] = (
    OPT_SEAT_0_1, OPT_SEAT_1_4, OPT_SEAT_4_7, OPT_BOOSTER,
    OPT_WHEELCHAIR, OPT_GUIDE_DOG, OPT_STROLLER, OPT_PETS, OPT_BIG_LUGGAGE,
)

# Детские опции — те, где отсутствие означает «ехать нельзя по закону» (перевозка детей).
CHILD_OPTIONS: frozenset[str] = frozenset({OPT_SEAT_0_1, OPT_SEAT_1_4, OPT_SEAT_4_7, OPT_BOOSTER})

# Опции доступности. Отказ ПОСЛЕ принятия такого заказа — не «передумал», а нарушение прав
# пассажира: Роспотребнадзор в 2025 взыскал с водителя 5 000 ₽ морального вреда и 30 000 ₽
# штрафа за отказ везти незрячего с собакой-проводником (Красноярск). Поэтому опция видна
# водителю ДО принятия — согласился, значит везёшь.
ACCESSIBILITY_OPTIONS: frozenset[str] = frozenset({OPT_WHEELCHAIR, OPT_GUIDE_DOG})

# Занимают багажник целиком. Отдельно от «большого багажа», потому что боль другая: семью
# с 7-месячным ребёнком трижды подряд отменили водители со словами «багажник забит» — просто
# потому, что узнавали о коляске уже на месте.
TRUNK_OPTIONS: frozenset[str] = frozenset({OPT_STROLLER, OPT_WHEELCHAIR, OPT_BIG_LUGGAGE})


# --- Сколько опция стоит (решение Александра 2026-08-23) -------------------------------
# Раньше все опции были бесплатны, и это ломало саму функцию. Детское кресло стоит 3–8 тысяч,
# живёт три-четыре года и занимает багажник постоянно — за ноль рублей водитель просто не
# включит галочку «у меня есть кресло», и заказ мамы с ребёнком не найдёт машину вообще.
# Ориентир рынка: Яндекс берёт за кресло 150–180 ₽ (замер 22.08.2026).
#
# Деньги идут водителю ЦЕЛИКОМ: это компенсация его расходов, а не выручка платформы,
# поэтому комиссия с них не берётся (см. debt.order_commission_kop).
_OPTION_PRICE_RUB: dict[str, int] = {
    OPT_SEAT_0_1: 150,      # автолюлька
    OPT_SEAT_1_4: 150,      # кресло 1–4
    OPT_SEAT_4_7: 150,      # кресло 4–7
    OPT_BOOSTER: 150,       # бустер
    OPT_PETS: 100,          # животное — чистка салона
    OPT_BIG_LUGGAGE: 100,   # большой багаж — место и погрузка
    OPT_STROLLER: 0,        # коляска: это не услуга, это семья с ребёнком
    OPT_WHEELCHAIR: 0,      # ↓ см. ниже — только ноль
    OPT_GUIDE_DOG: 0,
}

# ⚠️ ДОСТУПНОСТЬ ВСЕГДА БЕСПЛАТНА, И ЭТО НЕ НАСТРОЙКА.
# Брать деньги за инвалидную коляску или собаку-проводника — дискриминация: в 2025 году
# с водителя взыскали 5 000 ₽ морального вреда и 30 000 ₽ штрафа за отказ везти незрячего
# с собакой (Красноярск). Поэтому цена доступности зашита в код нулём и НЕ переопределяется
# конфигом: настройка, которую можно случайно поменять в .env, здесь недопустима.
FREE_FOREVER: frozenset[str] = ACCESSIBILITY_OPTIONS


def option_price_rub(code: str) -> int:
    """Цена одной опции, ₽. Доступность — всегда 0, что бы ни стояло в конфиге."""
    key = (code or "").strip().lower()
    if key in FREE_FOREVER:
        return 0
    if key not in OPTIONS:
        return 0
    from .config import settings   # локальный импорт: config тянет car_class на старте
    override = getattr(settings, "option_prices_rub", None) or {}
    try:
        if key in override:
            return max(int(override[key]), 0)
    except (TypeError, ValueError):
        pass                       # кривое значение в конфиге не должно ломать заказ
    return _OPTION_PRICE_RUB.get(key, 0)


def options_fee_rub(raw: Optional[str]) -> int:
    """Сколько стоят выбранные опции вместе, ₽. Пустая строка / мусор → 0."""
    return sum(option_price_rub(code) for code in parse_options(raw))


# --- Хранение списков строкой ---------------------------------------------------------
# Опции и включённые классы лежат CSV-строкой, а не булевыми колонками: их семь и они будут
# добавляться. Фильтр кандидатов и так идёт перебором в Python (instant_service.eligible),
# индексы по ним не нужны, а миграция под каждую новую галочку — нужна была бы.
def parse_list(raw: Optional[str], allowed: Iterable[str]) -> list[str]:
    """CSV → список известных значений, без дублей, в порядке `allowed` (стабильный вывод)."""
    if not raw:
        return []
    got = {p.strip().lower() for p in raw.split(",") if p.strip()}
    return [a for a in allowed if a in got]


def dump_list(values: Optional[Iterable[str]], allowed: Iterable[str]) -> str:
    """Список → CSV. Неизвестное молча отбрасываем: клиент новее сервера не должен ломать запись."""
    if not values:
        return ""
    got = {str(v).strip().lower() for v in values}
    return ",".join(a for a in allowed if a in got)


def parse_options(raw: Optional[str]) -> list[str]:
    return parse_list(raw, OPTIONS)


def dump_options(values: Optional[Iterable[str]]) -> str:
    return dump_list(values, OPTIONS)


def parse_classes(raw: Optional[str]) -> list[str]:
    return parse_list(raw, CLASSES)


def dump_classes(values: Optional[Iterable[str]]) -> str:
    return dump_list(values, CLASSES)


# --- Цвет кузова ----------------------------------------------------------------------
# Закон РБ № 77-з ст. 15.2 (ред. № 764-з от 15.09.2023): такси — чёрная, белая или жёлтая
# цветовая гамма. Это САМЫЙ жёсткий фильтр для сельского водителя (перекраска 30–80 тыс ₽),
# поэтому в онбординге цвет спрашиваем ПЕРВЫМ — чтобы человек не заполнял анкету зря.
# В Челябинской области (Магнитогорск) требования к цвету нет — регион его не вводил.
TAXI_COLORS: frozenset[str] = frozenset({"black", "white", "yellow"})

# Что пишут люди в поле цвета. Список намеренно щедрый: «молочный» и «серебристый» должны
# развестись по разные стороны допуска, а не упасть в «не распознали».
_COLOR_WORDS: dict[str, tuple[str, ...]] = {
    "black": ("чёрн", "черн", "black"),
    "white": ("бел", "молочн", "white"),
    "yellow": ("жёлт", "желт", "yellow"),
    "silver": ("серебр", "silver"),
    "grey": ("сер", "графит", "grey", "gray"),
    "blue": ("син", "голуб", "blue"),
    "red": ("красн", "бордов", "вишн", "red"),
    "green": ("зелен", "зелён", "green"),
    "brown": ("коричн", "беж", "brown"),
    "other": (),
}


def normalize_color(raw: Optional[str]) -> str:
    """Свободный текст цвета → код. Не распознали → "" (не путать с «не подходит»)."""
    s = (raw or "").strip().lower()
    if not s:
        return ""
    for code, words in _COLOR_WORDS.items():
        for w in words:
            if w and w in s:
                return code
    return "other"


def color_allowed(raw: Optional[str], *, region: str = "РБ") -> Optional[bool]:
    """Проходит ли цвет по требованиям региона.

    True — проходит, False — нет, None — не распознали (решает модератор по фото; отказывать
    из-за того, что человек написал «мокрый асфальт», нельзя).
    """
    if region != "РБ":
        return True          # Челябинская, Оренбургская и др. цвет не регулируют
    code = normalize_color(raw)
    if not code or code == "other":
        return None
    return code in TAXI_COLORS


# --- Авто-расчёт доступных классов ----------------------------------------------------
# M1 (легковое такси) — не более 8 мест для пассажиров помимо водителя. Больше — это автобус:
# категория D у водителя и лицензия на перевозки у службы заказа. Поэтому потолок жёсткий.
MAX_PASSENGER_SEATS = 8


class CarSpec:
    """Характеристики машины для расчёта класса. Отдельный объект, чтобы правила можно было
    прогонять и на заявке (её ещё нет в БД), и на профиле водителя."""

    __slots__ = ("year", "seats", "has_ac", "clean_salon", "body_ok", "is_sedan",
                 "leather", "color", "premium")

    def __init__(self, *, year: Optional[int] = None, seats: int = 4, has_ac: bool = False,
                 clean_salon: bool = True, body_ok: bool = True, is_sedan: bool = False,
                 leather: bool = False, color: Optional[str] = None, premium: bool = False):
        self.year = year
        self.seats = seats
        self.has_ac = has_ac
        self.clean_salon = clean_salon      # салон целый, без чехлов и накидок, без запаха
        self.body_ok = body_ok              # кузов без крупных вмятин, ржавчины, «разных» деталей
        self.is_sedan = is_sedan
        self.leather = leather              # кожа или комбинированный салон
        self.color = color
        self.premium = premium              # премиум-марка; ставит модератор при очном допуске


def _age(year: Optional[int], now_year: int) -> Optional[int]:
    if not year:
        return None
    return now_year - int(year)


def missing_for(car_class: str, spec: CarSpec, now_year: int,
                *, comfort_max_age: int, business_max_age: int, minivan_max_age: int,
                minivan_min_seats: int) -> list[str]:
    """Чего не хватает машине до класса. Пустой список = класс доступен.

    Коды причин, а не текст: подписи живут в клиенте на двух языках.
    """
    out: list[str] = []
    age = _age(spec.year, now_year)
    seats = int(spec.seats or 0)

    if seats > MAX_PASSENGER_SEATS:
        # Не «недостаток класса», а стоп для всего такси: 9+ мест — уже автобус.
        return ["too_many_seats"]

    if car_class == ECONOMY:
        if not spec.clean_salon:
            out.append("clean_salon")
        return out

    if car_class == COMFORT:
        if age is None:
            out.append("year_unknown")
        elif age > comfort_max_age:
            out.append("too_old")
        if not spec.has_ac:
            out.append("no_ac")
        if not spec.clean_salon:
            out.append("clean_salon")
        if not spec.body_ok:
            out.append("body")
        if seats < 4:
            out.append("few_seats")
        return out

    if car_class == BUSINESS:
        if age is None:
            out.append("year_unknown")
        elif age > business_max_age:
            out.append("too_old")
        if not spec.is_sedan:
            out.append("not_sedan")
        if normalize_color(spec.color) not in ("black", "white"):
            out.append("color_business")
        if not spec.leather:
            out.append("no_leather")
        if not spec.has_ac:
            out.append("no_ac")
        if not spec.clean_salon:
            out.append("clean_salon")
        if not spec.body_ok:
            out.append("body")
        if not spec.premium:
            # Ставит модератор при очном допуске (видеозвонок + осмотр). Без него в Бизнес
            # не пускаем: там и цена выше, и ожидания пассажира другие.
            out.append("not_verified_premium")
        return out

    if car_class == MINIVAN:
        if age is None:
            out.append("year_unknown")
        elif age > minivan_max_age:
            out.append("too_old")
        if seats < minivan_min_seats:
            out.append("few_seats")
        if not spec.clean_salon:
            out.append("clean_salon")
        if not spec.body_ok:
            out.append("body")
        return out

    return ["unknown_class"]


def available_classes(spec: CarSpec, now_year: int, *, comfort_max_age: int,
                      business_max_age: int, minivan_max_age: int,
                      minivan_min_seats: int) -> list[str]:
    """Все классы, требованиям которых машина соответствует. Порядок — как в CLASSES."""
    return [
        c for c in CLASSES
        if not missing_for(c, spec, now_year, comfort_max_age=comfort_max_age,
                           business_max_age=business_max_age, minivan_max_age=minivan_max_age,
                           minivan_min_seats=minivan_min_seats)
    ]


def available_or_legacy(available_raw: Optional[str], car_class: Optional[str]) -> list[str]:
    """Доступные классы с оглядкой на старые профили.

    У водителей, заведённых до классификатора, `car_classes_available` пуст — там был только
    `car_class`. Выводим набор из него и СОХРАНЯЕМ прежнее поведение подбора: комфорт-машина
    и раньше получала обычные заказы (фильтр резал только comfort-заказы обычным машинам).
    Иначе после выката все действующие таксисты разом остались бы без половины заказов.
    """
    got = parse_classes(available_raw)
    if got:
        return got
    c = (car_class or ECONOMY).strip().lower()
    if c in (COMFORT, BUSINESS, MINIVAN):
        return [ECONOMY, c]
    return [ECONOMY]


def effective_classes(available: Iterable[str], enabled_raw: Optional[str]) -> list[str]:
    """Классы, по которым водителю реально шлём офферы: доступные ∩ включённые им.

    Ничего не включил (пустое поле) → берём ВСЕ доступные. Это важное умолчание: человек
    прошёл модерацию и вышел на линию — он ждёт заказы, а не пустой экран из-за незаполненной
    галочки.
    """
    avail = [c for c in CLASSES if c in set(available)]
    enabled = parse_classes(enabled_raw)
    if not enabled:
        return avail
    keep = [c for c in avail if c in set(enabled)]
    return keep or avail


def driver_takes(order_category: Optional[str], available: Iterable[str],
                 enabled_raw: Optional[str]) -> bool:
    """Берёт ли водитель заказ этой категории."""
    return category_to_class(order_category) in set(effective_classes(available, enabled_raw))


def covers_options(driver_options_raw: Optional[str], order_options_raw: Optional[str]) -> bool:
    """Есть ли у водителя всё, что запросил пассажир.

    Жёстко: заказ с детским креслом машине без кресла не предлагаем вообще. Подобрать «раз
    никого нет» — значит обмануть в том единственном, ради чего галочку и ставили (та же
    логика, что у «только женщина за рулём»).
    """
    need = set(parse_options(order_options_raw))
    if not need:
        return True
    return need.issubset(set(parse_options(driver_options_raw)))
