"""Авто-проверка прав спрашивает не только «действующие ли», но и «его ли».

Сервер сам читает фото прав (OCR) и ставит заявке вердикт: pass / needs_human / reject.
Вердикт `pass` при включённом флаге `driver_autoapprove_enabled` означает автоматический
допуск к перевозке людей.

Проверялось при этом только само удостоверение: есть ли текст, нашёлся ли номер, не истёк ли
срок. Чьё оно — не спрашивалось вовсе. Скачанное из интернета фото ЧУЖИХ прав набирало
максимальный балл 1.0 и получало `pass` (проверено пробой, аудит 2026-08-14, волна 67).

Сверяем по дате рождения из заявки: она есть отдельным полем, печатается на правах и уже
вытаскивается из распознанного текста. ФИО не годится — в заявке таксиста его нет вовсе,
а имя профиля человек пишет как хочет («Марат», «Марат Такси»).

Не сошлось — отдаём ЧЕЛОВЕКУ, а не отказываем: OCR путает цифры и плохо читает мятые права.
Отказ по подозрению обиднее лишней минуты модератора.
"""
from __future__ import annotations

from datetime import date

import pytest

from app import driver_check

MY_BIRTH = date(1990, 5, 20)

MINE = (
    "ВОДИТЕЛЬСКОЕ УДОСТОВЕРЕНИЕ\n"
    "20.05.1990\n"                 # моя дата рождения
    "12 34 567890\n"
    "15.03.2020 15.03.2030\n"
)
SOMEONE_ELSES = (
    "ВОДИТЕЛЬСКОЕ УДОСТОВЕРЕНИЕ\n"
    "01.01.1975\n"                 # чужая дата рождения
    "12 34 567890\n"
    "15.03.2020 15.03.2030\n"
)
GARBAGE = "фото кота"


@pytest.fixture
def ocr(monkeypatch):
    """Подменяем распознавание: ключа OCR в тестах нет, а проверять надо решение, а не Яндекс."""
    def _use(text: str):
        monkeypatch.setattr(driver_check, "_ocr_text", lambda img, ext: text)
        monkeypatch.setattr(driver_check, "get_storage",
                            lambda: type("S", (), {"load": staticmethod(lambda k: b"x")})())
    return _use


def _check(birth_date=MY_BIRTH):
    return driver_check.check_driver_docs("/secure/docs/a.jpg", "/secure/docs/car.jpg",
                                          birth_date=birth_date)


def test_чужие_права_автоматически_не_одобряем(ocr):
    """Главная история: документ настоящий и действующий, но не его."""
    ocr(SOMEONE_ELSES)
    res = _check()
    assert res["result"] == "needs_human"
    assert "birth_date_mismatch" in res["data"]["reasons"]


def test_свои_права_проходят_как_раньше(ocr):
    """Страховка от перестраховки: честному водителю проверка не мешает."""
    ocr(MINE)
    res = _check()
    assert res["result"] == "pass"
    assert res["data"]["birth_date_match"] is True


def test_без_даты_рождения_ведём_себя_как_прежде(ocr):
    """Документы шлют и до заявки — тогда сверять нечем, и вердикт прежний."""
    ocr(SOMEONE_ELSES)
    res = _check(birth_date=None)
    assert res["result"] == "pass"
    assert res["data"].get("birth_date_checked") is None


def test_мусор_вместо_прав_по_прежнему_отклоняем(ocr):
    """Сверка личности не должна проглотить проверку «это вообще права?»."""
    ocr(GARBAGE)
    res = _check()
    assert res["result"] == "reject"
    assert "not_a_license" in res["data"]["reasons"]


def test_несошедшаяся_дата_это_не_отказ_а_человек(ocr):
    """OCR путает цифры и плохо читает мятые права. Отказ по подозрению обиднее
    лишней минуты модератора — поэтому именно needs_human, а не reject."""
    ocr(SOMEONE_ELSES)
    assert _check()["result"] != "reject"
