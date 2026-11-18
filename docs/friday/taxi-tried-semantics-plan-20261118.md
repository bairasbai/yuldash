# Tried rollback: выбор семантики и совместимый план

База 87c68cc. Этот пакет только исследование/план, не исправление runtime. Доказательства: [PG диагностика](taxi-tried-rollback-postgres-20261118.md), четыре исхода для NULL/nonNULL lifecycle, авторский и независимый PG25 PASS. Реальная PostgreSQL ошибка COMMIT 23503 оставляет Redis exclusion без committed offer; synthetic exception после настоящего успешного commit оставляет offer. Redis здесь fakeredis EXEC, не проверка сети.

## Сравнение

1. **Best-effort attempted history (нынешнее поведение).** Успешный SQL UPDATE победителя запускает Redis EXEC до COMMIT. Это история попытки, не подтверждение назначения, доставки или просмотра. При SQL rollback может потеряться доступный кандидат; при Redis outage/read error/TTL expiry возможны повторы. Сохранение этой политики совместимо с нынешними clients/writers и не исправляет availability дефект. Рекомендуется как честное описание текущей базы, не как обещание строгого no-repeat.
2. **Committed exclusions (offer и reassign).** История записывается в той же PostgreSQL транзакции, что публикация offered. Rollback убирает обе записи; successful commit сохраняет обе даже при потерянном ACK. Следующий matcher читает источник истины SQL, а не Redis cache как обязательный exclusion. Это рекомендуемое направление для устранения именно ложного исключения после rollback. Цена: новая ограниченная таблица/протокол, retention, правила rollout и дополнительные SQL операции. Это ещё не принятое runtime решение.
3. **Delivery/seen history.** История только фактически доставленных/увиденных предложений требует клиентских ACK и политики повторов. Ни FCM send, ни SQL commit это не доказывают. Не подходит как небольшой совместимый backend-only этап.
4. **Компенсация либо перенос EXEC.** SREM/DELETE после ошибки может удалить prior/duplicate или concurrent valid membership, ошибочно отменить успешный commit с потерянным ACK. SELECT затем SREM не атомарны. EXEC после COMMIT открывает окно immediate decline → matcher до history. Один Lua/operation token без изменения readers и ownership не решает SQL/Redis атомарность. Эти варианты не внедрять.

## Предлагаемые этапы, каждый отдельно принимается

A. Сейчас: зафиксировать решение и acceptance matrix; runtime/настройки/схему не менять. Я рекомендую вариант 2 для будущей работы, но пока сохраняю вариант 1 в рабочем коде.

B. После принятия протокола: аддитивная неактивная SQL history foundation. Альтернатива отдельной таблице — nullable bounded список driver IDs в самой строке InstantOrder: NULL legacy, nonNULL включая пустой список SQL-mode, атомарный UPDATE под существующим row lock. Это меньше схемы и проще ownership, но требует доказанного лимита размера через все advance/route/fallback, правил JSON/array portability и решения срока истории. Не предполагать, что instant_max_offers ограничивает весь lifecycle. Выбор представления пока открыт; предпочтение меньшему row-local варианту только после доказательства bound. Таблица не обязательна и не принята.

Для сравнения table-варианта: Предварительная форма ключа: order_id + nonNULL lifecycle + driver_id; запись обозначает exclusion в цикле, а не событие доставки. Нужно отдельно решить audit/retention и FK deletion policy до миграции. Не хранить адреса/телефон/push payload. Никаких backfill из Redis: он уже может содержать rolled-back attempts, информация о commit там отсутствует. Не применять к существующей БД. Legacy NULL остаётся старым путём.

C. Защищённая default-off реализация только для **новых** явно помеченных SQL-history циклов после совместимого writer rollout. Не выводить режим только из nonNULL lifecycle или текущего флага: существующий tokenized цикл всё ещё Redis-based; выключение флага не должно менять источник истины уже начатого цикла. Нужен сохранённый per-cycle mode/version с правилами чтения всеми writers. Все участники цикла сохраняют mode; new cycle выбирает mode атомарно с lifecycle/revision. Старые binaries не знают mode и потому не поддерживаются в активном SQL-mode fleet. Перед включением требуются обновление/дренирование всех writers, не только HTTP; флаг off не разрешает binary downgrade при активных SQL-mode циклах.

D. SQL-mode matcher: под тем же order row lock/CAS, что successful offered UPDATE, создать идемпотентный exclusion и commit вместе. До окончательного алгоритма проверить stale candidate lookup: конкурентный matcher мог прочесть history до чужого commit. После получения lock обязателен fresh ownership/history recheck; CAS loser без history/push. Reader использует SQL exclusions, не union с Redis, иначе rollback false exclusion возвращается. Redis GEO/presence может остаться; исчезновение Redis по-прежнему не означает доступный подбор. Не обещать устранения всех outage причин.

E. Driver cancel/reassign — второй writer и особый случай: отменившийся водитель должен быть исключён **в новом цикле** в той же транзакции, что перевод cancelled → searching. Это не committed offer нового цикла; корректное общее имя — committed exclusion history с reason offered / reassign_cancelled. Snapshot cancelled_driver_id и ownership проверяются до записи; postcommit Redis больше не источник SQL-mode exclusion. Не терять это правило при заявлении «committed-offer history».

F. Terminal/accept cleanup: SQL history не удалять старым Redis cleanup. Новый lifecycle отделяет циклы; retention отдельно и никогда не удаляет активные циклы. Не переносить небезопасный late DELETE на SQL. Rollback/error не компенсировать успешный commit по догадке. Unknown commit outcome требует fresh reconciliation текущего состояния, но не автоматического повторного offer/push.

## Матрица до runtime acceptance

- Actual deferred PG COMMIT refusal: offered и exclusion отсутствуют после rollback, sole eligible driver доступен следующему matcher. Контроль second candidate и real exclude, не mocked constant candidate.
- Actual successful commit + synthetic lost ACK: offered и exclusion сохраняются; caller rollback не отменяет их. Явно модель, не network timeout.
- Duplicate history insert и два concurrent matcher: один CAS winner, loser без history/push; stale lookup до lock не обходит committed exclusion. Проверить observable PG lock wait, не только asyncio scheduling.
- Driver cancel/reassign: new lifecycle + cancelled driver exclusion атомарны, rollback обоих; нельзя предложить ему же до postcommit continuation. Lost ownership не создаёт чужую history.
- Same lifecycle valid prior member / concurrent committed writer: никаких удалений/компенсации. SQL-mode Redis содержит ложный member после legacy fault, но он не отсекает кандидата; Redis отсутствие не обходит SQL exclusion.
- NULL, tokenized Redis mode, SQL mode; флаг on/off между циклами и в активном цикле; invalid token/mode fail closed без silently adopting. Сохранить revision/overflow/CAS и privacy/price policies.
- All cycle writers: start/restart, reassign, advance/retry, route/waypoints/fallback, expiry/cancel/accept; worker и lazy GET reconcile. Выписать точные функции и locks перед реализацией.
- Isolated schema upgrade/downgrade/metadata, SQL uniqueness/rollback/concurrency, retention active-cycle protection. Old binaries mixed rollout не объявлять безопасным.
- Адресные SQLite и real disposable PG, независимое review, затем FULL нового runtime; старый FULL7333 не относится к будущим изменениям.

## Открытые решения и границы

Вариант 2 является предложением, не скрытым включением новой продуктовой политики. До кода согласовать название committed exclusions, сохранённый mode/version и lifecycle migration policy, retention/TTL семантику (нынешний Redis TTL3600 допускает повтор в том же долгом цикле; SQL вечный запрет изменил бы поведение). Нельзя автоматически переносить Redis TTL как строгую бизнес-гарантию. Количество попыток/search_round уже ограничено отдельно.

Нет обещания exactly-once push, actual delivery, клиентского подавления старых сообщений, строгого no-repeat при всех сбоях, смешанных старых writers или реального network fault injection. Main/production/реальная миграция и приостановленные операции не затрагиваются.

## Уточнение направления после поручения родителя

По умолчанию сохраняем существующую часовую TTL-семантику, не расширяем срок до всего lifecycle. Важно: нынешний EXPIRE обновляет срок **всего набора** на 3600 секунд при каждом успешном remember, включая duplicate SADD; это не отдельный TTL каждого driver. Поэтому SQL-варианту нужен общий history_expires_at, свежая проверка expiry при чтении и атомарные reset-expired-set + add + renewal под row lock. Timestamp фиксируется один раз на операции; точную временную точку (нынешний Redis EXEC против SQL commit) и обработку transaction delay нужно явно принять и протестировать. Не заявлять побитовую временную эквивалентность разных часов Redis/DB.

Row-local список предпочтителен по минимальности только при доказанном bound; добавляются общий expiry и mode (может кодироваться nullable history), все mutation одной строки под её lock. Array/JSON mutable read-modify-write без lock/CAS недопустим. Таблица лучше ограничивает размер строки и уникальность driver, но требует общего expiry/mode в owner-строке, order-first lock ordering и атомарного удаления истёкшего набора/insert/renewal; per-row expires_at без общего renewal не эквивалентен Redis. Оба варианта требуют аддитивной схемы до readers, запрета eager-init активных legacy циклов, all-writer rollout перед активацией и нового PG concurrency review. До доказательства bounds ни один вариант не выбран для кода.

Дополнительные обязательные тесты: duplicate member продлевает общий expiry; добавление второго member продлевает срок первого; до/на/после expiry; expired-set reset не оживляет старых drivers; два concurrent renewal/reset не теряют свежую историю; rollback renewal сохраняет прежние SQL values; lost ACK сохраняет фактически committed expiry. SQL-mode чтение expired history не требует немедленного физического cleanup. Retention не должен удалять свежие rows из-за stale cutoff.

## Независимое review

Journal принял 95fd791 как docs-only предложение, не runtime protocol approval. Уточнение sliding whole-set TTL внесено в 761df55. Остальные обязательные условия: table retention обязан атомарно доказывать inactive cycle под согласованным lock, не SELECT → DELETE; mode независим от namespace, nonNULL empty row-local history означает SQL-mode однозначно; unknown mode/version fail closed. После DB commit до dispatch возможен crash: exclusion сохранён, предложение не доставлено. Это сознательная граница committed semantics, не устранённая потеря доступности. Ни SQL history, ни эта рекомендация не гарантируют delivery.

Проверки этого docs-only пакета: git diff --check PASS, новый план detect-secrets 0 findings. Новых runtime tests/FULL/PG нет и не требовалось для неизменного кода. Представление и точный алгоритм ещё требуют отдельного решения и приёмки.

Journal дополнительно принял 761df55 docs-only: timestamp renewal выбирать после получения owner lock; PostgreSQL transaction_timestamp/now() фиксируется в начале транзакции и после lock wait может быть устаревшим. Обязателен PG lockwait → renewal с заранее начатой transaction, плюс проверка, что поздний stale renewal не сокращает свежий expiry. GC использует тот же order-first lock и перечитывает expiry после lock. Нельзя вводить silent cap/prune списка ради искусственного bound. Если существующий строгий bound не доказан, table + общая owner expiry предпочтительнее row-local; это всё ещё рекомендация до отдельной реализации/приёмки. Crash-after-commit и долгий delay доcommit остаются явными границами.
