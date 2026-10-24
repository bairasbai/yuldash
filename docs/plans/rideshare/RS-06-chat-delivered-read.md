# RS-06 — В чате видно «доставлено» и «прочитано»

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | да — миграция рабочей базы (таблица `chatreadmark`) |
| Зависит от | RS-01 (договор ошибок `herr(..., code=)`, D2; новых кодов план не вводит); по общим файлам — после RS-05 (очередь в индексе) |
| Не параллельно с | RS-05 (`models.py`, `ApiClient.kt`, `cleanup.py`, `account.py`, `BookingActiveTripScreen.kt`, миграции); RS-13, если трогает `FcmService.kt` — по индексу |
| Деление на PR | `[RS-06А]` сервер с миграцией (облачный агент) → `[RS-06Б]` сайт → `[RS-06В]` Android (агент на компьютере Александра) |
| Проверка `yuldash-security-privacy` | да — кто видит отметки, блокировка, окно, пуш как признак «телефон в сети» |
| Двигает сценарии приёмки | W1-E2E-04 (чат после обрыва и перезапуска), W1-E2E-05 (только участники, окно, блокировка) |
| Зона файлов | `backend/app/`: `models.py` (`ChatReadMark`), `config.py` (`chat_receipts_since`), `routers/chat.py` (`websocket_endpoint`, `send_message`, `ConversationOut`/`conversations`, новые ручки отметок), `cleanup.py`, `account.py`; `backend/alembic/versions/<префикс>_chat_read_mark_<дата>.py`; `backend/tests/test_chat_receipts.py`. `android/app/src/main/java/com/yuldash/app/`: `ChatDeliveryMark.kt` (новый), `TripChatState.kt`, `TripChatWiring.kt`, `TripChatScreen.kt`, `BookingActiveTripScreen.kt` (только `MessageBubble`), `ConversationCard.kt`, `RidesRequestsChatScreens.kt` (только бейдж `ChatCard`), `data/ApiClient.kt`, `data/ChatSocket.kt`, `data/Conversation.kt`, `data/FcmService.kt`; тесты в `android/app/src/test/…/app/`: `ChatDeliveryMarkTest.kt`, `TripChatReceiptsTest.kt`, `data/ChatSocketReceiptTest.kt`, `BookingActiveTripDeep2ContentTest.kt` (текст повтора). `webapp/src/`: `api/chat.ts`, `utils/tripChat.js`, `utils/tripChat.d.ts`, `screens/TripChatScreen.tsx`, `screens/ChatInboxScreen.tsx`, `components/TripChatCard.tsx`, `ui.css` (классы `bubble__status`, `inbox-row__badge`); `webapp/tests/chat-receipts-test.mjs`. Мозг: `docs/tasks.md`, `docs/decisions.md` |
| Источник | `c5be55a:docs/research/apps/master/part-001.md` п. 15, 28; `master/part-002.md` «Минимальные контракты надёжности» (событие, восстановление, доступ); решения 24.10: Q16 а, Q9, Q10, Q11 |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
**Было.** Пассажирка в Сибае пишет водителю: «Стою у школы, в синей куртке». Водитель едет по трассе из Акъяра, связь пропадает. Она не знает, получил ли он, прочитал ли. Звонит — он за рулём. Бейдж «новых сообщений» живёт в памяти телефона: на сайте и после перезапуска он другой.

**Стало.** Как в мессенджерах: одна серая галочка — сервер принял; две серые — телефон водителя получил; две зелёные — водитель открыл чат и прочитал. Под последним своим сообщением подпись «Доставлено» или «Прочитано». Число новых в списке чатов считает сервер — одинаково на телефоне и на сайте. Отметки видят только двое участников поездки и только пока открыт чат поездки; заблокировал — отметок нет. Время прочтения и «был в сети» не показываем.

## 2. Что уже есть в коде
- `backend/app/models.py:621-646` `Message` — полей доставки/прочтения нет.
- `backend/app/routers/chat.py:717-757` отправка с ключом повтора; `:779-802` REST, пуш `data={"type":"chat","id":booking_id}` (`:800`); `:805-816` история — список, проверка только участника; `:892-955` `/conversations` без непрочитанных, но уже читает все сообщения броней одним запросом (`:923-928`).
- `chat.py:255-356` WebSocket: токен первым кадром, только участник (`:283`), сторож закрывает канал при блокировке (`:161-177`, `:293-300`), кадры не `message` пропускаются (`:308-309`), пуш `:345-350`.
- `chat.py:136-154` окно чата — `depart_at + chat_after_trip_hours` (48 ч, `backend/app/config.py:980`); `backend/app/visibility.py:298-319` окно контактов — то же число; `chat.py:884-888` «скрыть у себя» собеседнику не рассылается.
- `backend/app/services.py:260-266` `is_blocked` в обе стороны, `:269` `blocked_user_ids`, `:452-493` FCM всегда data-only; `:2063-2082` `notify_chat_message` синхронная: Redis или `from_thread.run` — из WebSocket без Redis кадр молча теряется; в сокете рассылают `await manager.broadcast` (`chat.py:344`), `run_in_threadpool` импортирован (`chat.py:13`).
- `backend/app/cleanup.py:28,151` сообщения — 30 дней; `:69-75` + `backend/tests/test_nothing_grows_forever.py`; `backend/tests/test_account_deletion.py:26-34`.
- Android (от `android/app/src/main/java/com/yuldash/app/`): `TripChatState.kt:58-69` отметки только у неотправленных, `:152-177` история и `seen.baseline`; `TripChatWiring.kt:78-108` `TripChatSeenMark` в памяти процесса (`:79`: «Сервер „прочитано“ … не хранит»); `TripChatScreen.kt:248-255` «прочитано» — лента внизу и экран активен, `:311-323` `MessageBubble`, `:485-512` бейдж карточки.
- `BookingActiveTripScreen.kt:3346-3362` параметры пузыря, `:3471-3492` «Не доставлено · Повторить» (на деле — сервер не принял) и «В очереди»; фото — текст с `[img]` (`:3371`), голос — `voice_url`: те же строки `Message`.
- `data/ChatSocket.kt:139-167` незнакомые кадры игнорирует; `data/FcmService.kt:29-36` чат-пуш приходит в приложение; `ConversationCard.kt:14` `unread = 0`; `RidesRequestsChatScreens.kt:2890-2898` бейдж `ChatCard` скрыт и мимо канона (`Color.White`, `MaterialTheme`, 30.dp, без «99+»); `TripChatScreen.kt:200-211` `UnreadBadge` — `Canon*`, «99+», описание RU/BA; `TripChatWiring.kt:64` «Не отправилось. Повтори.»; `data/Conversation.kt:6-23`; `OutboxAutoFlush.kt:14-38` очередь (#210).
- Сайт: `webapp/src/utils/tripChat.js:19-36,55-59` метка в памяти вкладки; `webapp/src/screens/TripChatScreen.tsx:463-490` метка, `:759-800` пузырь без статуса; `webapp/src/api/chat.ts:93-101` `Conversation` без `unread`, `:150-170` кадры; `webapp/src/components/TripChatCard.tsx:61` — локальный счёт; `webapp/src/screens/ChatInboxScreen.tsx:27-66` у `ChatCard` бейджа нет вовсе, `:234-245` строки списка.
- Серверных «доставлено»/«прочитано» — не нашёл.

## 3. Что сделать
### Сервер (`backend/`)
1. **Модель** `ChatReadMark` (`chatreadmark`): `id`, `booking_id` (FK `booking.id`, `ondelete=CASCADE`), `user_id` (FK `user.id`, `ondelete=CASCADE`), `delivered_up_to`, `read_up_to` (int, 0), `updated_at`; `UniqueConstraint("booking_id","user_id", name="uq_chat_read_mark")` — он же индекс по `booking_id`, отдельный не нужен. Отметка — «до какого сообщения собеседника», по `id`; время наружу не отдаём.
1а. **Дата начала** `settings.chat_receipts_since` (`config.py`, UTC, ставит выкладка): иначе вся старая переписка станет «новой». Сервер отдаёт `receipts_from_id` — первый `Message.id` после даты (один запрос, кеш); сообщения раньше — без значков и не в `unread`. Не задана — `open=false`, клиенты живут по-старому.
2. **Можно ли** `_receipts_open(...)`: участник (`booking_and_ride_for_user`), чат не закрыт (`_booking_chat_closed`), нет блокировки (`is_blocked`). Иначе ничего не пишем и не рассылаем, отвечаем `open=false`.
3. **Применить** `_apply_marks(session, booking, me, delivered, read)`: `delivered = max(delivered, read)`; значение **прижать** к последнему настоящему сообщению собеседника (`max(Message.id)` при `sender_id != me`, `id <= value`) — подбор чисел не даст сотни записей; условный `UPDATE … WHERE read_up_to < :r` (и `delivered`); строки нет — вставка, `IntegrityError` → `rollback` и тот же `UPDATE`. Функция **синхронная и сама не рассылает**: возвращает кадр `{"type":"receipt","user_id":me,"delivered_up_to":d,"read_up_to":r}` или `None` (не выросло — ни записи, ни кадра). REST после `commit` шлёт кадр через `notify_chat_message`; WebSocket — `frame = await run_in_threadpool(_apply_marks, …)` и `await manager.broadcast(booking_id, frame)`.
4. **Ручки:** `GET /bookings/{id}/receipts` → `{"open","receipts_from_id","peer_delivered_up_to","peer_read_up_to","my_read_up_to","unread"}` (`peer_*` = `null` при `open=false`); `POST` туда же с `{"delivered_up_to"?,"read_up_to"?}` → тот же ответ.
5. **WebSocket** (`chat.py:308`): кадр `{"type":"receipt",...}` — та же проверка и `_apply_marks`; ошибка — пропустить, канал не рвать. **Частота:** не чаще 1 записи в секунду на соединение; кадры чаще склеиваются (храним максимум, одна отложенная задача применяет его по истечении секунды и отменяется при закрытии сокета).
6. **`unread`** — сообщения собеседника с `id > my_read_up_to` и `id >= receipts_from_id`, не удалённые, не скрытые мной; при `open=false` — 0. `ConversationOut.unread: int = 0` считать в проходе `chat.py:923-928`; отметки — одним запросом, блокировки — `blocked_user_ids`.
7. **Пуш:** в `data` чат-пушей (`chat.py:349`, `:800`) — `"mid": msg.id`.
8. **Совместимость:** история остаётся списком; старые клиенты новые кадры игнорируют и отметок не шлют — у собеседника «Отправлено».
9. **Срок и удаление** — §4 (`cleanup._rules`, `_ALLOWED_TABLES`, `account.py`).

### Android (`android/`)
1. `data/ApiClient.kt`: `getChatReceipts`, `postChatReceipts` (сессия входа); 404 — старый сервер → `open=false`. `data/Conversation.kt` `parseConversationDto` — поле `unread`.
2. `data/ChatSocket.kt`: `sendReceipt(delivered, read)`; кадр `receipt` (целые > 0, иначе пропуск) → `onReceipt`: `user_id` собеседника — его отметки, свой — `my_read_up_to` (второе устройство).
3. `TripChatState.kt`: `peerDelivered`, `peerRead`, `receiptsOpen`; читать с историей (`drainHistory`) и после переподключения. «Доставлено» — после истории и на чужой кадр; «прочитано» — когда сдвигается `markSeen`. Отправка **пачкой**: раз в 1,5 с и только при росте; сокет жив — кадром, нет — `POST`. Значения только растут.
4. `TripChatWiring.kt`: `TripChatSeenMark` стартует с `my_read_up_to`; локальная метка — запасной путь для старого сервера.
5. `ChatDeliveryMark.kt` (новый): `chatDelivery(...)` → `Queued | Failed | Sending | Sent | Delivered | Read | None` (`None` и для `id < receipts_from_id`); `ChatDeliveryTicks`: `Icons.Default.Done` (отправлено, `CanonMuted`), `Icons.Default.DoneAll` (доставлено — `CanonMuted`, прочитано — `CanonGreen2`); смена — `AnimatedContent` (fade + scale, `CanonMotion.QUICK`, `CanonTokens.kt:404`); `contentDescription` на двух языках. Значки и подпись — **под пузырём на `CanonBg`** (как строка «В очереди»), не внутри зелёного пузыря; подпись §5 — только под последним своим. Не только цветом: форма значка и подпись.
6. `MessageBubble`: параметр `delivery` (флаги `failed/queued` остаются); «Не доставлено · Повторить» → «Не отправилось · Повторить» — как `TripChatWiring.kt:64` (BA прежний «Ебәрелмәне · Ҡабатлау»); у «Повторить» тач-цель ≥ 48 dp (`minimumInteractiveComponentSize`). `TripChatScreen.kt` передаёт `delivery`.
7. `data/FcmService.kt`: чат-пуш с `mid` и верным адресатом → один `postChatReceipts(delivered = mid)` без очереди (как `ApiClient.fireRegisterPushToken`, `:26`), до проверки «уведомления выключены».
8. `ConversationCard.kt` — `unread` с сервера; бейдж `ChatCard` (`RidesRequestsChatScreens.kt:2890-2898`) заменить на `UnreadBadge` (`Canon*`, «99+», описание RU/BA) — без `Color.White` и `MaterialTheme`.

### Сайт (`webapp/`)
1. `api/chat.ts`: `fetchReceipts`, `postReceipts`, `Conversation.unread?`; в `openBookingChat` — `onReceipt`, `sendReceipt`.
2. `utils/tripChat.js` (+ `.d.ts`): `deliveryState(...)` как на Android (с `receipts_from_id`), `unreadCount` от серверной метки, пачка 1,5 с.
3. `TripChatScreen.tsx`: значки у своих пузырей (`IconCheck` и новый двойной в `components/Icons.tsx`), подпись под последним, `aria-label` на двух языках; «прочитано» — лента внизу и вкладка видима (`:486`); стили `bubble__status` в `ui.css` — переменные темы.
4. `ChatInboxScreen.tsx`: бейджа нет — сделать с нуля: проп `unread` у `ChatCard` (`:27-66`), «99+», стиль `inbox-row__badge` в `ui.css` (переменные темы), `aria-label` на двух языках. `TripChatCard.tsx` — `unread` сервера, старый сервер — локальный счёт.
5. Доставку через веб-пуш не делаем: у service worker нет входа.

## 4. База данных
Миграция `<префикс>_chat_read_mark_<дата>`, префикс — по индексу (D3); `down_revision` — голова `main` на момент слияния (`alembic heads`), голова одна; up → down → up на SQLite и PostgreSQL. Создаёт `chatreadmark`; повторный запуск безопасен (`inspect`); `downgrade` удаляет таблицу. **Ждёт «да»** на рабочей базе; `CHAT_RECEIPTS_SINCE` ставит выкладка.
- **Срок хранения и удаление аккаунта:** `chatreadmark` — `cleanup._rules` 30 дней по `updated_at` (`MSG_DAYS`); `account.py` удаляет строки `user_id` и броней человека.

## 5. Надписи
Русский; башкирский — черновик `yuldash-bashkir-linguist` в `docs/tasks.md`, «Переводы на проверку».
- Подписи: «Отправлено», «Доставлено», «Прочитано»; описание значка для экранного диктора: «Сообщение отправлено», «Сообщение доставлено», «Сообщение прочитано».
- Изменение: «Не доставлено · Повторить» → «Не отправилось · Повторить» (BA прежний).
- Бейдж списка чатов для диктора: «Новых сообщений: N» / «Яңы хәбәрҙәр: N» — уже есть в `UnreadBadge`.
- Прежние: «В очереди · отправим при сети» / «Сиратта · селтәр булғас ебәрербеҙ».

## 6. Состояния экрана
Новых экранов нет. Отметки не загрузились или `open=false` — значков нет, чат работает как сейчас. Нет сети — «В очереди»; отказ — «Не отправилось · Повторить». Длинная башкирская подпись не ломает пузырь на 360 dp и при крупном шрифте (`sp`). Тёмная тема — `Canon*`; контраст значков ≥ 4.5:1 на `CanonBg` (`ContrastGuardTest`).

## 7. Тесты
**Сервер** (`pytest`, `ruff check app/ --select F`), `test_chat_receipts.py`:
1. Три сообщения собеседника → `POST delivered` → у отправителя `peer_delivered` = последнее, `peer_read` = 0; `POST read` → оба растут.
2. Меньшее значение — без `UPDATE` и рассылки (подменить `notify_chat_message`). 3. `10**9` и число между `id` прижимаются; свои сообщения не считаются.
4. Чужой — 403; блокировка в любую сторону — `open=false`, ничего не пишется; после разблокировки — прежние значения. 5. Окно закрыто (`done`, выезд 49 ч назад) — `open=false`; «забыл вещь» — `true`.
6. WebSocket **без Redis**: кадр `receipt` читателя доходит отправителю (`manager.broadcast`, не `from_thread`); без роста — тишина; неверный кадр не рвёт канал; 100 кадров подряд → не больше 2 записей и итог — максимум.
6а. `chat_receipts_since`: сообщения до даты — без значков и не в `unread`; не задана — `open=false`.
7. `/conversations.unread` — без своих, удалённых, скрытых мной; 0 при блокировке. 8. История — список; голос и фото как текст; в пуше `mid`.
9. Миграция вверх/вниз; сторожа `test_nothing_grows_forever.py`, `test_account_deletion.py`, миграций.
Мутации: убрать `is_blocked` → красный п. 4; прижатие → п. 3; условие роста → п. 2; рассылку из сокета через `notify_chat_message` → п. 6; дату начала → п. 6а.

**Android** (`:app:testDebugUnitTest`, `:app:assembleDebug`): `ChatDeliveryMarkTest`; Compose-тест пузыря (значки, подписи RU/BA); `TripChatReceiptsTest` (`MockWebServer`: история → один `POST`; 10 кадров за секунду → не больше двух отправок; 404 — без значков); `data/ChatSocketReceiptTest`; `BookingActiveTripDeep2ContentTest.kt:144-145` — новый текст «Не отправилось · Повторить», тач-цель ≥ 48 dp; бейдж `ChatCard` с «99+». Сторожа: `BilingualSourceGuardTest`, `CanonSourceGuardTest`, `ColorSourceGuardTest`, `ContrastGuardTest`, `SpacingRatchetGuardTest`, `NotifTypesGuardTest`.
**Сайт:** `npm run check` (`tests/chat-receipts-test.mjs`: состояния, пачка, `receipts_from_id`, бейдж и `aria-label` списка чатов), `npm run build`.

## 8. Приёмка
Принимает агент другой «семьи» (Q9), по умолчанию «не принято».
- [ ] Отметки — только участникам, при открытом чате, без блокировки; время прочтения нигде не отдаётся.
- [ ] Отметки только растут и прижаты к настоящим сообщениям; без роста — ни записи, ни кадра; кадры сокета не чаще 1/с; кадр из сокета доходит без Redis.
- [ ] После выкладки старая переписка не стала «новой» (дата `chat_receipts_since`).
- [ ] История прежней формы; старый клиент не ломается; новый на старом сервере — без значков.
- [ ] Эмулятор + сайт (Q11), обе пары ролей: «Отправлено» → собеседник получил (лента или пуш) → «Доставлено» → долистал вниз → «Прочитано» за ~2 с; без сети — «Отправлено», сеть вернулась — догоняет; перезапуск — отметки с сервера; голос и фото — так же.
- [ ] Блокировка — значки пропадают у обоих; окно закрыто — значков нет; бейдж списка чатов одинаков на Android и сайте (`Canon*`/тема, «99+», диктор RU/BA).
- [ ] RU/BA × светлая/тёмная, 360 dp, крупный шрифт; BA — черновик в `docs/tasks.md`.
- [ ] FCM на эмуляторе — если пуши там работают; иначе остаток W1-E2E-07.
- [ ] Двигает W1-E2E-04 и W1-E2E-05. Статус — только `docs/audit-blocks.md`.

**Мозг** (⭐ п.6): `decisions.md` — отметки чата: участники, окно, блокировка, без времени; «доставлено» через пуш выдаёт, что телефон был в сети (без времени); выключателя «не показывать» нет (Q16 а); `chat_receipts_since`. `architecture.md` — `chatreadmark`, `/receipts`, кадр `receipt`. `tasks.md` — части А/Б/В, переводы. `lessons.md` — рассылка из сокета без Redis.

## 9. Риски и границы
- «Доставлено» — устройство получило данные, а не «человек увидел»; телефон выключен — останется «Отправлено». Через пуш собеседник узнаёт, что телефон был в сети (без времени). Веб-пуш доставку не подтверждает.
- Выключателя «не показывать, что я прочитал» нет — можно добавить позже отдельным планом.
- Нагрузка: запись только при росте, на настоящих сообщениях, не чаще 1/с на сокет; клиенты шлют пачкой; `/conversations` без новых проходов.
- Не входит: чаты такси и посылок (`/instant`, `/parcels`), поддержка, групповые чаты.

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан |
