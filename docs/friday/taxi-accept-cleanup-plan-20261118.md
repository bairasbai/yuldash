# Accept cleanup: guard принятого назначения

Базаbd019ea (remote6aa7cea), ветка fix/friday-taxi-accept-cleanup-generation-20261118. Runtime пока не менялся. Два точных RED: настоящий accept commit, затем настоящий driver cancel→DriverCancel event→reassign revision+1→новый offer/SADD; прежний accept стирает tried обоих водителей и отправляет устаревшее accepted/pickup уведомление. Scoped2FAIL,exit1,1.28s. Для обычного terminal cancel естественная аналогичная гонка не установлена: cancelled не принимает start_matching, успешный reassign возвращается до cleanup. Cancel path не менять.

## План до кода

1. Только при успешном target=accepted снять DB scalar assignment snapshot под UPDATE-lock до commit: search_revision, driver_id, accepted_at (offer_token как дополнительный консервативный guard). Не stale ORM.
2. После commit fresh SELECT FOR UPDATE/populate_existing. Cleanup разрешён только при совпадении assignment и status в UNLOCKED (accepted/arriving/onboard/done): продвижение той же поездки не должно мешать старой очистке. Cancelled/searching/offered, другая assignment или missing row → skip cleanup.
3. Уведомления accepted/pickup разрешены только если та же assignment ещё accepted в момент проверки. Уже advanced/cancel/newsearch/missing → skip старых уведомлений. Для notify сохранить detached value snapshot нужных полей под lock (id/passenger_id/from_text/to_text/pickup_fee_kop/pickup_km/price_estimate), без ORM-state копирования. Использовать существующие helpers/тексты/формулы, не дублировать их.
4. Cleanup под read row-lock, finally rollback освобождает новую транзакцию на любом exit/exception; committed accept не откатывается. Notify после release по сохранённым значениям; возврат актуального ORM допустим. Остальные transition targets и cancel cleanup не менять.
5. Регрессии исходной гонки, обычный accept, sameassignment→arriving/onboard/done cleanup без старых notices, missing, changeddriver/revision, Redis failure/lockrelease и notification snapshot после release. Настоящий PG test: driver cancel/reassign ждёт cleanup lock; после release новый tried остаётся, exception тоже release. Независимая приёмка и FULL перед отдельной публикацией.

## Границы и цена решения

Row lock удерживается на best-effort Redis DELETE: connect/read timeout1с не whole deadline, retries/DNS/outage могут задержать другие writers. Неопределённый late DELETE после timeout и delayed push после контрольной точки не решены. Tokenized Redis lifecycle/atomic compare-delete — отдельный более широкий план: search_revision нельзя механически сделать namespace, tried должен переживать advance/reassign. Mixed old writers и другие cleanup не покрываются. Не менять архитектуру, общий Redis client, реальные БД, main/prod или приостановленные пакеты.

## Inventory оставшихся cleanup и направление timeout-протокола

Accept-гонка воспроизведена настоящими публичными service-переходами. Для terminal cancel аналогичный допустимый пользовательский путь не подтверждён: start_matching не принимает cancelled, а успешный reassign возвращается раньше terminal cleanup. Искусственный прямой вызов приватного helper не считается доказательством этой гонки.

Защита от неопределённого позднего Redis DELETE потребует отдельного lifecycle-протокола, не ещё одного status check. Возможные направления для отдельного исследования: удалять конкретный lifecycle-key, который никогда не используется новым поиском; либо атомарно сравнивать Redis ownership token и удалять соответствующий набор. В обоих случаях необходима проверка всех SADD/read/expiry/accept/cancel/reassign writer-ов, TTL и перехода legacy order_id key. Search revision нельзя бездумно подставить как ключ: advance/route/fallback должны сохранять подходящую историю tried. Нужно отдельно определить границы нового жизненного цикла при expiry/restart и accept/cancel/reassign, rollback и неопределённом исходе команд. Здесь ни схема lifecycle, ни dual-write/миграция Redis, ни гарантии mixed fleet не реализованы.

## Реализация и проверки кандидата

Plan126c4d0 независимо принят до кода. Runtime6618aab меняет только accepted-ветку transition: scalar assignment до commit, fresh locked tuple equality (включая None), cleanup той же UNLOCKED поездки, notify только ещё accepted по value snapshot до rollback. Другие targets/cancel path не изменены.

12 новых SQLite-проверок включают исходные2RED, обычный/legacyNULL accept, три advanced-статуса, смену revision/driver/missing, Rediserror и снимок уведомления после release. Для normal/snapshot кейсов настоящие _notify_transition/_notify_pickup_fee и _settle_pickup_fee, подменены только транспорты. Собственный временный тариф и fakeRedisGEO позволяют проверить формулу подачи и прежнюю цену текста после изменения DB price/km.

Авторский scoped114 PASS,exit0,5.79s. Настоящий PostgreSQL14 PASS,exit0,2.74s (предыдущие12+2 новых): pg_blocking_pids подтверждает ожидание настоящего driver cancel/reassign на acceptcleanup-lock; после release tried обоих водителей сохранён, unexpected cleanup exception тоже освобождает lock. Временный кластер очищен. Ruff app/newtests F, bash syntax и diffcheck PASS. Независимая приёмка реализации и FULL ещё ожидаются.

## Финальный локальный gate

Независимая приёмка runtime6618aab:104 scoped PASS/5.69s и dropped-order10 PASS/3.11s; настоящий PG14 PASS/2.50s, всеexit0, cleanup подтверждён, P0/P1/P2 не найдено. FULL на2fc89e7:7059 passed,133 skipped,804 warnings,exit0,657.42s. Команда pytest tests -q --disable-socket --allow-unix-socket, TMPDIR=/tmp/taxi-accept-cleanup-full, DATABASE_URL/proxy unset,TZ=UTC. Четырнадцать PG-сценариев в SQLite FULL skipped и отдельно реально пройдены выше. Это локальный полный gate без coverage threshold, не remote CI.

Runtime после FULL не менялся. Ruff app/newtests F, bash syntax, diffcheck PASS, credential-pattern scan0. Готовится отдельная публикация проверенной ветки. Main/production/реальные существующие БД не тронуты; ограничения late DELETE, задержки Redis, late push и mixed writers остаются в силе.
