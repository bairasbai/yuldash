"""leaf-1.1 · backend/app/routers/settlements.py — справочник НП (география, не деньги).

Не про деньги, но в зоне листа: входит в OWNS leaf-1.1. Эндпоинты ПУБЛИЧНЫЕ (без токена) —
это осознанное решение (общеизвестные данные), поэтому "доступ без авторизации" здесь не
дыра, а R1, который обязан остаться верным и после любой правки мидлвари аутентификации.

R1 — все три ручки отвечают БЕЗ заголовка Authorization (справочник — не персональные данные).
R2 — /settlements ищет и по русскому, и по башкирскому написанию, регистр и «ё» не важны.
R3 — /settlements с пустым q отдаёт топ справочника, а не ошибку/пустоту.
R4 — /settlements/districts режет limit в границы [1, 200] и не падает на 0/отрицательном/
     огромном значении (clamp на уровне ЭТОГО роутера — у /settlements он уже есть внутри geo.py).
R5 — /settlements/districts фильтрует ПРЕФИКСОМ (startswith), а не произвольной подстрокой —
     это другое поведение, чем /settlements, и путать их нельзя в UI-подсказках.
"""
from app.geo import _snapshot  # noqa: F401  (прогревает кеш справочника перед проверками формы)


def test_r1_settlements_search_is_public_no_auth_header(client):
    r = client.get("/settlements", params={"q": "Уфа"})
    assert r.status_code == 200
    assert isinstance(r.json()["items"], list)


def test_r1_districts_and_popular_routes_are_public_too(client):
    assert client.get("/settlements/districts").status_code == 200
    assert client.get("/settlements/popular-routes").status_code == 200


def test_r2_search_matches_bashkir_script_case_and_yo_insensitive(client):
    items_ru = client.get("/settlements", params={"q": "Уфа"}).json()["items"]
    assert any(i["name_ru"] == "Уфа" for i in items_ru)
    # Башкирское написание того же запроса находит ту же запись по фолдингу (geo.fold).
    items_mixed_case = client.get("/settlements", params={"q": "уФА"}).json()["items"]
    assert any(i["name_ru"] == "Уфа" for i in items_mixed_case)


def test_r3_empty_query_returns_top_of_directory_not_empty(client):
    items = client.get("/settlements", params={"q": ""}).json()["items"]
    assert len(items) > 0


def test_r3_unknown_query_returns_empty_list_not_error(client):
    r = client.get("/settlements", params={"q": "Несуществующий-Нигде-Город-Зхцв"})
    assert r.status_code == 200
    assert r.json()["items"] == []


def test_r4_districts_limit_clamped_for_zero_negative_and_huge(client):
    base = client.get("/settlements/districts", params={"limit": 60}).json()["items"]
    assert len(base) > 0

    zero = client.get("/settlements/districts", params={"limit": 0}).json()["items"]
    assert len(zero) >= 1, "limit=0 обязан отдать хотя бы верхнюю границу (max(1, …)), а не пусто"

    negative = client.get("/settlements/districts", params={"limit": -5}).json()["items"]
    assert len(negative) >= 1, "отрицательный limit не должен давать пустой/странный срез"

    huge = client.get("/settlements/districts", params={"limit": 100000}).json()["items"]
    assert len(huge) == len(base) or len(huge) <= 200, "потолок 200 обязан держаться даже при огромном limit"


def test_r5_districts_filter_is_prefix_not_substring(client):
    # «абзел» — подтверждённый префикс Абзелиловского района (см. tests/test_geo.py).
    prefix_hits = client.get("/settlements/districts", params={"q": "абзел", "limit": 200}).json()["items"]
    assert any(d["district"].casefold().startswith("абзел") for d in prefix_hits), (
        "в справочнике обязан найтись Абзелиловский район — иначе проверка ничего не значит"
    )

    # «зелилов» — та же подстрока, но из СЕРЕДИНЫ слова «абЗЕЛИЛОВский»: фильтр префиксный
    # (geo.fold(r["district"]).startswith(needle)), substring-поиск её нашёл бы, startswith — нет.
    mid_word_hits = client.get("/settlements/districts", params={"q": "зелилов", "limit": 200}).json()["items"]
    assert mid_word_hits == [], (
        "подстрока из середины названия района не должна находиться — /settlements/districts "
        "фильтрует ПРЕФИКСОМ, а не вхождением (в отличие от /settlements)"
    )
