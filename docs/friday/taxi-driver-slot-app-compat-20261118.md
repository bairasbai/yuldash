# Driver-slot candidate: actual-code compatibility in disposable PostgreSQL

Base ea52e10 / remote362d4ed. Следующий test-only план до независимого review. Не migration, не production activation. Runtime/model/migration files не менять.

## Цель и изоляция

Предыдущий CASE UNIQUE доказан только на raw SQL fixture. Теперь проверить реальный app код, не выдавая fixture за API. Индекс и optional shape CHECK временно устанавливаются на настоящую `public.instantorder` ТОЛЬКО внутри нового пустого одноразового PG-кластера, который штатный runner создаёт специально для этого suite. Production DB/schema не подключать/не читать/не менять.

Предлагается один явный allowlisted runner mode `driver-slot-app-compat`; default mode сохраняет прежний251-case regression. Новый mode запускает только новый testfile в новом cluster/fixed guarded DB. Не передавать arbitrary pytest args/URL/schema из окружения. Guard database/data_directory/host/port до lifespan/DDL как прежде; отдельная проверка mode до bootstrap. Новый suite не добавлять в общий список: старые synthetic rows могут содержать дубликаты, а candidate не должен менять их поведение. Старый regression запускать вторым отдельным invocation.

Fixture после настоящего app lifespan/create_all ставит уникально именованный CASE index; проверяет его actual definition. Optional named CHECK ставится только в отдельном test context; exact names отслеживаются. Cleanup DROP только собственных index/CHECK, не ALTER чужих полей/constraints; отдельная healthy transaction после завершения всех workers/caller rollback. Удаляются только созданные fixtures/весь disposable cluster штатным runner; никакой чистки реальной БД. Никакой скрытой дедупликации или глобального DELETE ради прохождения CREATE INDEX.

## Проверки actual кода

1. Actual `begin_first_sql_search` + `publish_sql_offer_lab` на реальных models/Connection: два разных orders одного driver; candidate index не позволяет оба committed publication. Два physical connections, actual blocking/readback; conflict exact23505/ownindex. При конфликте savepoint возвращает searching owner/history, caller outer rollback обязан выполниться. Winner rollback даёт второму успех. Это actual storage helper, не production matcher admission.
2. Actual legacy `try_offer_next`: зафиксировать выбор candidate через existing controlled candidates/Redis fixture, не подменять offered UPDATE, commit, `_remember_tried` или error. Конкурирующий занятый driver заставляет реальный UPDATE получить23505/ownindex до Redis remember/dispatch. Capture Redis EXEC и transport spies, без actualnetwork. Caller Session после exception требует rollback; independent SQL readback и history/Redis показывают отсутствие частичной публикации проигравшего. Это проверка conflict handling gap действующего service, не успешный next-candidate retry; не считать исключение пользовательским409 без настоящего handler.
3. Actual authenticated HTTP `POST /me/delete`: fresh synthetic user/driver, чужой offered order, без debt/live-owned trip/media. С index-only ожидается обычное завершение API с user removed, offered recipient NULL (это сохранённая existing policy/known malformed shape, не lifecycle fix). С optional shape CHECK тот же API путь должен реально встретить23514/ownconstraint; test client может пробросить server exception, это не готовый409. После request Session закрытия/rollback independentconnection подтверждает user/offer и связанные ранее удаляемые synthetic rows сохранены, никаких частичных committed изменений. Не mock auth, guard_can_delete или delete_user_account. Secrets/tokens не печатать. Transport/media boundary отключить безопасно, fixtures не содержат настоящих media.
4. Индекс/constraints отсутствуют после fixturecleanup; новый чистый suite режим не влияет на default regression. Реальный account API успешный control и failure diagnostic нужны рядом. Если исходные ожидания неверны, сохранить actual failure и уточнить вывод, не подгонять outcome/подавлять неизвестную ошибку.

## Честные границы

Candidate install в disposable app schema не означает разрешение миграции. Shape CHECK incompatibility account API ожидается как blocker; не исправлять удаление пользователя в тестовом пакете. Unique failure в legacy matcher может стать500/abortedsession: это доказательство необходимости future bounded conflict handling, а не safe mixed fleet. User→Order/global graph/crossmode/admission/time-expiry остаются открытыми. Доставка, true networklostACK, exactlyonce и online indexbuild не проверяются.

Future runtime решение должно сохранять rollback всей businessunit, distinguish exact uniqueindex (не любой IntegrityError), release failed transaction before nextcandidate/recompute, bounded candidatebudget, immutable snapshot/dispatchaftercommit и allwriter drain. Appcompat experiment не должен сам менять эти paths.

## Gates

Journal independent pre-code plan → tests+allowlistedrunner mode → static/collection → author newcompat PG + unchanged default PG regression → independent review и оба PG → отдельная публикация exacttree. No new runtime code; старый FULL не объявляется прогоном новых tests, PG доказательства явные. Тяжёлый ресурс согласовать с Amio. При unexpected external call stop по reliability, без fallback на production.

## Read-only исследование actual paths

Подтверждено: guard_can_delete проверяет passenger_id/driver_id, не offered recipient; offered-only driver проходит этот gate. delete_user_account имеет один SQL commit, затем external media/storage/presence cleanup. В тестах failure доcommit должен иметь нулевые postcommit effects; success spies только external boundary. Auth не подменяется; добавить unauthenticated401/nochanges и после успешного удаления oldtoken401. Catch IntegrityError через TestClient означает проброшенное server exception, не наблюдённый HTTP500; писать точный результат. CHECK снять до cleanup own data, иначе cleanup сам может воспроизвести incompatibility. Matcher forced candidate — явная TOCTOU модель, не доказательство настоящей eligibility; actual UPDATE/CAS/snapshot/history сохраняются.

## Принятый независимый pre-code контракт

Journal PASS `b352422` только fresh disposable cluster. Runner mode enum validate до bootstrap; runner pre-pytest guard проверяет fixed database/data_directory/host до import conftest/app, не только test fixture. Inherited DATABASE_URL/PG options/proxies scrubbed, no arbitrary pytest args; default251 suite отдельным fresh invocation без изменения состава. DDL после lifespan, owned names/actual definitions, no dedup/globalDELETE. Cleanup healthy connection после rollback/join; CHECK снять до own fixture cleanup, ошибки не скрывать. Legacy exact23505/index + no actual Redis EXEC/tried delta/push, caller rollback и fresh owner readback; это не409/retry. HTTP strict exact23514/constraint, related synthetic rows/user/order restored, zero postcommit external calls; Python exception не называть HTTP500. SQL/remember/auth/guard/delete не подменяются.

## Первый actual-app PG и сохранённый отказ

Freeze `ce71c07`: 5 cases collected/static PASS. Первый actual PG дал **4 PASS, 1 FAIL**, exit 1, 2.10 с (`/tmp/taxi-slot-app-compat-pg.log` / `.exit`). Настоящий index-only HTTP delete уже вернул200, old token401, SQL deletion assertions прошли. Ошибка только в fixture trace: delete_user_account вызывает `_safe_unlink_media` для пяти пустых synthetic URL (avatar + четыре TaxiApplication поля), прежде чем storage/presence; тест неверно ожидал только последние два вызова. Пустые URL в настоящем helper no-op, реальных media не было. Исправление должно сохранять spy и явно проверить пустые аргументы/точную последовательность, а strict failure — всё ещё ноль postcommit calls. Исходный FAIL не скрывать; app код не меняется.

Повтор PG2 на `6d9b886` выявил ещё одну fixture assumption: **3 PASS, 2 FAIL**, exit1, 1.89 с (`/tmp/taxi-slot-app-compat-pg2.log` / `.exit`). Фактические synthetic media значения — `('', None, None, None, None)`, а уточнённый тест без проверки model defaults ожидал пять пустых строк. Исправление сохраняет точные None и порядок вызовов, не нормализует и не разрешает произвольный trace. Правило для этого suite: expected cleanup inputs выводить из проверенных model defaults/реального predelete SQL, не превращать семантическое «нет media» в догадку о типе/строке. Старые FAIL остаются; default regression запускается независимо, runtime не изменялся.

## Исправленная actual-code проверка

Final tests `0083749`: independently verified model defaults `User.avatar_url=''`, четыре TaxiApplication media fields `None`; spy сохраняет исходные None, exact ordered trace и strict zero не ослаблены. Author compatibility PG3: **5 PASS**, 2.00 с, actual exit0 (`/tmp/taxi-slot-app-compat-pg3.log` / `.exit`). Отдельный unchanged default regression: **251 PASS**, 11.07 с, exit0 (`/tmp/taxi-slot-app-compat-regression.log` / `.exit`), собственные DDL/cluster очищены. Runner invalid/extra/empty mode exit64 доbootstrap, default suite состав неизменен.

Реальные outcomes: actual storage unique conflict откатывает offer/history; legacy matcher выдаёт exact23505 доRedisEXEC/push, после explicit callerrollback SQL unchanged; actual authenticated account delete с index-only200/oldtoken401, с strictCHECK пробрасывает23514, user/offer/SafetyProfile доошибочногоDELETE восстановлены, postcommitcalls0. Это diagnostics, не реализация409/retry или lifecycle fix. Independent оба режима ещё впереди.

## Независимый итог

Independent `0083749`: dedicated actual-app **5 PASS**, 2.37 с, exit0; unchanged default **251 PASS**, 12.61 с, exit0. Разные fresh clusters, owned candidate DDL cleanup assertions прошли, остаточных cluster каталогов нет. Логи `/tmp/taxi-slot-app-compat-independent-pg.*` и `/tmp/taxi-slot-app-compat-independent-regression.*`. Финальная приёмка PASS, открытых P0/P1/P2 нет. Secret scan0; ресурс передан Amio.

Предыдущие4PASS1FAIL и3PASS2FAIL сохранены как ошибки fixture assumptions, исправления проверены без подавления SQL ошибок. Runtime/migrations/models не менялись, новый FULL не запускался. Candidate unique index выглядит совместимым с проверенным delete path, но strict shape CHECK несовместим с нынешним API; legacy matcher не обрабатывает23505 как штатный новый подбор. Следующий этап — отдельный plan исправления lifecycle удаления и ограниченного conflict handling, не активация constraint или rollout.
