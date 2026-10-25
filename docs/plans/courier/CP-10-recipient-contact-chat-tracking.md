# CP-10 — Получатель связь и отслеживание

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | передача ПД/согласия, SMS/телефония договоры, политика return tracking |
| Зависит от | CP-02/07/09; проверенный RS-06 receipts; LG-06 |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 04,07,08,09,10 |
| Зона файлов | backend/app/routers/{share, family, chat}.py, livepos.py, models.py; Android ParcelsScreen.kt/ParcelChatScreen.kt/CourierLocationService.kt/data/FcmService.kt; PWA api/family.ts/ParcelChatScreen.tsx/ParcelTrackMap.tsx |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Получатель без приложения видит свою посылку, не чужие адреса, может связаться и получить код. Обе стороны знают, что сообщение отправлено, доставлено или только ждёт сети.

## 2. Что уже есть в коде

Android recipient share UI ParcelsScreen:2942–3030; PWA family wrappers127–132 без найденного UI. ParcelTrackMap:23–90 selected mounted socket, no reconnect/staleness. Android location service есть; CourierReturnTrackingTest:48–80 намеренно запрещает live GPS на возврате.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Паритет receiver invite/revoke Android/PWA, limited scopes и channel ownership; надёжный chat/reconnect, честная platform capability. Не включать return GPS без отдельного privacy решения.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Invite scope/expiry/hash/revoke; sender consent не автоматически recipient processing basis. Допустимый канал по LG-06.
2. Sender/recipient/courier contact ACL по phase; reassignment лишает старого курьера доступа.
3. Receipt на message IDs, outbox доставки, masked phone provider опционален, raw phone только по approved минимальному договору.
4. GPS sequence/freshness/generation terminal closes/revokes, snapshot resync при gap.
5. Recipient no-account complaint/proof имеет bounded authority; не меняет budget/route без отправителя.

### B Android

Процесс death/background GPS/notification cold start на верном заказе; permissions explain/denied. Один service управляет всеми разрешёнными active IDs; stop callback не считается network revoke.

### C PWA

Добавить recipient link UI и revoke; browser закрыт → не обещать background GPS. Reconnect/backoff/resync и stale marker; multi-order selected карта не обозначается whole fleet coverage.

### Межмодульный договор R2

K2 replace_recipient_authority/revoke_recipient_authority доступны sender либо уполномоченному merchant; support только разрешённый recovery с audit. Verify_recipient_channel выполняет только доказанный владелец нового канала; sender/merchant не подтверждает чужой канал за него. Смена лица/канала увеличивает authority version, отзывает старые grants, unused challenges и contactless consent атомарно; новое лицо подтверждает канал. Изменение адреса/цены/условий требует M3 amendment, а не скрытый grant update. Поздняя выдача OTP старой authority отбрасывается.

### Уточнение R3

K2 разделяет replace (new pending generation), revoke (revoked), verify (current pending → active без bump), issue/rotate grant (same receiving authority). POST recipient-grants принимает только issue/rotate; смена лица/канала идёт отдельной authority command. Verification-only invite не даёт tracking/расчётных прав.

### Уточнение R4

Отдельное действие «Отключить ссылку» вызывает revoke_recipient_grant: текущий grant и его unused proofs отозваны, authority/channel остаются, G2 не выпускается. «Сменить получателя» — другая операция. Пассажир чужой поездки не видит receiver/содержимое посылки.

### Честный ETA R4

C9 обязателен: единый target-stop forecast, отдельные captured_at/received_at/generated_at, expiry и accuracy provenance, route/assignment/sequence guard; свежий GPS не омолаживает старый прогноз. Трафик/ready/route change/handoff пересчитывают либо инвалидируют ETA. Абсолютное окно, его сдвиг и customer elapsed видны отдельно от платного F5 wait. Нет вымышленного движения, вечных «5 минут» или скрытого optimistic smoothing.

Privacy фильтруется сервером, включая геометрию пассажирских остановок и return roles. Уполномоченный отправитель может видеть status/ETA возврата по scope; неудавшийся получатель не видит последующий чужой путь. Live return GPS не включается этим договором. Adapter не выдумывает timestamp/accuracy units; physical Android/PWA/background/permissions и город/село/межгород проверки обязательны.

M7 добавляет bounded TrackingSnapshot/Forecast с input hashes/seq, не второй order/receipt ledger. CP-22 панели раздельно показывают freshness, signed error/interval coverage, unavailable denominator, delay disclosure latency, split-brain/privacy. Gates численных thresholds/provider/SLO/retention остаются OPEN.

### API и события

C4 recipient-grants/track, chat receipt contract reused, GPS stream sequence. Grant не общий auth token; recipient DTO исключает другие stops/полные контакты.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M7 grants/scopes/recipient authority version/channel verification/revocation + event receipts reuse. GPS buffer bounded, retained evidence отдельно от live; no arbitrary forever. Account switch clears private caches.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Координаты обновлялись… назад». «Ссылка больше не действует». «Сообщение ждёт отправки». «Получатель может увидеть только эту доставку».

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

No app/unverified channel/revoked invite/offline stale GPS/reconnect/terminal/no geo permission/chat pending; accessibility recipient page.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

### Новые безопасные проекции

GET tracking/recipient track и tracking.updated/eta.changed/degraded/restored используют C9, общий allowlist и resync; локальный навигационный ETA не заменяет опубликованный forecast.

## 7. Тесты

**REV-MAP-01 — детализация C9 и ETA-N02/05/06/08/16/18/19, не новый tracking-договор.** На Android и PWA воспроизвести одну и ту же таблицу; numeric freshness/skew limits берутся из явно выбранной test policy и не утверждают production thresholds.

| Проверочный вход | Наблюдаемый критерий |
|---|---|
| Старый captured_at при свежем received_at; будущий captured_at вне допуска; duplicate/out-of-order seq | Receipt не подменяет GPS-время; stale/implausible/unknown по C9; старая точка не вытесняет current и не становится fresh из-за будущего времени |
| Нет новых событий; затем reconnect/GET с той же точкой | Возраст растёт и stale наступает по policy без входящего event; reconnect не сбрасывает возраст и не возобновляет вымышленное движение |
| Выбран заказ B, поздний socket/HTTP/map callback заказа A; смена assignment/route | Account/order/generation/seq guards применяются до отображения; точка/ETA A не попадают в B, старый callback не оживляет подписку |
| Logout/account switch/cross-tab logout, unmount, terminal/revoke | Старые subscriptions/watchers/timers прекращены, поздние callbacks игнорируются; cache не раскрывается другому аккаунту, разрешённая история не превращается в live-доступ |
| Map SDK/load failure, permission denied/withdrawn, background ограничения | Честный fallback статуса/возраста/доступного recovery без ложной точки; остановка ненужных callbacks и bounded retry без утечки слушателей; сбой карты не закрывает custody |
| RU/BA, отсутствие/старение точки, restore/retry | Одинаковая семантика состояний и действий, no fake movement; BA linguistic review отдельно, screenshot/interaction evidence для обеих платформ |

Проверить lifecycle также после повторных mount/unmount/reconnect: число живых подписок ограничено активным разрешённым scope, предыдущий callback не пишет после cleanup. Unit/fake-clock тест не заменяет browser/device evidence CP-24.


SIM-34: sender alone cannot verify recipient; pure grant revoke без новой ссылки/смены лица, old G denied, separate verified channel remains valid по policy.

R3-GRANT: existing active authority grant rotate без recipient change; old grant/proof revoked, новый active; late old verify denied, valid new verify не самоуничтожается.

R2-RECIPIENT: revoke/consume race и delayed code callback; новый recipient не получает чужую финансовую историю; revoke invalidates media и POD, не переписывает прошлый verified handover.

SIM-03/11/15; IDOR tokens/media/referrer leakage, recipient link revoke then replay, old courier chat, callback after terminal, two active parcels, PWA hidden tab, real push/GPS physical gate.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

SIM-37 и ETA-N01…22: old fix/new HTTP, fresh fix/old ETA, traffic5 → 20, clock skew/reboot, stale/no provider, wrong door, route/handoff race, hidden passenger geometry, return role scopes. Проверить реальными устройствами до runtime acceptance; эти проверки ещё NOT_EXECUTED.

## 8. Приёмка

Получатель проходит путь с настоящей ссылкой и неверным/верным кодом; DTO parity и revocation доказаны. Return tracking baseline не изменён молча.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не считать push received==read/delivered. Не публиковать bearer URL в логи/аналитику. Не обещать masked calling до provider integration; не просить сторонний аккаунт за получателя.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
