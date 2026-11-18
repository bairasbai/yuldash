# Tried lifecycle: аддитивная схема, план до кода

База 9b988c3, ветка feat/friday-taxi-tried-lifecycle-base-20261118. Основание: docs/friday/taxi-late-effects-boundaries-20261118.md. Только фундамент схемы; протокол Redis и writers не включать.

1. Добавить InstantOrder.tried_lifecycle: nullable String(32), Python default None, без SQL default, индекса, unique и backfill. Это внутренний идентификатор будущего lifecycle (предполагаемый uuid4.hex), не credential и не API-поле. В этом пакете значения не генерируются и не ротируются.
2. Новая Alembic migration cm_instant_tried_lifecycle после cl_instant_search_revision: add nullable VARCHAR(32), online idempotent если колонка существует, offline PostgreSQL DDL. Downgrade только удаляет эту колонку; выполнять исключительно на собственной временной тестовой БД. Реальные существующие БД/production не трогать.
3. Изолированные тесты SQLite upgrade/double-upgrade, legacy statuses и NULL/default, значения32символа roundtrip, legacy SQL writer сохраняет существующий lifecycle (структурная совместимость, не безопасность mixed writers), downgrade сохраняет остальные поля; PostgreSQL offlineDDL и SQLModel metadata. HTTP offered/decline legacy без обязательных полей и без lifecycle в response, runtime остаётся NULL. Только собственные fixture-объекты очищать.
4. Независимая приёмка плана до реализации; затем scoped/ruff, независимая приёмка реализации, новый backend FULL после согласования ресурсов. Отдельная ветка, точное remote tree/readback и recovery bundle.

Схема должна быть установлена до запуска модели, которая SELECT-ит новую колонку; сам этот пакет ничего не мигрирует в deployment. Старый код на расширенной схеме структурно совместим, новый код на старой схеме не объявлять совместимым. Downgrade возможен только после остановки кода, читающего колонку, и уничтожает будущие значения lifecycle.

Никакие late DELETE/SADD/EXPIRE, delayed push, TTL gap, SQL rollback и mixed writers этим этапом не исправлены. Ключ Redis остаётся order_id-only. Не добавлять dual-write/read, обязательность API или скрытую инициализацию. Следующий этап требует отдельного независимого плана writers/NULL-init/rollout, сохранения истории при advance/route/fallback и явной политики существующих legacy orders.

## Независимый review плана

Journal принял e7b95a1 до кода. Дополнительные обязательные проверки: реальные nullable VARCHAR(32)/server_default None; HTTP не выдаёт даже nonNULL sentinel; downgrade сохраняет остальные поля/строки. Повторный upgrade лишь пропускает уже существующую колонку, не обещает валидацию/ремонт schema drift. Несовместимую заранее созданную колонку автоматически не исправляем.

## Кандидат реализации

Модель получила только Optional[str] Field(None,max_length=32); migration cm добавляет nullable VARCHAR(32), без backfill/default/index. Ни один Redis writer/reader/API serializer не изменён. 8 новых тестов: 5 legacy statuses, offline PostgreSQL/model metadata, HTTP NULL и nonNULL sentinel без exposure/init/rotation. Downgrade и double-upgrade выполняются только на собственном tmp SQLite.

Первый scoped 42 PASS/1 FAIL выявил неверное требование теста isinstance(AutoString,String), не дефект DDL: SQLModel оборачивает String. Исправлен только тест: dialect_impl.storage String length32 и compile ровно VARCHAR(32) для SQLite/PostgreSQL, nullable/default проверки сохранены. После исправления 43 PASS/exit0/5.02с (8 новых +7 revision base +28 token), migration chain suites отдельно 8 PASS/exit0/2.28с. Ruff app/migration/newtest F и diffcheck PASS. Настоящая PostgreSQL миграция в этом пакете не запускалась; offline DDL не выдаётся за online PG.

Независимая приёмка f54e856: 51 PASS/exit0/5.48с (8 новых+7revision-base+28token+8migration-chain), ruff F/diffcheck PASS, P0/P1/P2 нет. Detect-secrets по пяти изменённым файлам: 0 находок. FULL ожидается; online PG не запускался.

## Финальный локальный gate

Новый FULL на8a060cb (runtime f54e856): 7069 passed, 133 skipped, 804 warnings, exit0, 656.91с. Из backend: `python -m pytest tests -q --disable-socket --allow-unix-socket`, свежий TMPDIR=/tmp/taxi-lifecycle-base-full, TZ=UTC, DATABASE_URL/proxy unset. Это полный локальный SQLite/fakeredis набор, не remote CI и не online PostgreSQL migration. Две диагностики late-effects по-прежнему наблюдают известные плохие исходы, не исправленную защиту.

После FULL модель/миграция/тесты не менялись. Независимый scoped51PASS, ruffF/diffcheck, detect-secrets0. Отдельная публикация готовится; реальная схема не мигрирована, протокол не включён. Redis late-effects/push/mixed writers/rollback/TTL ограничения остаются.
