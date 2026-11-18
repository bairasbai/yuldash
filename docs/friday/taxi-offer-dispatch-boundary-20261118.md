# Offer dispatch: проверка postcommit границы

База 44427ff (проверенный privacy пакет), отдельная ветка test/friday-taxi-offer-dispatch-boundary-20261118. Следующий приоритет после metadata и sanitizer: проверить, может ли winner matcher после commit отправить предыдущему кандидату payload нового предложения или terminal заказа.

## План до кода

1. Read-only проследить matcher UPDATE → commit → session.get → _push_offer, ORM expiration и capture token/expiry. Не утверждать дефект по одному предположению.
2. Добавить отдельные диагностические тесты с реальным matcher и transport spy; в точке после его commit выполнить независимый committed переход. Зафиксировать старый recipient и новый SQL owner/token/status, сравнить реально отправленные metadata. Отдельно проверить mutation внутри helper: существующий metadata snapshot не является snapshot всего заказа.
3. На доказательствах спроектировать минимальный совместимый server guard/snapshot. Не удерживать row lock на сети и не обещать невозможность поздней доставки после проверки. Это отдельный review до runtime; текущий пакет только диагностический и плановый.
4. Независимая проверка тестов и плана, scoped/ruff, сохранение отдельной ветки при успехе. Runtime не изменять в этом пакете; полный backend gate предыдущего пакета применим только при неизменности runtime. Реальная FCM/клиентская UI проверка не производится.

Границы: прежние paused действия не повторять, main/production/реальную БД не менять. Адреса очищаются только в поддержанной структурированной грамматике; это не универсальная приватность произвольного текста. Token не credential и не авторизация. Клиентские Android/PWA пакеты с неполной приёмкой не включать.

## Read-only вывод и предложение следующего runtime этапа

В try_offer_next после UPDATE/commit остаётся старый scalar did, но session.get может загрузить новую строку из-за expire-on-commit. _push_offer не проверяет offered/current recipient и снимает token/expiry уже с этого нового объекта. Изменение после commit, до get, может связать D1 с T2. Отдельно token/expiry снимаются до passenger_stats, но цена/адрес читаются после него: metadata snapshot не является полным value snapshot. Это статический вывод; runtime воспроизведение ниже должно отделить факт от предположения.

Предложение для отдельного reviewed runtime пакета: frozen value snapshot собственного выигранного offer из DB scalar SELECT до commit, пока UPDATE удерживает строку. Поля: id, passenger_id, recipient, status, token/expiry, revision/lifecycle, created_at, price_estimate/price_final, from_text/to_text, pickup_fee_kop/options_fee_kop/weather_fee_kop. Сверить поля компенсации с COMPENSATION_FIELDS, использовать настоящий order_commission_kop, не копировать денежную формулу. Tried-before-commit не перемещать. После commit все поля offer payload и recipient брать только из snapshot, не stale ORM. Перед dispatch свежий scalar guard exact offered/recipient/token/revision/lifecycle в отдельной короткой read-session; закрыть её до transport, без FOR UPDATE и без rollback caller. Missing/superseded skip. Новое значение не adopt.

Нужно отдельно решить контракт прямых private helper вызовов в тестах, не оставляя production bypass ради старых тестов. Legacy NULL metadata omission сохранять, но NULL не объявлять уникальным поколением. Проверки: normal winner snapshot vs SQL; real decline/advance and cancel interleaves; изменение внутри stats/fee helpers; компенсации и privacy literals; guard read-session закрыта до transport; delayed transport после успешного guard по-прежнему допускает старый целостный snapshot. Guard не атомарен с отправкой/доставкой. Client GET reconciliation и абсолютный expiry остаются необходимой отдельной интеграцией; настоящая доставка не доказана.

## Диагностики и независимая оценка плана

Кандидат dc64831: две диагностики PASS (exit 0, 1.17 с) подтверждают плохой исход, не исправление. Первый case использует независимый committed SQL replacement D2/T2/expiry2 и настоящий matcher/push: старый D1 получает T2/expiry2. Это моделирование смены поколения, не end-to-end decline. Второй case вызывает настоящий cancel_order пассажира с cleanup/notifications; исходный matcher затем отправляет instant_offer отменённого заказа. Spy фильтрует только offer, literal адресная privacy сохраняется. Логи `/tmp/taxi-offer-dispatch-boundary.log` и `.exit`; runtime не изменён.

Journal принял TEST-ONLY scope eab1d63 и дал обязательные условия будущего runtime. Дополнить snapshot completeness-тестом против COMPENSATION_FIELDS, чтобы getattr default 0 не скрыл будущий пропуск. Recipient только fresh scalar собственного successful UPDATE, сверить с did. Для created_at=None использовать единый timestamp, снятый в snapshot. Guard сравнивает exact expiry и для nonNULL требует deadline в будущем; legacy NULL допускается без обещания уникальности. Guard после stats/fee и подготовки payload, ошибки failclosed/no send. Отдельная short Session не должна затрагивать caller transaction/identity map; проверить SQLite bind/connection semantics и настоящую PG, не rollback caller. Прямой private helper контракт не должен сохранять обход проверки. Frozen offer не замораживает независимую stats/fee policy и не делает guard атомарным с сетью. Runtime этой оценкой ещё не принят.


Независимая приёмка dc64831 (docs-only HEAD e762a4d): 197 scoped PASS, exit 0, 5.24 с; Ruff F/diff PASS, P0/P1/P2 в тестах не обнаружены. Проверены реальный commit, исходный assignment до вмешательства, synthetic SQL case и настоящий cancel case, payload/SQL/изоляция/cleanup. Runtime/schema diff относительно 44427ff пуст; новый FULL/PG не запускался, FULL 7293 относится только к предыдущей базе. Реальные FCM/UI не проверены.
