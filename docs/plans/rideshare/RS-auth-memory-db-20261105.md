# PLAN04 — одна SQLite RAM, product schema и Session
DRAFT, нужна fresh независимая приёмка. Только план; source/connect/schema/run сейчас запрещены.
Owner будущего среза: /root; автор плана /root/gate175_deadline_analysis. Root ведёт global lease; locks не менять.
S=C:/Users/Bayra/.codex/worktrees/rs-plans-20261024/Yuldash; E=S/test-results/legacy-reconcile-20261102.
V=C:/Users/Bayra/Yuldash/backend/.venv/Lib/site-packages; T=C:/Users/Bayra/AppData/Local/yuldash/legacy-reconcile-20261102.
Мастер155: S/docs/plans/rideshare/RS-legacy-ui-auth-bridge-20261105.md, SHA256 5bece3fa4dab4ea46a3685107ecaf8844134992eaa9b933d9ddfd304272120f5.
Опора20/20: E/auth-bootstrap02-host02-independent-acceptance.json, SHA256 833ba0df88b2d1a3cd6a297771508de932ce7b510459f4f620226c680f54da6e.
22 actual PID закрыты: E/auth-bootstrap02-host02-process-cleanup.json, SHA256 2db25ec1b85a7edf118a6dfad881098238f63f91aea20417011ea2ead39c2b7a.
Source02/03, мастер155, APP/thirdparty неизменяемы. Runtime0; Auth/services/main/server/OTP/телефоны/участники5/7/прод/денежные данные закрыты.

## Проверенные основания
S/backend/app/db.py:31–32 — engine при import; :85–95 — init_db/create_all/миграция; :98–100 — generator Session. init_db не вызывать.
S/backend/app/models.py:1–8 — stdlib/SQLAlchemy/SQLModel/timeutil; :56/127/140 определяет User/RefreshToken/OtpCode, не строки.
S/backend/app/timeutil.py:8–14 — stdlib/utcnow; config-imports внутри функций (:41 и далее). Функции ради схемы не вызывать.
V/sqlmodel/main.py:871–872 — default tablename; models.py:1324 — явное имя; :537–542 — SQLite partial index.
V/sqlalchemy/pool/impl.py:458–472 — StaticPool с одним record; :514–518 пересоздаёт record после invalidation!
V/sqlalchemy/pool/base.py:896 — creator, :1326–1328 — checkout, :786–797 — invalidate/soft_invalidate.
V/sqlalchemy/dialects/sqlite/pysqlite.py:617–625 переводит approved sqlite:// в :memory:; :267–282 требует shared connection для threads.
V/sqlalchemy/orm/session.py:1461 — concurrent Session запрещена; :2490–2512 — close переиспользуем по умолчанию, close_resets_only=False окончателен.
V/fastapi/concurrency.py:18–40 — enter/exit в workers, отдельный exit limiter; V/starlette/concurrency.py:32–34 — anyio worker.
Новые обязательные source SHA256: models.py 26de6770e61c840da401df5412722476db9c865b40a7792f3e877b80f3777a37; timeutil.py e1d198b8e47ad0c612c15d9fe999bb148e2830522279ea62b5b0c39da1b74014.
Worker source SHA256: fastapi/concurrency.py c4718310e40003a72f1440d7e3aa2adebdadd59778b15beb79a4607481388ee3; starlette/concurrency.py 3e9e245d060ef39716c58ed754db7995c62f8581d4aaec8b82e09e73b84a2a83.
Эти exact source/ABI/AST pins включить в будущую регистрацию ДО import. Source подтверждает механизм, не успешный SQLite DDL/HOST04.

## Scope: реальная пустая product schema
По решению Root schema-only productmodels входит в04 сейчас; QA-table не выдавать за схему приложения.
Allowlist app.models+timeutil только в SCHEMA-owner phase; никаких services/main/lifespan/seeds/тарифов/reference initialization.
Новый cold child. Только SQLModel.metadata.create_all(bind=единственная held OWNED Connection), не db.init_db и не миграция.
До source-freeze составить pinned manifest table=True/явных имён/columns/PK/FK/indexes/unique constraints текущих models.
Сверить его с runtime metadata И реальным SQLite inspector, включая partial index; не сравнивать инспектор с самим собой.
Реальный COUNT=0 каждой продуктовой таблицы. Единственное DML — public numeric markers отдельной qa_ram04_probe.
После schema commit проверить отсутствие внешней незавершённой транзакции до Session. SQLite-совместимость пока НЕ подтверждена.

## Единственный physical connection, точечное расширение DENY
Новый controlled source04, не патч живого/замороженного02. Default02-denials остаются.
BOOTSTRAP_DENY → private OPENING → SCHEMA → READY → REVOKED → CLOSED; обратных переходов нет.
Private entry без caller URL/SQL/callback даёт один startup permit. DBAPI аргумент строго :memory:, uri=False/check_same_thread=False; approved URL sqlite://.
Permit потребить ДО первой попытки creator. Ошибка первой попытки — terminal, не retry; второй creator DENY ДО DBAPI.
Обычные sqlite3.connect/engine.connect/raw_connection/Session aliases не восстанавливать. Audit-исключение только для собственного startup call/thread/nonce.
Creator недостаточен: checkout cached connection тоже требует private permit/того же record и DBAPI object.
Held SQLAlchemy Connection открыта до cleanup; strong reference/сравнение is, не переиспользуемый id. Все Session bind к ней.
Никакого raw Engine/Connection/Session или произвольного SQL API наружу. ATTACH/DETACH/file/URI/shared-cache/network запрещены.
На том же ресурсе проверить memory temp-store/journal и database_list; не подключать старую БД.
Unexpected close/invalidate/soft_invalidate/dispose/recreate, смена identity, cancel, expiry/ошибка немедленно REVOKED без IO в callback.
Новая пустая БД не восстановление. Cleanup может закрыть существующее, но не выдать новый connect/Session/схему.

## Session: сериализация и реальный worker-handoff
Private Session(bind=heldConnection, close_resets_only=False), отдельная на операцию; публичные constructors всё ещё DENY.
Один межпоточный permit покрывает enter/body/commit-or-rollback/close; не thread-owned RLock.
Lease связан со state/connection generation/nonce: ожидание ≤1s, active deadline ≤5s monotonic, без продления. Native5/10/120 не меняются.
До подтверждённого close первой Session вторую не выдавать. Expiry не освобождает занятый connection другому worker: revoke/ограниченная уборка/STOP.
Fixture использует actual FastAPI contextmanager_in_threadpool + run_in_threadpool, реальные Session, без HTTP/server.
Event barriers должны доказать ≥2 разных фактических worker-id и отсутствие overlap. Один случайно reused thread — coverage OPEN.
Это проверка нужного lifecycle, ещё не Auth/product get_session integration.
Healthy explicit rollback проверять следующим lease: committed marker есть, rolled-back marker нет.
При exception/cancel — terminal; разрешён только rollback/close УЖЕ существующих объектов, без новой query ради доказательства.

## Существенные fixtures после отдельного допуска
1. Cold positive: один creator attempt/success/physical object; actual schema inspector/пустота; наблюдаемые counters/readbacks, не константы.
2. Sequential Session, worker-handoff и две concurrent операции: causal barriers, maxActive=1, нет чтения незавершённого marker.
3. Commit/rollback; failure/cancel/cleanup-error → terminal/no subsequent authority.
4. Hard/soft invalidation, unexpected close/dispose, first-creator failure → второго DBAPI call/empty replacement нет.
5. Обычные connect/raw_connection/Session/init_db, wrong URL/ATTACH, foreign/stale/replay lease и after-revoke/close → DENY.
6. Pure границы deadline без подмены настоящих часов; timeout/unknown CLOSE → STOP/no next child.
Сохранить20 bootstrap default-DENY oracles; точные новые IDs/N/recipe фиксируются при source-freeze, не считать их уже выполненными.
Только fixed codes/observed counters/schema names: без SQL/row payload/Settings/repr ошибок или хешей данных.

## Допуск, ownership, cleanup
Предлагаемые NEW: E/auth-memory04-source/{qa_bootstrap03.py,bootstrap03_host_case.py,test_bootstrap03.py}, E/auth-memory04-source-{plan,change}.json, E/auth-memory04-root-host.ps1; owner=/root.
Перед файлами exact paths/pins/caps/countable inverse к02. Расширение DENY только private RAM permit; thirdparty/APP/02 не менять.
PLAN → independent review → bounded source → exact security-review → отдельный Root HOST-admission → один run/приёмка schema+Session+cleanup.
Не авторизует ordinary Auth/bridge/private UI/actors; schema сама не доказывает создание/владение участником. Остальное — мастер155.
Base Python3.12.9/PE/SITES и self-PID/FILETIME↔Popen matching, clean-env — как принятый02. Порты не открывать; не объявлять OS port measurement.
Future Root cwd T/auth-memory04-owned; сейчас не создавать. Future L/auth-memory04-host.stdout.log, stderr.log; E/auth-memory04-host-results.json; L как Source02.
Пределы120s parent/20s child/3+3s stop/8192 на поток/150s outer; unknown completion/identity mismatch — STOP/точная уборка Root, без авто-повтора.
Revoke → rollback/close Session → close held connection/engine → known CLOSE → сверка ВСЕХ actual PID/birth/argv, не только launcher.
Сохранить минимальные public receipts; затем убрать own runtime logs/empty cwd по правилу. DB-file/sidecars не создавать; OS physical-erasure/global no-disk не обещать.
Сейчас один PLAN≤12KB. Source04/import/connect/schema/backend fixture/SDK/API/server/actor run0; другие файлы и locks не менять.
