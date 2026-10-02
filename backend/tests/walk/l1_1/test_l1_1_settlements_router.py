"""leaf-1.1 · backend/app/routers/settlements.py — справочник НП (география, не деньги).

Не про деньги, но в зоне листа: входит в OWNS leaf-1.1. Эндпоинты ПУБЛИЧНЫЕ (без токена) —
это осознанное решение (общеизвестные данные), поэтому "доступ без авторизации" здесь не
дыра, а R1, который обязан остаться верным и после любой правки мидлвари аутентификации.

R1 — все три ручки отвечают БЕЗ заголовка Authorization (справочник — не персональные данные).
R2 — /settlements ищет и по русскому, и по БАШКИРСКОМУ написанию (настоящий башкирский
     запрос, не просто другой регистр латиницы кириллицы), регистр не важен.
R3 — /settlements с пустым q отдаёт топ справочника, а не ошибку/пустоту.
R4 — /settlements/districts режет limit в границы [1, 200]: ПРОВЕРЕНО численно на каждой
     границе (0→1, отрицательный→1, огромный→ровно 200 на подложных 500 районах — на настоящих
     ~50 районах РБ потолок 200 в принципе недостижим, поэтому /districts здесь подменяется).
R5 — /settlements/districts фильтрует ПРЕФИКСОМ (startswith), а не произвольной подстрокой —
     это другое поведение, чем /settlements, и путать их нельзя в UI-подсказках.

Исправлено по замечанию независимого ревью Opus (2026-10-02): прежняя редакция R4 проверяла
«huge» и «negative» ничего не доказывающими утверждениями (`len(huge) <= 200` верно для ЛЮБОГО
ответа, раз районов в справочнике меньше 200; `rows[:-5]` на несрезанном списке тоже не пуст).
R2 проверял только регистр латинских букв кириллицы, а не настоящий башкирский текст.
"""
from app.geo import _snapshot  # noqa: F401  (прогревает кеш справочника перед проверками формы)


def test_r1_settlements_search_is_public_no_auth_header(client):
    r = client.get("/settlements", params={"q": "Уфа"})
    assert r.status_code == 200
    assert isinstance(r.json()["items"], list)


def test_r1_districts_and_popular_routes_are_public_too(client):
    assert client.get("/settlements/districts").status_code == 200
    assert client.get("/settlements/popular-routes").status_code == 200


def test_r2_search_matches_real_bashkir_script_not_just_case(client):
    items_ru = client.get("/settlements", params={"q": "Уфа"}).json()["items"]
    assert any(i["name_ru"] == "Уфа" for i in items_ru)
    # «өфө» — настоящее башкирское название Уфы (подтверждено в tests/test_geo.py), не просто
    # другой регистр того же кириллического написания.
    items_ba = client.get("/settlements", params={"q": "өфө"}).json()["items"]
    assert any(i["name_ru"] == "Уфа" for i in items_ba), (
        "поиск по настоящему башкирскому названию не нашёл Уфу — R2 проверялся бы только регистром"
    )
    # Регистр и смешанный раскладки — отдельно от предыдущего, тоже должны работать.
    items_mixed_case = client.get("/settlements", params={"q": "уФА"}).json()["items"]
    assert any(i["name_ru"] == "Уфа" for i in items_mixed_case)


def test_r3_empty_query_returns_top_of_directory_not_empty(client):
    items = client.get("/settlements", params={"q": ""}).json()["items"]
    assert len(items) > 0


def test_r3_unknown_query_returns_empty_list_not_error(client):
    r = client.get("/settlements", params={"q": "Несуществующий-Нигде-Город-Зхцв"})
    assert r.status_code == 200
    assert r.json()["items"] == []


def test_r4_districts_limit_clamps_zero_and_negative_to_exactly_one(client):
    """limit=0 и limit=-5 обязаны вести себя ОДИНАКОВО: `max(1, min(limit, 200))` даёт 1 в обоих
    случаях. Числа проверяем ТОЧНО (не «>= 1»): пустой срез (`rows[:0]`) и срез с хвоста
    (`rows[:-5]`) оба дают «не пусто» случайно, но не по единице — слабое `>= 1` этого не ловит."""
    zero = client.get("/settlements/districts", params={"limit": 0}).json()["items"]
    assert len(zero) == 1, f"limit=0 обязан дать РОВНО 1 запись (max(1, min(0,200))), а не {len(zero)}"

    negative = client.get("/settlements/districts", params={"limit": -5}).json()["items"]
    assert len(negative) == 1, (
        f"limit=-5 дал {len(negative)} записей — похоже на необрезанный `rows[:-5]` "
        "(срез с хвоста на несрезанном списке тоже не пуст, но это не кламп)"
    )


def test_r4_districts_limit_caps_at_200_even_when_the_directory_is_huge(client, monkeypatch):
    """На настоящих ~50 районах РБ потолок 200 недостижим в принципе — подменяем справочник
    районов 500 подложными строками, чтобы потолок было ЧЕМ проверить."""
    подложные_районы = [{"district": f"Тестовый район {i}", "region": "РБ", "settlements": 1}
                        for i in range(500)]
    monkeypatch.setattr("app.routers.settlements.geo.districts_payload", lambda session: подложные_районы)

    huge = client.get("/settlements/districts", params={"limit": 100000}).json()["items"]
    assert len(huge) == 200, f"потолок 200 не удержан на 500 подложных районах: получили {len(huge)}"

    малый = client.get("/settlements/districts", params={"limit": 3}).json()["items"]
    assert len(малый) == 3, "обычный (не огромный) limit продолжает работать как раньше"


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
