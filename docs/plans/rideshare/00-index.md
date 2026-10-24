# Попутка — список планов

Правила работы по планам — [README](../README.md). Основа кода: `main` `0ef03f4194012431e0996be4d8e748d37e34d63d`.
Текущая доработка сохраняет авторский head `7e532d0e3274c2bc4988f6bf4d4507fbaae753dc` и устраняет замечания
[обзора B1–B20/N1–N8](https://github.com/bairasbai/yuldash/pull/211#issuecomment-6385928475).
Предыдущая формулировка «блокирующих замечаний нет» этим обзором опровергнута. Новая независимая проверка —
на точном SHA после правок. Все описанные продуктовые сценарии/миграции пока **NOT_EXECUTED**.
«Принято» здесь не ставится: приёмка режима — только в [едином реестре](../../audit-blocks.md)
(аудит `W1-E2E-20261018`, семь обязательных сценариев — [состав](../../audit-traceability/rideshare-scope.md)).

Этапы — по решению Александра 24.10 (Q18): 1 — убрать ложные обещания, 2 — основа, 3 — надёжность обеих
сторон, 4 — условия и удобства, 5 — полный прогон. Крупный пункт делится на PR с тегами `[RS-XXА]`, `[RS-XXБ]`…
(деление — в шапке плана); замок — открытый PR с тегом части.

**Столбец «Ждёт «да»»:** деньги и тарифы, миграции рабочей базы, удаление данных, платные сервисы и договоры,
тексты соглашения/политики, класс такси. Выкладка сервера и сайта ждёт «да» **всегда** (`AGENTS.md` §8)
и в столбце не повторяется.

| № | Что получит человек | Этап | Статус | Ждёт «да» | Зависит от | Сценарии |
|---|---|---|---|---|---|---|
| [RS-01](RS-01-honest-promises.md) | Приложение не обещает того, чего нет: выключенные способы входа не видны, код посадки — честная «сверка», онлайн-оплата брони закрыта; единый вид ошибок с кодом | 1 | не начато | Г: замок онлайн-оплаты брони (деньги), текст соглашения | — | E2E-05, E2E-06 |
| [RS-02](RS-02-segment-seats-price.md) | Место освобождается, когда пассажир вышел; цена до своей остановки | 2 | не начато | А: миграция; Б: деньги, миграция и gate D7 до реализации | RS-01 А; Б после А | E2E-01, E2E-02, E2E-03 |
| [RS-03](RS-03-meeting-points.md) | Точное место встречи и высадки на карте, водителю видно только после подтверждения | 2 | не начато | да: миграция, ночная зачистка точек (удаление данных) | RS-01, RS-02 | E2E-01, E2E-05 |
| [RS-04](RS-04-sms-login.md) | Все способы входа: СМС (главный), Telegram, VK, WhatsApp — каждый включает владелец на экране «Выключатели» | 2 | не начато | да: миграции, платные СМС/WhatsApp, VK ID, ключи, договоры, политика данных | RS-01 | E2E-05, E2E-07 |
| [RS-05](RS-05-no-duplicates.md) | Повтор при плохой связи не создаёт вторую поездку или бронь | 3 | не начато | да: миграция | RS-01, RS-02 | E2E-02, E2E-03, E2E-04 |
| [RS-06](RS-06-chat-delivered-read.md) | В чате видно «доставлено» и «прочитано» | 3 | не начато | да: миграция | RS-01 | E2E-04, E2E-05 |
| [RS-07](RS-07-before-booking-terms.md) | До брони видно, как платить, что будет при отмене, и предупреждение о мошенниках | 4 | не начато | да: только часть Б (миграция — способы оплаты водителя) | RS-01 | E2E-01, E2E-03 |
| [RS-08](RS-08-card-payment-switch.md) | Неактивный раздел будущей карты (каркас, hard lock неизменен) | 4 | не начато | да: деньги, миграция | RS-01 Г, RS-04 А1/А2, RS-07 Б | — |
| [RS-09](RS-09-return-and-dates.md) | Обратная поездка одной кнопкой (А) и публикация на несколько дат (Б) | 4 | не начато | денежная часть — gate D7 и отдельное «да» | А: RS-02; Б: RS-02, RS-05 А/Б, RS-10 А | E2E-01, E2E-04 |
| [RS-10](RS-10-route-watch-stops.md) | «Появилась поездка» видит остановки и повторяющиеся рейсы; кнопка «Следить» на сайте | 4 | не начато | да: миграция дедупа уведомлений | RS-01 А, RS-02 А | E2E-01 |
| [RS-11](RS-11-search-history.md) | История поиска: повторить, удалить одну, очистить всё | 4 | не начато | нет | — | E2E-05 |
| [RS-12](RS-12-seats-filter.md) | Фильтр «сколько мест мне нужно» (А — сейчас, Б — по участкам) | 4 | не начато | нет | Б: RS-02 | E2E-01 |
| [RS-13](RS-13-rate-from-notification.md) | Оценка поездки прямо из уведомления (5 ★ с подтверждением или «Есть замечания») | 4 | не начато | нет | RS-01 | E2E-01, E2E-04, E2E-05 |
| [RS-14](RS-14-several-cars.md) | Несколько машин у водителя, места и класс от выбранной машины | 4 | не начато | да: миграция, класс такси, удаление данных (номера архивных машин) | А: RS-01 А, RS-02 А; Г: RS-14 А/Б/В, RS-07 А/В | E2E-01, E2E-05 |
| [RS-15](RS-15-edit-stops.md) | Правка остановок без броней; при бронях цена только вниз | 4 | не начато | да: деньги, gate D7; отдельное «да» на слияние/выкладку | RS-01 А, RS-02 А/Б; RS-10 А — для рассылки | E2E-01, E2E-03 |
| [RS-16](RS-16-full-run.md) | Полный прогон сценариев на эмуляторе: А — стенд и пробный проход, Б — финальный прогон | 5 | не начато | нет | всё влитое в `main`; исключения — решением Александра | E2E-01…06 |

Сценарий W1-E2E-07 (два живых телефона, настоящие пуши/GPS/Telegram, выпуск) по решению Q11 пока
не проходим: попутка остаётся «не принятой» по этому пункту, пока Александр не решит иначе.

Онлайн-оплата и выплаты водителю в попутку не входят (Q5): только «по договорённости».
RS-08 — каркас с `effective=false`; все флаги/ключи/договоры True не снимают hard lock RS-01.
Нужен отдельный принятый банковский проект и разрешение владельца. Push/merge/выкладка здесь не разрешаются.

## Общие механизмы (вводятся один раз)

| Что | Где вводится | Кто пользуется |
|---|---|---|
| Ошибка с машинным кодом: `herr(status, ru, ba, *, code=None, **extra)`, сайт `ApiError.code`, Android `detailCode`; клиенты ветвят по `code`, не по HTTP-статусу | RS-01 | все планы |
| «Настройки владельца с журналом»: `owner_switches.py`, таблицы `appsetting` + `appsettingchange`, экран админки «Выключатели» | RS-04 (А1) | RS-04 (способы входа), RS-08 (оплата картой) |
| Ключ повтора `Idempotency-Key` `[A-Za-z0-9_-]{16,128}`, durable tombstone/сверка исхода; TTL не меняет unresolved-intent | RS-05 | RS-09 (серия: базовый ключ ≤115 + суффикс даты, повтор одной даты тоже series) |
| Места по участкам `ride_seats.py` (`take`, `free_on`, `max_free`, `recompute`) | RS-02 (А) | RS-10, RS-12 (Б), RS-15 |
| Полная `Ride.terms_version`: route/price/stop_prices/seats_total/free/car, atomically increment; expected version + повторная проверка после модерации | RS-02 А | RS-02 Б, RS-05, RS-09, RS-14, RS-15; оба клиента |
| Единый allowlist Booking DTO по роли/фазе, включая команды и replay | RS-03 А | RS-03 Б/В, RS-05, RS-07/08/14/15; расширение DTO не расширяет права |
| `ChatMessageReceipt` по явным message_id; FCM только сигнал перечитать | RS-06 А | Android/PWA; вызов API не означает delivered |
| `enqueue_route_watch(session,ride,source_key,group_key=None,siblings=())` → `RouteWatchEvent` до domain commit; worker `notify_route_watchers(session,event_id)`, `RouteWatchNotice`/Notification атомарно | RS-10 А | RS-09 Б передаёт siblings; RS-15 А вызывает после правки при готовом RS-10 А |

## Порядок миграций Alembic

Голова на `main` `0ef03f4` — `cb_sos_retry_intent_20261021` (одна; сторожа `test_migrations_reach_production.py`,
`test_migrations_match_the_models.py`). Цепочка только линейная, merge-ревизий не делаем.

| Очередь | Пункт | Ревизия (суть) | Что добавляет |
|---|---|---|---|
| 1 | RS-02 А | `<префикс>_ride_segment_seats_<дата>` | места по участкам, `ride.terms_version` default0; старые резервы восстанавливаются при первой записи |
| 1б | RS-02 Б | `<префикс>_ride_stop_prices_<дата>` | цены остановок в рублях (D7), после RS-02 А |
| 2 | RS-03 А | `<префикс>_booking_meeting_points_<дата>` | точки встречи/высадки у `booking` (строго после RS-02) |
| 3 | RS-04 А1/А2 | `<префикс>_owner_switches_<дата>` и др. | `appsetting`, `appsettingchange`; `otpbudgetbucket`, `otpsendattempt`, `otpbudgetalert`; User/RefreshToken auth_epoch, assurance и отзыв legacy-admin сессий |
| 4 | RS-04 Б, Г | `<префикс>_user_vk_id_<дата>`, `<префикс>_user_phone_change_<дата>` | `user.vk_id`; `user.phone_changed_at`; auth_time/auth_method/auth_epoch уже в А2, повторного ALTER нет |
| 5 | RS-05 А | `<префикс>_command_receipt_<дата>` | `commandreceipt`, unique user_id+request_key, durable tombstone до удаления аккаунта |
| 6 | RS-06 А | `<префикс>_chat_message_receipt_<дата>` | `chatmessagereceipt`, unique message_id+user_id |
| 7 | RS-07 Б | `<префикс>_driver_pay_settings_<дата>` | `driverpaysettings` |
| 8 | RS-08 | `<префикс>_card_switch_<дата>` | ключ `rideshare_card_payments` в `appsetting`, `accepts_card` в `driverpaysettings` (строго после RS-04 А1 и RS-07 Б) |
| 9 | RS-10 А | `<префикс>_route_watch_dispatch_<дата>` | `routewatchevent` с unique per-ride source_key/ride+version, group_key series/recurrence и due-index, `routewatchnotice` unique watch_id+ride_id; доменный outbox/право записи в центр; recurrence5events/1group/1Notification/5Notice |
| 10 | RS-14 А | `<префикс>_driver_cars_<дата>` | `drivercar`, immutable `drivercarversion`, `carchangeevent` unique ride+version+booking, ссылки рейса/согласия/просмотра брони; перенос без выдуманных старых номеров |
| — | RS-01, 09, 11–13, 15, 16 | нет | RS-15 использует terms_version RS-02 А; новое поле требует строки здесь и gate миграции |

Правила:
1. Префикс и `down_revision` — по факту, а не по плану: перед слиянием `git fetch`, `cd backend; alembic heads`
   на свежем `origin/main`; `down_revision` = эта голова, префикс — следующая свободная буква. Таблица — порядок по умолчанию.
2. Переименовать ревизию можно, пока она не применена ни к одной общей базе (рабочей или её копии); потом — только новая ревизия.
3. Слияние ревизии в `main` = её применение при ближайшей выкладке (`alembic upgrade head` идёт по всей цепочке).
   Поэтому PR с миграцией вливается только после «да» Александра на эту миграцию. Ждущий «да» не держит очередь:
   следующий ставит `down_revision` на текущую голову `main`, ждущий потом перестраивает цепочку.
4. В каждом PR: одна голова; up → down → up на SQLite и PostgreSQL; обе миграционные проверки зелёные;
   перед рабочей базой — `backup-db.sh`; применение — по одной, в порядке цепочки.
5. **Проверка на старых данных.** Чистая база этого не доказывает: базовая миграция строит таблицы через `create_all`
   из текущих моделей и скрывает забытый `ALTER` (`backend/tests/test_migrations_match_the_models.py:7-12`).
   Порядок: `git worktree add ../yuldash-prev origin/main` → там `alembic upgrade head` на временной базе и
   синтетические строки (не живые данные) в затронутые таблицы → в своей ветке `alembic upgrade head` на ту же
   базу → старые значения на месте, перенос данных верен → `alembic downgrade -1` → `upgrade head`. На SQLite и
   PostgreSQL; вывод — в PR. Рабочую базу и её копии для этого не трогать.
   Основание fixture фиксировать полным SHA непосредственно предыдущего main **до этой миграции**, а не только
   сегодняшним `0ef03f4`: у второй миграции уже есть схема первой. Не запускать target `create_all` поверх fixture.

Минимальные старые строки и ожидаемый перенос (каждый ряд — отдельный NOT_EXECUTED прогон на SQLite/PostgreSQL):

| Миграция | Старые синтетические строки → проверка после upgrade | Downgrade в тесте и граница отката |
|---|---|---|
| RS-02 А | рейс без/с остановками, pending/confirmed/onboard/terminal брони, несколько мест → leg_free пуст/default, terms_version=0, старые цены/ID сохранены; первая запись строит корректный снимок | удалить только новые поля, старые суммы/брони неизменны; новые сегментные данные теряются |
| RS-02 Б | legacy price=500 и брони → цена остаётся500, backfill накопительных цен по выбранному правилу RS-02, нет ×100 | удалить stop_prices_json, legacy price сохранён; перед общим откатом блокировать новые сегментные цены |
| RS-03 А | старые брони без точек → NULL/версия0, данные контактов не расширили видимость | новые точки/предложения теряются; старые брони сохранены |
| RS-04 А1 | старые users/admin → строки default ключей, нет случайного включения платных методов, корректны FK журнала | удалить новые таблицы; старый default-off должен оставаться безопасным |
| RS-04 А2 | старые OtpCode и access/refresh admin/SMS → legacy/epoch0 (admin epoch+1), отзыв admin сессий, бюджеты/attempts/alerts и нужные индексы | не восстанавливать отозванные сессии; откат auth-кода без assurance запрещён, только исправляющая миграция |
| RS-04 Б/Г | предыдущая схема после А2: auth_time/auth_method/auth_epoch уже есть и сохраняются; old user без vk_id/phone_changed_at → nullable/default, телефон без изменений | теряется новая привязка/метаданные; нельзя запускать старый вход с ослаблением прав |
| RS-05 А | старые rides/bookings → IDs/резервы неизменны, новая таблица пуста, уникальность user_id+request_key | ledger/tombstone теряется; в общей среде нельзя возобновлять retry без восстановления ledger, предпочтительно forward fix |
| RS-06 А | старые сообщения → без receipt, не помечены delivered/read; unique message+user | новые receipt теряются, UI показывает неизвестно; сообщения сохранены |
| RS-07 Б | старые водители/брони → default способов по RS-07, цены не меняются | новые предпочтения теряются, legacy договорённости неизменны |
| RS-08 А | owner switch+pay-settings → stored=false/effective=false, hard lock работает | удалить только поля каркаса; запрет card/sbp сохраняется |
| RS-10 А | старые подписки разных watch_kind/direction и Notification → Event/Notice без фантомного статуса доставки/нового push, консервативный backfill Notice из сопоставимых старых уведомлений; unique права работает | новые права теряются; worker остановить, повторная рассылка после rollback без ledger запрещена |
| RS-14 А | driver profile+рейсы/брони разных фаз → актуальная известная identity в версии, исторически неизвестная остаётся неизвестной; допуски не повышены | snapshots теряются; общий откат после новых версий запрещён без сохранения соответствий, forward fix |

Проверка down/up на временной БД не является разрешением разрушительного отката общей базы.
Реальный rollback требует отдельного owner gate: безопасный совместимый код, сохранённые ledger/snapshots и
подтверждённый backup/restore; тесты никогда не используют рабочие персональные данные.

## Очередь по общим файлам

Файл в зоне двух пунктов — строго по очереди слева направо (Q10; «один файл — один агент», `AGENTS.md` §11–§12).
Следующий берёт файл от свежего `main` после слияния предыдущего; открытый PR `[RS-XX…]` с этим файлом — замок.
Правки «≤ N строк» тоже ждут. Пункт, ждущий «да», может уступить файл независимому пункту от `main`, затем
перебазируется; обязательный predecessor при этом никуда не исчезает.
- **Очередь — по частям.** У пункта с частями (`[RS-XXА]`, `[RS-XXБ]`) файл держит только та часть, в чьей «Зоне
  файлов» он есть; остальные части этого пункта файл не занимают.
- **Уступить можно только файл, не зависимость.** Если следующему нужны схема или API предыдущего пункта (столбец
  «Зависит от»), он ждёт их слияния в `main` и не начинает «от `main` без них».
- **Пауза видна.** В разделе 10 и передаче координатору указать `active / paused-released / blocked-dependency`,
  actor, точные файлы и part tag. `paused-released` освобождает файл, `blocked-dependency` не разрешает обойти API.
  Если PR уже разрешён — та же пометка в нём; публикация без разрешения не нужна. Перед возвратом заново занять
  файл и прочитать свежий diff, не полагаться на старые номера строк.

| Файл | Очередь |
|---|---|
| `backend/app/errors.py` | RS-01 (первым — `herr(code=)`) |
| `backend/app/models.py` + `alembic/versions/` | RS-02 А → RS-02 Б → RS-03 А → RS-04 А1/А2/Б/Г → RS-05 А → RS-06 А → RS-07 Б → RS-08 А → RS-10 А → RS-14 А |
| `backend/app/schemas.py` | RS-02 → RS-07 → RS-08 → RS-09 → RS-14 |
| `backend/app/routers/rides.py` | RS-02 А/Б → RS-05 А → RS-10 А → RS-09 А/Б → RS-12 А/Б → RS-15 А → RS-14 А/Г |
| `backend/app/routers/bookings.py` (`book()` и др.) | RS-02 → RS-03 → RS-05 → RS-07 → RS-08 → RS-13 → RS-15 → RS-14 |
| `backend/app/services.py` | RS-02 А/Б → RS-04 А2/В → RS-07 Б → RS-08 А → RS-10 А (вводит рассылку) → RS-09 Б → RS-13 А → RS-15 А → RS-14 А |
| `routers/requests.py` · `routers/auth.py` · `config.py` | RS-01 А → RS-02 А/Б → RS-15 А → RS-14 А · RS-01 А → RS-03 А → RS-04 А2/Б/В/Г → RS-14 А · RS-03 → RS-04 → RS-06 → RS-08 → RS-14 |
| `routers/wallet.py` · `security.py` | RS-01 (замок онлайн-оплаты брони) → RS-08 · RS-04 |
| `account.py` · `cleanup.py` | RS-02 А → RS-03 А → RS-04 А1/А2/Б/Г → RS-05 А → RS-06 А → RS-07 Б → RS-08 А → RS-10 А → RS-14 А |
| Android `data/ApiClient.kt` | RS-01 → RS-02 → RS-03 → RS-04 → RS-05 → RS-06 → RS-07 → RS-08 → RS-12 → RS-13 → RS-15 → RS-14 |
| `MainActivity.kt` | RS-02 → RS-04 → RS-07 → RS-08 → RS-14 |
| `YuldashApp.kt` | RS-02 → RS-04 → RS-05 → RS-09 → RS-12 → RS-14 (RS-02, 05, 12 — одна ветка брони) |
| `data/TripPass.kt` (паспорт, `Outbox`) | RS-03 → RS-05 → RS-13 → RS-14 |
| `BookingActiveTripScreen.kt` | RS-01 → RS-02 → RS-03 → RS-05 → RS-06 → RS-07 → RS-08 → RS-14 |
| `CreateRideScreen.kt` | RS-02 В → RS-05 Б → RS-09 А/Б → RS-14 Б; RS-11 этого файла не требует (уточнение после проверки его §3) |
| `ProfileScreen.kt` | RS-02 → RS-04 → RS-07 → RS-08 → RS-09 → RS-15 |
| `MapScreen.kt` · `SecondaryScreens.kt` | RS-10 → RS-11 → RS-12 · RS-01 → RS-04 → RS-12 |
| `data/SessionKeys.kt` · `data/FcmService.kt` | RS-05 → RS-07 → RS-11 → RS-12 · RS-06 → RS-13 |
| `AndroidManifest.xml`, `app/build.gradle.kts` | RS-01 → RS-04 → RS-13; ни с кем параллельно (§12) |
| Сайт `screens/CreateRideScreen.tsx` | RS-02 → RS-05 → RS-09 → RS-14 |
| `components/RideSheet.tsx` | RS-02 → RS-03 → RS-05 → RS-07 → RS-12 |
| `screens/ActiveTripScreen.tsx` | RS-03 → RS-05 → RS-07 → RS-13 → RS-14 |
| `screens/HomeScreen.tsx` · `screens/RidesScreen.tsx` · `screens/ProfileScreen.tsx` | RS-10 → RS-12 · RS-10 → RS-11 · RS-04 |
| `api/client.ts` · `api/rides.ts` · `RideEditActions.tsx` | RS-01 (`code` в `ApiError`) · RS-02 → RS-05 → RS-07 → RS-08 → RS-12 → RS-15 · RS-02 → RS-15 |
| `DriverCabinetScreen.tsx` · `App.tsx` · `AuthProvider.tsx` · `utils/privacy.ts` | RS-02 → RS-07 → RS-08 → RS-09 · RS-04 → RS-14 · RS-13 · RS-07 → RS-11 |
| Сервер: `routers/drivers.py` · `routers/family.py` · `safety_logic.py` · `main.py` | RS-07 → RS-08 → RS-14 · RS-05 → RS-13 · RS-02 → RS-14 · RS-01 → RS-14 |
| Сервер: `routers/health.py` · `routers/__init__.py` · `ride_seats.py` · `owner_switches.py` | RS-01 → RS-04 · RS-03 → RS-04 · RS-02 → RS-15 · RS-04 → RS-08 |
| Android: `Domain.kt` · `data/BookingDtos.kt` · `RidesRequestsChatScreens.kt` | RS-02 → RS-07 → RS-08 → RS-14 · RS-03 → RS-07 → RS-08 → RS-14 · RS-02 → RS-06 → RS-10 |
| Android: `BookingSeatCount.kt` · `data/DriverBookings.kt` · `RideshareCompletedScreen.kt` | RS-02 В → RS-07 В → RS-12 А · RS-02 В → RS-03 А · RS-01 В → RS-08 В |
| Сайт: `components/NearbyFilters.tsx` · `api/bookings.ts` · `api/driver.ts` | RS-11 → RS-12 · RS-02 → RS-03 → RS-05 → RS-07 → RS-08 → RS-13 · RS-02 → RS-05 → RS-09 → RS-14 |
| Сайт: `components/PayTripCard.tsx` · `screens/TripReceiptScreen.tsx` · `components/DriverPassengers.tsx` | RS-01 → RS-08 · RS-01 → RS-08 · RS-02 → RS-03 |
| `backend/app/routers/taxi.py`, `cars.py`, `routers/cars.py`, `routers/safety.py`, `cancel_reason_text.py` | RS-14 А (оба писателя taxi: заявка и модератор), затем RS-14 Г; safety_logic после RS-02 А |
| `webapp/tests/run.mjs`, `ui.css`, `docs/{tasks,decisions,architecture,lessons}.md` | один писатель; очередь по part tag от координатора, даже для append-only. При конфликте прочитать diff и сохранить чужой текст; «дописать» не разрешает перезаписать файл |

Родительские номера в оставшихся строках таблицы раскрываются по частям ниже; файл занимает лишь часть,
которой он назначен. Если новый путь не указан, сначала добавить его в матрицу и получить замок, затем править.
Утверждения «RS-11 параллелен всему RS-13/14» нет: общие `run.mjs`, `ui.css` и docs всё равно сериализуются.
Сборка Android и эмулятор — по одному на компьютер (`docs/locks.md`).

### Части: обязательные predecessors и занимаемые файлы (N8)

Здесь перечислены общие файлы каждой части; новые собственные файлы/тесты — в её §3/«Зоне файлов».
Пути backend относительно `backend/app`, Android относительно `…/com/yuldash/app`, PWA относительно `webapp/src`.
Неуказанное изменение общего файла сначала согласовать с координатором. PR с изменением двух частей занимает
объединение их файлов и ждёт все predecessors, а не только первую удобную зависимость.

| Часть | Обязательный договор до начала зависимой работы | Общие файлы, которые занимает часть |
|---|---|---|
| RS-01 А | — | backend errors/login_methods, routers/{auth,health,requests}, main |
| RS-01 Б | RS-01 А | PWA api/{client,auth,requests}, LoginScreen, loginFlow, PayTripCard, TripReceiptScreen |
| RS-01 В | RS-01 А | Android ApiClient, LoginScreen, BookingActiveTripScreen, RideshareCompletedScreen, SecondaryScreens, build.gradle |
| RS-01 Г | RS-01 А; отдельное «да» | backend wallet; PWA legal, web legal-content; общий guard оплаты |
| RS-02 А | RS-01 А | backend models/schemas/services/request_segments/safety_logic/account, routers/{rides,bookings,requests,medical}, миграция мест/terms_version |
| RS-02 Б | RS-02 А, D7 | backend models/schemas/services/request_segments, routers/{rides,bookings,requests}, миграция цен |
| RS-02 В | RS-02 А, для цен RS-02 Б/D7 | Android Domain/MainActivity/YuldashApp/ApiClient/DriverBookings/RequestRouteDtos/BookingSeatCount/BookingActiveTripScreen/CreateRideScreen/ProfileScreen/RidesRequestsChatScreens/RequestsFeedScreen |
| RS-02 Г | RS-02 А, для цен RS-02 Б/D7 | PWA api/{rides,driver,bookings,requests}, pickupStops, RideSheet/RideCard/RideEditActions/DriverPassengers, CreateRideScreen/DriverCabinetScreen/RequestsFeedScreen |
| RS-03 А | RS-01 А, RS-02 А/Б | backend models/config/cleanup, bookings/auth, serializer; Android ApiClient/BookingDtos/DriverBookings/TripPass/PickupStopPicker/BookingActiveTripScreen/DriverPassengerRow; PWA bookings/YandexMap/RideSheet/DriverPassengers/ActiveTripScreen |
| RS-03 Б | RS-03 А (DTO/поля/version), RS-02 terms_version | те же файлы точек/команд, routers/meeting, оба клиента предложения |
| RS-03 В | RS-03 А/Б (разрешённая applied point) | backend meeting; Android BookingActiveTripScreen/TripPass; PWA ActiveTripScreen |
| RS-04 А1 | RS-01 А | backend models/config/owner_switches/login_methods/health/admin_switches/account/cleanup; Android MainActivity/AppNavAdmin/SecondaryScreens/ApiClient/LoginMethods/LoginScreen; PWA App/AdminCabinet/Login/api-auth |
| RS-04 А2 | RS-04 А1, D8 | backend auth/security/models/services/config/account/cleanup, OTP бюджеты/attempts/alerts и OtpCode.attempt_id; Android ApiClient/LoginScreen; PWA api-auth/LoginScreen |
| RS-04 Б | RS-04 А2 (assurance) | backend auth/security/models/config/account/cleanup; Android LoginScreen/ApiClient/Manifest; PWA App/api-auth/LoginScreen/loginFlow |
| RS-04 В | RS-04 А2, provider gate | backend auth/services/config; Android LoginScreen/ApiClient; PWA api-auth/LoginScreen |
| RS-04 Г | RS-04 А2/Б/В | backend auth/security/models/account/cleanup; Android ProfileScreen/YuldashApp/ApiClient; PWA ProfileScreen/App/api-auth |
| RS-05 А | RS-01 А, RS-02 А/Б; при точках DTO RS-03 А | backend models/idempotency/account/cleanup, routers/{rides,bookings,family}; миграция ledger |
| RS-05 Б | RS-05 А | Android ApiClient/TripPass/SessionKeys/CreateRideScreen/YuldashApp/BookingActiveTripScreen/BoardingApi; PWA driver/bookings/rides/family/outbox/CreateRideScreen/ActiveTripScreen/RideSheet |
| RS-06 А | RS-01 А | backend models/config/chat/account/cleanup; миграция receipt |
| RS-06 Б / В | RS-06 А | Б: PWA api-chat/tripChat/TripChatScreen/ChatInboxScreen/TripChatCard/Icons/ui.css; В: Android ApiClient/ChatSocket/FcmService/TripChat*/BookingActiveTripScreen/ConversationCard/RidesRequestsChatScreens |
| RS-07 А / В | RS-01 А; для новых полей DTO RS-03 А | А: backend incidents/bookings/schemas, PWA RideSheet/ActiveTripScreen/BookingScreen/HelpScreen/api-safety/bookings/privacy; В после А: Android BookingActiveTripScreen/BookingSeatCount/ApiClient/BookingDtos/SessionKeys |
| RS-07 Б | RS-07 А/В, D7 если меняется денежный контракт | backend models/schemas/drivers/services/account/cleanup; Android ProfileScreen/Domain/MainActivity/ApiClient/BookingDtos; PWA DriverCabinet/api-rides/bookings |
| RS-08 А | RS-01 Г, RS-04 А1/А2, RS-07 Б | backend config/owner_switches/models/drivers/wallet/bookings/schemas/services/account/cleanup; effective=false |
| RS-08 Б / В | RS-08 А | Б: PWA AdminSwitches/DriverCabinet/PayTripCard/TripReceipt/BookingTerms/api-rides/bookings; В: Android AdminSwitches/ProfileScreen/BookingTermsCard/BookingActiveTripScreen/RideshareCompletedScreen/Domain/MainActivity/ApiClient/BookingDtos; оба guard оплаты |
| RS-10 А | RS-01 А, RS-02 А | backend services/route_watch/models/cleanup/account/rides; миграция Event/Notice, worker |
| RS-10 Б / В | RS-10 А | Б: PWA routeWatch/RouteWatchesScreen/RidesScreen/NearbyRideCard/HomeScreen; В: Android RouteWatchApi/RouteWatchesScreen/RidesRequestsChatScreens/MapScreen |
| RS-09 А | RS-02 А/Б, D7 | backend rides/schemas; Android CreateRideScreen/ProfileScreen/YuldashApp; PWA CreateRideScreen/DriverCabinet/api-driver/ui.css |
| RS-09 Б | RS-02 А/Б, RS-05 А/Б, RS-10 А, D7 | backend rides/schemas/services; Android CreateRideScreen/YuldashApp/RideSeriesApi; PWA CreateRideScreen/DriverCabinet/api-driver; всегда series даже повтор1даты |
| RS-11 А / Б | серверных predecessors нет; файловая очередь обязательна | А: PWA privacy/NearbyFilters/RidesScreen/ui.css; Б: Android NearbyCityFilter/MapScreen/SessionKeys. Очистка в шторке, отдельного PR для CreateRideScreen нет |
| RS-12 А / Б | А: RS-01 А для новых ошибок; Б: RS-02 А | А: backend rides; Android ApiClient/NearbyFeedSearch/NearbyFiltersPanel/SecondaryScreens/SessionKeys/MapScreen/YuldashViewModel/YuldashApp/BookingSeatCount (после RS-02 В/RS-07 В по файлу); PWA discovery/rides/nearbyFeed/filterPrefs/NearbyFilters/RideSheet/FiltersScreen/HomeScreen. Б: backend rides по участкам |
| RS-13 А | RS-01 А | backend rate_reminder/rating_service/family/bookings/instant/services |
| RS-13 Б / В | RS-13 А | Б: PWA push-sw/AuthProvider/api-bookings/ActiveTripScreen; В: Android FcmService/TripPass/ApiClient/Manifest |
| RS-15 А | RS-01 А, RS-02 А/Б, RS-10 А; D7 | backend rides/bookings/requests/request_segments/services/ride_seats, terms_version и атомарные цены/уведомления |
| RS-15 Б / В | RS-15 А | Б: PWA api-rides/RideEditActions; В: Android ProfileScreen/ApiClient |
| RS-14 А | RS-01 А, RS-02 А для terms_version/мест; отдельный денежный gate класса | backend cars/models/config/schemas/main/services/account/cleanup/safety_logic/cancel_reason_text, routers/{cars,drivers,taxi,rides,bookings,requests,safety,auth}; миграция identity |
| RS-14 Б / В | RS-14 А | Б: Android MainActivity/Domain/YuldashApp/CreateRideScreen/SosVerifyScreens/BookingActiveTripScreen/RideshareTripContacts/ApiClient/BookingDtos/TripPass; В: PWA App/cars/driver/tripPass/offlineTripPass/MyCarsScreen/CreateRideScreen/VerifyDriverScreen/ActiveTripScreen |
| RS-14 Г | RS-14 А/Б/В; cancel-preview RS-07 А/В | backend rides/bookings/cars/safety_logic; оба клиента согласия машины; car_changed серверный |
| RS-16 А / Б | А: доступный состав для разведки; Б: весь заявленный main + исключения владельца | tools/rideshare_run, доказательства вне git, docs по отдельной очереди; продуктовый код не править |

Во всех строках `tests/run.mjs`, общие тестовые фикстуры и docs занимают отдельный замок по фактической
необходимости. Пример уступки: RS-08 ждёт «да» → освобождает ApiClient, RS-11 Б работает лишь со своими
MapScreen/SessionKeys; RS-09 Б при этом всё равно ждёт ledger RS-05 и API/таблицы RS-10 А.
Рассылка имеет одного первого владельца RS-10 А; «кто вторым» теперь определено: RS-09 Б и RS-15 А
добавляют вызов/событие в своих транзакциях после него, не параллельно правят services/rides.
