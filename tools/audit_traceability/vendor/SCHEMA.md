# Контракт манифеста и приёмки 1.0

Формальная структура: [`schema/manifest.schema.json`](schema/manifest.schema.json). Готовый синтетический пример: [`demo/accepted.json`](demo/accepted.json). Все объекты закрытые (`additionalProperties: false`); поле не может молча добавить альтернативный статус. Все перечисленные ниже поля обязательны, кроме явно помеченных.

Используется собственный проверяющий код **только для словаря ключей поставляемой схемы**: `$schema`, `$id`, `$defs`, `$ref`, `title`, `description`, `type`, `enum`, `const`, `properties`, `required`, `additionalProperties: false`, `items`, `minItems`, `uniqueItems`, `minLength`, `pattern`, `minimum`, `maximum`. Это не универсальная библиотека JSON Schema. Неподдерживаемый keyword, внешний `$ref` или `$ref` с соседними ключами блокирует проверку (`SCHEMA_ENGINE`, exit 2). Значение `$schema` — идентификатор стандарта; ничего из сети не загружается. Даты проверяются локальным кодом дополнительно, без `format`; разрешены реальные RFC3339 даты с timezone, без leap seconds.

## Корень и общие типы

Корень: `schema_version: "1.0"`, `audit`, массивы `people`, `artifacts`, `evidence`, `features`, `requirements`, `implementations`, `tests`, `acceptances`, `skip_decisions`. Первые пять массивов непустые; последние четыре могут быть пустыми для честного незавершённого аудита, но такой аудит gate не проходит. Массивы ссылок уникальны. У требования массивы звеньев могут быть пустыми, чтобы валидатор показал конкретный незакрытый блокер.

- `id`: верхний регистр латиницы/цифры с разделителями `.`, `_`, `-`; пример `FEATURE-DEMO-FAVORITES`. Глобально уникален между типами сущностей и audit. ID — идентичность, не результат поиска по заголовку.
- `text`: непустая строка хотя бы с одним непробельным символом.
- `commit`: полный lowercase hex длиной 40 или 64. Сокращение, `HEAD` и имя ветки не допускаются. Наличие commit в Git инструмент не устанавливает.
- `sha256`: 64 lowercase hex; считается по исходным байтам файла без нормализации строк.
- `timestamp`: `2026-10-23T09:30:00Z` или с 1…6 цифрами долей секунд/смещением `+00:00`. Поддерживается подмножество RFC3339 с точностью до микросекунды, ASCII-цифрами, часами 00…23 и минутами/секундами 00…59, без leap seconds. У UTC offset минуты тоже 00…59. Более 6 дробных цифр отклоняются вместо обрезания; реальная календарная дата и порядок событий проверяются без потери разрешённой точности.

У полноформатных ID/commit/hash/timestamp регулярные выражения используют совместимую с ECMAScript проверку фактического конца строки `(?![\s\S])`: одного `$` недостаточно, он может совпасть перед последним LF. Для обычного текста `\S` сохраняет значение «есть непробельный символ», многострочный текст разрешён. CLI сериализует Unicode с ASCII-экранированием; это не меняет значения строк после JSON parse.

## Сущности

| Объект | Поля и назначение |
|---|---|
| `audit` | `id`, `title`, `scope` (`demo`/`real`), `target_commit`, `author_ids`, `claimed_state` (`unknown`/`implemented`/`accepted`/`accepted_with_skips`); необязательное `reported_score` 0…100 только для диагностики |
| `people[]` | `id`, `name`, `roles`: непустой массив `audit_author`/`implementation`/`test`/`acceptance`. Идентичность человека требует внешней проверки |
| `artifacts[]` | `id`, `path` относительно манифеста, `sha256`, `kind`: `source`/`implementation`/`execution`/`acceptance`/`decision` |
| `evidence[]` | `id`, `certainty`: `observed`/`inferred`/`unknown`, `kind`: `static`/`runtime`/`public`, `claim`, `locator`, `artifact_ids` (непустой). `locator` — место внутри источника; он не заменяет файл |
| `features[]` | `id`, `title`, `classification`: `business_feature`/`robustness`/`helper`, непустой `requirement_ids` |
| `requirements[]` | `id`, `feature_id`, `text`, `level`: `must`/`should`/`may`, `priority`: `P0`/`P1`/`P2`, `evidence_ids`, `implementation_ids`, `test_ids`, `acceptance_ids`, `disposition`: `active`/`skipped`; необязательный `skip_decision_id` (обязателен смысловым правилом при skip, запрещён при active) |
| `implementations[]` | `id`, непустой `requirement_ids`, `commit`, `author_ids`, `state`: `implemented`/`proposed`/`unknown`, `summary`, непустой `artifact_ids` с kind `implementation` |
| `tests[]` | `id`, непустые `requirement_ids`, `implementation_ids`; `state`: `executed`/`proposed`/`unknown`; `certainty`; `result`: `passed`/`failed`/`unknown`; `target_commit`, `executor_id`, `started_at`, `finished_at`, `command`, `environment`, непустой `artifact_ids` с kind `execution` |
| `acceptances[]` | `id`, непустые `requirement_ids`, `implementation_ids`, `test_ids`; `target_commit`, `reviewer_id`, `decision`: `accepted`/`rejected`/`pending`; `certainty`, `reviewed_at`, `reason`, `reviewed_artifact_ids` (исходные evidence/implementation/execution), `artifact_ids` с kind `acceptance` |
| `skip_decisions[]` | `id`, непустой `requirement_ids`, `decider_id`, `decision`: `approved`/`rejected`/`pending`, `reason`, `decided_at`, `artifact_ids` с kind `decision` |

`evidence.kind` описывает способ наблюдения; `artifacts.kind` — назначение файла. Для static/public evidence допускаются source artifacts. Для runtime evidence — source или execution artifacts. Для обязательного execution test требуются именно execution artifacts: файл со статическим DEX-фрагментом не становится результатом выполнения от ссылки на него.

## Инварианты графа

1. `feature.requirement_ids` ↔ `requirement.feature_id` согласованы.
2. `requirement.implementation_ids` ↔ `implementation.requirement_ids`, и аналогично для tests/acceptances. Ссылка проверяется в обе стороны.
3. Каждый test связан с реализацией своих requirements; каждая его implementation относится хотя бы к одному requirement этого test. Для требования совокупность его хороших executed tests должна покрыть все его implementation IDs.
4. Каждая acceptance должна покрывать всю цепочку implementation/test каждого своего requirement. Ссылки на unrelated реализации/тесты отклоняются. Приёмка неполной половины цепочки несколькими отдельными записями не складывается в полную.
5. В `reviewed_artifact_ids` должны входить все source evidence artifacts, все связанные implementation artifacts и все execution artifacts. Собственный текст `accepted` вместо review логов не подходит. Само заявление о просмотре не доказывает, что просмотр действительно был.
6. Весь набор использует один `audit.target_commit` для implementation/test/acceptance. У источника APK может быть собственный SHA256 без Git commit; provenance разных систем нельзя смешивать.
7. Для skip обе стороны обязаны ссылаться на одну запись решения. Для `must` **или** P0 это решение никогда не закрывает blocker.

## Результат CLI

| Поле | Значение |
|---|---|
| `valid`, `gate_passed` | Равны; нет найденных нарушений строгого gate. Это не просто успешный JSON parse |
| `gate` | Всегда `strict_acceptance`; ослабляющего режима/порога нет |
| `computed_state` | `invalid` (схема/вход), `blocked` (есть пробелы), `implemented` (все реализации есть, но gate блокирован), `accepted`, `accepted_with_skips` |
| `implemented` | У всех requirements наблюдённый source и исправные implemented записи на target. Не утверждает выполнение тестов или независимую приёмку |
| `accepted` | Только при отсутствии всех blockers и принятии каждого requirement. При допустимом optional skip — false |
| `requirements[]` | Вычисленные `implemented`, `accepted`, `executed_tests_complete`, `mandatory`, `disposition`, `skip_approved`, связанные `blocker_codes`, `observed_support_ids`, отдельные счётчики certainty/kind |
| `features[]` | Свёртка своих requirements, с сохранением classification |
| `blockers[]` | `code`, JSON Pointer `path`, `message`, при наличии `entity_id`. Нельзя игнорировать блокеры из-за успешной строки ниже |
| `skips[]` | requirement ID, approved/mandatory, decision ID, причина, `blocks_gate`. Не удаляются из результата и denominator |
| `diagnostics` | Количество/процент accepted requirements, счётчики по классификации, `mandatory_open_ids`, `reported_score_ignored`, всегда `score_controls_gate: false` |
| `limitations` | Границы проверки подлинности, независимости и provenance; для DEMO отдельное предупреждение |

Основные коды: `SCHEMA`, `DUPLICATE_ID`, `DANGLING_REFERENCE`, `LINK_MISMATCH`, `ARTIFACT_MISSING`, `ARTIFACT_PATH`, `ARTIFACT_IO`, `HASH_MISMATCH`, `ARTIFACT_KIND`, `COMMIT_MISMATCH`, `TEST_NOT_EXECUTED`, `TEST_NOT_PASSED`, `SOURCE_NOT_OBSERVED`, `IMPLEMENTATION_INCOMPLETE`, `EXECUTION_INCOMPLETE`, `ACCEPTANCE_INCOMPLETE`, `REVIEWER_NOT_INDEPENDENT`, `EXECUTION_REVIEW_INCOMPLETE`, `SKIP_DECISION_MISSING`, `SKIPPED_BLOCKER`, `CLAIM_OVERSTATED`. Полный источник конкретных условий — `traceability/validator.py`; regression tests проверяют поведение, а не только названия кодов.

При ошибке входа/схемы поля успешной смысловой проверки могут отсутствовать, `requirements` пуст и положительного утверждения о приёмке нет. Перечень blockers детерминирован для неизменных манифеста и файлов; системный текст IO-ошибки может зависеть от ОС.
