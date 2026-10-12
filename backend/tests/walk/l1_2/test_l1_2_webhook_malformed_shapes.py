"""leaf-1.2 — F7 (независимое ревью Opus 5.5, 2026-10-02): вебхук с валидным JSON неожиданной
формы (не словарь, `object` не словарь, числовой id) должен быть no-op, а не падать 500.

Карточка обещает «телу не доверяем» — но доверие «не доверяем ЗНАЧЕНИЯМ» не означает «доверяем
ФОРМЕ». Провайдер (или кто угодно под его видом) может прислать синтаксически валидный, но
неожиданно устроенный JSON — это такое же «тело», которому нельзя доверять.
"""
import pytest

from app.config import settings


@pytest.mark.parametrize("payload", [
    [],
    {"object": "not-a-dict"},
    {"object": ["also", "not", "a", "dict"]},
    {"object": {"id": 12345}},
    {"object": {"id": None}},
    "a bare json string",
    42,
])
def test_malformed_but_valid_json_shapes_are_a_noop(client, monkeypatch, payload):
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    response = client.post("/payments/yookassa/webhook", json=payload)
    assert response.status_code == 200, response.text
    assert response.json() == {"ok": True}
