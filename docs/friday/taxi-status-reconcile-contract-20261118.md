# Status push: HTTP reconciliation contract

База 0ec0439, отдельная test/friday-taxi-status-reconcile-contract-20261118. После offer snapshot остаётся поздняя доставка status push; не вводить новую sequence без семантики и не объявлять search_revision порядком accepted/arriving/onboard/done.

## План до кода

1. Read-only inventory status payload и consumers. Существующий status push содержит type/order_id/status, а серверный guard не упорядочивает доставку.
2. Test-only контракт: настоящий accepted transition формирует задержанный status push, затем настоящий cancel/reassign меняет SQL; авторизованный HTTP GET текущего заказа должен отдать актуальный статус, не старый payload. Проверить владельца-пассажира, прежнего водителя после снятия участия (403 без private payload), постороннего и новый candidate с действующими ограничениями.
3. Отдельно проверить, что search_revision не различает все нормальные статусы одной поездки. Это диагностическое доказательство необходимости refresh/другого протокола, не реализация клиентского подавления.
4. Независимая оценка плана/тестов, scoped/ruff, отдельная публикация test-only. Runtime/schema/Android/PWA не менять и не повторять заблокированные клиентские/UI gates. Новый FULL не требуется для неизменного runtime, но старый результат не считать результатом новых тестов.

Никакого обещания актуальности в момент доставки/показа: после GET возможна новая смена состояния. Клиент должен трактовать push как hint, не источник авторизации; 403/404/logout требует не показывать private payload, сеть/error не делает старое сообщение новым. Конкретное UI поведение требует отдельного дизайна/приёмки. Адресный sanitizer остаётся ограниченной грамматикой, authorized API from_text контракт не меняется. Production/paused/main untouched.

## Независимая оценка до тестов

Journal принял cb4d6a2 для TEST-ONLY. GET имеет lazy reconciliation и не является чистым чтением: основной сценарий использует future expiry и сравнивает ответ с SQL после GET. Отдельный boundary: old candidate проходит initial authorization, GET истекающего offer продвигает поиск к D2; повторный order_payload participant guard должен вернуть 403, без actor_authorized обхода. Проверять отсутствие route/phone/coords/token в forbidden JSON. Passenger HTTP не раскрывает current candidate id: D2 ownership подтверждается SQL и его собственным authorized GET, passenger получает offered/driver_id None без offer token.

Статусный payload получаем настоящим _notify_transition, задерживаем только transport. Сохраняются текущие blur/phone/from_text контракты; нельзя притвориться, что authorized offered pickup уже скрыт. Для done body содержит полные адреса пассажиру и может появляться на lockscreen; это отдельная известная граница, без скрытого изменения текста/политики. No-auth/missing ответы подтверждают только HTTP contract, не клиентский logout/offline UX. После любого GET возможен новый переход.

Read-only inventory показал: accepted detached snapshot не содержит всех полей будущих metadata, другие callers читают ORM после commit. Поэтому расширять общий _status_data сейчас небезопасно без отдельной snapshot реализации. Timestamp конкретного перехода потенциально полезен как контекст, но sent_at/UUID/search_revision не решают ordering. Клиентские consumers пока показывают title/body; metadata сама по себе suppression не даст.

## Авторский кандидат

9b01a55: четыре test-only проверки, 4 PASS, exit 0, 2.04 с, `/tmp/taxi-status-reconcile-contract-phones.log` и `.exit`. Настоящие transition/cancel/matcher/snapshot/reconcile/order_payload/auth остаются, подменены только transports и candidate selection. Проверены старый accepted hint против актуального passenger HTTP offered, SQL ownership после GET, отсутствие candidate id/token у пассажира, запрещённые ответы без private fields, действующие candidate blur/phone/from_text контракты. Отдельный GET действительно выполняет lazy advance, после чего D1 получает 403 от повторной проверки участия. Нормальные accepted/arriving/onboard имеют одну revision. 401/404 проверены как HTTP outcomes, не client UX. Ruff F/diff PASS; runtime/schema/client неизменны. Независимая приёмка ожидается.


Независимая приёмка 9b01a55: **204 PASS, exit 0, 6.17 с**, Ruff F/diff PASS; P0/P1/P2 не обнаружены. Изолированная SQLite, sockets disabled, DB/proxies unset, lifecycle flag false. Detect-secrets трёх файлов: 0 findings. Runtime/schema/runner неизменны относительно 0ec0439; новый FULL/PG не запускался. Предыдущий FULL7318 относится к базе, не к четырём новым тестам. Это подтверждение HTTP contract и известных границ, не клиентское подавление поздних сообщений/FCM/UI.
