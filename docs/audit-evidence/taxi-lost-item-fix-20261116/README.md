# Такси: lost-item WebSocket, кодовый кандидат

Пятница / облако. Пользователь прямо разрешил узкую правку общего chat.py с последующим самостоятельным сведением веток. Main/production не меняются. Локальный Codex продолжает попутку.

База ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8, source `8c6970a7d0303e607ab4ccbf4749a37215bbb7c7`, ветка `fix/taxi-lost-item-ws-20261116`.

Изменены только taxi instant_chat_ws (5 добавленных/4 удалённых строки) и новый отдельный тест. Повторная проверка перед записью использует тот же `_order_for_chat`, что REST и handshake, включая действующее lost-item окно. Booking/parcel handlers, роли, схемы, тарифы, клиенты не изменены. Возможный конфликт при сведении: общий `backend/app/routers/chat.py`, участок instant_chat_ws; не заменять файл целиком.

13 регрессий: оба участника done/cancelled, REST/WS parity, одно сохранение/broadcast, точный push order_chat другой стороне, истёкшее/отсутствующее/равное текущему времени окно, истечение после handshake, переход accepted→done, outsider, блокировка. Участники неизменны; cached other_id при смене состава требует отдельного пакета.

- RED: 5 failed / 8 passed, 12.71s, exit 1 на исходном chat.py.
- GREEN: 13 passed, 2.18s, exit 0.
- Чатовые регрессии, включая попутку/доставку: 130 passed, 24.01s, exit 0 (green входит в эти 130, не дополнительные уникальные тесты).
- Ruff app/ --select F: exit 0.
- Независимая адресная приёмка: см. independent-review.md.
- FULL backend на source8c6970a7: **6855 passed, 119 skipped**, 782 warnings, 1494.15s (24m54s), exit 0; coverage **93.34%** при обязательном пороге 85%. Лог full.log и full.exit. 119 пропусков не считаются проверенными сценариями; это SQLite-прогон, не PostgreSQL/устройства. Исторические прогоны account/courier сюда не включены.

Прогоны: cwd backend; TZ=UTC; собственная TMPDIR в workspace, PYTHONDONTWRITEBYTECODE=1; пустые REDIS_URL/SENTRY_DSN/FIREBASE_CREDENTIALS/TELEGRAM_BOT_TOKEN; HTTP(S)/ALL_PROXY удалены из env. Python /tmp/yuldash-audit-20261116-venv/bin/python через существующий /tmp/yuldash-audit-20261116-logs/run-isolated-tests.py; wrapper блокирует nonloopback Python socket до импорта. Это не OS network sandbox. Данные синтетические, SQLite отдельная. UI/реальные интеграции/нагрузка/production NOT_RUN.

Команда targeted после wrapper: `tests/test_taxi_lost_item_ws.py tests/test_chat_closes_after_trip.py tests/test_chat_edges.py tests/test_chat_hidden_privacy.py tests/test_chat_message_idempotency.py tests/test_chat_mutations_live.py tests/test_chat_push_leads_to_the_chat.py tests/test_chat_ws_idempotency.py tests/test_parcel_chat.py tests/test_parcel_chat_is_not_inherited.py tests/test_rest_chat_local_delivery.py -q`.

FULL команда после wrapper: `tests/ -q --maxfail=25 --cov=app --cov-report=term --cov-fail-under=85`. Исходники заморожены до конца прогона; время общего тяжёлого слота согласовано с Амио, после exit0 слот освобождён. Main/production не менялись.

Публикуемый red.log санитизирован: 5 строк repr аутентифицированной тестовой fixture заменены маркером, поскольку содержали фрагменты синтетических JWT. Assertion, traceback, счётчики и результаты не менялись. Исходный лог сохранён отдельно в приватном облачном стенде и не публикуется. Обычный detect-secrets не распознал усечённые JWT, поэтому дополнительно проверены JWT-фрагменты и строки credentials.
