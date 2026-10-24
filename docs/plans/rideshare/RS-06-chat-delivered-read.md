# RS-06 — В чате видно «доставлено» и «прочитано»

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | да — миграция рабочей базы (таблица `chatreadmark`), выкладка сервера |
| Зависит от | — ; с RS-05 общие `models.py`, `ApiClient.kt`, `cleanup.py`, `account.py`, `BookingActiveTripScreen.kt`, миграции — по очереди |
| Двигает сценарии приёмки | W1-E2E-04 (чат после обрыва и перезапуска), W1-E2E-05 (только участники, окно, блокировка) |
| Зона файлов | `backend/app/`: `models.py` (`ChatReadMark`), `routers/chat.py` (`websocket_endpoint`, `send_message`, `ConversationOut`/`conversations`, новые ручки отметок), `cleanup.py`, `account.py`; `backend/alembic/versions/<префикс>_chat_read_mark_<дата>.py`; `backend/tests/test_chat_receipts.py`. `android/app/src/main/java/com/yuldash/app/`: `ChatDeliveryMark.kt` (новый), `TripChatState.kt`, `TripChatWiring.kt`, `TripChatScreen.kt`, `BookingActiveTripScreen.kt` (только `MessageBubble`), `ConversationCard.kt`, `data/ApiClient.kt`, `data/ChatSocket.kt`, `data/Conversation.kt`, `data/FcmService.kt`; тесты в `android/app/src/test/…/app/`: `ChatDeliveryMarkTest.kt`, `TripChatReceiptsTest.kt`, `data/ChatSocketReceiptTest.kt`. `webapp/src/`: `api/chat.ts`, `utils/tripChat.js`, `utils/tripChat.d.ts`, `screens/TripChatScreen.tsx`, `screens/ChatInboxScreen.tsx`, `components/TripChatCard.tsx`, `ui.css` (классы `bubble__status`); `webapp/tests/chat-receipts-test.mjs`. Мозг: `docs/tasks.md`, `docs/decisions.md` |
| Источник | `c5be55a:docs/research/apps/master/part-001.md` п. 15, 28; `master/part-002.md` «Минимальные контракты надёжности» (событие, восстановление, доступ); решения 24.10: Q16 а, Q9, Q10, Q11 |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
**Было.** Пассажирка в Сибае пишет водителю: «Стою у школы, в синей куртке». Водитель едет по трассе из Акъяра, связь пропадает. Она видит только, что сообщение ушло на сервер, и не знает, получил ли он, прочитал ли. Звонит — он за рулём. Бейдж «новых сообщений» живёт в памяти телефона: на сайте и после перезапуска он другой.

**Стало.** Как в мессенджерах: одна серая галочка — сервер принял; две серые — телефон водителя получил; две зелёные — водитель открыл чат и прочитал. Под последним своим сообщением подпись «Доставлено» или «Прочитано». Число новых в списке чатов считает сервер — одинаково на телефоне и на сайте. Отметки видят только двое участников поездки и только пока открыт чат поездки; заблокировал — отметок нет. Время прочтения и «был в сети» не показываем.

## 2. Что уже есть в коде
- `backend/app/models.py:621-646` `Message` — полей доставки/прочтения нет.
- `backend/app/routers/chat.py:717-757` отправка с ключом повтора; `:779-802` REST, пуш `data={"type":"chat","id":booking_id}` (`:800`); `:805-816` история — список, проверка только участника; `:892-955` `/conversations` без непрочитанных, но уже читает все сообщения броней одним запросом (`:923-928`).
- `chat.py:255-356` WebSocket: токен первым кадром, только участник (`:283`), сторож закрывает канал при блокировке (`:161-177`, `:293-300`), кадры не `message` пропускаются (`:308-309`), пуш `:345-350`.
- `chat.py:136-154` окно чата — `depart_at + chat_after_trip_hours` (48 ч, `backend/app/config.py:980`); `backend/app/visibility.py:298-319` окно контактов — то же число; `chat.py:884-888` «скрыть у себя» собеседнику не рассылается.
- `backend/app/services.py:260-266` `is_blocked` в обе стороны, `:269` `blocked_user_ids`, `:2063-2078` `notify_chat_message` через Redis, `:452-493` FCM всегда data-only.
- `backend/app/cleanup.py:28,151` сообщения — 30 дней; `:69-75` + `backend/tests/test_nothing_grows_forever.py`; `backend/tests/test_account_deletion.py:26-34`.
- Android (от `android/app/src/main/java/com/yuldash/app/`): `TripChatState.kt:58-69` отметки только у неотправленных, `:152-177` история и `seen.baseline`; `TripChatWiring.kt:78-108` `TripChatSeenMark` в памяти процесса (`:79`: «Сервер „прочитано“ … не хранит»); `TripChatScreen.kt:248-255` «прочитано» — лента внизу и экран активен, `:311-323` `MessageBubble`, `:485-512` бейдж карточки.
- `BookingActiveTripScreen.kt:3346-3362` параметры пузыря, `:3471-3492` «Не доставлено · Повторить» (на деле — сервер не принял) и «В очереди»; фото — текст с `[img]` (`:3371`), голос — `voice_url`: те же строки `Message`.
- `data/ChatSocket.kt:139-167` незнакомые кадры игнорирует; `data/FcmService.kt:29-36` чат-пуш приходит в приложение; `ConversationCard.kt:14` `unread = 0`; `RidesRequestsChatScreens.kt:2890-2898` бейдж `ChatCard`; `data/Conversation.kt:6-23`; `OutboxAutoFlush.kt:14-38` очередь (#210).
- Сайт: `webapp/src/utils/tripChat.js:19-36,55-59` метка в памяти вкладки; `webapp/src/screens/TripChatScreen.tsx:463-490` метка, `:759-800` пузырь без статуса; `webapp/src/api/chat.ts:93-101` `Conversation` без `unread`, `:150-170` кадры; `webapp/src/screens/ChatInboxScreen.tsx:234-245`, `webapp/src/components/TripChatCard.tsx:61` — локальный счёт.
- Серверных «доставлено»/«прочитано» — не нашёл.

## 3. Что сделать
### Сервер (`backend/`)
1. **Модель** `ChatReadMark` (`chatreadmark`): `id`, `booking_id` (FK `booking.id`, `ondelete=CASCADE`, индекс), `user_id` (FK `user.id`, `ondelete=CASCADE`), `delivered_up_to`, `read_up_to` (int, 0), `updated_at`; `UniqueConstraint("booking_id","user_id", name="uq_chat_read_mark")`. Отметка — «до какого сообщения собеседника», по `id`; время наружу не отдаём.
2. **Можно ли** `_receipts_open(...)`: участник (`booking_and_ride_for_user`), чат не закрыт (`_booking_chat_closed`), нет блокировки (`is_blocked`). Иначе ничего не пишем и не рассылаем, отвечаем `open=false`.
3. **Применить** `_apply_marks(session, booking, me, delivered, read)`: `delivered = max(delivered, read)`; значение **прижать** к последнему настоящему сообщению собеседника (`max(Message.id)` при `sender_id != me`, `id <= value`) — подбор чисел не даст сотни записей; условный `UPDATE … WHERE read_up_to < :r` (и `delivered`); строки нет — вставка, `IntegrityError` → `rollback` и тот же `UPDATE`. Не выросло — ни записи, ни рассылки; выросло — `notify_chat_message(booking_id, {"type":"receipt","user_id":me,"delivered_up_to":d,"read_up_to":r})`.
4. **Ручки:** `GET /bookings/{id}/receipts` → `{"open","peer_delivered_up_to","peer_read_up_to","my_read_up_to","unread"}` (`peer_*` = `null` при `open=false`); `POST` туда же с `{"delivered_up_to"?,"read_up_to"?}` → тот же ответ.
5. **WebSocket** (`chat.py:308`): кадр `{"type":"receipt",...}` — та же проверка и `_apply_marks`; ошибка — пропустить, канал не рвать.
6. **`unread`** — сообщения собеседника с `id > my_read_up_to`, не удалённые, не скрытые мной; при `open=false` — 0. `ConversationOut.unread: int = 0` считать в проходе `chat.py:923-928`; отметки — одним запросом, блокировки — `blocked_user_ids`.
7. **Пуш:** в `data` чат-пушей (`chat.py:349`, `:800`) — `"mid": msg.id`.
8. **Совместимость:** история остаётся списком; старые клиенты новые кадры игнорируют и отметок не шлют — у собеседника «Отправлено».
9. **Срок:** `cleanup._rules` «отметки прочтения >30д» по `updated_at` (`MSG_DAYS`, `_ALLOWED_TABLES`); `account.py` удаляет отметки человека и его броней.

### Android (`android/`)
1. `data/ApiClient.kt`: `getChatReceipts`, `postChatReceipts` (сессия входа); 404 — старый сервер → `open=false`. `parseConversationDto` — `unread`.
2. `data/ChatSocket.kt`: `sendReceipt(delivered, read)`; кадр `receipt` (целые > 0, иначе пропуск) → `onReceipt`: `user_id` собеседника — его отметки, свой — `my_read_up_to` (второе устройство).
3. `TripChatState.kt`: `peerDelivered`, `peerRead`, `receiptsOpen`; читать с историей (`drainHistory`) и после переподключения. «Доставлено» — после истории и на чужой кадр; «прочитано» — когда сдвигается `markSeen`. Отправка **пачкой**: раз в 1,5 с и только при росте; сокет жив — кадром, нет — `POST`. Значения только растут.
4. `TripChatWiring.kt`: `TripChatSeenMark` стартует с `my_read_up_to`; локальная метка — запасной путь для старого сервера.
5. `ChatDeliveryMark.kt` (новый): `chatDelivery(...)` → `Queued | Failed | Sending | Sent | Delivered | Read | None`; `ChatDeliveryTicks`: `Icons.Default.Done` (отправлено, `CanonMuted`), `Icons.Default.DoneAll` (доставлено — `CanonMuted`, прочитано — `CanonGreen2`); смена — `AnimatedContent` (fade + scale, `CanonMotion.QUICK_MS`); `contentDescription` на двух языках; под последним своим — подпись §5. Не только цветом: форма значка и подпись.
6. `MessageBubble`: параметр `delivery` (флаги `failed/queued` остаются); «Не доставлено · Повторить» → «Не отправлено · Повторить» (BA «Ебәрелмәне · Ҡабатлау» уже так). `TripChatScreen.kt` передаёт `delivery`.
7. `data/FcmService.kt`: чат-пуш с `mid` и верным адресатом → один `postChatReceipts(delivered = mid)` без очереди (как `ApiClient.fireRegisterPushToken`, `:26`), до проверки «уведомления выключены».
8. `ConversationCard.kt` — `unread` с сервера.

### Сайт (`webapp/`)
1. `api/chat.ts`: `fetchReceipts`, `postReceipts`, `Conversation.unread?`; в `openBookingChat` — `onReceipt`, `sendReceipt`.
2. `utils/tripChat.js` (+ `.d.ts`): `deliveryState(...)` как на Android, `unreadCount` от серверной метки, пачка 1,5 с.
3. `TripChatScreen.tsx`: значки у своих пузырей (`IconCheck` и новый двойной в `components/Icons.tsx`), подпись под последним, `aria-label` на двух языках; «прочитано» — лента внизу и вкладка видима (`:486`); стили `bubble__status` в `ui.css` — переменные темы.
4. `ChatInboxScreen.tsx`, `TripChatCard.tsx` — бейдж из `unread` сервера (старый сервер — локальный счёт).
5. Доставку через веб-пуш не делаем: у service worker нет входа.

## 4. База данных
Миграция `<следующий префикс>_chat_read_mark_<ГГГГММДД>`, `down_revision` — голова на момент работы (24.10 — `cb_sos_retry_intent_20261021`; после RS-05 — её миграция). Создаёт `chatreadmark` с уникальным ограничением и индексом `booking_id`; повторный запуск безопасен (`inspect`). Старые сообщения без отметок — до выкладки показываются как «Отправлено». `downgrade` — удалить таблицу. **Ждёт «да»** на рабочей базе.

## 5. Надписи
Русский; башкирский — черновик `yuldash-bashkir-linguist` в `docs/tasks.md`, «Переводы на проверку».
- Подписи: «Отправлено», «Доставлено», «Прочитано»; описание значка для экранного диктора: «Сообщение отправлено», «Сообщение доставлено», «Сообщение прочитано».
- Изменение: «Не доставлено · Повторить» → «Не отправлено · Повторить» (BA прежний).
- Прежние: «В очереди · отправим при сети» / «Сиратта · селтәр булғас ебәрербеҙ».

## 6. Состояния экрана
Новых экранов нет. Отметки не загрузились или `open=false` — значков нет (ничего не обещаем), чат работает как сейчас. Нет сети — «В очереди»; отказ — «Не отправлено · Повторить». Длинная башкирская подпись не ломает пузырь на 360 dp и при крупном шрифте (`sp`). Тёмная тема — `Canon*`; контраст значков ≥ 4.5:1 на `CanonBg` (`ContrastGuardTest`).

## 7. Тесты
**Сервер** (`pytest`, `ruff check app/ --select F`), `test_chat_receipts.py`:
1. Три сообщения собеседника → `POST delivered` → у отправителя `peer_delivered` = последнее, `peer_read` = 0; `POST read` → оба растут.
2. Меньшее значение — без `UPDATE` и рассылки (подменить `notify_chat_message`). 3. `10**9` и число между `id` прижимаются; свои сообщения не считаются.
4. Чужой — 403; блокировка в любую сторону — `open=false`, ничего не пишется; после разблокировки — прежние значения. 5. Окно закрыто (`done`, выезд 49 ч назад) — `open=false`; «забыл вещь» — `true`.
6. WebSocket: кадр `receipt` читателя доходит отправителю; без роста — тишина; неверный кадр не рвёт канал.
7. `/conversations.unread` — без своих, удалённых, скрытых мной; 0 при блокировке. 8. История — список; голос и фото как текст; в пуше `mid`.
9. Миграция вверх/вниз; `test_nothing_grows_forever.py`, `test_account_deletion.py`, `test_migrations_match_the_models.py`.
Мутации: убрать `is_blocked` → красный п. 4; прижатие → п. 3; условие роста → п. 2.

**Android** (`:app:testDebugUnitTest`, `:app:assembleDebug`): `ChatDeliveryMarkTest`; Compose-тест пузыря (значки, подписи RU/BA, обе темы); `TripChatReceiptsTest` (`MockWebServer`: история → один `POST`; 10 кадров за секунду → не больше двух отправок; 404 — без значков); `data/ChatSocketReceiptTest`. Сторожа: `BilingualSourceGuardTest`, `CanonSourceGuardTest`, `ColorSourceGuardTest`, `ContrastGuardTest`, `SpacingRatchetGuardTest`, `NotifTypesGuardTest`.
**Сайт:** `npm run check` (`tests/chat-receipts-test.mjs`), `npm run build`.

## 8. Приёмка
Принимает агент другой «семьи» (Q9), по умолчанию «не принято».
- [ ] Отметки — только участникам, при открытом чате, без блокировки; время прочтения нигде не отдаётся.
- [ ] Отметки только растут и прижаты к настоящим сообщениям; без роста — ни записи, ни кадра.
- [ ] История прежней формы; старый клиент не ломается; новый на старом сервере — без значков.
- [ ] Эмулятор + сайт (Q11), обе пары ролей (Android-пассажир — сайт-водитель и наоборот): «Отправлено» → собеседник получил (лента или пуш) → «Доставлено» → долистал вниз → «Прочитано» за ~2 с; собеседник без сети — «Отправлено», сеть вернулась — догоняет; перезапуск — отметки с сервера; голос и фото — так же.
- [ ] Блокировка — значки пропадают у обоих; окно закрыто — значков нет; бейдж списка чатов совпадает на Android и сайте.
- [ ] RU/BA × светлая/тёмная, 360 dp, крупный шрифт; BA — черновик в `docs/tasks.md`.
- [ ] FCM на эмуляторе — если пуши там работают; иначе остаток W1-E2E-07.
- [ ] Двигает W1-E2E-04 и W1-E2E-05. Статус — только `docs/audit-blocks.md`.

## 9. Риски и границы
- «Доставлено» — устройство получило данные, а не «человек увидел»; телефон выключен — останется «Отправлено». Веб-пуш доставку не подтверждает.
- Выключателя «не показывать, что я прочитал» нет — можно добавить позже отдельным планом.
- Нагрузка: запись только при росте и на настоящих сообщениях (не больше числа сообщений собеседника), клиенты шлют пачкой; `/conversations` без новых проходов.
- Не входит: чаты такси и посылок (`/instant`, `/parcels`), поддержка, групповые чаты.

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан |
