"""Разовая чистка `ops/scrub_relative_phones.py`: телефон близкого уходит из публичного комментария.

Старый сайт (до PR #168) писал в комментарий заявки «Телефон близкого: +7…» / «Яҡындың телефоны: +7…»,
старое приложение (29.06–05.07.2026) — «Заказ за близкого · +7…» / «Яҡын кеше өсөн заказ · +7…».
Комментарий видели все вошедшие в ленте заявок. Скрипт запускает Александр сам на сервере, поэтому
здесь проверяем ровно то, на что он опирается:
  * примерка ничего не меняет, а её вывод не несёт ни номера, ни текста заметки, ни имени близкого;
  * на старом коде сервера (без scrub_text) примерка считает, а запись отказывается;
  * запись чистит только заявки старых клиентов; цифр номера в итоге не остаётся никогда —
    с какими угодно разделителями (неразрывный пробел, тире, перевод строки, «/»…);
  * правленые комментарии (надпись не в начале): длинные цепочки цифр механически → «<телефон>»;
  * повторный запуск находит 0; сбой — понятная строка «СТОП» без трассировки.
"""
import importlib.util
import os
import pathlib
import re
import subprocess
import sys
import time
from datetime import datetime

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import RideRequest

BACKEND = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = BACKEND / "ops" / "scrub_relative_phones.py"

_spec = importlib.util.spec_from_file_location("scrub_relative_phones", SCRIPT)
scrub = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = scrub      # dataclass ищет свой модуль в sys.modules
_spec.loader.exec_module(scrub)

WHEN = datetime(2026, 9, 3, 8, 15)   # фиксированная дата: вывод не зависит от дня прогона
RU, BA = "Заказ за близкого", "Яҡын кеше өсөн заказ"

# (комментарий в базе, каким станет, заметку убрали?, личное, чего не должно быть в выводе)
OLD_ROWS = [
    ("Телефон близкого: +7 (917) 345-67-89. Бабушка с тростью, поможете с сумкой?",
     f"{RU}. Бабушка с тростью, поможете с сумкой?", False, ["+7 (917) 345-67-89", "Бабушка"]),
    ("Телефон близкого: 89274813562", RU, False, ["89274813562"]),
    ("Яҡындың телефоны: 8 (347) 264-18-90. Өләсәйгә ярҙам кәрәк",
     f"{BA}. Өләсәйгә ярҙам кәрәк", False, ["8 (347) 264-18-90", "Өләсәйгә"]),
    ("Яҡындың телефоны: 8.927.318.24.65", BA, False, ["8.927.318.24.65"]),
    # номер набран с «. » внутри — продолжение номера, а не заметка (мобильный и городской)
    ("Телефон близкого: 8. 917 643 28 51. Встретить у подъезда",
     f"{RU}. Встретить у подъезда", False, ["8. 917 643 28 51", "Встретить"]),
    ("Яҡындың телефоны: 8. 347 264-18-90. Подъезд 2", f"{BA}. Подъезд 2", False, ["347 264-18-90", "Подъезд"]),
    # заметка из одних цифр — тоже продолжение номера («. 15.30»)
    ("Телефон близкого: 89173456789. 15.30", RU, False, ["89173456789", "15.30"]),
    # общий скруб в заметке: почта → <почта>
    ("Телефон близкого: 89173456789. Пишите на gulnara@mail.ru",
     f"{RU}. Пишите на <почта>", False, ["89173456789", "gulnara", "Пишите"]),
    # старое приложение: всё после «·» — номер, заметки нет; метка своего языка
    ("Заказ за близкого · +7 (917) 222-33-44", RU, False, ["+7 (917) 222-33-44"]),
    ("Яҡын кеше өсөн заказ · 8 927 111 22 33", BA, False, ["8 927 111 22 33"]),
    # номер в заметке (свой, на всякий случай): заметка уходит целиком, цифр не остаётся
    ("Телефон близкого: +7 917 482 19 37. Если не ответит — звоните мне 8-937-156-23-48",
     RU, True, ["+7 917 482 19 37", "8-937-156-23-48", "ответит"]),
    # случаи первой проверки безопасности (приёмка PR #173)
    ("Телефон близкого: 8 917 123. 45 67 мама. Встретить", RU, True, ["8 917 123", "45 67", "мама"]),
    ("Телефон близкого: 8 917. 1234567 мама", RU, True, ["1234567", "мама"]),
    ("Телефон близкого: +7 917 482 19 37. Или мне: 347 264 18 90", RU, True, ["347 264 18 90", "Или мне"]),
    ("Телефон близкого: +7 917 482 19 37. дом 2-64-18", RU, True, ["2-64-18", "64-18", "дом"]),
    ("Яҡындың телефоны: 89274813562. Если что 123-45-67", BA, True, ["123-45-67", "Если что"]),
    # повторная проверка: необычные разделители между цифрами (неразрывный пробел, тире,
    # перевод строки, «/»), в том числе номер САМОГО близкого ещё раз в заметке
    ("Телефон близкого: 89274813562. звоните мне 8\xa0937\xa0156\xa023\xa048",
     RU, True, ["8\xa0937\xa0156\xa023\xa048", "звоните"]),
    ("Телефон близкого: 89274813562. Или 8–917–765–43–21", RU, True, ["8–917–765–43–21", "Или"]),
    ("Яҡындың телефоны: 89274813562. Шылтыратығыҙ:\n8 917\n765 43 21", BA, True, ["765 43 21", "Шылтыратығыҙ"]),
    ("Телефон близкого: +7 917 482 19 37. Её номер ещё раз: 917/482/19/37", RU, True, ["917/482/19/37", "Её номер"]),
]

# Обычные заметки с цифрами: правило «5+ цифр подряд» их не трогает (буква или конец рвут цепочку).
ORDINARY_NOTES = ["2 сумки, 1 кресло", "Выезд в 8:30, обратно в 17:45", "3-4 человека", "Цена до 1 500 руб"]

# Любой разделитель между цифрами, кроме буквы, не спасает номер.
SEPARATORS = [",", "/", "_", "–", "—", "‑", "−", "－",
              "\xa0", " ", " ", "\n", "\t", " - ", ". "]

# Эти комментарии скрипт не трогает и не называет.
UNTOUCHED = [
    "Еду с ребёнком, нужно кресло",
    "",
    "телефон близкого: спрошу у вас в чате",          # регистр не тот — писал не старый клиент
    "Заказ за близкого. Позвоните заранее",            # новый сайт
    "Яҡын кеше өсөн заказ",                            # новое приложение / новый сайт по-башкирски
    "Заказ за близкого",
]

# Надпись есть, но не в начале (человек правил комментарий): текст не читаем — при записи
# каждая цепочка из 5+ цифр механически → «<телефон>». (было, станет, сколько заменено)
MANUAL = [
    ("Мой Телефон близкого: позвоню сам", "Мой Телефон близкого: позвоню сам", 0),
    ("Привет. Яҡындың телефоны: 8 917 000 11 22", "Привет. Яҡындың телефоны: <телефон>", 1),
    ("Еду с мамой. Заказ за близкого · 8 917 000 33 44", "Еду с мамой. Заказ за близкого · <телефон>", 1),
    ("Привет! Телефон близкого: 8\xa0917\xa0000\xa055\xa066, мой 8–937–000–77–88",
     "Привет! Телефон близкого: <телефон>, мой <телефон>", 2),
]
MANUAL_BEFORE = [m[0] for m in MANUAL]
MANUAL_AFTER = [m[1] for m in MANUAL]


@pytest.fixture(autouse=True)
def _allow_sqlite(monkeypatch):
    monkeypatch.setenv(scrub.ALLOW_SQLITE_ENV, "1")


def _no_phone_digits(text: str, phone: str) -> None:
    """В тексте нет ни самого номера, ни пяти его цифр подряд — в каком угодно написании."""
    assert phone not in text
    national = re.sub(r"\D", "", phone)[-10:]
    # Номера заявок и даты — разрешённые цифры вывода; без них цепочки не склеиваются с ними
    # через « · » и проверка не зависит от того, какой номер достался заявке в общей базе.
    allowed = re.sub(r"#\d+|\d\d\.\d\d\.\d{4} \d\d:\d\d", " | ", text)
    runs = [re.sub(r"\D", "", m.group()) for m in re.finditer(r"\d(?:[\W_]{0,3}\d)*", allowed)]
    for i in range(len(national) - 4):
        window = national[i:i + 5]
        assert not any(window in run for run in runs), f"в выводе цифры номера ({window}):\n{text}"


def _no_personal_text(out: str) -> None:
    for comment, _, _, secrets in OLD_ROWS:
        assert comment not in out
        for secret in secrets:
            assert secret not in out, f"в выводе личное: {secret!r}\n{out}"
            if len(re.sub(r"\D", "", secret)) >= 5:
                _no_phone_digits(out, secret)
    for before, after, _ in MANUAL:
        assert before not in out and after not in out
        for phone in re.findall(r"\d(?:[\W_]{0,3}\d){4,}", before):
            _no_phone_digits(out, phone)
    assert "Галия" not in out, "в выводе имя близкого"


@pytest.fixture
def seed(user_factory):
    """Кладёт заявки одного пассажира и убирает их за собой."""
    passenger = user_factory("Пассажир")
    made: list[int] = []
    with Session(engine) as s:
        assert scrub.find_rows(s) == [] and scrub.find_manual(s) == [], "в общей тестовой базе уже есть такие заявки"

    def put(comments, relative="Галия", status="active"):
        ids = []
        with Session(engine) as s:
            for c in comments:
                r = RideRequest(passenger_id=passenger["id"], from_city="Баймак", to_city="Уфа",
                                comment=c, created_at=WHEN, for_relative_name=relative, status=status)
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


def _line(out: str, rid: int) -> str:
    [line] = [ln for ln in out.splitlines() if f"заявка #{rid} " in ln]
    return line


@pytest.mark.parametrize("comment, expected, dropped, _secrets", OLD_ROWS)
def test_plan_old_comment(comment, expected, dropped, _secrets):
    plan = scrub.plan_comment(comment)
    assert plan.result == expected
    assert bool(plan.dropped) is dropped
    assert plan.note_len == (len(expected) - len(expected.split(". ", 1)[0]) - 2 if ". " in expected else 0)
    assert scrub.problems(comment, plan.result) == []
    assert scrub.plan_comment(plan.result) is None, "второй прогон снова нашёл бы заявку"
    assert not scrub.is_manual(plan.result)


@pytest.mark.parametrize("sep", SEPARATORS, ids=[f"U+{ord(s[0]):04X}x{len(s)}" for s in SEPARATORS])
def test_any_separator_between_digits_drops_the_note(sep):
    """Свой номер и номер самого близкого в заметке — с любым не-буквенным разделителем."""
    for note_phone in (f"8{sep}937{sep}156{sep}23{sep}48", f"917{sep}482{sep}19{sep}37"):
        plan = scrub.plan_comment(f"Телефон близкого: +7 917 482 19 37. Звоните мне {note_phone}")
        assert plan.result == RU and plan.dropped, (repr(sep), plan)
        assert scrub.mask_long_digits(f"Привет. Телефон близкого: {note_phone}")[1] >= 1


@pytest.mark.parametrize("note", ORDINARY_NOTES)
def test_ordinary_notes_with_numbers_stay(note):
    plan = scrub.plan_comment(f"Телефон близкого: 89274813562. {note}")
    assert plan.result == f"{RU}. {note}" and not plan.dropped
    assert scrub.mask_long_digits(note) == (note, 0)


@pytest.mark.parametrize("comment", UNTOUCHED + MANUAL_BEFORE)
def test_other_comments_are_not_cleaned(comment):
    assert scrub.plan_comment(comment) is None
    assert scrub.is_manual(comment) is (comment in MANUAL_BEFORE)


@pytest.mark.parametrize("before, after, count", MANUAL)
def test_manual_rows_get_only_digit_chains_masked(before, after, count):
    assert scrub.mask_long_digits(before) == (after, count)
    assert scrub.mask_long_digits(after) == (after, 0)
    assert scrub.is_manual(after)


def test_dry_run_changes_nothing_and_prints_no_personal_text(seed, capsys):
    old_ids = seed([c for c, *_ in OLD_ROWS[1:]])
    [no_name_id] = seed([OLD_ROWS[0][0]], relative=None, status="closed")
    other_ids = seed(UNTOUCHED)
    manual_ids = seed(MANUAL_BEFORE)

    assert scrub.main([]) == 0
    out = capsys.readouterr().out

    assert _comments([no_name_id] + old_ids) == [c for c, *_ in OLD_ROWS], "примерка изменила базу"
    assert _comments(other_ids) == UNTOUCHED and _comments(manual_ids) == MANUAL_BEFORE
    assert "Режим: ПРИМЕРКА" in out and "в базе ничего не изменено" in out
    assert f"Найдено заявок: {len(OLD_ROWS)}" in out
    assert f"Проверить руками: {len(MANUAL)}" in out
    note = OLD_ROWS[0][1].split(". ", 1)[1]
    assert _line(out, no_name_id) == (
        f"  заявка #{no_name_id} · 03.09.2026 13:15 по Уфе · закрыта · сайт · имени близкого нет → станет: "
        f"метка + заметка ({len(note)} символов)")
    for rid, (comment, expected, dropped, _) in zip(old_ids, OLD_ROWS[1:]):
        line = _line(out, rid)
        assert " · активна · " in line
        assert ("приложение" if comment.startswith(("Заказ", "Яҡын кеше")) else "сайт") in line
        assert "имени близкого нет" not in line
        assert ("метка + заметка" in line) is (". " in expected)
        assert ("только метка" in line) is (". " not in expected)
        assert ("заметку убираю" in line) is dropped
    for rid, (_, _, count) in zip(manual_ids, MANUAL):
        assert _line(out, rid).endswith(f"заменю цепочек цифр: {count}" if count
                                        else "длинных цепочек цифр нет, не меняю")
    for rid in other_ids:
        assert f"#{rid} " not in out
    _no_personal_text(out)


def test_apply_cleans_old_rows_masks_manual_and_is_idempotent(seed, capsys):
    old_ids = seed([c for c, *_ in OLD_ROWS])
    other_ids = seed(UNTOUCHED)
    manual_ids = seed(MANUAL_BEFORE)
    masked = sum(1 for *_, n in MANUAL if n)

    assert scrub.main(["--apply"]) == 0
    out = capsys.readouterr().out
    assert _comments(old_ids) == [e for _, e, *_ in OLD_ROWS]
    assert _comments(other_ids) == UNTOUCHED, "скрипт тронул чужие комментарии"
    assert _comments(manual_ids) == MANUAL_AFTER
    assert (f"Изменено заявок: {len(OLD_ROWS) + masked} — по старой надписи: {len(OLD_ROWS)}, "
            f"цифры заменены в «Проверить руками»: {masked}") in out
    for rid, (_, _, count) in zip(manual_ids, MANUAL):
        assert _line(out, rid).endswith(f"цифры заменены: {count}" if count else "длинных цепочек цифр нет, не меняю")
    assert "подходящих заявок осталось 0" in out and "Готово." in out
    _no_personal_text(out)

    # второй запуск — и с записью, и примеркой — находит 0 и ничего не меняет
    assert scrub.main(["--apply"]) == 0
    again = capsys.readouterr().out
    assert "Найдено заявок: 0" in again and "Изменено заявок: 0" in again
    assert scrub.main([]) == 0
    again = capsys.readouterr().out
    assert "Найдено заявок: 0" in again and f"Проверить руками: {len(MANUAL)}" in again
    assert again.count("длинных цепочек цифр нет, не меняю") == len(MANUAL)
    assert _comments(old_ids) == [e for _, e, *_ in OLD_ROWS]
    assert _comments(manual_ids) == MANUAL_AFTER


def test_apply_masks_manual_rows_even_without_old_rows(seed, capsys):
    ids = seed(MANUAL_BEFORE[1:2])
    assert scrub.main(["--apply"]) == 0
    out = capsys.readouterr().out
    assert "Изменено заявок: 1 — по старой надписи: 0, цифры заменены в «Проверить руками»: 1" in out
    assert _comments(ids) == MANUAL_AFTER[1:2]


def test_old_server_code_counts_but_does_not_clean(seed, capsys, monkeypatch):
    """Код сервера от 04.07 без scrub_text: примерка — номера и даты, запись — отказ."""
    ids = seed([OLD_ROWS[0][0], OLD_ROWS[8][0]])
    manual_ids = seed(MANUAL_BEFORE[1:2])
    monkeypatch.setattr(scrub, "scrub_text", None)

    assert scrub.main([]) == 0
    out = capsys.readouterr().out
    assert "Найдено заявок: 2" in out and "Проверить руками: 1" in out
    assert _line(out, ids[0]) == f"  заявка #{ids[0]} · 03.09.2026 13:15 по Уфе · активна · сайт"
    assert _line(out, ids[1]) == f"  заявка #{ids[1]} · 03.09.2026 13:15 по Уфе · активна · приложение"
    assert _line(out, manual_ids[0]).endswith("заменю цепочек цифр: 1")
    assert "→ станет" not in out and "после выкладки" in out
    _no_personal_text(out)

    assert scrub.main(["--apply"]) == 1
    out = capsys.readouterr().out
    assert "СТОП: чистка — после выкладки" in out
    assert _comments(ids) == [OLD_ROWS[0][0], OLD_ROWS[8][0]]
    assert _comments(manual_ids) == MANUAL_BEFORE[1:2]


def test_refuses_sqlite_without_the_switch(seed, capsys, monkeypatch):
    """Не прочитан .env → SQLite по умолчанию: отказ до любого чтения базы."""
    if engine.url.get_backend_name() != "sqlite":
        pytest.skip("отказ касается только SQLite; этот прогон — на PostgreSQL")
    ids = seed([OLD_ROWS[1][0]])
    monkeypatch.delenv(scrub.ALLOW_SQLITE_ENV)
    assert scrub.main(["--apply"]) == 1
    assert "СТОП: скрипт смотрит в файл SQLite" in capsys.readouterr().out
    assert _comments(ids) == [OLD_ROWS[1][0]]


def test_apply_stops_when_final_check_fails(seed, capsys, monkeypatch):
    """Итог не прошёл проверку (ошибка скрипта) → СТОП с номерами, база как была."""
    ids = seed([OLD_ROWS[0][0]])
    manual_ids = seed(MANUAL_BEFORE[1:2])
    monkeypatch.setattr(scrub, "plan_comment", lambda c: scrub.Plan(c) if scrub.match_old(c) else None)
    assert scrub.main(["--apply"]) == 1
    out = capsys.readouterr().out
    assert f"СТОП: 1 заявок не прошли итоговую проверку (номера: {ids[0]})" in out
    assert _comments(ids) == [OLD_ROWS[0][0]]
    assert _comments(manual_ids) == MANUAL_BEFORE[1:2]
    _no_personal_text(out)


def test_apply_rolls_back_when_after_check_fails(seed, capsys, monkeypatch):
    """Запись прошла, но сверка в той же транзакции не сошлась → откат всей транзакции."""
    ids = seed([c for c, *_ in OLD_ROWS[:2]])
    manual_ids = seed(MANUAL_BEFORE[1:2])
    # «чистка» оставляет надпись старого сайта, а итоговую проверку выключили:
    # после записи подходящие заявки остаются — сверка обязана откатить ВСЁ.
    monkeypatch.setattr(scrub, "plan_comment", lambda c: scrub.Plan(c + " ") if scrub.match_old(c) else None)
    monkeypatch.setattr(scrub, "problems", lambda original, cleaned: [])
    assert scrub.main(["--apply"]) == 1
    out = capsys.readouterr().out
    assert "СТОП: сверка после записи не сошлась" in out and "Всё откатилось" in out
    assert "цифры заменены" not in out, "строки «заменены» печатаются только после записи"
    assert _comments(ids) == [c for c, *_ in OLD_ROWS[:2]], "откат не сработал"
    assert _comments(manual_ids) == MANUAL_BEFORE[1:2], "откат не сработал"


def test_unexpected_error_prints_only_its_type(seed, capsys, monkeypatch):
    ids = seed([OLD_ROWS[1][0]])

    def boom(*_a, **_k):
        raise RuntimeError(OLD_ROWS[1][0])      # текст ошибки несёт номер — печатать его нельзя

    monkeypatch.setattr(scrub, "find_manual", boom)
    assert scrub.main(["--apply"]) == 1
    out = capsys.readouterr().out
    assert "СТОП: ошибка RuntimeError. Всё откатилось" in out and "Traceback" not in out
    _no_personal_text(out)
    assert _comments(ids) == [OLD_ROWS[1][0]]


def test_commit_failure_says_unknown(seed, capsys, monkeypatch):
    ids = seed([OLD_ROWS[1][0]])

    class FailingCommit(scrub.Session):
        def commit(self):
            raise ConnectionError("связь оборвалась")

    monkeypatch.setattr(scrub, "Session", FailingCommit)
    assert scrub.main(["--apply"]) == 1
    out = capsys.readouterr().out
    assert "СТОП: ошибка ConnectionError в момент записи — неизвестно, записалось ли" in out
    assert "Запусти примерку" in out
    assert _comments(ids) == [OLD_ROWS[1][0]]


def test_new_row_during_apply_asks_to_run_again(seed, capsys, monkeypatch):
    """Пока шла чистка, старый клиент записал ещё одну такую заявку → «ЕЩЁ РАЗ», код 2."""
    [first] = seed([OLD_ROWS[1][0]])
    late: list[int] = []

    class LateRow(scrub.Session):
        def commit(self):
            super().commit()
            if not late:
                late.extend(seed([OLD_ROWS[3][0]]))

    monkeypatch.setattr(scrub, "Session", LateRow)
    assert scrub.main(["--apply"]) == 2
    out = capsys.readouterr().out
    assert "Изменено заявок: 1" in out and "ЕЩЁ РАЗ:" in out and "СТОП" not in out
    assert _comments([first]) == [RU]
    monkeypatch.undo()
    monkeypatch.setenv(scrub.ALLOW_SQLITE_ENV, "1")
    assert scrub.main(["--apply"]) == 0
    assert _comments(late) == [BA]


def test_waits_for_a_busy_row_at_most_five_seconds(seed, capsys):
    """PostgreSQL: строку держит другая операция → не висим, через 5 с «СТОП», база как была."""
    if engine.url.get_backend_name() != "postgresql":
        pytest.skip("блокировки строк и lock_timeout есть только в PostgreSQL")
    ids = seed([OLD_ROWS[1][0]])
    with Session(engine) as other:
        other.exec(select(RideRequest).where(RideRequest.id == ids[0]).with_for_update()).one()
        started = time.monotonic()
        assert scrub.main(["--apply"]) == 1
        waited = time.monotonic() - started
        other.rollback()
    out = capsys.readouterr().out
    assert "СТОП: заявки заняты другой операцией дольше 5 секунд" in out, out
    assert 4 < waited < 30, waited
    assert _comments(ids) == [OLD_ROWS[1][0]]


def test_runs_as_a_command_like_on_the_server(seed, tmp_path):
    """Так его запустит Александр: `python -u ops/scrub_relative_phones.py` — отдельным процессом."""
    [rid] = seed([OLD_ROWS[2][0]])
    env = {**os.environ, "PYTHONIOENCODING": "utf-8", scrub.ALLOW_SQLITE_ENV: "1"}
    proc = subprocess.run([sys.executable, "-u", str(SCRIPT)], cwd=tmp_path, env=env, capture_output=True,
                          text=True, encoding="utf-8", errors="replace", timeout=120)
    assert proc.returncode == 0, proc.stdout + proc.stderr[-2000:]
    assert "Режим: ПРИМЕРКА" in proc.stdout and "Найдено заявок: 1" in proc.stdout
    assert f"заявка #{rid} " in proc.stdout
    _no_personal_text(proc.stdout + proc.stderr)
    assert _comments([rid]) == [OLD_ROWS[2][0]]
