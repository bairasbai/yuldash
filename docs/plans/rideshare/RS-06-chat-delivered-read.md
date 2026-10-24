# RS-06 — В чате видно «доставлено» и «прочитано»

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | да — миграция рабочей базы (таблица `chatmessagereceipt`) |
| Зависит от | RS-01 (договор ошибок `herr(..., code=)`, D2 — общие решения, `docs/plans/README.md`; новых кодов план не вводит); по общим файлам — после RS-05 (очередь в индексе) |
| Не параллельно с | RS-05 (`models.py`, `ApiClient.kt`, `cleanup.py`, `account.py`, `BookingActiveTripScreen.kt`, миграции); RS-13, если трогает `FcmService.kt` — по индексу |
| Деление на PR | `[RS-06А]` сервер с миграцией (облачный агент) → `[RS-06Б]` сайт → `[RS-06В]` Android (агент на компьютере Александра) |
| Проверка `yuldash-security-privacy` | да — кто видит отметки, блокировка, окно, пуш как признак «телефон в сети» |
| Двигает сценарии приёмки | W1-E2E-04 (чат после обрыва и перезапуска), W1-E2E-05 (только участники, окно, блокировка) |
| Зона файлов | `backend/app/`: `models.py` (`ChatMessageReceipt`), `config.py` (`chat_receipts_since`), `routers/chat.py` (`websocket_endpoint`, `send_message`, `ConversationOut`/`conversations`, новые ручки отметок и cursor-пагинация истории), `cleanup.py`, `account.py`; `backend/alembic/versions/<префикс>_chat_message_receipt_<дата>.py`; `backend/tests/test_chat_receipts.py`. `android/app/src/main/java/com/yuldash/app/`: `ChatDeliveryMark.kt` (новый), `TripChatState.kt`, `TripChatWiring.kt`, `TripChatScreen.kt`, `BookingActiveTripScreen.kt` (только `MessageBubble`), `ConversationCard.kt`, `RidesRequestsChatScreens.kt` (только бейдж `ChatCard`), `data/ApiClient.kt`, `data/ChatSocket.kt`, `data/Conversation.kt`, `data/FcmService.kt`; тесты в `android/app/src/test/…/app/`: `ChatDeliveryMarkTest.kt`, `TripChatReceiptsTest.kt`, `data/ChatSocketReceiptTest.kt`, `BookingActiveTripDeep2ContentTest.kt` (текст повтора). `webapp/src/`: `api/chat.ts`, `utils/tripChat.js`, `utils/tripChat.d.ts`, `screens/TripChatScreen.tsx`, `screens/ChatInboxScreen.tsx`, `components/{TripChatCard,Icons}.tsx`, `ui.css` (классы `bubble__status`, `inbox-row__badge`); `webapp/tests/chat-receipts-test.mjs`. Мозг: `docs/tasks.md`, `docs/decisions.md` |
| Источник | `c5be55a:docs/research/apps/master/part-001.md` п. 15, 28; `c5be55a:docs/research/apps/master/part-002.md` «Минимальные контракты надёжности» (событие, восстановление, доступ); решения 24.10: Q16 а, Q9, Q10, Q11 |
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
1. **Явные ID, никакого префикса (B8).** `ChatMessageReceipt` (`chatmessagereceipt`): message_id FK message.id CASCADE, user_id FK user.id CASCADE, delivered bool false, read bool false, updated_at; composite PK/unique(message_id,user_id), индекс user_id. Это подтверждение конкретного сообщения данным участником, не всех id≤mid. Поле delivered/read содержит максимум0/1 и только растёт. Message.id глобальный и имеет пробелы; нельзя выводить непрерывную доставку по одному числу, порядку push или max полученных id.
2. **Дата начала:** config `chat_receipts_since` UTC, ставит выкладка. Отметки доступны только Message.created_at≥даты; не задана — open=false, старое поведение. В ответах `receipts_since`, никакой фиктивной «вся старая история непрочитана». История остаётся списком для старого endpoint; новому клиенту добавить `GET /bookings/{id}/message-page?before_id?&after_id?&limit≤500` → `{messages,next_before_id,next_after_id,has_more}`. Курсор только внутри данной брони, после проверки участника/окна/блокировки. Нельзя считать последние500 доказательством получения пропущенных ранних сообщений; их ID не подтверждаются.
3. **Доступ** `_receipts_open`: участник booking_and_ride_for_user, открыто окно `_booking_chat_closed`, нет is_blocked в обе стороны. При запрете marks не писать/не рассылать; GET/POST возвращает open=false и пустой набор, посторонний403. Скрытое пользователем сообщение не включать в историю/unread; удалённое или недоступное не принимать в ack. Текущие guards истории усилить теми же проверками, чтобы новая пагинация не расширила доступ.
4. **Применить:** `POST /bookings/{id}/receipts` `{delivered_ids:[int],read_ids:[int]}` (каждый список≤100, положительные unique; пустые допустимы). Сначала одним SELECT отфильтровать настоящие сообщения собеседника данной брони после даты, доступные текущему пользователю. read_ids подразумевают delivered_ids тех же ID. INSERT…ON CONFLICT UPDATE ставит `delivered=old.delivered OR incoming.delivered`, `read=old.read OR incoming.read`, обновляет updated_at только при росте любого поля. Не использовать WHERE только по read: новый delivered при прежнем read тоже сохраняется. PostgreSQL upsert; SQLite эквивалентный атомарный upsert/CAS, конфликт не сбрасывает второй независимый флаг. Неверные ID не создают строки и не подтверждают соседние сообщения. Максимум две строки на Message (участники); для своего сообщения receipt не писать.
5. **Ответ/кадр:** GET `/bookings/{id}/receipts?after_message_id=&limit≤500` → `{open,receipts_since,items:[{message_id,peer_delivered,peer_read,my_delivered,my_read}],next_after_message_id,unread}`. Страница items соответствует Message данной брони после cursor в порядке id, а не только существующим receipt-строкам; для сообщения от меня peer_* читаются из строки собеседника, для входящего my_* — из моей строки, остальные false. Пустые отметки false, даты/время прочтения наружу не идут. POST возвращает тот же envelope для переданных допустимых ID, current unread; WebSocket принимает `type:receipt` с delivered_ids/read_ids, исполняет тот же сервис и после commit рассылает только изменённые `{message_id,user_id,delivered,read}`. Нет роста → ни записи ни кадра. REST notify_chat_message после commit, WS `run_in_threadpool` + `await manager.broadcast` (без Redis работает). Не подтверждать receipt по факту вызова API: отправитель видит только полученный и сохранённый сервером peer receipt.
6. **Частота:** клиенты копят множества ID≤100, отправляют не чаще1,5с; сервер не чаще1 записи-пачки/сек на соединение, pending множества объединяются по ID/OR без превращения в max ID. При disconnect отложенную пачку применить/commit перед закрытием, либо клиент повторит сохранённые pending IDs. `unread` = доступные сообщения собеседника после даты без receipt.read=true; считать по всем сообщениям брони, не только последней странице. В `/conversations` один групповой запрос/проход по набору броней плюс предзагруженные блокировки, без N+1; сохранить текущую форму и добавить unread.
7. **Пуш:** data `mid=msg.id` только подсказывает перечитать данные; регистрация канала/вызов FCM/preview не есть доставка сообщения. Потерянный/переставленный push не меняет статусы. Старые клиенты незнакомые кадры игнорируют, receipts не шлют — у собеседника остаётся Sent.

### Android (`android/`)
1. ApiClient: GET/POST receipts и пагинация message-page, typed DTO по п.5;404 означает старый сервер, open=false. Conversation.unread от сервера. ChatSocket.sendReceipt принимает массивы ID, onReceipt обновляет запись ТОЛЬКО данного message_id с OR флагов; своё user_id синхронизирует второе устройство.
2. TripChatState/TripChatWiring: delivered_ids добавляются после получения и успешного сохранения ПОЛНЫХ Message из REST/WS, read_ids — только реально отрисованные/видимые сообщения при активном экране (нижняя позиция сама не доказывает прочтение не загруженной страницы). Пропущенные110 и пришедшее120 не дают Delivered110. После reconnect загрузить историю/receipts страницами и отправить только действительно полученные ID. Pending ack хранить account/booking-scoped, повтор допустим, два устройства объединяются на сервере.
3. FcmService: проверить сессию и recipient до любого действия, mid не подтверждать немедленным POST. Push при наличии возможности лишь запускает авторизованное чтение соответствующей Message/страницы; ack только IDs фактически полученных полных сообщений после записи локально. При запрете фона/ошибке сети ничего не обещать: история догонится при открытии. Получение одного120 не означает получение110; уведомления выключены — запрет показа сохраняется, background fetch сам по себе не значит Read.
4. ChatDeliveryMark.kt: Queued/Failed/Sending/Sent/Delivered/Read/None по конкретному ID; read⇒delivered. До receipts_since и open=false — None. Icons.Done/DoneAll и подпись под последним своим на CanonBg, цвета CanonMuted/CanonGreen2, AnimatedContent+CanonMotion. Форма+подпись+двуязычный contentDescription, не один цвет.
5. MessageBubble: «Не доставлено · Повторить» → «Не отправилось · Повторить» (BA прежний «Ебәрелмәне · Ҡабатлау»), ≥48dp. ChatCard/ConversationCard: серверный unread, общий UnreadBadge Canon*,99+,RU/BA. Новых экранов нет.

### Сайт (`webapp/`)
1. api/chat.ts и tripChat.js/.d.ts: тот же контракт ID/envelope, receipt map message_id→flags, OR, cursor-пагинация, пачка≤100/1,5с. TripChatScreen подтверждает только сохранённые полные Message; Read — фактически показанные сообщения при document.visibilityState=visible и активном чате. Порядок REST/WS/вкладок не продвигает префикс.
2. Галочки/подписи под своим пузырём, aria-label RU/BA, переменные темы в ui.css; бейдж с нуля у ChatInboxScreen.ChatCard с unread и99+, TripChatCard — серверный unread (старый сервер — прежний локальный). Components/Icons.tsx — двойная галочка.
3. Service worker без сессии не посылает receipts; web-push только приглашает обновить чат. Переоткрытая вкладка сначала восстанавливает сообщения и серверные индивидуальные отметки, не считает preview доставкой/прочтением.

## 4. База данных
Миграция `<префикс>_chat_message_receipt_<дата>` создаёт chatmessagereceipt c unique/FK/индексами п.3.1, не chatreadmark. down_revision — голова main по очереди индекса; одна голова. Старые Message не превращаются в прочитанные/новые произвольно: gate chat_receipts_since явно задаёт начало протокола.
- На previous-main schema со старыми синтетическими Message/Booking проверить target на SQLite/PostgreSQL, сохранность сообщений и корректные FK/defaults; up→down→up на временной БД. Downgrade удаляет только receipt-таблицу; старый код без отметок, содержание Message не меняется. **Ждёт «да»** на применение рабочей БД/дату включения.
- Receipt живёт пока соответствующее сообщение: CASCADE при message deletion, уборка orphan и updated_at старше30д с учётом живых Message; не удалять индивидуальную read-отметку раньше Message, иначе unread воскреснет. account.py удаляет строки user_id и сообщений/броней удаляемого аккаунта. Включить таблицу в правила cleanup/account и guards.

## 5. Надписи
Русский; башкирский — черновик `yuldash-bashkir-linguist` в `docs/tasks.md`, «Переводы на проверку».
- Подписи: «Отправлено», «Доставлено», «Прочитано»; описание значка для экранного диктора: «Сообщение отправлено», «Сообщение доставлено», «Сообщение прочитано».
- Изменение: «Не доставлено · Повторить» → «Не отправилось · Повторить» (BA прежний).
- Бейдж списка чатов для диктора: «Новых сообщений: N» / «Яңы хәбәрҙәр: N» — уже есть в `UnreadBadge`.
- Прежние: «В очереди · отправим при сети» / «Сиратта · селтәр булғас ебәрербеҙ».

## 6. Состояния экрана
Новых экранов нет. Отметки не загрузились или `open=false` — значков нет, чат работает как сейчас. Нет сети — «В очереди»; отказ — «Не отправилось · Повторить». Длинная башкирская подпись не ломает пузырь на 360 dp и при крупном шрифте (`sp`). Тёмная тема — `Canon*`; контраст значков ≥ 4.5:1 на `CanonBg` (`ContrastGuardTest`).

## 7. Тесты
Будущие сценарии — **NOT_EXECUTED**, все push/сеть управляемы тестом, реальная доставка отдельно.
**Сервер** test_chat_receipts.py (`pytest`, `ruff`):
1. Сообщения110/120: POST delivered_ids=[120] →120=true,110=false; read_ids=[120] ⇒read/delivered120=true,110false. Пропущенный/переставленный push не создаёт receipt110. Чужие/свои/несуществующие/другая бронь/до даты/скрытые IDs не создают записи; лимит100 валидируется.
2. Одновременные PG-сессии: delivered110 и read120, delivered/read одного ID, меньший/повторный флаг: ни одна отметка не откатывается, растёт каждое поле независимо. Без роста — ни UPDATE ни кадра. 100 WS-пачек→склейка ID/OR, не max(id); финальная пачка после disconnect не теряется.
3. WS без Redis →peer получает receipt после commit; ошибка кадра канал не рвёт. REST/WS/две вкладки/FCM в разном порядке дают один итог на ID. API вызван, но response/commit failed →отправитель не видит фиктивного Delivered.
4. Блокировка обеих сторон, закрытое окно, чужой →без записей/рассылки; open=false скрывает peer receipts. После снятия блока прежние индивидуальные отметки, unread по всей истории, не одной странице. История>500: ack только полученных страниц, пробел110 не закрыт120. Пагинация не раскрывает чужую историю.
5. receipts_since: не задано→старый режим; старые сообщения без значков/unread; новые учитываются. Cleanup не воскресит unread на живом Message, deletion CASCADE. Migration previous-main→target→оговорённый rollback/up наSQLite/PG; guards retention/deletion/migrations.
Мутации: mid→prefix, OR→overwrite, WHERE только read, убрать is_blocked, рассылка WS через sync notify без Redis, preview→ack — каждая краснит соответствующий тест.
**Android/PWA:** одинаковые fixture110/120, потерян/переставлен push, смешанные WS/REST/FCM, reconnect/рестарт/две сессии,500+истории, открыта вкладка с незагруженным110. Delivered только на полученных полных Message, Read только на видимых; неподтверждённый POST receipt не рисует успех отправителю. Compose/unit/GuardTest+assembleDebug и PWA check/build — при реализации с выводом. RU/BA×темы,99+,диктор,48dp,крупный шрифт,360dp.

## 8. Приёмка
Независимая от авторов; сейчас **NOT_EXECUTED**.
- [ ] Только участники при открытом чате/без блока; время прочтения не отдаётся, незнакомый ID не создаёт receipt.
- [ ] Delivered/Read — по конкретному ID, атомарный OR каждого поля, read⇒delivered; повтор без побочек, WS без Redis доходит после commit.
- [ ] Доказан110/120 с потерянным/переставленным push и смешанным REST/WS/FCM:110 остаётся Sent, пока его данные не получены. Push preview и регистрация канала не выданы за доставку.
- [ ] Android↔PWA обе пары ролей: Sent→получено полное Message→Delivered→сообщение видно→Read; перезапуск восстанавливает карту отметок, пагинация не признаёт пропущенное прочитанным. Бейдж unread одинаков на обеих платформах.
- [ ] Старый сервер/клиент работает без значков; receipts_since не превращает старую историю в новую. Cleanup/account/up/down проверены на синтетических старых данных, owner gate применения не обойдён.
- [ ] RU/BA×темы,узкий экран/шрифт/диктор; реальный FCM/два телефона и BA носителем остаются отдельно. Статус режима только docs/audit-blocks.md, W1-E2E-04/-05 только по фактическим прогонам.
**Мозг:** решения — индивидуальные подтверждения, receipt не «был в сети», дата начала; архитектура — chatmessagereceipt, пагинация, envelope/WS; задачи — части/переводы. Выключателя «не показывать прочтение» нет (Q16а), отдельно согласовать при изменении продукта.

## 9. Риски и границы
- Delivered означает полные данные сообщения на устройстве по клиентскому ack, Read — сообщение показано клиентом; сервер не доказывает внимание человека. Клиентская статика чужого приложения не источник серверной гарантии.
- Два устройства объединяют положительные факты по конкретным ID; удаление локальной копии не откатывает исторический receipt. Индивидуальные строки увеличивают объём, но ограничены сроком Message; N+1 не вводится.
- Не входят такси/посылки, поддержка/группы; push/FCM вызов и зарегистрированное устройство не считаются доставкой. Фон может не выполниться, при открытии чат сверяется заново.

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан |
