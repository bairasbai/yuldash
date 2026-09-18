"""Обещанная человеку комиссия совпадает с той, что мы берём на самом деле.

Зачем. Ставка живёт в настройке и меняется одной строкой без пересборки. А написана она
словами ещё в дюжине мест: онбординг таксиста, экран «Честные правила», профиль водителя,
главная сайта и — самое важное — оферта. Меняешь настройку, а обещание остаётся прежним.

Это не гипотеза. Волна 153 чинила ровно такое: попутка обещала «0 ₽ комиссия сервиса»,
а код удерживал 8% при оплате картой. Человек видел одно, получал другое, и нигде это
не объяснялось. Сегодня, поднимая такси с 8% до 15%, мы расставили ту же ловушку заново —
поэтому и появился этот сторож.

Проверяем не «есть ли где-то правильное число», а обратное: **нет ли где-то устаревшего**.
Цифра берётся из конфига, ищется в текстах, а старая — не должна встречаться вовсе.
"""
import re
from pathlib import Path

import pytest

from app.config import settings

ROOT = Path(__file__).resolve().parents[2]

# Файлы, где комиссия ТАКСИ названа словами. Путь → зачем он тут (для человека, который
# однажды увидит красный тест и не поймёт, при чём здесь этот файл).
TAXI_TEXT_FILES = {
    "web/components/legal-content.ts":
        "ОФЕРТА и правила на сайте — юридически значимый текст",
    "web/components/lang.tsx":
        "главная страница сайта, крупная цифра в блоке цифр",
}

# Файлы, где ставку НЕ пишут словами — с 09.09.2026 (аудит, WEB42, коммит b945f6f8) онбординг
# таксиста и «Честные правила» отсылают в кабинет, а кабинет рисует живую лесенку с сервера
# (`/driver/dashboard`). Здесь сторож обратный: в видимых человеку строках не должно быть
# НИ ОДНОГО процента — иначе число снова живёт в двух местах и разъезжается при смене настройки.
TAXI_LIVE_RATE_FILES = {
    "android/app/src/main/java/com/yuldash/app/TaxiOnboardingScreen.kt":
        "онбординг таксиста — первое, что человек читает про деньги",
    "android/app/src/main/java/com/yuldash/app/SecondaryScreens.kt":
        "экран «Честные правила» — там мы обещаем открытость",
    "android/app/src/main/java/com/yuldash/app/ProfileScreen.kt":
        "профиль и лесенка комиссии в кабинете — числа приходят с сервера",
}


def _pcts(text: str) -> set[str]:
    """Все проценты, названные в тексте: «15%», «3%», «15 %»."""
    return {m.group(1) for m in re.finditer(r"(\d{1,2})\s*%", text)}


def _visible_strings(kotlin: str) -> str:
    """Только то, что человек может прочитать: строковые литералы без комментариев.

    Иначе сторож обманывается: «(3/8/15%)» в комментарии к функции засчитывалось за обещание
    в кабинете, хотя сам экран числа не печатает (аудит main, замечание к commission_promise).
    """
    без_комментариев = re.sub(r"/\*.*?\*/", "", kotlin, flags=re.S)
    без_комментариев = re.sub(r"//[^\n]*", "", без_комментариев)
    return "\n".join(m.group(0) for m in re.finditer(r'"[^"\n]*"', без_комментариев))


def _read(rel: str) -> str:
    p = ROOT / rel
    assert p.is_file(), f"файл пропал: {rel}"
    return p.read_text(encoding="utf-8")


@pytest.mark.parametrize("rel", sorted(TAXI_TEXT_FILES))
def test_no_stale_taxi_commission_promised(rel):
    """Ни в одном тексте про такси не должно остаться прежней ставки.

    Ловим именно устаревшее число: правильное могли дописать рядом, а старое забыть удалить —
    и человек прочитает то, которое попалось первым.
    """
    current = f"{settings.service_fee_percent:g}"
    text = _read(rel)
    named = _pcts(text)
    assert current in named, (
        f"{rel} ({TAXI_TEXT_FILES[rel]}): ставка такси сейчас {current}%, но в тексте её нет. "
        f"Названы: {sorted(named)}. Поменял настройку — поменяй и обещание."
    )


@pytest.mark.parametrize("rel", sorted(TAXI_LIVE_RATE_FILES))
def test_app_does_not_hardcode_the_taxi_rate(rel):
    """Приложение не пишет ставку словами — только показывает ту, что прислал сервер.

    Стоило написать «15%» в онбординге, как смена настройки на сервере оставила бы человеку
    старое обещание до следующего релиза приложения (а релиз — это ещё и модерация в сторе).
    """
    named = _pcts(_visible_strings(_read(rel)))
    assert not named, (
        f"{rel} ({TAXI_LIVE_RATE_FILES[rel]}): в видимых строках снова есть проценты {sorted(named)}. "
        "Ставку и лесенку берём с сервера, словами не пишем."
    )


def test_the_ladder_is_promised_exactly_as_it_works():
    """Лесенка «первые N поездок / следующие M / дальше» названа теми же числами, что в коде.

    Она особенно коварна: человек читает её один раз, а расходится она через сотню поездок —
    когда он уже привык и проверять не пойдёт. Единственное место, где лесенка написана
    словами, — оферта на сайте (в приложении её рисует сервер).
    """
    ladder = {f"{settings.fee_tier1_percent:g}",
              f"{settings.fee_tier2_percent:g}",
              f"{settings.service_fee_percent:g}"}
    text = _read("web/components/legal-content.ts")
    named = _pcts(text)
    missing = ladder - named
    assert not missing, (
        f"оферта обещает не ту лесенку: в коде {sorted(ladder)}, "
        f"в тексте {sorted(named)}, не хватает {sorted(missing)}"
    )
    первые = f"первые {settings.fee_tier1_trips} "
    следующие = f"следующие {settings.fee_tier2_trips - settings.fee_tier1_trips} "
    assert первые in text, f"оферта не называет границу первой ступени «{первые.strip()}»"
    assert следующие in text, f"оферта не называет ширину второй ступени «{следующие.strip()}»"


def test_offer_names_both_rates_separately():
    """Оферта обязана назвать такси и доставку отдельно — ставки у них разные.

    Пока они совпадали, одна фраза «в режимах Такси и Доставка — 8%» была правдой. С разными
    ставками та же фраза становится неверной для одной из сторон, а это уже юридический текст.
    """
    text = _read("web/components/legal-content.ts")
    taxi = f"{settings.service_fee_percent:g}%"
    courier = f"{settings.courier_service_fee_percent:g}%"
    assert taxi in text, f"в оферте нет ставки такси {taxi}"
    assert courier in text, f"в оферте нет ставки доставки {courier}"
    if taxi != courier:
        assert "В режимах «Такси» и «Доставка»" not in text, (
            "оферта объединяет такси и доставку одной ставкой, а они разные — "
            f"такси {taxi}, доставка {courier}"
        )


def test_ladder_only_grows():
    """Ступени идут вверх, а не вниз.

    В текстах когда-то стояло «чем дольше возишь, тем меньше платишь» — и это было прямым
    враньём: ставка растёт. Такое обещание живёт до первого месяца работы, а потом
    становится причиной уйти — не из-за денег, а из-за обмана.
    """
    steps = [settings.fee_tier1_percent, settings.fee_tier2_percent, settings.service_fee_percent]
    assert steps == sorted(steps), f"лесенка идёт вниз: {steps}"


def test_ladder_has_no_cliff():
    """Ни одна ступень не должна прыгать больше чем втрое.

    5% → 15% означало бы, что на 61-й день человек платит втрое больше вчерашнего, без
    предупреждения и без своей вины. Уходят не от размера ставки, а от того, что она прыгнула.
    """
    steps = [settings.fee_tier1_percent, settings.fee_tier2_percent, settings.service_fee_percent]
    for a, b in zip(steps, steps[1:]):
        if a > 0:
            assert b / a <= 3.0, (
                f"ступень {a}% → {b}% — это скачок в {b / a:.1f} раза. "
                f"Смягчи промежуточную ступень или предупреждай заранее."
            )
