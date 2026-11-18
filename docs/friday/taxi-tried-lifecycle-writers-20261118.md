# Tried lifecycle writers: план до реализации

База 6e08f6c (schema-only, remote9241c436), отдельная ветка fix/friday-taxi-tried-lifecycle-writers-20261118. Реальную БД/production не мигрировать, main не менять. Предыдущие прерванные операции остаются на паузе.

## Ограниченный результат

Подготовить opt-in namespace Redis для нового жизненного цикла tried. Новый config `taxi_tried_lifecycle_enabled=False` управляет только переходом NULL→новый идентификатор на разрешённой границе цикла. Уже nonNULL lifecycle новый код обязан читать/писать даже при выключенном флаге. Сам флаг нигде не включать кроме изолированных тестов. Старый код не поддерживает nonNULL namespace: mixed fleet и rollback binary после активации не безопасны.

## Матрица переходов

- start из created/scheduled/expired: в том же условном UPDATE, что status/revision, создать новый uuid4.hex если flag on ИЛИ существующий lifecycle nonNULL; иначе NULL. Решение по свежим SQL значениям, не stale ORM.
- start уже searching: сохранить lifecycle, включая NULL. Это продолжение текущей истории, не новый цикл.
- advance offered→searching, route destination/waypoints, fallback: всегда сохранить lifecycle. revision продолжает меняться по прежнему контракту.
- matcher legacy searching NULL: НЕ переносить/очищать legacy tried и НЕ инициализировать namespace. Существующая revision NULL-init остаётся, сама по себе lifecycle не создаёт. NULL-init lifecycle разрешён лишь на настоящей границе нового цикла.
- accepted cleanup: удалить ключ lifecycle принятого назначения, снятого fresh DB scalar до commit; добавить lifecycle в assignment equality, не принимать новые значения после commit.
- expired cleanup: захваченный matcher lifecycle входит в CAS и fresh locked equality; удалить только этот конкретный ключ.
- reassign cancelled→searching: новый lifecycle если old nonNULL либо flag on, в том же UPDATE с revision; fresh scalar lifecycle и revision снять под UPDATE-lock до commit. Добавить бросившего водителя только в захваченный новый ключ. Старые leftover tried не копировать: обычный accept уже очистил историю. Эта политика не обещает сохранить остатки неуспешного legacy cleanup.
- terminal cancel: снять lifecycle с успешного UPDATE под DB-lock до commit, cleanup по этому старому ключу. Не расширять scope изменения cancel/fee/notifications.

## Все Redis операции и CAS

`_tried_key(order_id,lifecycle=None)` сохраняет прежний ключ для None, namespaced key для валидного nonNULL. `_tried_set` и `_cleanup_tried` получают захваченный scalar lifecycle; никакого перечитывания текущего владельца внутри Redis helper. Matcher SMEMBERS/SADD/EXPIRE используют один snapshot; SQL offered/expired CAS включает lifecycle exact equality (NULL не coalesce). Late DELETE/SADD/EXPIRE старого namespace не адресуют новый. Reassign SADD/EXPIRE тоже используют снятый до commit scalar, не обновившийся ORM.

Валидный nonNULL — ровно 32 lowercase hex символа; None — единственный legacy. Invalid matcher/reassign/newstart fail-closed без Redis/lookup, не исправлять/ротировать мусор молча. Accept/terminalcancel сохраняют committed пользовательское действие; invalid cleanup пропускается без legacy fallback. Route/fallback не меняют lifecycle, дальнейший matcher на invalid останавливается. Не менять общие Redis connection/timeouts. TTL3600, отдельные SADD/EXPIRE и ошибки остаются прежними; атомарный TTL и SQL rollback-before-commit SADD не входят в гарантию. Не убирать существующие row-lock cleanup guards: они ещё нужны legacy path и прочим эффектам.

## Rollout и обратимость

1. Только разработческая схема-before-code. Deploy/migration/включение требует отдельного разрешения.
2. Сначала все API/worker поддерживают protocol, flag default-off, старые процессы и in-flight команды drained до активации.
3. При активации активные NULL orders остаются legacy до нового cycle boundary. Никакого dual-read/write, copy legacy history или eager NULL conversion. На таких orders защиты namespace ещё нет, это явно ожидаемый compatibility path.
4. Flag off прекращает новые NULL conversions, но existing nonNULL по-прежнему honors namespace/rotates на новом cycle. Откат на старый binary нельзя считать выключением флага; сначала завершить/drain nonNULL orders или отдельный migration plan. Downgrade schema тоже не входит в пакет.

## Проверки и независимая приёмка

До кода независимый review матрицы/rollout. RED: queued timeout DELETE через реальный accept→cancel/reassign удаляет новую историю при enabled-кандидате до внедрения namespace. После реализации позитивные retained-history проверки и явный default-off legacy diagnostic.

Tests: flagdefaultoff; NULL/nonNULL×flag on/off×startstatus; stale ORM decision; concurrent NULL first boundary; late DELETE/SADD/EXPIRE; real accept/reassign; expiry/restart; terminalcancel; preservation advance/route/fallback; exact NULL CAS loser no effects; invalid token failclosed; existingnonNULL flagoff; no APIexposure. Собственные fixtures очищать.

Настоящий disposable PG: конкурирующие start/NULL boundary и reassign/cleanup locks с distinct backend PID/pg_blocking_pids, exception release. Без existing DB. Scoped/ruff, независимый reviewer не автор плана, согласованный FULL, отдельная публикация с exacttree/readback.

Late push, максимум общей Redis latency, same-lifecycle rollback SADD, TTL gap и mixed writers не исправляются. Это не полная распределённая атомарность и не готовность production rollout.

## Независимая оценка до кода

Journal условно принял31c387a; недостающая invalid semantics выше конкретизирована до реализации. Обязательные условия: lifecycle DBscalar до каждого commit, cancel до первогоcommit (не послеDriverCancel); один immutable matcher snapshot на read/SADD/EXPIRE/CAS; postcommit ownership включает captured revision+lifecycle; freshstart SQLCASE или rowlock; no adoption чужого поколения. CAS loser/invalid/exception без Redis effects, accepted/cancel invalidcleanup не отменяет committed действие. TTLgap/rollbackSADD/mixedfleet/push остаются внеscope.

## Реализация и авторские проверки

Runtime1f4f3fa: default-off config, helpers captured nullable lifecycle, exact lifecycle в owner/CAS, fresh source scalar FOR UPDATE перед start/reassign, lifecycle SQLCASE в том же UPDATE. ActiveNULL не мигрируется. Accept snapshot включает lifecycle; terminalcancel снимает scalar до первогоcommit. Invalid cleanup skip не отменяет committed accept/cancel. TTL/ошибки прежние. 4 старых теста адаптированы только к явной передаче lifecycle, assertions не ослаблены.

Исходный RED: настоящий start→offer→accept cleanup timeout→cancel/reassign→newSADD→lateoldDELETE стирает новую историю, 1FAIL/exit1/1.58с. После реализации 67 новых SQLite cases плюс прежние regression suites:175PASS/exit0/4.85с. Первый combined запуск174PASS/1FAIL был отсутствующим новым required expected_lifecycle аргументом в старом прямом вызове expiry helper; добавлено explicitNone для legacyfixture, исходныйлог сохранён.

3 новых PG: два tokenizedacceptcleanup→actualcancel/reassign (обычный/exception release) и два concurrent createdNULL callers, где secondstart реально ждёт lock и сохраняет lifecycle первого. pg_blocking_pids/distinctPID, boundedbarriers, finallyrelease; cleanupтолькосвоиобъекты. Новый disposablePG17PASS/exit0/2.97с (14 прежних+3 новых), кластер удалён. Это TCPloopback свежийкластер, не существующаяБД. RuffF/diffcheck/bashsyntaxPASS. Независимаяприёмка/FULL ожидаются.

Независимая приёмка1f4f3fa: expandedscoped236PASS/exit0/8.79с, realPG17PASS/exit0/2.78с, runnercleanup подтверждён; P0/P1/P2 нет. RuffF/diffcheckPASS, detect-secrets по11файлам: 1 finding Secret Keyword в config.py:4, строка полностью совпадает с базой6e08f6c; в новых строках находок нет. FULL ожидается.

## Финальный локальный gate

Новый FULL наc7823eb (runtime1f4f3fa):7136 passed,136 skipped,804 warnings,exit0,649.35с. Изbackend: `python -m pytest tests -q --disable-socket --allow-unix-socket`, TAXI_TRIED_LIFECYCLE_ENABLED=false, TZ=UTC, TMPDIR=/tmp/taxi-lifecycle-writers-full, DATABASE_URL/proxyunset. 17PG cases вSQLite skipped и отдельно пройдены автором/независимо. Enabled поведение проверяют новые scoped cases с временным test-only override. Legacy late-effects diagnostics по-прежнему показывают ограничения NULL/default-off; delayedpushне исправлен.

ПослеFULLruntime/testsне менялись. Поправлена только точность отчёта scanner:1старыйнеизменённыйdevelopmentplaceholder findingвconfig, новыхнет. Это не доказательство актуального productionENV/ключа. Независимые236scoped+17PGPASS,ruffF/bashsyntax/diffcheckPASS. Готовится отдельная публикация; productionмиграции/активациинет.
