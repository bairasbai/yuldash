"""Заглушка вместо номера ОРД — это не маркировка.

Запрет «без erid в эфир не идём» в коде был, но проверял только «непусто». В демо-данных
жила строка «ожидает присвоения», и объявление уходило в эфир с ней вместо номера —
юридически это тот же показ без маркировки, только выглядит аккуратнее
(ст. 14.3 КоАП — до 500 000 ₽ юрлицу).
"""
from app.routers.ads import erid_ok


def test_real_erid_passes():
    assert erid_ok("2VtzqwH7uMn")
    assert erid_ok("erid-test")
    assert erid_ok("2Ru_XmT1z5k")


def test_placeholder_and_junk_do_not_pass():
    assert not erid_ok("")
    assert not erid_ok("   ")
    assert not erid_ok("ожидает присвоения")   # заглушка из демо-данных
    assert not erid_ok("нет")                  # кириллица вообще не формат ОРД
    assert not erid_ok("2Vt")                  # обрывок: настоящий номер длиннее
    assert not erid_ok("2Vtzqw H7uMn")         # пробел внутри
