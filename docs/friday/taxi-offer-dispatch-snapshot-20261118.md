# Offer dispatch: immutable snapshot и ограниченный guard

База 9090989, отдельная fix/friday-taxi-offer-dispatch-snapshot-20261118. Авторизация родителя: реализовать отдельно после независимо подтверждённых диагностик; не обещать защиты поздней доставки.

Применяется reviewed план и все условия из taxi-offer-dispatch-boundary-20261118.md. Runtime writer владеет только instant_service.py; test writer отдельными тестами и четырьмя direct-helper suites; координатор docs. До runtime freeze подтвердить контракт factory/helper, затем новый scope, PG и FULL по очереди с Amio, независимая приёмка и отдельная публикация.

## Критерии

- Fresh DB scalar frozen snapshot собственного successful UPDATE до commit под удерживаемым lock; recipient и генерация не adopt после commit. Полнота compensation fields проверяется против единого списка.
- Payload использует snapshot, прежние price/fee/санитайзер/optional metadata контракты сохраняются. Private helper не имеет unchecked ORM bypass.
- Финальная проверка после вычислений сравнивает offered/recipient/token/revision/lifecycle/expiry; nonNULL deadline ещё в будущем. Missing/error failclosed. Отдельная short read Session закрывается до transport, без FOR UPDATE и без rollback/mutation caller; SQLite shared connection и PG требуют явных тестов.
- Captured created_at fallback единый. Legacy NULL metadata omission совместима, но NULL не гарантия уникального поколения.
- Guard и send не атомарны: переход после guard и поздняя доставка остаются возможны. Никакой универсальной приватности свободных адресных текстов, actual FCM/UI/Android/PWA интеграции не заявлять.
- Старые диагностические PASS доказывали дефекты; в новом пакете regression должна требовать отсутствие stale sends. Старые логи сохраняются, ожидания меняются по исправлению, не маскировке.

Никаких main, production, реальной существующей БД или повторов приостановленных операций.

## Контракт до реализации и shared-connection ограничение

Frozen `_OfferSnapshot`, factory `_capture_offer_snapshot(session, order_id, driver_id)`, dispatch `_push_offer(session, snapshot)` без ORM обхода. Factory сверяет собственный recipient; matcher дополнительно сохраняет сгенерированный token до UPDATE для проверки принадлежности snapshot, не принимает новое поколение после commit.

Guard не создаёт отдельную Session при bind Connection; при StaticPool/SingletonThreadPool также всегда failclosed. Иначе close guard может откатить caller writes на общем DBAPI connection. Это сознательный пропуск отправки в опасном shared-connection окружении, не отключение проверки. Отсутствие caller transaction не исключает чужую транзакцию на том же shared connection, поэтому исключений для idle shared pool нет. Нормальные PG/fileSQLite engine проверяются отдельно. Тест должен доказать сохранность pending и flushed caller writes реальным commit и свежим чтением, не одним identity map assert. Работающая production база не исследуется/не меняется.

## Кандидат и авторские результаты

Runtime 59b8f58: frozen 16-field snapshot, scalar SELECT до commit, own issued token/revision/lifecycle проверены. Guard after helpers сравнивает owner/status/generation/expiry и закрывает отдельную Session до transport. Общие пулы и Connection bind всегда failclosed. Изменены только instant_service runtime, изолированный PG runner и тесты/документация; schema/API/actions/Redis timing/санитайзер не менялись.

Авторский первый scoped: 166 PASS, 2 FAIL, 3.50 с, исходный `/tmp/taxi-offer-dispatch-first-scoped.log`. Причина: старый UTC-тест вручную заменял canonical naive DB snapshot на aware datetime, exact guard корректно не считал их одинаковыми. Исправлен только тест: aware input canonical persistence → DB factory snapshot → literal UTC. Clock контролируемый; guard не ослаблялся. Затем expanded 262 PASS, exit 0, 8.37 с, `/tmp/taxi-offer-dispatch-expanded-final.log`. Старый F811 imported fixture/unused parameter устранён usefixtures без suppression; address suite 7 PASS, 2.01 с.

Новые проверки сохраняют actual money/helpers, compensation field completeness, real decline/cancel, pending/flushed caller writes с commit/freshread в shared pools и Connection bind, failclosed/error/missing/expired, immutable payload при ORM mutation, отсутствие guard transaction при transport и честную late-after-guard границу. Defensive created_at NULL fallback проверен unit row stub: штатная schema NOT NULL.

Авторский disposable PG: 21 PASS, exit 0, 3.14 с, `/tmp/taxi-offer-dispatch-pg.log`; cleanup подтверждён. Новые 4 cases: committed supersession skip и pending/flushed caller isolation, guard closed before transport, concurrent UPDATE успевает во время transport без row lock. Это не доставка FCM и не атомарность guard/send. Ruff F всех изменённых Python файлов и diff-check PASS. Независимая финальная приёмка/FULL ожидаются.

Независимая финальная приёмка 59b8f58: 336 scoped PASS, 8.46 с; disposable PG21 PASS, 3.56 с, exit 0, временных кластеров нет. P0/P1/P2 не обнаружены, Ruff всех changed tests/app и diff-check PASS. Detect-secrets всех изменённых файлов: 0 findings. Runtime/tests после freeze не менялись. Следующий gate — новый FULL, без claims о remote CI или реальной доставке.


## Финальный локальный gate

FULL на 42a54dd (runtime/tests 59b8f58): **7318 passed, 140 skipped, 804 warnings, 707.90 с, exit 0**, `/tmp/taxi-offer-dispatch-full.log` и `.exit`. Команда backend `python -m pytest tests -q --disable-socket --allow-unix-socket`, fresh TMPDIR, TZ=UTC, lifecycle flag false, DATABASE_URL/proxies unset. 21 PG case пропускаются в SQLite и отдельно пройдены дважды на disposable PG. По actual exit слот передан Amio. После freeze runtime/tests не менялись.

Это локальная backend приёмка, не remote CI, реальная FCM доставка или клиентская UI проверка. Frozen snapshot устраняет смешение чужого нового offer с прежним recipient; guard подавляет изменения, видимые в момент проверки. После guard всё ещё возможны смена статуса и поздняя доставка. Shared-pool/Connection bind сознательно failclosed; guard DB failure также skip push, автоматический retry не добавлен. Ограниченная адресная грамматика, legacy NULL и mixed-version границы не расширялись. Main, production, реальные существующие БД и paused операции не затронуты.
