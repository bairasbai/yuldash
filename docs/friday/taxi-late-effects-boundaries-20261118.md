# Taxi late effects: доказательства и следующий план

База `02e3126` (проверенный accept cleanup guard), отдельная ветка `test/friday-taxi-late-effects-boundaries-20261118`. Этот пакет меняет только диагностические тесты и документы. Это не исправление Redis/FCM, не включение нового протокола и не разрешение миграции рабочей базы.

## Воспроизведённые границы

`backend/tests/test_taxi_late_effects_boundaries.py` содержит две диагностические проверки. PASS означает наблюдение известного нежелательного исхода, не доказательство защиты. При исправлении протокола эти проверки необходимо заменить положительными требованиями, а не сохранять дефект ради зелёного теста.

1. Настоящий accept вызывает cleanup; тестовый Redis transport сохраняет точную DELETE-команду и выдаёт `redis.exceptions.TimeoutError`. Accept завершается, настоящий driver cancel/reassign увеличивает revision, matcher записывает нового водителя. Затем выполняется именно сохранённая DELETE-команда: новое tried исчезает, SQL-заказ остаётся offered новому водителю.
2. Настоящий `_notify_transition` формирует accepted payload и RU/BA-текст. Транспорт сохраняет его; после настоящего driver cancel/reassign payload доставляется поздно и всё ещё утверждает accepted при текущем offered.

Это детерминированная модель неопределённого исхода/очереди, не измерение вероятности, не реальный Redis network timeout и не проверка мобильного отображения. Подбор кандидатов и push offer подменены; реальные UPDATE, revision, DriverCancel и SADD остаются. Сетевые обращения запрещены тестовым запуском, собственные заказы/события/ключи очищаются.

## Inventory текущего протокола

Все tried-операции runtime найдены в `backend/app/instant_service.py`:

- `_tried_key` (2976): общий `instant:order:{id}:tried`; `_tried_set` (2980): SMEMBERS, ошибка даёт пустое множество.
- `_cleanup_tried` (2987): безусловный DELETE, ошибки подавляются. Вызывается accepted (2800), terminal cancel (2895), expired (3235).
- Matcher (3333, 3355–3356): read, затем SADD/EXPIRE 3600 до SQL commit под UPDATE-lock.
- Reassign (2965–2966): SADD отменившего водителя/EXPIRE после SQL commit и проверки ownership.
- Advance/route/fallback не очищают историю. `start_matching` тоже не очищает её сам.

Accept обычно уже удалил историю до reassign: reassign сохраняет оставшиеся записи и добавляет отменившего водителя, но не гарантирует всех прошлых отказников. Нельзя незаметно превратить это в новую продуктовую политику.

`services._cache_client` (1996–2010) задаёт connect/read timeout 1 сек. Это не общий deadline операции, SQL lock или запроса. Не заявляем верхнюю границу ожидания, отсутствие retries/DNS задержек или отмену уже посланной команды. Redis outage под row lock способен задержать другие writers. Поздние SADD/EXPIRE общего ключа тоже потенциально загрязняют новую историю/TTL; здесь их отдельный runtime-repro ещё не сделан.

## Предпочтительное направление: отдельный immutable lifecycle

Это предложение для независимой приёмки до реализации, не уже действующая гарантия.

Добавить отдельный nullable идентификатор жизненного цикла tried в SQL и использовать ключ `instant:order:{id}:tried:{lifecycle}`. Каждая команда получает захваченный lifecycle, не перечитывает «текущий» после commit. Идентификаторы не переиспользуются.

- Новый retry после expired создаёт новый lifecycle атомарно с SQL-переходом. Повторный start уже searching сохраняет lifecycle.
- Advance, destination/waypoints и fallback сохраняют lifecycle, хотя увеличивают search_revision: история отказов нужна продолжающемуся поиску.
- Accept cleanup удаляет захваченный старый ключ; следующий reassign получает новый lifecycle и явно добавляет бросившего водителя. Это следует обычной нынешней семантике успешного accept-cleanup. Перенос всей остаточной истории после неуспешного cleanup требует отдельного решения, не скрытого изменения.
- Terminal cleanup удаляет конкретный старый ключ. Поздний DELETE/SADD/EXPIRE старого lifecycle не адресует новый ключ.
- TTL остаётся ограниченной best-effort памятью. Раздельные SADD/EXPIRE допускают отсутствие TTL при промежуточном сбое; атомарное SADD+TTL нужно оценить и проверить отдельно. Нельзя обещать уборку всех orphan keys только потому, что один EXPIRE запланирован.

Почему не search_revision: он меняется на advance/route/fallback, где tried должен сохраниться. Копирование множества на каждый revision создаёт дополнительные гонки. Почему не простой GET owner→DELETE: это не атомарно. Lua compare-delete общего ключа требует также условных SADD/TTL и безопасной публикации owner; иначе поздний writer вернёт старый owner. Namespace проще изолирует адрес команды, но не решает все ошибки транзакций.

### Безопасные этапы совместимости

1. Отдельная аддитивная nullable схема, без автоматической миграции реальной БД и без обязательного включения.
2. Все readers/writers/cleanup обновляются согласованно; NULL init под свежим row lock с повторной проверкой. Никаких ключей namespace `None`.
3. Mixed old/new writers не считать безопасными. Полная гарантия требует остановки старых процессов и завершения старых in-flight команд. Dual-read/write legacy общего ключа не доказывает защиту.
4. Для существующих NULL-заказов определить явную rollout-политику истории: ограниченный best-effort перенос legacy после draining либо отложенная активация до нового жизненного цикла. Не выбирать молча потерю истории.
5. Обязательная матрица: late DELETE/SADD/EXPIRE; concurrent NULL init; expiry/restart; accept/reassign; advance/route/fallback retention; SQL rollback; TTL gap; mixed-version как небезопасный контроль; настоящий PG locks и независимый FULL перед runtime публикацией.

SADD до SQL commit может пережить rollback и ложно исключить водителя внутри того же lifecycle. Namespace это не исправляет. Перенос после commit открывает окно decline раньше SADD, поэтому не входит в этот минимальный план без отдельного доказательства.

## Push: отдельная граница и совместимый план

`_status_data` (3707) содержит type/order_id/status, без event sequence. `send_push` (`services.py:467`) отправляет Web Push, затем FCM data-only. FCM recipient guard защищает смену аккаунта, не актуальность заказа. Android `FcmService.kt:39–51` показывает offer по payload; общие статусы показываются по title/body. PWA `public/push-sw.js:62–85` проверяет session binding, не актуальный SQL lifecycle. Это статическая проверка исходников, не UI-приёмка.

Для offer `_push_offer` передаёт относительный ttl_sec без token/абсолютного expiry. Уникальный offer_token нельзя сортировать как sequence. Совместимый следующий план: добавить token/абсолютный expiry в payload, затем новые клиенты трактуют push как сигнал refresh и сверяют текущий offer/recipient/token/expiry перед показом и действием. Legacy поля сохраняются; обязательность не включать. Клиентские token-пакеты ранее сохранены отдельно, но не опубликованы из-за неполных Android/визуальных gates; их статус этим исследованием не меняется.

Для status/search_revision недостаточно: accepted→arriving→onboard→done одной поездки не обязаны увеличивать search_revision. Нужен отдельный порядок событий либо обязательный refresh текущего состояния перед показом; локальная high-water mark не спасёт устройство, ещё не получившее новое событие. Даже refresh/check перед показом имеет окно после проверки. Нельзя обещать отсутствие устаревшего текста в момент фактической доставки. Повторный серверный check или outbox не упорядочивают сеть. Держать SQL lock на всём внешнем push не решает позднюю доставку и увеличивает задержки.

Следующий приоритет: независимо согласовать Redis lifecycle schema/rollout, отдельно подготовить совместимый offer payload и требования client refresh. Не объединять оба протокола в один необозримый runtime пакет.

## Проверки и независимая приёмка

Кандидат ac13c9f: авторский combined scoped 78 PASS/exit 0/2.40с; независимый 78 PASS/exit 0/2.28с, P0/P1/P2 не найдено. Команда из backend: `python -m pytest tests/test_taxi_late_effects_boundaries.py tests/test_taxi_accept_cleanup_generation.py tests/test_taxi_expiry_cleanup_generation.py tests/test_taxi_search_revision_writers.py -q --disable-socket --allow-unix-socket`, свежий TMPDIR, TZ=UTC, DATABASE_URL и proxy unset. Ruff app и нового теста --select F, diff check PASS.

Нового FULL/PG нет: runtime/schema/deps и старые тесты неизменны. Переиспользуется применимый runtime FULL базы: 7059 PASS, 133 skipped, exit 0; прежние 14 PG отдельно пройдены. Новые две диагностики выполнены адресно и независимо, а не объявлены частью старого FULL. Никакой готовности Redis lifecycle/client rollout этим пакетом не заявляется.
