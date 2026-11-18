# Done push: маршрут в уведомлении

База b070fc0, отдельная fix/friday-taxi-done-push-privacy-20261118. Поручение родителя исследовать известную границу done lockscreen, без production/paused действий.

## План до кода

1. Проследить _notify_transition(S.done) → push_bilingual → send_push → WebPush/FCM. Сейчас body route составлен из точных from_text/to_text; адресат пассажир, но notification preview может быть виден посторонним. Это не unauthorized API access и не доказательство фактического отображения на конкретном телефоне.
2. Доказать настоящий helper/transport payload на синтетических точных адресах в RU/BA; оценить обычный completion path и authorized GET/receipt. Не использовать sanitize regex как обещание приватности произвольных комментариев: убрать маршрут из данного push целиком.
3. Предпочтительный узкий runtime вариант после независимого review: сохранять существующие bilingual title 'Поездка завершена'/'Сәфәр тамамланды', body пустой для обеих локалей, прежний instant_status/order_id/status payload и recipient. Не добавлять неподтверждённый перевод/идентификаторы/координаты. Заголовок уже сообщает исход, пустой body не должен восстанавливать route через downstream fallback; проверить реальный transport boundary.
4. Authorized GET/receipt, driver money, shares SMS, другие статусы и другие режимы не менять. Не менять notification policy globally или client UI/настройки.
5. Минимальный RED, review до runtime, scoped/review/FULL если runtime меняется, отдельная проверенная ветка. Если consumer требует непустой body, сначала явно уточнить контракт по коду/тесту, не подставлять адрес обратно.

Граница: убираем route только из данного нового done push. Старые доставленные сообщения, done status/order id как факт поездки, screenshots, иные notifications/lockscreen настройки, реальные Android/PWA экраны и late delivery не защищены этим пакетом. Никакого нового клиентского rollout или production deploy.

## Независимая pre-code оценка

Journal принял 94b83d4: empty body совместим с push_bilingual (BA fallback остаётся пустым), WebPush JSON, SW (опускает body), Android (пустая строка не восстанавливает маршрут). Требуются реальные RU/BA user.language ветки, настоящий helper/push_bilingual/send_push, transport spies ниже send_push: WebPush JSON и FCM data/title/body. Не считать mock push_bilingual проверкой fallback. В сообщении не должно быть literal from/to/квартиры/кода; share SMS вызов отделить от push, его контракт не менять. Authorized GET/receipt и остальные statuses сохранить. Это узкая серверная минимизация нового payload, не доказательство реального UI или общей lockscreen приватности.

## Кандидат и авторские доказательства

Runtime e293552: удалено вычисление route, только S.done body RU/BA заменены на пустые строки; заголовки и data/recipient неизменны. Tests 46b3b11: исходные 4 RED (RU/BA × FCM/WebPush) сохранены в `/tmp/taxi-done-privacy-red.log`, exit 1, 1.73 с. После правки 11 новых cases + 6 существующих recipient: 17 PASS, exit 0, 2.11 с, `/tmp/taxi-done-privacy-green.log`. Настоящие SDK JSON encoder/WebPush serialization, interception только конечного сетевого transport, synthetic credentials/session/binding без реальных секретов.

Проверены literal title/body/data и отсутствие маршрута, language RU/BA, Notification IDs unchanged, остальные accepted/arriving/onboard тексты. Authorized GET и receipt сохраняют точный маршрут. Настоящий service onboard→done сохраняет прежнее округление: 300 ₽ плюс waiting 250 коп → final 302 ₽/receipt 30200 коп. Это не полный router settlement/commission gate. Shares done/sat invocation сохранён отдельным spy, не реальная SMS. Ruff F/diff PASS. Независимая приёмка и FULL ожидаются.

Независимая приёмка 46b3b11: 430 scoped PASS, exit 0, 7.13 с, 24 deprecation warnings; P0/P1/P2 не обнаружены, Ruff F/diff PASS. Авторский disposable PG21 PASS, 2.99 с; независимый PG21 PASS, 3.11 с, оба exit 0 и cleanup подтверждён. Логи `/tmp/taxi-done-privacy-pg.log` и `/tmp/taxi-done-privacy-independent-pg.log`. PG регрессия проверяет прежние server races, не настоящую сеть push. Detect-secrets четырёх файлов: 0 findings. Runtime/tests frozen; следующий FULL после короткой согласованной очереди Amio.


## Финальный локальный gate

FULL на 0e2e0c8 (runtime e293552, tests46b3b11): **7333 passed, 140 skipped, 815 warnings, 742.07 с, exit 0**, `/tmp/taxi-done-privacy-full.log` и `.exit`. Backend pytest tests -q --disable-socket --allow-unix-socket, fresh TMPDIR, TZ=UTC, lifecycle flag false, DATABASE_URL/proxies unset. Runtime/tests во время прогона не менялись. PG21 отдельно авторский и независимый PASS; фактический heavy exit передан Amio.

Подтверждена узкая серверная гарантия: новые taxi done push не содержат route, без изменения разрешённых GET/receipt и других status-текстов. Реальные FCM/WebPush transport перехвачены, не доставлены; реальный lockscreen/клиентская UI проверка не выполнялись. Старые уведомления, другие типы сообщений, сам факт поездки/order_id/status и поздняя доставка не защищены этим изменением. Main/production/реальные существующие БД и paused операции не затронуты.
