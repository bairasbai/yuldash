"""Модерация текста: телефоны, мессенджеры, грубость.

Главное, что проверяем, — не «ловит ли», а «НЕ ловит ли лишнего». Ложное срабатывание на
честном человеке («буду через 10 минут, подъезд 89») хуже пропущенного нарушителя, потому что
бьёт по тем, кто ничего не нарушал, и приучает не читать плашки.
"""
import pytest

from app.antifraud import (
    MESSAGE_FLAG_ABUSE,
    MESSAGE_FLAG_CONTACT,
    MESSAGE_FLAG_WARN,
    abuse_flag,
    contact_flag,
    moderate_text,
)


# ------------------------------ телефоны: ловим ------------------------------
@pytest.mark.parametrize("text", [
    "89171234567",
    "8 917 123 45 67",
    "+7 917 123-45-67",
    "+79171234567",
    "8-917-123-45-67",
    "8(917)1234567",
    "звони 8 917 123 45 67 если что",
    "917 123 45 67",              # без кода страны, как часто пишут
    "мой номер 9171234567",
])
def test_phone_caught(text):
    assert contact_flag(text) == MESSAGE_FLAG_CONTACT, text


# ------------------------------ телефоны: НЕ ловим ------------------------------
@pytest.mark.parametrize("text", [
    "буду через 10 минут, подъезд 89",
    "дом 5, подъезд 4, этаж 3",
    "цена 800 руб, 3 места, 2 сумки",
    "код посадки 4821",
    "выезжаем в 10:30",
    "машина а123бв 102",
    "стоимость 1500",
    "нас 2 взрослых и 1 ребёнок",
    "квартира 117, домофон 117",
    "приеду 03.08 в 14:00",
    "",
    "   ",
])
def test_phone_not_caught(text):
    assert contact_flag(text) == "", text


# ------------------------------ мессенджеры ------------------------------
@pytest.mark.parametrize("text", [
    "напиши мне в вотсап",
    "скинь в тг",
    "давай в телеграм",
    "пиши на whatsapp",
    "мой вайбер",
    "@ivan_petrov",
])
def test_messenger_caught(text):
    assert contact_flag(text) == MESSAGE_FLAG_CONTACT, text


@pytest.mark.parametrize("text", [
    # само упоминание мессенджера — не улика: приложение и логинит через Telegram
    "я вошёл через телеграм",
    "телеграм не работает",
    "вход через telegram удобнее",
    "почта ivan@mail.ru",           # хвост почты — не ник
])
def test_messenger_not_caught(text):
    assert contact_flag(text) == "", text


# ------------------------------ грубость: ловим, включая обходы ------------------------------
@pytest.mark.parametrize("text", [
    "ты мудак",
    "какого хуя",
    "пиздец опоздал",
    "бля забыл",
    "сука опять",
    "ты дебил",
    "х у й",                        # растянуто пробелами
    "х.у.й",                        # растянуто точками
    "МУДАК",                        # регистр
    "myдак",                        # латиница вперемешку
    "мyдaк",
])
def test_abuse_caught(text):
    assert abuse_flag(text) == MESSAGE_FLAG_ABUSE, text


# ------------------------------ грубость: честные слова НЕ ловим ------------------------------
@pytest.mark.parametrize("text", [
    "возьми с собой себя и вещи",
    "требовать деньги вперёд не буду",
    "не употребляю за рулём",
    "хлебать чай будем в дороге",
    "страховка оформлена",
    "блик на стекле мешает",
    "гнидник — это не про нас",
    "сосиски в тесте купил",
    "художник рисует",
    "нужен грузчик",
    "подсосед по даче",
    "скотч есть?",
    "поеду через Учалы",
    "три поросёнка",
])
def test_abuse_not_caught(text):
    assert abuse_flag(text) == "", text


# ------------------------------ общая точка входа и приоритеты ------------------------------
def test_moderate_priority_phishing_over_contact():
    # фишинг опаснее увода сделки — он забирает деньги прямо сейчас
    t = "переведи на другой номер 8 917 123 45 67"
    assert moderate_text(t) == MESSAGE_FLAG_WARN


def test_moderate_priority_contact_over_abuse():
    t = "звони 89171234567 мудак"
    assert moderate_text(t) == MESSAGE_FLAG_CONTACT


def test_moderate_pooling_allows_phone():
    """Попутки: соседи меняются номерами — это норма, комиссии там нет."""
    t = "мой номер 8 917 123 45 67"
    assert moderate_text(t, check_contact=True) == MESSAGE_FLAG_CONTACT
    assert moderate_text(t, check_contact=False) == ""


def test_moderate_pooling_still_catches_abuse():
    """Но грубость и фишинг ловим везде, в попутках тоже."""
    assert moderate_text("ты мудак", check_contact=False) == MESSAGE_FLAG_ABUSE
    assert moderate_text("скажи код из смс", check_contact=False) == MESSAGE_FLAG_WARN


@pytest.mark.parametrize("text", ["", None, "   ", "буду через 10 минут"])
def test_moderate_clean(text):
    assert moderate_text(text) == ""
