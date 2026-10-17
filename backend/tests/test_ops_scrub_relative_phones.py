"""Разовая чистка `ops/scrub_relative_phones.py`: телефон близкого уходит из публичного комментария.

Старый экран сайта «За близкого» (до PR #168) писал в комментарий заявки «Телефон близкого: +7…»
(или «Яҡындың телефоны: +7…» по-башкирски), а комментарий видели все вошедшие в ленте заявок.
Скрипт запускает Александр сам на сервере, поэтому здесь проверяем ровно то, на что он опирается:
  * примерка ничего не меняет и не печатает ни одной цифры номера — вывод можно читать при ком угодно;
  * запись чистит только заявки старого сайта, заметку человека оставляет, номер в заметке маскирует;
  * повторный запуск находит 0; провал проверки — откат, в базе ничего не изменено.
"""
import importlib.util
import os
import pathlib
import re
import subprocess
import sys
from datetime import datetime

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import RideRequest

BACKEND = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = BACKEND / "ops" / "scrub_relative_phones.py"

_spec = importlib.util.spec_from_file_location("scrub_relative_phones", SCRIPT)
scrub = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(scrub)

WHEN = datetime(2026, 9, 3, 8, 15)   # фиксированная дата: вывод не зависит от дня прогона

# (комментарий в базе, каким должен стать, номера, которых не должно быть в выводе)
OLD_ROWS = [
    ("Телефон близкого: +7 (917) 345-67-89. Бабушка с тростью, поможете с сумкой?",
     "Заказ за близкого. Бабушка с тростью, поможете с сумкой?", ["+7 (917) 345-67-89"]),
    ("Телефон близкого: 89274813562",
     "Заказ за близкого", ["89274813562"]),
    ("Яҡындың телефоны: 8 (347) 264-18-90. Өләсәйгә ярҙам кәрәк",
     "Заказ за близкого. Өләсәйгә ярҙам кәрәк", ["8 (347) 264-18-90"]),
    ("Яҡындың телефоны: 8.927.318.24.65",
     "Заказ за близкого", ["8.927.318.24.65"]),
    # номер в самой заметке (свой, на всякий случай) — тоже маскируется
    ("Телефон близкого: +7 917 482 19 37. Если не ответит — звоните мне 8-937-156-23-48",
     "Заказ за близкого. Если не ответит — звоните мне <телефон>",
     ["+7 917 482 19 37", "8-937-156-23-48"]),
    # номер набран с «. » внутри: «917 643 28 51» — продолжение номера, а не заметка
    ("Телефон близкого: 8. 917 643 28 51. Встретить у подъезда",
     "Заказ за близкого. Встретить у подъезда", ["8. 917 643 28 51"]),
    # то же с городским номером: «347 264-18-90» без 8 общий скруб не узнаёт — уходит с номером
    ("Яҡындың телефоны: 8. 347 264-18-90. Подъезд 2",
     "Заказ за близкого. Подъезд 2", ["8. 347 264-18-90"]),
    # мобильный без +7/8 в самой заметке — общий скруб (с PR #169) маскирует и его
    ("Телефон близкого: 89274813562. Не дозвонитесь — пишите на 917 555-44-33",
     "Заказ за близкого. Не дозвонитесь — пишите на <телефон>", ["89274813562", "917 555-44-33"]),
]

# Эти комментарии скрипт не трогает ни при каких условиях.
UNTOUCHED = [
    "Еду с ребёнком, нужно кресло",
    "",
    "телефон близкого: спрошу у вас в чате",          # регистр не тот — писал не старый сайт
    "Мой Телефон близкого: позвоню сам",               # надпись не в начале
    "Заказ за близкого. Позвоните заранее",            # уже чистый (новый сайт)
    "Яҡын кеше өсөн заказ",                            # новый сайт по-башкирски
]


def _no_phone_digits(text: str, phone: str) -> None:
    """В тексте нет ни самого номера, ни пяти его цифр подряд — в каком угодно написании."""
    assert phone not in text
    national = re.sub(r"\D", "", phone)[-10:]
    runs = [re.sub(r"\D", "", m.group()) for m in re.finditer(r"\d(?:[ \-.()+]*\d)*", text)]
    for i in range(len(national) - 4):
        window = national[i:i + 5]
        assert not any(window in run for run in runs), f"в выводе цифры номера ({window}):\n{text}"


@pytest.fixture
def seed(user_factory):
    """Кладёт заявки одного пассажира и убирает их за собой."""
    passenger = user_factory("Пассажир")
    made: list[int] = []
    with Session(engine) as s:
        assert scrub.find_rows(s) == [], "в общей тестовой базе уже лежат заявки старого сайта"

    def put(comments):
        ids = []
        with Session(engine) as s:
            for c in comments:
                r = RideRequest(passenger_id=passenger["id"], from_city="Баймак", to_city="Уфа",
                                comment=c, created_at=WHEN)
                s.add(r)
                s.commit()
                s.refresh(r)
                ids.append(r.id)
        made.extend(ids)
        return ids

    yield put
    with Session(engine) as s:
        for r in s.exec(select(RideRequest).where(RideRequest.id.in_(made))).all():
            s.delete(r)
        s.commit()


def _comments(ids):
    with Session(engine) as s:
        got = dict(s.exec(select(RideRequest.id, RideRequest.comment).where(RideRequest.id.in_(ids))).all())
    return [got[i] for i in ids]


@pytest.mark.parametrize("comment, expected, _phones", OLD_ROWS)
def test_clean_comment_old_site(comment, expected, _phones):
    assert scrub.clean_comment(comment) == expected
    assert scrub.problems(comment, expected) == []


@pytest.mark.parametrize("comment", UNTOUCHED)
def test_clean_comment_leaves_other_comments(comment):
    assert scrub.clean_comment(comment) is None


def test_dry_run_changes_nothing_and_prints_no_phone(seed, capsys):
    old_ids = seed([c for c, _, _ in OLD_ROWS])
    other_ids = seed(UNTOUCHED)

    assert scrub.main([]) == 0
    out = capsys.readouterr().out

    assert _comments(old_ids) == [c for c, _, _ in OLD_ROWS], "примерка изменила базу"
    assert _comments(other_ids) == UNTOUCHED
    assert "Режим: ПРИМЕРКА" in out
    assert f"Найдено заявок: {len(OLD_ROWS)}" in out
    for rid, (_, expected, _) in zip(old_ids, OLD_ROWS):
        assert f"заявка #{rid} от 03.09.2026 13:15 по Уфе → станет: «{expected}»" in out
    for rid in other_ids:
        assert f"#{rid} " not in out
    for original, _, phones in OLD_ROWS:
        assert original not in out
        for phone in phones:
            _no_phone_digits(out, phone)


def test_apply_cleans_only_old_rows_and_is_idempotent(seed, capsys):
    old_ids = seed([c for c, _, _ in OLD_ROWS])
    other_ids = seed(UNTOUCHED)

    assert scrub.main(["--apply"]) == 0
    out = capsys.readouterr().out
    assert _comments(old_ids) == [e for _, e, _ in OLD_ROWS]
    assert _comments(other_ids) == UNTOUCHED, "скрипт тронул чужие комментарии"
    assert f"Изменено заявок: {len(OLD_ROWS)}" in out
    assert "подходящих заявок осталось 0" in out and "Готово." in out
    for _, _, phones in OLD_ROWS:
        for phone in phones:
            _no_phone_digits(out, phone)

    # второй запуск — и с записью, и примеркой — находит 0 и ничего не меняет
    assert scrub.main(["--apply"]) == 0
    again = capsys.readouterr().out
    assert "Найдено заявок: 0" in again and "Изменено заявок: 0" in again
    assert scrub.main([]) == 0
    assert "Найдено заявок: 0" in capsys.readouterr().out
    assert _comments(old_ids) == [e for _, e, _ in OLD_ROWS]


def test_apply_refuses_when_check_fails(seed, capsys, monkeypatch):
    """Проверка перед записью не прошла → отказ, база как была, номер не напечатан."""
    old_ids = seed([OLD_ROWS[0][0]])
    monkeypatch.setattr(scrub, "clean_comment", lambda c: c if c and c.startswith(scrub.PREFIXES) else None)

    assert scrub.main(["--apply"]) == 1
    out = capsys.readouterr().out
    assert "НЕ ЧИЩУ АВТОМАТИЧЕСКИ" in out and "ничего не изменено" in out
    assert _comments(old_ids) == [OLD_ROWS[0][0]]
    _no_phone_digits(out, OLD_ROWS[0][2][0])


def test_apply_rolls_back_when_after_check_fails(seed, capsys, monkeypatch):
    """Запись прошла, но сверка в той же транзакции не сошлась → откат всей транзакции."""
    old_ids = seed([c for c, _, _ in OLD_ROWS[:2]])
    # «чистка» оставляет надпись старого сайта, а проверку перед записью выключили:
    # после записи подходящие заявки остаются — сверка обязана откатить ВСЁ.
    monkeypatch.setattr(scrub, "clean_comment",
                        lambda c: c + " " if c and c.startswith(scrub.PREFIXES) else None)
    monkeypatch.setattr(scrub, "problems", lambda original, cleaned: [])

    assert scrub.main(["--apply"]) == 1
    out = capsys.readouterr().out
    assert "сверка после записи не сошлась" in out and "откатено" in out
    assert _comments(old_ids) == [c for c, _, _ in OLD_ROWS[:2]], "откат не сработал"


def test_refuses_on_server_code_without_scrub(seed, capsys, monkeypatch):
    """На сервере код старше общего скруба → понятный отказ до любого чтения базы, ничего не меняем."""
    old_ids = seed([OLD_ROWS[1][0]])
    monkeypatch.setattr(scrub, "scrub_text", None)
    assert scrub.main(["--apply"]) == 1
    assert "Запусти после выкладки" in capsys.readouterr().out
    assert _comments(old_ids) == [OLD_ROWS[1][0]]


def test_runs_as_a_command_like_on_the_server(seed, tmp_path):
    """Так его запустит Александр: `python ops/scrub_relative_phones.py` — отдельным процессом."""
    [rid] = seed([OLD_ROWS[2][0]])
    env = {**os.environ, "PYTHONIOENCODING": "utf-8"}
    proc = subprocess.run([sys.executable, str(SCRIPT)], cwd=tmp_path, env=env, capture_output=True,
                          text=True, encoding="utf-8", errors="replace", timeout=120)
    assert proc.returncode == 0, proc.stdout + proc.stderr[-2000:]
    assert "Режим: ПРИМЕРКА" in proc.stdout and "Найдено заявок: 1" in proc.stdout
    assert f"заявка #{rid} " in proc.stdout
    _no_phone_digits(proc.stdout + proc.stderr, OLD_ROWS[2][2][0])
    assert _comments([rid]) == [OLD_ROWS[2][0]]
