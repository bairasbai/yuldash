# Карточка: `backend/app/price_freeze.py`

- Статус: verified
- Лист: leaf-1.2
- Проверял: Sonnet 5 (leaf-1.2); принимал: Opus 5.5

## Назначение

Заморозка цены на `price_freeze_sec` (120 с по умолчанию): показали человеку сумму — по ней
и везём, пока он думает. Правило работает ТОЛЬКО В ПОЛЬЗУ человека — берём минимум из
замороженной и пересчитанной цены (выросла → держим старую; упала → берём новую). Отдельные
копии для такси (`remember`/`apply`, ключ по классу/опциям/кругу) и для доставки
(`remember_courier`/`apply_courier`, ключ по «подписи» заказа — размер/срочность/тип). Живёт
только в Redis; без Redis или при `price_freeze_sec=0` заморозки просто нет — расчёт идёт
заново, как раньше (деньги важнее метрики, сломанный Redis не должен мешать уехать).

## Функции и разбор

| Функция | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_courier_key` | 60–63 | Ключ заморозки доставки: user_id + координаты (3 знака) + «подпись» (размер/срочность/тип) | sha1 от строки, 12 символов — коллизия невозможна на практике | ок |
| `remember_courier` | 66–78 | Запомнить показанную цену доставки на `lock_seconds()` секунд | `r is None`/выключено/`priced` пуст → no-op (0); любое исключение Redis — проглочено, расчёт цены не падает | ок |
| `apply_courier` | 81–98 | Цена доставки к заказу: замороженная или новая — какая дешевле | Сравнение `было >= стало` отдаёт новую (не хуже); иначе — замороженный снимок ЦЕЛИКОМ (включая `breakdown`); битый JSON/нет ключа — тихий фолбэк на новую | ок |
| `_key` | 101–107 | Ключ заморозки такси: user_id + координаты + класс + опции + круговой рейс | Весь набор, что меняет цену, входит в подпись — иначе класс/опции можно было бы подменить бесплатно | ок |
| `lock_seconds` | 110–112 | Сколько держим цену (0 = выключено настройкой) | `max(int(settings.price_freeze_sec), 0)` — отрицательная настройка не уйдёт в минус | ок |
| `remember` | 115–128 | Запомнить показанную цену такси, вернуть срок закрепления (0 = не вышло) | Снимок — ТОЛЬКО поля из `FROZEN_KEYS` (половина расчёта не замораживается); исключения Redis проглочены | ок |
| `apply` | 131–158 | Цена такси к заказу: замороженная или новая — какая дешевле, весь расчёт целиком | `"price" not in frozen` / не-число / нет ключа → фолбэк на новую цену; `price_was_frozen=True` — честная пометка для ответа человеку | ок |

## Связи

- Такси: `remember()` зовётся из `POST /instant/estimate`, `apply()` — из `POST /instant/orders`
  (оба в `routers/instant.py`, вне зоны этого листа).
- Доставка: `remember_courier()`/`apply_courier()` зовутся из `routers/courier.py` (вне зоны) —
  там же эндпоинт `courier_order_create` читает из `priced[...]` ровно те поля, что обязаны
  быть в `FROZEN_COURIER_KEYS` (проверено сторожем, см. тесты ниже).
- Redis: `config.settings.price_freeze_sec`; экземпляр redis передаётся вызывающим кодом
  (в проде — реальный клиент, в тестах — `fakeredis`).

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Такси: берём МЕНЬШУЮ из старой/новой цены (обе стороны правила) | `backend/tests/test_price_freeze.py::test_price_that_grew_is_held`, `::test_price_that_fell_is_taken_fresh`, `::test_the_whole_breakdown_is_frozen_together` | да — M6 |
| R2 | Доставка: та же защита (мин. из старой/новой), расшифровка заморожена вместе с итогом | `backend/tests/test_frozen_delivery_price_freezes_its_explanation_too.py::test_frozen_price_keeps_its_own_breakdown`, `::test_new_cheaper_price_keeps_its_own_breakdown` | да — M7 |
| R3 | Ключ учитывает класс/опции/человека/подпись — нельзя подменить бесплатно | `backend/tests/test_price_freeze.py::test_another_class_is_another_price`, `::test_another_person_does_not_inherit_the_price`, `::test_child_seat_is_not_smuggled_in_for_free` | да — M8 |
| R4 | Выключение рубильником `price_freeze_sec=0` забывает уже лежащий в памяти снимок (не только «не запоминает новый») | `backend/tests/walk/l1_2/test_l1_2_price_freeze_off_switch.py::test_switching_off_forgets_the_already_remembered_taxi_price`, `backend/tests/test_frozen_delivery_price_freezes_its_explanation_too.py::test_switch_off_really_switches_off` (доставка), `backend/tests/test_price_freeze.py::test_no_redis_no_freeze_but_no_crash`, `::test_broken_snapshot_is_ignored` | да — M9 |
| R5 | `FROZEN_COURIER_KEYS` покрывает ВСЕ поля, которые читает создание доставки (`breakdown` включительно) | `backend/tests/test_frozen_delivery_price_freezes_its_explanation_too.py::test_frozen_snapshot_covers_everything_the_order_reads`, `::test_frozen_price_keeps_its_own_breakdown` | да — M10 |

## Найденные ошибки

Ошибок не найдено. Это один из самых тщательно защищённых файлов обхода: оба направления
правила (дороже/дешевле), границы ключа (класс/человек/опции/подпись), откат при пустом/битом
Redis и сторож полноты списка полей — всё уже покрыто существующими тестами до этого листа.
Нашёл только один пробел в покрытии (не ошибку в коде): для такси не было теста на «выключили
рубильник, пока в памяти уже лежал снимок» (для доставки такой тест был) — закрыл его новым
тестом R4, добавленным в этом листе.

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M6 | `apply()`: `was >= now` → `was <= now` (берётся МАКСИМУМ вместо минимума) | test_price_that_grew_is_held | KILLED |
| M7 | `apply_courier()`: `было >= стало` → `было <= стало` | test_frozen_price_keeps_its_own_breakdown | KILLED |
| M8 | `_key()`: класс выпадает из подписи ключа | test_another_class_is_another_price | KILLED |
| M9 | `apply()`: гейт `or` → `and` (без Redis падает вместо фолбэка) | test_switching_off_forgets_the_already_remembered_taxi_price | KILLED |
| M10 | `FROZEN_COURIER_KEYS`: `"breakdown"` закомментирован | test_frozen_price_keeps_its_own_breakdown | KILLED |

## Остаток и ограничения

Реальный Redis не проверялся (только `fakeredis` — сетевых вызовов тут нет в принципе, это
in-memory структура данных провайдера, подмена честная). Поведение при настоящей сетевой
задержке/партиционировании Redis не проверялось — функция рассчитана на `except Exception`
как общий рубильник отказа, более тонкие сценарии (Redis отвечает медленно, но не падает)
не исследовались отдельно.
