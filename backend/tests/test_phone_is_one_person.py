"""Один номер — один человек, как бы он его ни набрал.

Что было. Вход искал пользователя по строке ТОЧНО как введено:

    user = session.exec(select(User).where(User.phone == body.phone)).first()

Значит «+79991234567», «79991234567», «89991234567» и «+7 999 123-45-67» — это ЧЕТЫРЕ разных
аккаунта одного человека. У каждого своя история поездок, свой рейтинг, свои документы водителя,
свой кошелёк, свои доверенные контакты. Поймано вживую (2026-08-13): вход по «70000000000»
завёл новую запись рядом с уже существующей «+70000000000».

Как это выглядит у человека. Он заходил с плюсом, потом набрал с восьмёрки — и попал в пустое
приложение: ни поездок, ни рейтинга, ни подтверждённых прав. Вернуться некуда: он не помнит,
каким написанием заходил в прошлый раз, а поддержке предъявить нечего — «номер тот же».

Отдельно про безопасность: водителю поставили паузу в «Справедливости» — он заходит с другого
написания и получает чистый аккаунт без наказания. Барьер по устройству остаётся, но он про
устройство, а не про человека.

Тесты держат оба конца: приведение номера к одному виду и то, что старая запись НАХОДИТСЯ
(а не дублируется) и выправляется на месте.
"""
from __future__ import annotations

import itertools

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import User
from app.security import normalize_phone
from app.services import find_user_by_phone

# База у тестов одна на всю сессию, поэтому номера берём уникальные на каждый тест: иначе
# «+79991234567», оставленный предыдущим тестом, ломал бы следующий.
_seq = itertools.count(1)


@pytest.fixture
def phones():
    # Префикс 9401 не занят ни одним другим тестом (остальные живут на 9990/9170/9991/9995).
    # База у тестов ОДНА на сессию, а порядок файлов случайный (pytest-randomly): пересечение
    # номеров даёт «UNIQUE constraint failed» в чужом тесте — то есть падение не там, где
    # ошибка. Один раз уже поймал себя на этом (2026-08-13).
    # Шаг 10, а не 1: один тест заводит «соседний» номер (+1), и с шагом 1 он занял бы номер
    # следующего теста.
    base = f"9401{next(_seq) * 10:06d}"[:10]
    return {
        "plus": "+7" + base,
        "seven": "7" + base,
        "eight": "8" + base,
        "spaced": f"+7 {base[:3]} {base[3:6]}-{base[6:8]}-{base[8:]}",
        "bare": base,
    }


@pytest.fixture
def db(client):   # client поднимает приложение → init_db создаёт таблицы
    with Session(engine) as s:
        yield s


# ---------- приведение к одному виду ----------

@pytest.mark.parametrize(
    "raw",
    ["+79991234567", "79991234567", "89991234567", "+7 999 123-45-67",
     "8 (999) 123 45 67", "9991234567", "+7-999-123-45-67"],
)
def test_любое_написание_российского_номера_даёт_один_вид(raw):
    assert normalize_phone(raw) == "+79991234567"


def test_иностранный_номер_не_переделываем():
    # Угадывать чужой план нумерации нельзя: «+380…» — Украина, а не наша восьмёрка.
    assert normalize_phone("+380 50 123 45 67") == "+380501234567"


def test_телеграм_заглушка_и_пустое_остаются_как_есть():
    # `tg<id>` — не телефон, а метка «номер ещё не сообщён». По ней отличают «нет номера»
    # от настоящего, портить её нельзя.
    assert normalize_phone("tg12345") == "tg12345"
    assert normalize_phone("") == ""
    assert normalize_phone(None) == ""


# ---------- поиск человека ----------

def test_человек_находится_по_любому_написанию(db, phones):
    user = User(phone=phones["plus"], name="Айгуль")
    db.add(user)
    db.commit()
    db.refresh(user)
    for variant in (phones["eight"], phones["seven"], phones["spaced"], phones["bare"]):
        found = find_user_by_phone(db, variant)
        assert found is not None, f"не нашёлся по написанию {variant}"
        assert found.id == user.id, f"написание {variant} привело к другому человеку"


def test_старая_запись_выправляется_на_месте(db, phones):
    # В базе номер лежит в старом виде (так писали до приведения). Первый же вход выправляет
    # строку — дальше человек ищется по одному виду, без разовой миграции всей базы.
    db.add(User(phone=phones["eight"], name="Рустам"))
    db.commit()
    found = find_user_by_phone(db, phones["plus"])
    assert found is not None
    assert found.phone == phones["plus"], "строку в базе не выправили"


def test_чужой_номер_не_подхватывается(db, phones):
    # Обратная сторона: приведение не должно склеить РАЗНЫХ людей.
    other = "+7" + str(int(phones["plus"][2:]) + 1).zfill(10)
    db.add(User(phone=phones["plus"], name="Айгуль"))
    db.add(User(phone=other, name="Рустам"))
    db.commit()
    found = find_user_by_phone(db, phones["eight"])
    assert found is not None and found.name == "Айгуль"


def test_пустой_номер_никого_не_находит(db, phones):
    db.add(User(phone=phones["plus"], name="Айгуль"))
    db.commit()
    assert find_user_by_phone(db, "") is None
    assert find_user_by_phone(db, None) is None


# ---------- обе двери к полю `User.phone` ----------

def test_поиск_по_телефону_идёт_через_одну_дверь():
    """Дверей к `User.phone` две: вход по коду и «поделился номером» из Telegram. Первую я
    починил, вторая осталась бы со старым сравнением — и правило держалось бы наполовину.

    Сторож читает исходники: прямое `User.phone ==` разрешено только внутри самой двери
    (`services.find_user_by_phone`). Всё остальное обязано звать её — иначе сравнение снова
    поедет по написанию номера.
    """
    import pathlib
    import re

    app_dir = pathlib.Path(__file__).resolve().parents[1] / "app"
    # Где сравнение по сырому полю оправдано, с причиной:
    allowed = {
        # сама дверь: она и перебирает написания
        "services.py",
        # приглашение по номеру и владелец промокода ищут ЧУЖОЙ номер, введённый вручную;
        # переводить их на дверь надо отдельной задачей — она чинит строку в базе, а это
        # побочный эффект, которого поиск «есть ли такой человек» не ждёт.
        "promo.py",
        "requests.py",
    }
    hits = []
    for path in sorted(app_dir.rglob("*.py")):
        if path.name in allowed:
            continue
        src = path.read_text(encoding="utf-8")
        for num, line in enumerate(src.splitlines(), 1):
            # `select(` на той же строке — чтобы не ловить упоминания в комментариях и
            # docstring'ах: настоящая дверь всегда выглядит как select(User).where(User.phone == …).
            if "select(" in line and re.search(r"User\.phone\s*==", line):
                hits.append(f"{path.relative_to(app_dir.parent).as_posix()}:{num}  {line.strip()[:90]}")
    assert not hits, (
        "поиск человека по телефону мимо find_user_by_phone() — %d шт. "
        "Разные написания одного номера снова дадут разные аккаунты:\n%s"
        % (len(hits), "\n".join(hits))
    )
