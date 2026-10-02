# Карточка: `backend/app/routers/settlements.py`

- Статус: verified
- Лист: leaf-1.1
- Проверял: Sonnet 5 (leaf-1.1); принимал: Opus 5.5

## Назначение

Справочник населённых пунктов Башкортостана и приграничья (~6600 сёл + города + райцентры,
источник OSM/ODbL) — автоподсказки «откуда/куда» при заказе и выбор района работы водителя.
Данные общеизвестные (не персональные), поэтому все три ручки **намеренно публичные** — без
`Depends(current_user)`. Это не про деньги (в отличие от остальных 4 файлов листа), но файл
входит в OWNS этого листа.

Поиск идёт по снимку справочника в памяти (`geo._snapshot`, вне зоны этого листа), а не
запросом в БД на каждую букву — иначе 6600 строк на каждое движение пальца по экрану легли бы
на базу.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `settlements(q, limit, session)` — `GET /settlements` | 17–21 | автоподсказки по имени (ru/ba), пустой `q` → топ справочника | `limit` клампится ВНУТРИ `geo.search_settlements` (`max(1, min(limit, 50))`, вне зоны листа, но поведение проверено) — этому роутеру клампить нечего; регистр/ё/башкирские буквы неважны (`geo.fold`) | ок |
| `districts(q, limit, session)` — `GET /settlements/districts` | 24–32 | список районов с именем/регионом/числом НП, фильтр по запросу | **фильтр — ПРЕФИКС** (`startswith`), не подстрока (`geo.fold(r["district"]).startswith(needle)`); `limit` клампится ЗДЕСЬ, в роутере (`max(1, min(limit, 200))`) | ок |
| `popular_routes(session)` — `GET /settlements/popular-routes` | 35–38 | пресеты межгород-маршрутов для чипов UI | без параметров, список ограничен самим справочником пресетов (`geo.POPULAR_ROUTES`, вне зоны) | ок |

## Связи

- `backend/app/geo.py` (вне OWNS, только чтение) — `search_settlements`, `districts_payload`, `popular_routes_payload`, `settlement_payload`, `fold`. Клэмп `limit` для `/settlements` живёт там.
- Регистрация: `backend/app/routers/__init__.py` (не проверял отдельно — ручки отвечают, значит зарегистрированы; проверено тестами ниже).
- UI: `ProfileScreen.kt` (`searchSettlements`), создание поездки/заявки (попутка/такси/курьер) — используют `/settlements` для автоподсказок; вне зоны листа.
- Rate-limit — общий `middleware.RateLimitMiddleware` (вне зоны), не повторяю его проверку здесь.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Все три ручки отвечают БЕЗ заголовка `Authorization` (справочник публичный, не персональные данные) | backend/tests/walk/l1_1/test_l1_1_settlements_router.py::test_r1_settlements_search_is_public_no_auth_header, backend/tests/walk/l1_1/test_l1_1_settlements_router.py::test_r1_districts_and_popular_routes_are_public_too | косвенно (структурно: роутер не меняли, тест фиксирует факт) |
| R2 | Поиск учитывает текст запроса `q` (а не отдаёт одно и то же на любой ввод); регистр и НАСТОЯЩЕЕ башкирское написание не важны (не только регистр латиницы/кириллицы — поправлено по замечанию независимого ревью Opus, см. «Остаток») | backend/tests/walk/l1_1/test_l1_1_settlements_router.py::test_r2_search_matches_real_bashkir_script_not_just_case | да — M4 |
| R3 | Пустой `q` → топ справочника (не пусто, не ошибка); неизвестный `q` → пустой список (не ошибка, не «вернуть всё») | backend/tests/walk/l1_1/test_l1_1_settlements_router.py::test_r3_empty_query_returns_top_of_directory_not_empty, backend/tests/walk/l1_1/test_l1_1_settlements_router.py::test_r3_unknown_query_returns_empty_list_not_error | да — M4 (вторая половина) |
| R4 | `/settlements/districts` режет `limit` в границы `[1, 200]`: 0 и отрицательный дают РОВНО 1, огромный — РОВНО 200 (числа проверены точно, не «>=1» — поправлено по замечанию ревью, см. «Остаток») | backend/tests/walk/l1_1/test_l1_1_settlements_router.py::test_r4_districts_limit_clamps_zero_and_negative_to_exactly_one, backend/tests/walk/l1_1/test_l1_1_settlements_router.py::test_r4_districts_limit_caps_at_200_even_when_the_directory_is_huge | да — M5 |
| R5 | `/settlements/districts` фильтрует ПРЕФИКСОМ района, а не произвольной подстрокой (другое поведение, чем `/settlements`) | backend/tests/walk/l1_1/test_l1_1_settlements_router.py::test_r5_districts_filter_is_prefix_not_substring | да — M6 |

Дополнительно (не мутировал — покрыто существующим набором, читал код и тесты): поиск по
русскому/башкирскому написанию и формат ответа (`id/name_ru/name_ba/region/kind/district/lat/lng`)
— `backend/tests/test_geo.py` (поиск `/settlements`, `/settlements/districts`,
`/settlements/popular-routes`), `backend/tests/test_villages.py`.

## Найденные ошибки

Ошибок не найдено. Проверено: публичный доступ — осознанное решение (докстринг файла это
прямо объясняет, не забытая авторизация); клампы `limit` на обоих уровнях (роутер и `geo.py`)
не допускают ни пустого, ни гигантского среза; разница «префикс vs подстрока» между двумя
похожими ручками в одном файле — задокументированное разное поведение, не опечатка (разный
UX: район выбирают из короткого списка «начинается с», а НП ищут где угодно в названии).

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M4 | `/settlements` игнорирует `q`, всегда зовёт поиск с пустой строкой | test_r3_unknown_query_returns_empty_list_not_error | KILLED: тест упал (вернулись записи вместо пустого списка) |
| M5 | `/settlements/districts` отдаёт `rows[:limit]` без клампа границ | test_r4_districts_limit_clamps_zero_and_negative_to_exactly_one | KILLED: тест упал (`limit=0` → пустой список вместо 1) |
| M6 | Фильтр района подменён с `startswith` на `in` (подстрока) | test_r5_districts_filter_is_prefix_not_substring | KILLED: тест упал (подстрока из середины слова неожиданно нашлась) |

Прогон: `python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-1.1.json --only M4,M5,M6` → `MUTATIONS KILLED 3/3`.

## Остаток и ограничения

Файл небольшой (38 строк) и полностью не про деньги — это единственный файл листа «Деньги»,
который геоданные, а не финансы (видимо, попал в лист по совпадению пути `settlements`
= «населённые пункты», а не «денежные расчёты»). Логику поиска (`geo.search_settlements`,
`geo.fold`, снимок в памяти) не проверял заново построчно — она вне OWNS этого листа и уже
покрыта `test_geo.py`/`test_villages.py`; я лишь подтвердил, что мой роутер корректно её
вызывает и не повторяет клампы неправильно.

**Исправлено по замечанию независимого ревью Opus (2026-10-02) — две слабые проверки в
первой сдаче:**
- R2 изначально проверял только РЕГИСТР одной и той же кириллической строки («Уфа» / «уФА»),
  а не настоящий башкирский текст — переименованный тест `test_r2_search_matches_real_bashkir_script_not_just_case`
  теперь честно ищет по «өфө» (башкирское имя Уфы, как в `test_geo.py`).
- R4 для «huge»/«negative» `limit` раньше проверял утверждения, верные для ЛЮБОГО ответа
  (`len(huge) <= 200` — в справочнике и так меньше 200 районов; `rows[:-5]` на несрезанном
  списке тоже не пуст). Теперь: граница 0/отрицательный проверена ТОЧНЫМ числом (1), а потолок
  200 — на 500 подложных районах через `monkeypatch` (`geo.districts_payload`), потому что на
  настоящих ~50 районах РБ потолок в принципе недостижим никаким `limit`.
