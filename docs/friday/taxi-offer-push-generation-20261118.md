# Offer push generation: совместимая серверная база

База1d4f002, веткаfeat/friday-taxi-offer-push-generation-20261118. Только data payload `_push_offer`; клиентские приложения, действияaccept/decline, маршрутизацию иproduction не менять.

## План до реализации

1. Добавить optional string `offer_token` и `offer_expires_at` в data instant_offer. Token брать существующий из InstantOrder, не генерировать/ротировать при push. Expiry брать из сохранённого offer_expires_at, не вычислять now+ttl. Оба значения снять scalar в начале `_push_offer` до вызова helpers/transport; это snapshot предложения, не новый lease.
2. Expiry сериализовать как ISO8601 UTC с Z; наивный DB datetime согласно timeutil означает UTC, aware привести к UTC. Сохранить точность microseconds, не зависеть от системного timezone. Если соответствующее значение None, поле отсутствует, не строка 'None', не выдуманный token/expiry. Остальной payload/legacy price/ttl_sec/fee/address sanitization/data_only без изменений.
3. Не вводить обязательность новых полей и не менять серверную обработку действий. Старые клиенты игнорируют дополнительные поля; новые ещё требуют отдельной интеграции. TTL_sec остаётся прежним legacy полем, поэтому старые клиенты всё ещё могут показать поздний push как свежий. Absoluteexpiry даёт материал для будущей проверки, не автоматическую защиту.
4. Тесты через настоящий matcher→_push_offer и transport spy: committed token/expiry соответствуютSQL; expiryUTCстрока/всеFCMdataстроки; legacyNULL omissions; aware offset/naiveUTC; late-sendnow не продлеваетexpiry; immutable snapshot при измененииorderвhelper; новые поля не обходят фильтрациюадресов/денежныйрасчёт. Отдельный delayedtransport retainedtoken/expiry не выдаётся за actualclient suppression.
5. Независимыйreviewплана, scoped/ruff, независимаяприёмка, полныйbackendFULL вочереди; отдельнаяпубликациятолькопослеPASS. РеальнаяFCM/WebPushдоставка/UIнепроверяются.

## Границы

Snapshotвначалеhelper не является гарантией актуальности recipient/status после предыдущегоcommit или доставки. Уже существующий postcommit stale-offer routing риск не маскировать: metadata нужно сверять с текущим GET, включаяrecipient/order/token/expiry. TokenUUID не sequence, search_revision не упорядочивает все статусы. Известный delayedacceptedpush не исправлен. Ранее сохранённые Android/PWAпакеты с неполнымиgates не публиковатьвместе. No main/production/реальнаямиграция, приостановленныеоперации untouched.

## Независимая оценка до кода

Journal принял be99016. Capture обоих полей до passenger_stats/fee helpers; микросекунды без округления, None omissions независимы. Проверить оба значения после helper mutation; настоящий matcher/transport и SQL commit, all-string payload, legacy money/address/data_only. Это не атомарный snapshot полного payload/recipient, не stale-routing fix и не клиентская защита. Runtime приёмка ещё впереди.

## Отдельная найденная privacy-граница

Новый широкий тест обнаружил прежнее поведение destination street_only: синтетическая строка «ул. Гагарина, 5, кв. 9» превращается в «ул. Гагарина, 5, кв.», оставляя номер дома. Это не создано metadata-изменением и не исправляется добавлением token/expiry. Исходный отказ 9PASS/1FAIL сохранён отдельно. В metadata integration проверяется прежний поддержанный формат «ул. Гагарина, 5к2» со строгим отсутствием номера и строгая pickup privacy; универсальная очистка destination не заявляется. По поручению родителя следующий приоритетный отдельный пакет — воспроизведение и исправление sanitizer с независимым review и матрицей форматов, без ложных гарантий.

## Реализация и адресные проверки

Runtime a4e30dd: 13 добавленных строк в helper и timezone import; token/expiry snapshot до helpers, optional payload fields, UTCZ с микросекундами. Остальные legacy поля и методы действий не менялись. Исходный RED настоящего matcher→push: missing offer_token, 1FAIL/exit1/1.28с. 10 новых cases покрывают SQL committed generation, independent NULL omission, naiveUTC/aware offsets, прошлый deadline без продления, mutation обоих полей внутри helper, delayed old payload после нового offer.

Авторские metadata+address suites:75PASS/exit0/5.68с. Disposable PG regression17PASS/exit0/2.62с, cleanupempty. PG проверяет прежние SQL races, не сетевую доставку push. RuffF/diffcheckPASS. Независимая приёмка/FULL ожидаются.

Независимая приёмка a4e30dd:170 scoped PASS/7.55с +21 address/push/commission PASS/1.99с; PG17PASS/2.81с, все exit0, cleanupempty. Новых P0/P1/P2 в metadata нет; известный sanitizer-дефект отдельно не закрыт. RuffF/diffcheckPASS, detect-secrets4files0. FULL ожидается.

## Финальный локальный gate

FULL на0fc8be3 (runtime a4e30dd):7167 passed,136 skipped,804 warnings,exit0,684.16с. Изbackend: `python -m pytest tests -q --disable-socket --allow-unix-socket`, TAXI_TRIED_LIFECYCLE_ENABLED=false,TZ=UTC,TMPDIR=/tmp/taxi-offer-push-generation-full,DATABASE_URL/proxyunset. 17PG scenarios skipped вSQLite и отдельно пройдены автором/независимо. ПослеFULLruntime/testsне менялись.

Независимые170+21scoped и17PGPASS,ruffF/diffcheckPASS,detect-secrets4files0. Это локальный backend gate, не remoteCI/FCM/nativeUI. Token/absoluteexpiry дают совместимые данные для будущей клиентской проверки, но не подавляют latepush. Известный destination sanitizer-дефект следующим отдельным пакетом; здесь не закрыт. Production/реальныеБД/pausedactions не тронуты.
