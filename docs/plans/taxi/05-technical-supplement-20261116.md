# Такси: техническое дополнение, 16.11.2026

## Статус, происхождение и приоритет

Это план проверки и разработки, не реализация и не приёмка такси. Основной реестр статусов: [taxi-scope](../../audit-traceability/taxi-scope.md) и [audit-blocks](../../audit-blocks.md). Статусы там не изменены.

Актуальный main повторно проверен: `ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8`. Пять документов 00–04 перенесены без изменения содержимого из `docs/taxi-scenarios-audit-20261105`, SHA `7de1d941d78d672da7983cb9ce38405442d74510`. Это исторические результаты 05.11 на baseline `1b02947b56aa279f7721f475d26c0dd8018cda75`, а не текущая приёмка. Их слова «актуальный», локальные пути, ссылки на прежние ветки и журналы относятся к той дате. Этот supplement уточняет современный baseline; исторические TF-01…12 требуют повторного воспроизведения.

Ветка исходного плана расходится с main (8 ahead, 199 behind на момент проверки). Переносится только документация, не её старый код/история. Параллельная `audit/comprehensive-20261116` / PR240 не изменяется. Ни main, ни production этим документом не меняются.

Навигация: [исторический индекс](00-index.md), [контракты](01-sources-states-contracts.md), [сценарии](02-user-scenarios.md), [находки](03-findings-and-acceptance.md), [проверки и REA](04-validation-and-handoff.md).

## Проверенные наблюдения по коду

Все ссылки ниже закреплены на main SHA выше. Это статический анализ, не доказательство поведения production.

- [instant_service.py](https://github.com/bairasbai/yuldash/blob/ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8/backend/app/instant_service.py): candidates выбирает первый непустой круг 3/7/15 км, ранжирование использует прямое расстояние, рейтинг, quality и priority. nearby/estimate и полноценный eligible имеют разные фильтры. ETA ближайшего водителя и оффера не является подтверждённым временем по дорогам с пробками. GEO без явного ограничения числа результатов и обращения к priority/presence/eligibility на водителя требуют измерения query budget.
- [pricing.py](https://github.com/bairasbai/yuldash/blob/ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8/backend/app/pricing.py): дорожный расчёт маршрута через Яндекс существует отдельно от dispatch, при настройках и ключе; есть таймаут, кеш и геометрический fallback. Наличие кода не доказывает подключённый договор/ключ. В [instant_service.py, surge](https://github.com/bairasbai/yuldash/blob/ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8/backend/app/instant_service.py#L336-L385) сбой GEO в _surge_supply возвращает 0, что при существующем Redis-клиенте может трактоваться как дефицит и увеличить surge. Это кандидат дефекта для регрессионного теста: unknown supply не равно zero supply. _surge_demand выбирает недавние заказы и фильтрует радиус в Python.
- [Locust](https://github.com/bairasbai/yuldash/blob/ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8/backend/loadtest/locustfile.py): InstantUser вызывает GET /instant/estimate и POST /instant/order; [router](https://github.com/bairasbai/yuldash/blob/ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8/backend/app/routers/instant.py) ожидает POST /instant/estimate и POST /instant/orders. Harness допускает 401/404/422 как успех. Реальный запуск не проводился; этот сценарий не доказывает пропускную способность такси.
- [Старое ТЗ dispatch](../../tz/TZ-dispatch-2026-09.md) полезно как гипотеза, но top-N, cache, timeout и оценки железа надо измерить заново; road_eta.py в проверенном дереве main отсутствует.

## Порядок реализации и сценарии

Сохранить все TAXI-E2E-01…10 и TP-01…70 / TD-01…63 / TX-01…32 / CT-01…24 из исторического плана. Дополнения не заменяют нормативные ID.

### D1. Единый eligibility и корректный dispatch

Сначала contract tests: класс/опции, допуск/документы, долг, pretrip, занятость, пауза, зона и свежесть координат. Один snapshot допустимых кандидатов для quote, карты подачи и dispatch; повторная атомарная проверка перед назначением. Не обещать наличие машины по одному heartbeat.

Сценарии: два водителя принимают один заказ; один водитель получает два заказа; устаревший GPS/будущее время; восстановление worker; отмена во время accept; закрытая зона; нет подходящего класса; прямое расстояние меньше, но мост/одностороннее движение делают путь дольше. Нужны ограниченные GEO выборки, batch/pipeline, верхняя граница работы на запрос и fairness без голодания кандидатов.

### D2. Честный дорожный ETA

Контракт: source (road/traffic/geometric/unknown), measured_at, coordinate_age, map/profile version, route_status, duration_seconds, distance_meters, degradation_reason. UI показывает диапазон или «примерно», время обновления/нет данных; неизвестное не подменяется точным обещанием.

Directed matrix водитель → пассажир. Проверять размеры ответа, null/unreachable, NaN/negative, порядок координат и единицы. Недостижимый маршрут не равен timeout провайдера. Не смешивать road duration для top-N с прямым расстоянием остальных в общей шкале. Проверить расширение радиуса: первый непустой круг не гарантирует лучшую дорожную подачу. Ограничить fan-out, общий deadline, circuit breaker и cache с инвалидированием по смещению/возрасту/профилю. Нет безусловного fallback на «доедет», если дорога недоступна.

[OSRM Table API](https://project-osrm.org/docs/v5.24.0/api/#table-service) позволяет матрицу времени/расстояний; null означает отсутствие маршрута. OSRM не поставляет готовый поток live traffic. У Valhalla поддержка traffic также не означает наличие лицензированных данных. Проверять покрытие на фактическом регионе.

### D3. Прозрачная цена и отсутствие двойных денег

Серверный quote identity связывает pickup/destination/waypoints/roundtrip, класс, опции, тарифную версию, срок действия и источник расчёта. До заказа видны состав цены, диапазон/фиксированность, платная подача и условия пересчёта. Изменение маршрута/цены требует понятного подтверждения, а не молчаливой подмены. Запретить рост surge из-за неизвестной supply.

Тесты: истечение quote, повтор create/finish/cancel, timeout после commit, неверный order owner, смена способа оплаты, частичный refund и повтор webhook. Одна проводка на бизнес-событие, ledger reconciliation; completed не означает paid/receipt. Новые комиссии, тарифы, штрафы, платёжные обещания этим планом НЕ утверждены.

### D4. Нагрузка, наблюдаемость и безопасность

Исправить harness до любых выводов: аутентифицированные тестовые пассажиры и допущенные водители, изолированные деньги/SMS, правильные endpoint/method и проверка бизнес-инвариантов. В положительном бизнес-сценарии 4xx/5xx не считать успехом; в отдельных негативных тестах ожидаемый корректный 4xx является успешным исходом проверки, но не успешной поездкой. Профиль включает presence, quote→create→offer→accept→finish/cancel, reconnect и idle connections.

Предлагаемые этапы, не SLA и не доказанная ёмкость:
1. Контрактные и concurrency tests на фиксированном seed, 0 двойных назначений/проводок.
2. Изолированный staging: ступени 1/10/25 RPS, затем 50/100/200 только при устойчивости предыдущей. 10 минут прогрев, 30 минут измерение; soak 2 часа на согласованной ожидаемой нагрузке. Реалистичный mix и количество online-водителей задаются до прогона.
3. Начальные проверяемые гипотезы: внутренние state mutations p95 ≤300ms, p99 ≤1s; quote с внешним provider p95 ≤2s, p99 ≤4s; business success ≥99% при исправных зависимостях. Это обсуждаемые SLO-гипотезы для staging, не контракт с пассажиром. Отдельно измерять dispatch до первого валидного offer и ETA error по реальным поездкам.
4. Сбой Redis/DB/provider, packet loss, worker kill, cache cold, burst; корректная деградация, bounded retry, отсутствие денежных дублей. Stop при нарушении инварианта, неизвестной стоимости или выходе за ресурсный budget.
5. Только после воспроизводимых результатов обсуждать реальные device/provider evidence, pilot и отдельное разрешение release.

На каждый прогон: SHA, конфигурация и регион, seed, hardware, achieved RPS, p50/p95/p99, error/business-success, DB query count/locks, event-loop lag, queue lag, provider requests/elements/cache hit/cost. Не заявлять «200 RPS выдерживает» по установленной цели генератора.

Секреты только server-side, scope и rotation; не логировать точные координаты/телефоны/токены, маскировать трассы. Rate limits, RBAC/ownership, replay/idempotency, проверка входных координат/URL, ограничение retention. Нельзя использовать личные поездки/реальные SMS/карты в нагрузочном тесте без отдельного основания.

### D5. Кандидаты live-location от параллельного аудита

Параллельный аудит B06 сообщил кандидаты, ещё не подтверждённые здесь runtime/device воспроизведением: InstantLocationSocket может поставить GPS в очередь до auth-frame, не закрепляет ownerSession, а reconnect способен взять токен уже сменившегося аккаунта; TripLocationService при смене брони A→B может сохранить peer-точку A. До исправления найти и закрепить точные файлы/строки/SHA, независимо воспроизвести и добавить регрессии: auth-before-location, logout/account switch при reconnect, смена брони и очистка чужой точки. Эти сообщения не означают доказанную утечку production; live location не считать готовым без проверки изоляции сессии/поездки.

## Провайдеры, лицензии и бюджет

Это shortlist для оценки, не установка и не закупка. Перед выбранной версией фиксировать commit/release, LICENSE/NOTICE, зависимости и условия данных.

| Компонент | Первичный источник | Решение |
|---|---|---|
| OSRM | [BSD-2-Clause](https://github.com/Project-OSRM/osrm-backend/blob/master/LICENSE.TXT) | Кандидат self-hosted road matrix; свой hosting, обновление графа, нет готовых пробок |
| Valhalla | [MIT](https://github.com/valhalla/valhalla/blob/master/COPYING), [документация](https://valhalla.github.io/valhalla/) | Сравнить регион/ресурсы/профили; отдельно источник traffic |
| OR-Tools | [Apache-2.0](https://github.com/google/or-tools/blob/stable/LICENSE) | Оптимизация, не источник карт или маршрутов; внедрять только если простой dispatch недостаточен |
| Namma Yatri | [AGPL-3.0](https://github.com/nammayatri/nammayatri/blob/main/LICENSE) | Только архитектурное исследование; код не копировать без отдельного анализа совместимости |
| OSM | [ODbL/FAQ](https://osmfoundation.org/wiki/FAQ) | Лицензия движка не заменяет обязанности по картографическим данным/атрибуции |
| Public Nominatim | [policy](https://operations.osmfoundation.org/policies/nominatim/) | Максимум 1 request/sec на приложение; autocomplete запрещён; нужен идентифицирующий User-Agent/Referer и атрибуция. Не выбирать public endpoint для production такси |

Яндекс Matrix: [цены](https://yandex.ru/dev/tariffs/doc/ru/matrix/prices/), [условия](https://yandex.ru/dev/tariffs/doc/ru/matrix/terms/), проверены 16.11.2026. Опубликованы ориентиры: 1000 элементов/сутки — 20 800 ₽ за 30 дней или 195 000 ₽ за год; 10 000 — 58 500 ₽ за 30 дней или 585 000 ₽ за год. Элемент = пара origin/destination, не HTTP-вызов. Trial 100/сутки, 7 дней. Это не подтверждение применимости тарифа к Юлдаш, договора, ключа или итогового счёта; перед закупкой проверить индивидуальные условия и все платежи. Правило cache до 30 дней для повышения производительности относится к указанному в условиях ежегодному standard-варианту, не автоматически к помесячному.

Бюджетная модель: elements/day = quote_count × candidates_per_quote × refreshes × (1-cache_hit) + retry_elements + dispatch_elements + reroute_elements. Например, 1000 quote ×8 кандидатов ×2 обновления без кеша =16000 элементов/сутки до retries, не 1000 API calls. Не считать такую модель гарантией биллинга; согласовать действующие ограничения провайдера.

Перед внешними запросами/закупкой согласовать provider, регион, передачу координат, договор, дневной/месячный предел денег и элементов, лимит превышения и поведение при исчерпании. Для self-hosted оценить RAM/CPU/disk, import/update time, redundancy, egress и сопровождение. Не называть open-source бесплатным production. Без согласованного бюджета только fixtures и локальное планирование.

## REA / APK / Ghidra: реальные границы

Проверены доступные локальные артефакты чтением и SHA-256, без запуска APK/анализаторов:

- Go 5.91.2, имя Wildroid.ru, 143479422 байта: SHA-256 `b27226f4c7e8fda5b0f34cc552fa0c1947aa09c75966cc69335409e8c0810b86`, совпадает с recovery manifest. Совпадение хеша не доказывает официальный источник/подпись; второй одноимённый Library-файл отдельно не сравнивался.
- Pro ARM64 split config.arm64_v8a.apk, 122198549 байт: SHA-256 `a1e560772752d02555893a4fe998216dfefc4f9d2d42f17ff4f2c5b59cd33afa`, совпадает с сохранённым отчётом восстановления от 27.10. Это split, не доказанный полный устанавливаемый комплект APK. Рядом есть libapp.so и libflutter.so; новую проверку сертификата, версии и семантического mapping ещё выполнить.
- Recovery ZIP с именем seq36 фактически содержит v25 / Page3 seq59, 1911 blocks по README/manifest, 893 entries. SHA-256 `405ac7035254aa6079dcb8812b8c98589c38faada10b039b38da9a9108da04a3`. Все 892 записи CHECKPOINT-SHA256SUMS совпали. Это проверка включённых файлов, НЕ полного исходного корпуса: manifest явно отмечает omissions, неполный QA fileset и derivative reports. Runtime NOT_EXECUTED, novelty UNKNOWN. В именах файлов этого ZIP нет Ghidra, что не доказывает отсутствие любых исторических результатов вне архива.
- Прочитаны README, RESTORATION-MANIFEST, SOURCE-IDENTIFICATION и сохранённые recovery-status/update. Они фиксируют частичное восстановление и Pro Dart/runtime metadata, но не точную Flutter release, не проверенную совместимость всех анализаторов и не полный historical ledger. Остальные отчёты/сырой корпус ещё требуют предметного чтения.

[Исторический handoff](04-validation-and-handoff.md) сообщает запуск REA 5.0.0 (FreeNAP/rea--, source SHA 1d13cef5f46f5e07f594bde7b1a4725f189b9a5e) на собственном PWA: 117 JS parsed, 1087 nodes, 3456 edges; общий граф частичен, были unresolved imports. Это прежний отчёт, не новый прогон и не APK/Ghidra-анализ.

Порядок исследования:
1. Прочитать recovery manifest и отчёты, связать каждый вывод с SHA-256 APK/архива, package/versionCode, сертификатом, ABI/splits, инструментом/версией и датой. Хранить происхождение; не публиковать APK или чужой декомпилированный код.
2. Для DEX/ресурсов использовать официальный [JADX](https://github.com/skylot/jadx); декомпиляция может быть неполной. Для native ELF/.so и нужных низкоуровневых участков пригодна официальная [Ghidra NSA](https://github.com/NationalSecurityAgency/ghidra). Само наличие Ghidra не восстанавливает backend dispatch/ценообразование.
3. Байрас 16.11 отдельно разрешил оригинальный morluto/rea в облаке для анализа доступных Go/Про. До выполнения зафиксировать точный источник, версию/commit, команду, изоляцию и соответствие этому scope; это не разрешение произвольного fork/плагина или иной цели. Исторический FreeNAP/rea-- не тождествен выбранному morluto/rea. В этом документационном шаге REA не запускался. APK не исполнять, не обходить авторизацию, не извлекать/использовать чужие токены, не атаковать сервисы.
4. Сопоставлять разрешённые наблюдения UX/контрактов с независимой реализацией Юлдаш. Нет оснований обещать полный reverse engineering, «алгоритм Яндекса» или текущую Ghidra-проверку.

## Приёмка этого документационного шага

Проверить побайтное сохранение пяти исходных документов, scope diff (только docs/plans/taxi), pinned baseline, доступность новых ссылок и отсутствие неподтверждённых статусов. Независимый reviewer проверяет противоречия и лицензии. Проверка документации не повышает TAXI-E2E до passed. Дальше отдельные небольшие code-ветки: harness/unknown-supply, eligibility, road ETA, quote contracts; merge и production только по отдельному решению Байраса.
