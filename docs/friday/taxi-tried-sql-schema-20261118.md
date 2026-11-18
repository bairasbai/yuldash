# Неактивная SQL history foundation: выбор структуры

База b054923. Только schema-only после отдельного review. Не подключать readers/writers, не включать flag, не мигрировать существующую БД.

## Bound и выбор

В start_matching searching → searching сохраняет tried_lifecycle, но сбрасывает search_round=0. Retry worker вызывает этот путь для searching с wait_until. Следовательно instant_max_offers ограничивает один отрезок между restart, не число уникальных drivers за lifecycle или sliding TTL. Конечный размер user table не является малым эксплуатационным bound списка. Нельзя вводить cap/prune: это меняет исключение кандидатов. Выбираем нормализованную таблицу, не неограниченный JSON/array внутри горячей order строки. Это не снимает необходимость retention/лимитов нагрузки; лишь не раздувает обновляемую order строку.

## Предлагаемая схема

- InstantOrder.tried_history_version: nullable INTEGER, default/server_default отсутствуют; NULL означает прежний Redis путь. Будущий протокол зарезервирует version=1, но этот этап не создаёт и не интерпретирует значения.
- InstantOrder.tried_history_expires_at: nullable naive UTC timestamp, без default/backfill/index. Общий sliding expiry **всего набора**, не per-member. Schema-only не меняет старый TTL3600.
- InstantTriedExclusion / instanttriedexclusion: composite primary key (order_id INTEGER, lifecycle VARCHAR(32), driver_id INTEGER), все NOT NULL. FK order_id → instantorder.id ON DELETE CASCADE, явная очистка при физическом удалении parent; никакого самостоятельного retention. driver_id не FK: удаление user не должно удалять exclusion; это внутренний идентификатор, без PII payload. Никаких address/phone/reason/event timestamp полей. Это set, не журнал доставки.
- lifecycle непустой token формата32 lowercasehex проверяется будущим writer; schema пока только VARCHAR32 NOT NULL, SQLite length не обещать. Не добавлять несовместимый dialect-specific regex constraint. driver positivity/validity также writer responsibility.
- PK начинается с order_id,lifecycle и обслуживает будущий set read; дополнительный index не нужен в этом этапе. Mode отдельно от namespace; активные tokenized Redis cycles не мигрируют автоматически.
- Migration cn после cm: аддитивная; поддержать create_all bootstrap skip, но не обещать repair ошибочно существующих колонок/таблицы. Downgrade только своей временной БД и после остановки новых readers, уничтожает history values/table; существующие order поля/строки сохраняются.

## Границы будущего runtime

Никакой runtime safety от пустой схемы. Shared expiry/reset/add/renewal потребуют owner row lock/order-first ordering, timestamp после lock, fresh ownership и SQL history reread после stale candidate lookup. Duplicate renews общий expiry, два writer не сокращают срок. GC только под тем же lock с перечитыванием состояния/expiry. Нет silent cap/prune. Commit-exclusion не delivery: crash после commit до dispatch остаётся. Mixed old binaries запрещены при активном sql_v1, nullable foundation лишь структурно совместима. Rollout off не делает binary downgrade безопасным.

## Проверки реализации

1. Model metadata и реальная SQLite upgrade: тип/nullable/no defaults, FK action, composite uniqueness, same driver разных cycles разрешён; rollback собственного insert; FK cascade с включённой foreign_keys.
2. Downgrade сохраняет исходные поля и строки; double-upgrade только проверка skip, не drift repair. Offline PG DDL chain single head.
3. HTTP обычной сериализацией: version/expiry не появляются при NULL и nonNULL sentinel, matching не инициализирует fields, новая таблица пуста; никакого режима sql_v1 не включать.
4. Guarded disposable real PG schema/PK/FK/rollback, не application production migration; ресурс согласовать с Amio. Author scoped, независимый review/scoped/PG, FULL из-за новой model metadata, затем отдельная публикация.

Перед реализацией независимое review выбора FK/delete/mode и плана. Этот документ не разрешает дальнейший SQL matcher.

## Pre-code review и принятое уточнение

Journal PASS d99302b для schema-only. Выбран INTEGER tried_history_version: NULL legacy, будущий 1 SQL, unknown/nonpositive fail closed будущим reader. Никакой reader сейчас не добавляется. Driver ID псевдонимный идентификатор, не «не персональные данные»; assumption no ID reuse, иначе новый аккаунт наследует exclusion. Не решать это скрытым prune. Добавить orphan-order rejection, driver deletion preserves membership, rollback parent delete restores cascaded rows, both lifecycle cascade, nonNULL HTTP sentinel no activation. Downgrade child before columns; schema до new model, old binary только structural compatibility. Fixtures очищают только собственные данные, FK-aware.

## Реализация и адресные проверки

Model/migration 5a67dbd, tests 8220d3a. Авторский новый schema набор 9 PASS (1.80 с); expanded с lifecycle schema 17 PASS, exit 0, 3.33 с, /tmp/taxi-history-schema-final.log. Ruff F, bash syntax, diff-check PASS; detect-secrets 7 изменённых файлов 0 findings. Alembic head единственная cn_instant_tried_history. Два новых disposable PG cases подготовлены (общий runner 27), ещё не запускались. Независимая приёмка и FULL ожидаются, schema-only не устраняет rollback exclusion.

Author PG27 на 237a3fd: 27 PASS, exit 0, 3.36 с, /tmp/taxi-history-schema-pg.log; cleanup временного кластера подтверждён. Независимый SQLite83 PASS/2 PG skips (8.47 с); выявленный P2 по неполным reflected types закрыт test-only 237a3fd: author17 PASS2.50с, independent17 PASS3.20с, /tmp/taxi-tried-schema-types-independent.log. Model/runtime unchanged. Независимый PG и FULL следующие gates.

## Первый FULL: отказ сохранён, не принят как PASS

364f7fc: 3 FAILED, 7339 PASS, 146 skipped, 815 warnings, 733.44 с, exit 1. Лог /tmp/taxi-history-schema-full.log и .exit сохраняются неизменными. Падают inventory guards: FK coverage игнорирует ON DELETE CASCADE, index guard ищет только текст index=True вместо составного PK, retention inventory не содержит новой таблицы. Не добавлять избыточный index или NOT EXISTS, блокирующий корректное parent deletion, не объявлять history KEEP_FOREVER.

Journal независимо принял узкую коррекцию: exact owned-retention регистрация child column → parent column + причина, валидируемая actual CASCADE metadata; только для этой пары. Negative controls: отсутствие регистрации/FK, RESTRICT, wrong parent не проходят. Index exception только для exact composite PK(order_id,lifecycle,driver_id), NOT NULL/unique; reordered/missing/order_id not prefix не проходят. Остальные guards неизменны; cleanup SQL/TTL не менять. Retention registration не означает ограниченный срок: parent может храниться бессрочно из-за ledger/rating. Неактивная схема не пишет rows; GC inactive cycle и long-lived parent обязательный блокер до активации будущего SQL writer. Новый scoped/independent/FULL обязательны после исправления.

Correction 1fe04c6: cleanup.py только OWNED_RETENTION metadata, schema/model/cleanup SQL не менялись. Авторские guards+schema+negative controls40 PASS2.85с. Независимо guards/schema/taxi cleanup68 PASS3.75с и общий cleanup20 PASS3.49с, exit0, Ruff/diff PASS, P0/P1/P2 нет. Логи /tmp/taxi-history-guards-independent.log и -cleanup.log. Точный CASCADE/PK proof, отрицательные варианты отвергаются. PG27 author/independent остаётся применимым к неизменной схеме. Detect-secrets всех12 изменённых файлов 0 findings. Повторный FULL следующий gate, исходный отказ не перезаписывается.

## Финальная локальная приёмка

Повторный FULL на 3b1a361: **7356 PASS, 146 skipped, 815 warnings**, actual exit 0, 723.74 с. Лог /tmp/taxi-history-schema-full2.log, exit /tmp/taxi-history-schema-full2.exit. Исходный FULL с 3 отказами сохранён отдельно; guards исправлены доказательно, не отключены. PG27 author3.36с / independent3.28с PASS применим к неизменным model/migration/tests. Независимая correction приёмка68+20 PASS. Amio получил ресурс через followup после actual exit.

Это проверенная неактивная foundation, НЕ rollback fix, НЕ activation/mixed-version/GC approval. Схему надо установить до новых ORM readers, но существующая БД не мигрировалась. Новая таблица не заполняется runtime; GC для долгоживущих parent остаётся блокером активации. Отдельная ветка без main/production.
