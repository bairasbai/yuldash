"""Журнал HTTPX не выдаёт секреты из строки запроса (18.10.2026, задача «httpx и sms.ru»).

HTTPX пишет на INFO полный адрес каждого исходящего запроса. Под uvicorn корневой логгер стоит
на WARNING, и строка не печатается — но стоит включить INFO целиком, и в журнал уходило:
`api_id` sms.ru, номер, код входа из СМС, текст SOS с координатами, `apikey` и точки геокодера.
Проверяем настоящие `send_sms`/`send_text`: подменён только транспорт (сокет не открывается).
"""
import logging

import httpx
import pytest

from app import services
from app.config import settings

API_ID = "QA_SYNTH_SMSRU_API_ID"
PHONE = "79990001122"
CODE = "4817"
SOS = "SOS: Айгуль, точка 54.123456,58.654321"


def _transport(request):
    if request.url.host == "sms.ru":
        return httpx.Response(200, json={"sms": {PHONE: {"status_code": 100}}})
    return httpx.Response(200, json={})


@pytest.fixture
def smsru(monkeypatch):
    monkeypatch.setattr(settings, "sms_provider", "smsru")
    monkeypatch.setattr(settings, "sms_ru_api_id", API_ID)
    monkeypatch.setattr(settings, "sms_from", "")
    with httpx.Client(transport=httpx.MockTransport(_transport), trust_env=False) as client:
        monkeypatch.setattr(httpx, "get", client.get)
        yield client


def _httpx_records(caplog):
    return [r for r in caplog.records if r.name == "httpx" and r.getMessage().startswith("HTTP Request:")]


def _assert_no_secret(caplog, *secrets):
    for record in caplog.records:
        text = record.getMessage() + repr(record.args)
        for secret in secrets:
            assert secret not in text, f"секрет в журнале: {record.name}"
    for secret in secrets:
        assert secret not in caplog.text


def test_sms_login_code_and_api_id_do_not_reach_the_log(smsru, caplog):
    with caplog.at_level(logging.INFO, logger="httpx"), caplog.at_level(logging.INFO, logger="yuldash"):
        services.send_sms(PHONE, CODE)
    _assert_no_secret(caplog, API_ID, PHONE, f"kod+{CODE}", f"kod {CODE}")
    records = _httpx_records(caplog)
    assert len(records) == 1, "строка HTTPX о запросе остаётся — без значений"
    assert ("https://sms.ru/sms/send?api_id=<скрыто>&to=<скрыто>&msg=<скрыто>&json=<скрыто>"
            in records[0].getMessage())
    assert '"HTTP/1.1 200 OK"' in records[0].getMessage(), "метод, хост и код ответа сохраняются"


def test_sos_text_with_coordinates_does_not_reach_the_log(smsru, caplog):
    with caplog.at_level(logging.INFO, logger="httpx"), caplog.at_level(logging.INFO, logger="yuldash"):
        assert services.send_text(PHONE, SOS) is True
    _assert_no_secret(caplog, API_ID, PHONE, "54.123456", "58.654321", "%D0%90%D0%B9")
    assert len(_httpx_records(caplog)) == 1


def test_any_outbound_query_is_reduced_to_names(smsru, caplog):
    with caplog.at_level(logging.INFO, logger="httpx"):
        smsru.get("https://geocode-maps.yandex.ru/1.x/?apikey=QA_SYNTH_GEO&geocode=58.65,54.12&format=json&flag")
        smsru.get("https://example.invalid/health")
    messages = [r.getMessage() for r in _httpx_records(caplog)]
    assert "https://geocode-maps.yandex.ru/1.x/?apikey=<скрыто>&geocode=<скрыто>&format=<скрыто>&flag" in messages[0]
    assert "https://example.invalid/health " in messages[1], "адрес без строки запроса не меняется"
    _assert_no_secret(caplog, "QA_SYNTH_GEO", "58.65", "54.12")


def test_telegram_bot_key_in_path_is_still_masked(caplog):
    with httpx.Client(transport=httpx.MockTransport(_transport), trust_env=False) as client:
        with caplog.at_level(logging.INFO, logger="httpx"):
            client.post("https://api.telegram.org/botQA_SYNTH_TG:KEY/sendMessage?chat_id=QA_SYNTH_CHAT")
    message = _httpx_records(caplog)[0].getMessage()
    assert "https://api.telegram.org/bot<скрыто>/sendMessage?chat_id=<скрыто>" in message
    _assert_no_secret(caplog, "QA_SYNTH_TG", "QA_SYNTH_CHAT")


def test_filter_is_linear_on_a_hostile_line(caplog):
    """Строка из тысяч «http://» без «?» не должна жечь процессор (было ~30 с на 140 КБ)."""
    import time
    record = logging.LogRecord("httpx", logging.INFO, __file__, 1, "GET " + "http://" * 20000, (), None)
    started = time.perf_counter()
    for f in logging.getLogger("httpx").filters:
        f.filter(record)
    assert time.perf_counter() - started < 0.5


def test_provider_error_text_with_the_url_is_scrubbed(smsru, monkeypatch, caplog):
    """Ошибка httpx вида HTTPStatusError несёт полный адрес — в журнал `yuldash` он уходит очищенным."""
    def failing_get(url, params=None, **kwargs):
        request = httpx.Request("GET", url, params=params)
        raise httpx.HTTPStatusError(f"Server error '502 Bad Gateway' for url '{request.url}'",
                                    request=request, response=httpx.Response(502, request=request))
    monkeypatch.setattr(httpx, "get", failing_get)
    with caplog.at_level(logging.INFO, logger="yuldash"):
        services.send_sms(PHONE, CODE)
        assert services.send_text(PHONE, SOS) is False
    warnings = [r.getMessage() for r in caplog.records if r.name == "yuldash" and "smsru error" in r.getMessage()]
    assert len(warnings) == 2, "диагностика ошибки остаётся"
    for message in warnings:
        assert "502 Bad Gateway" in message
        for secret in (API_ID, PHONE, f"kod+{CODE}", "54.123456"):
            assert secret not in message
