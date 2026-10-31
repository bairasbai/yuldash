from app.errors import herr


def test_error_without_code_keeps_legacy_detail():
    error = herr(409, "Ошибка", "Хата")
    assert error.status_code == 409
    assert error.detail == {"ru": "Ошибка", "ba": "Хата"}


def test_error_carries_machine_code_and_extra_without_replacing_translation():
    error = herr(403, "Выключено", "Һүндерелгән", code="login_method_disabled", method="sms")
    assert error.detail == {
        "ru": "Выключено", "ba": "Һүндерелгән", "code": "login_method_disabled", "method": "sms",
    }
