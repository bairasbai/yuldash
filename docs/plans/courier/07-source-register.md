# Источники проверенные факты и ограничения

## Правила происхождения

Дата исходного чтения: 25.10.2026. Репозиторий: `bairasbai/yuldash`; code baseline `0ef03f4194012431e0996be4d8e748d37e34d63d`; PR211 template baseline `54802962227671ca094715079a38f007b3c63c80`. Номера строк ниже относятся только к этим SHA. После обновления main проверить их заново.

`VERIFIED_SOURCE` означает прочитанный исходник/первичную страницу. Это не исполненный тест и не проверка production-конфига. `PROPOSED`/`OWN_DESIGN_NOT_VENDOR_SPEC` — наше техническое решение. `OWNER_PENDING`/`LEGAL_PENDING` — не принято. `NOT_EXECUTED` — будущий runtime/тест. Старый PASS не переносится на новый SHA.

Предлагаемые новые files/API/entities в CP отмечены «новые/предлагаемые». Сокращённые Android пути относятся к `android/app/src/main/java/com/yuldash/app/`; PWA экраны к `webapp/src/screens/`, компоненты к `webapp/src/components/`. Если exact path не установлен, его необходимо проверить в CP-01 до edit, а не придумывать по имени класса.

## Репозиторий

| ID | Источник на закреплённом SHA | Что действительно подтверждает |
|---|---|---|
| R01 | [AGENTS.md](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/AGENTS.md) | RU/BA/Canon/accessibility, деньги в копейках, owner gates, независимая приёмка, общий audit registry. Единственный AGENTS в проверенном дереве |
| R02 | [PR211 TEMPLATE](https://github.com/bairasbai/yuldash/blob/54802962227671ca094715079a38f007b3c63c80/docs/plans/TEMPLATE.md), [README](https://github.com/bairasbai/yuldash/blob/54802962227671ca094715079a38f007b3c63c80/docs/plans/README.md), [RS index](https://github.com/bairasbai/yuldash/blob/54802962227671ca094715079a38f007b3c63c80/docs/plans/rideshare/00-index.md) | Формат 10 разделов, RS01…16 ещё планы, общие договоры/очередь/приёмка. Не код courier |
| R03 | [Июльский план](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/docs/courier-plan-2026-07.md), [Dispatch сентябрь](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/docs/tz/TZ-dispatch-2026-09.md#L201-L232) | Исторические решения hybrid/buy-bring/transparent fee и уже описанный nearest-feed gap. Старые проценты/заявления готовности не текущий факт |
| R04 | [models.py1877–2056](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/models.py#L1877-L2056) | ParcelDelivery/CourierApplication/Profile, семь статусов, деньги, car/cargo, zones; food лишь категория |
| R05 | [courier.py834–1190](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/routers/courier.py#L834-L1190) | Заявка/approval/online/feed, unknown geo compatibility, id-desc/FEED_MAX, estimated commission |
| R06 | [courier.py1196–1434](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/routers/courier.py#L1196-L1434) | Estimate/cache freeze/create/60sec semantic dedup, не durable intent после любого рестарта |
| R07 | [parcels.py1109–1360](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/routers/parcels.py#L1109-L1360) | Arrival, claim rowlock/CAS, transit и server delivery code |
| R08 | [parcels.py1373–1655](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/routers/parcels.py#L1373-L1655) | Release/failed attempt/redelivery/return-start/done; return-done не требует sender code |
| R09 | [cleanup.py674–704](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/cleanup.py#L674-L704), [taxi_worker.py294–346](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/taxi_worker.py#L294-L346) | Таймеры auto terminal/release; риск смешения времени и физической передачи, не доказанный runtime инцидент |
| R10 | [parcels.py1712–2063](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/routers/parcels.py#L1712-L2063) | recent10, Incident(parcel_id), admin actions, receipt only delivered/returned |
| R11 | [geo.py650–688](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/geo.py#L650-L688) | Общий zone_allows; не полное покрытие всех целевых территорий |
| R12 | [config.py244–300](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/config.py#L244-L300), [courier.py295–415](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/routers/courier.py#L295-L415), [ledger.py82–94](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/ledger.py#L82-L94) | Defaults, Decimal fee, minimum и lifetime ступени; не production rates |
| R13 | [courier.py437–792](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/routers/courier.py#L437-L792), [instant_service.py2280–2299](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/instant_service.py#L2280-L2299) | Подача от work city, return/cancel/wait formula, route price; неоднородные round paths |
| R14 | [courier.py1924–1990](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/routers/courier.py#L1924-L1990), [payments.py217–250](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/routers/payments.py#L217-L250) | Комиссионный Payment с snapshot/retry, не marketplace payout/escrow |
| R15 | [Android ParcelsScreen1760–1819](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/android/app/src/main/java/com/yuldash/app/ParcelsScreen.kt#L1760-L1819), [PWA ParcelSendWizard213–275](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/webapp/src/components/ParcelSendWizard.tsx#L213-L275) | Settlement point и текст точного адреса, не подтверждённая цена door-to-door |
| R16 | [PWA courier API19–104](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/webapp/src/api/courier.ts#L19-L104), [CourierScreen367–525](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/webapp/src/screens/CourierScreen.tsx#L367-L525) | Старый zone DTO и скрытые ошибки/accept behavior |
| R17 | [PWA ParcelTrackMap23–90](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/webapp/src/components/ParcelTrackMap.tsx#L23-L90), [Android CourierLocationService54–247](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/android/app/src/main/java/com/yuldash/app/CourierLocationService.kt#L54-L247) | Foreground selected-map PWA vs Android service с active IDs; не real background acceptance |
| R18 | [CourierReturnTrackingTest48–80](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/android/app/src/test/java/com/yuldash/app/CourierReturnTrackingTest.kt#L48-L80), [share.py221–245](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/routers/share.py#L221-L245) | Преднамеренная privacy граница возврата/terminal; нельзя молча включить tracking |
| R19 | [courier-scope](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/docs/audit-traceability/courier-scope.md), [traceability README](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/docs/audit-traceability/README.md) | Десять обязательных E2E групп, четыре роли, доказательства одного SHA, реальные телефоны отдельно |

Current audit-blocks статусы полностью не проверены этим source audit: документ большой и чтение было неполным. Нельзя заполнить отсутствующие статусы догадками. Выводы о готовности/production settings здесь отсутствуют.

## Первичные источники операторов

Дата проверки страниц 25.10.2026. Это примеры публичного поведения конкретного продукта/города, не тарифы Юлдаш и не право применять их договоры.

| Источник | Использование в CP | Ограничение |
|---|---|---|
| [Башкортостанстат: география](https://02.rosstat.gov.ru/storage/mediabank/12_iyunya-Den%27_Rossii.pdf) | Семь субъектов в scope | Список шести соседей прочитан в извлечении официального домена; прямой PDF вернул 502, доступность повторно проверить |
| [Яндекс: стоимость](https://yandex.ru/support/delivery-profile/ru/express/cost-of-delivery) | CP05 breakdown/geo/channel | Цена зависит от города/продукта, не единый тариф РФ |
| [Яндекс API FAQ](https://yandex.ru/support/delivery-profile/ru/api/express/faq) | Quote expiry, server reconciliation, limited contacts | Примерно 10 мин оффер Яндекса не наш утверждённый TTL |
| [Яндекс status model](https://yandex.ru/support/delivery-profile/ru/api/express/claim-process) | CP02/12/18 отдельные получение/вручение/возврат | Свои domain states Юлдаша определены отдельно |
| [Яндекс cancel info](https://yandex.ru/support/delivery-profile/ru/api/express/openapi/IntegrationV2ClaimsCancelInfo) | CP11 предварительное раскрытие последствий | Не переносим штрафы/право отмены автоматически |
| [Яндекс Про выполнение экспресса](https://pro.yandex.ru/ru-ru/ruza/knowledge-base/delivery-auto/execution/how-to-express), [нештатные ситуации](https://pro.yandex.ru/ru-ru/moskva/knowledge-base/delivery-foot/execution/tricky-situations) | CP09/12 contact/return/support | Времена/число звонков оператора не утверждены для Юлдаша |
| [Яндекс Про коды](https://pro.yandex.ru/ru-ru/iglino/knowledge-base/delivery-auto/execution/sms-codes), [Маршрутизация фото](https://yandex.ru/routing/doc/ru/delivery/functions/photo-confirmation) | Proof context/privacy | Разные продукты; фото не полная гарантия передачи |
| [Достависта тарифы](https://dostavista.ru/tariffs), [FAQ](https://dostavista.ru/faq) | Separate ожидание/выкуп/COD/расходы | Открытая страница Москвы:20 ₽/5 мин после 15, cancel120 ₽ после 5 мин — наблюдение, НЕ рекомендуемые ставки RB |
| [Достависта компенсации](https://dostavista.ru/refund), [Яндекс страхование](https://care.yandex.ru/delivery-express/) | CP12/19 evidence/claims | Объявленная ценность не безусловная выплата/универсальный limit ответственности |
| [Яндекс запреты доставки по России](https://yandex.ru/support/delivery-profile/ru/other-day/prohibited-for-delivery) | Goods policy separation | Не применять список другого продукта как законодательный blanket запрет |

Официальные юридические источники и текущая/будущая применимость перечислены прямо в [LG](06-launch-gates.md). Исследование не является юридическим заключением, договором провайдера, разрешением владельца или проверкой конкретного исполнителя.

## Статическое исследование заказчика

Использовано с разрешения заказчика в виде минимального обезличенного обзора принципов, без частных ссылок/внутренних файлов/сырых APK и без копирования чужого кода/текстов. Проверенные образцы: Go5.91.2, Pro13.71, BlaBlaCar6.35.0. Это не сегодняшние production версии и не проверка официальности дистрибуции. Нового декодирования для этого ТЗ не проводилось.

| Проверенные claim labels и дата | Bounded вывод | Наше собственное требование |
|---|---|---|
| Go C187, C198-r1/C199; 25.10.2026 | Локальное изменение/normal Unit/выбор тарифа не доказывает authoritative server commit или quote | CP05/07: versioned quote и durable command reconciliation |
| Go C68, C72–C77; 25.10.2026 | Диалог согласия, ответ cancel и cleanup разные границы | CP11: consent связан с version и последствиями, refund отдельно |
| Go C209/C56/C57; 25.10.2026 | Push handled/refresh completion могут не означать применённый свежий snapshot | CP10/22: sequence/resync/staleness/terminal close |
| Pro route-point reconciliation/delayed accept; 25.10.2026 | Captured identity/generation/guard требуют отдельных проверок; runtime гонка не воспроизводилась | CP08/18: lease/identity/generation при commit |
| Bla локальная fee формула, support QA25.10.2026 | p×r в выбранных телах, но источник ставки/scale/финальное списание не установлены | CP06/14: explicit units/base/rounding/version/settlement |
| Bla GPS consumer/overflow/service-stop,25.10.2026 | Selective clear/local stop не доказывают durable ACK/завершение callbacks/смену аккаунта | CP07/10/21: ownership и устойчивое восстановление/очистка отдельно |

Обычная courier handover/POD/OTP/photo/identity, фактическая доступность рынков и серверный delivery outcome из этих APK не установлены. Rover/такси/посадка попутки не заменяют их. Эти материалы не являются публично воспроизводимым raw proof в данном repository; публичные официальные источники выше независимо поддерживают необходимость разделения сценариев. Никаких процентов полноты APK или доказанного runtime Юлдаша не заявляется.

## Реестр решений и недостающих входов

- Подтверждены: второй режим после попутки, подробный план, use existing research + web, широкий охват видов/транспорта и Башкортостан+6 соседей, отдельный docs commit после независимой проверки, разрешённая текстовая симуляция сценариев.
- Не выбраны: новый процент/минимум/tier, тарифная непрерывность/субсидии, promised net policy, лимит выкупа/ответственности, юрлицо/договор/НПД/COD/ККТ, банк/ЭДО/страховщик, data retention, pilot зона/даты/SLA/поддержка, фактический supply.
- Не подтверждены: production config/сбор комиссии/затраты/маржа, реальные courier flows, результаты current build/physical phones, применимость всех региональных требований.
- Before implementation: перечитать main и новые PR, подтвердить exact files/exports/DB head, пройти cross-family plan review и владельческие решения для затронутой части. Источник ветки исследования закреплять реальным SHA, не подменять обновлёнными private материалами.


## R2 совместимость общего времени и intent

- [backend/app/timeutil.py, закреплённый main, строки 1–40](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/timeutil.py#L1-L40): намеренно naive UTC в backend; K4 сохраняет этот договор и добавляет courier API adapter, не меняет общий clock.
- [RS-05, закреплённый PR211](https://github.com/bairasbai/yuldash/blob/54802962227671ca094715079a38f007b3c63c80/docs/plans/rideshare/RS-05-no-duplicates.md): proposed applied/rejected/closed_no_effect/unseen, общий GET/atomic-close и retention. K6 явно расширяет actor deletion FK для courier, а не утверждает, что это уже есть в main. Существование документа не равно merged implementation.


## OD-2026-10-25-01 Решение владельца о бизнес-модели

Источник: прямое уточнение владельца в переписке 25.10.2026 после обсуждения различия двух продуктов. Владелец подтвердил: попутчик может получать доход за посылку, Юлдаш не берёт комиссию при доставке по пути; при отдельном вызове курьера из A в B комиссия применяется. Бесплатная для пользователя платформа попутки/on-route рассматривается как привлечение аудитории к приложению.

Это источник бизнес-решения, не вывод из APK/конкурента/кода. Approved scope: zero platform commission on_route и комиссионная dedicated модель. На дату OD-01 ещё не утверждался driver price-setting; последующий OD-04 задаёт DRIVER_SET_PRICE. Не утверждены конкретные dedicated проценты, произвольный detour threshold, договоры/провайдеры/production операции или прибыльность. Срочность — пример потребности, не единственный classifier: scheduled dedicated тоже dedicated. Реализация целевой ветки fee 0 ещё не выполнена; текущие defaults описаны отдельно.


## R4 проверенная существующая основа Ride

- [models.py:359–417, pinned main](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/models.py#L359-L417): Ride, passenger seats, baggage boolean, waypoints; category=parcel с receiver_name/parcel_size. Наличие этой основы не выдаётся за уже проверенный joint seats/baggage/parcels reserve.
- [models.py:1877–1938](https://github.com/bairasbai/yuldash/blob/0ef03f4194012431e0996be4d8e748d37e34d63d/backend/app/models.py#L1877-L1938): ParcelDelivery отдельно от Ride.parcel, cargo_type/weight/legacy delivery_type и существующая доставка. Комментарий старого сбора не отменяет новое OD-01 zero-fee решение владельца; код этим планом не изменён.
- [RS-02 pinned PR211](https://github.com/bairasbai/yuldash/blob/54802962227671ca094715079a38f007b3c63c80/docs/plans/rideshare/RS-02-segment-seats-price.md) и [RS-15](https://github.com/bairasbai/yuldash/blob/54802962227671ca094715079a38f007b3c63c80/docs/plans/rideshare/RS-15-edit-stops.md): proposed segment seats, pure occupancy/CAS, Ride → Booking lock, full terms_version и ограничения route/time edit. Это predecessor contracts, не доказательство merged implementation. Существующие CreateRideScreen Android/PWA пути проверены по pinned repository inventory; новые поля/bridge — proposed.

## OD-2026-10-25-02 и OD-2026-10-25-03

Прямая переписка владельца 25.10.2026: сценарий зимних вещей Уфа → Сибай через поиск попутки; optional parcel block при публикации, сумки/вес/размеры/документы; пассажиры необязательны. Межгород/сёла и конкретный toggle UX предложены как начальная форма, не бессрочный numeric/geo запрет. Защита от обхода не даёт гарантии доказать цель каждой поездки и не разрешает произвольный штраф/слежение.

Отдельно подтверждены ready pickup и покупка по списку, payer sender/recipient/merchant. Это PRODUCT_SCOPE_APPROVED, не согласие всех будущих плательщиков и не legal/provider/LIVE approval. Dedicated квартира/офис/магазин/кафе и city/inter-village уже входят в план; новый источник уточняет роли, а не объявляет реализованный сервис.

## Решение владельца OD-2026-10-25-04

Прямое уточнение 25.10.2026: водитель сам назначает цену, агрегатор рекомендует приблизительную сумму только как совет. BUSINESS_POLICY_APPROVED: DRIVER_SET_PRICE / ADVISORY_ONLY / on-route fee 0. Конкретный плательщик ещё должен согласиться на конкретную сумму; LIVE/provider/legal не одобрены. Выбор handoff UX делегирован: собственное проектное решение — встреча у маршрута, door detour только после согласия и проверки ограничений. Ответ о хранении был вопросом, не одобрением договоров: recovery network остаётся NOT_OPERATIONALLY_READY до реальных партнёров/условий/ответственных.

## Публичные источники честного ETA проверены 25.10.2026

- [Экспресс API: прогноз по точкам](https://yandex.ru/support/delivery-profile/ru/api/express/openapi/IntegrationV2ClaimsPointsEta): прогноз source/destination/return и позиция, для которой он рассчитан. Наличие свежего HTTP response не доказывает generated_at/freshness всех полей.
- [Экспресс API: положение исполнителя](https://yandex.ru/support/delivery-profile/ru/api/express/openapi/IntegrationV2ClaimsPerformerPosition): GPS timestamp; accuracy нельзя трактовать как метры, документация оговаривает неустановленные единицы. Наш adapter оставляет unknown, пока единицы/семантика не подтверждены.
- [Яндекс Маршрутизация: ETA](https://yandex.ru/routing/doc/ru/delivery/functions/eta): дорожная обстановка, актуальность и порядок точек влияют на расчёт. Это другой продукт, не доказательство устройства Go и не SLA Юлдаш.
- [Рассказ пользователя 08.06.2023](https://vc.ru/claim/720553-deliveri-market-ili-kak-kurer-s-podderzhkoi-ne-smogli-spravitsya-s-elementarnoi-dostavkoi): автор описывает долго повторяющееся «1–5 минут» в Деливери Маркете. Это датированная жалоба другого продукта/периода, не независимое доказательство вины, намеренного обмана, частоты проблем или текущего дефекта Go.

C9/SIM-37/ETA-N01…22 — OWN_DESIGN_NOT_VENDOR_SPEC, NOT_EXECUTED. Честный новый прогноз может снова дать пять минут; требование — не скрывать сдвиг absolute window/elapsed и неопределённость, а не запрет повторения числа. Не обещаем точный ETA или полное исключение GPS spoofing. Дополнительные complaint cases из исследования не используются как статистика качества.
