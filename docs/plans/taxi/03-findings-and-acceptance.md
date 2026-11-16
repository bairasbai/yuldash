# Расхождения и критерии исправления

[Индекс](00-index.md) · [Сценарии](02-user-scenarios.md) · [Протокол](04-validation-and-handoff.md)

Ниже 12 записей на закреплённом main. **Это доказательства и требования, не второй статусный реестр.**
Приоритет — последствия для пользователя: P1 существенное нарушение основного пути/денежной ясности,
P2 ограниченное ухудшение/обходной путь. P0 в этой работе не доказан. Код не исправлялся.
Воспроизведённый UI с fixture, API на SQLite и чтение исходника всегда подписаны раздельно.
Номер TF не заменяет B03/PATH/QA. «Acceptance» — что исполнителю предстоит доказать, а не полученный PASS.

| ID | Приоритет | Что меняется для человека | Вид доказательства | Привязка |
|---|---|---|---|---|
| TF01 | P1 | Android показывает цену без выбранных доплат до создания | КОД, runtime Android не выполнен | E2E04; TP22; PATH 04 |
| TF02 | P1 | Завершение создаёт впечатление полученных денег до оплаты | КОД; отдельные локальные API/UI наблюдения в протоколе | E2E04/08; TP63/TD54; QA003 |
| TF03 | P1 | Повтор остановки переворачивает намерение и денежный интервал | КОД и локальный последовательный API probe; не гонка PostgreSQL | E2E04/06; TP57/TD43/TX13 |
| TF04 | P1 | Изменение маршрута/оплаты в Android может молча не сохраниться | КОД, runtime Android не выполнен | E2E01/04/05; TP54–58/TD39–45 |
| TF05 | P1 | Временная ошибка восстановления стирает сохранённый заказ водителя PWA | БРАУЗЕР с синтетическим 503 + КОД | E2E05; TD49/TX15; PATH 05 |
| TF06 | P1 | Неудачное обновление оставляет старую поездку без признака устаревания | БРАУЗЕР PWA с 503 + КОД Android driver | E2E05; TP59/TD46; PATH 04/05 |
| TF07 | P1 | Истёкший ключ входа Android обрывает передачу машины до выхода с экрана | КОД; прежний открытый W1-FIN-26, не новый runtime | E2E05/07/10; TD47/TX16 |
| TF08 | P1 | При активации предзаказа расчёт не получает все сохранённые части маршрута | КОД; ограниченный observer probe отдельно | E2E01/04; TP36; PATH 04 |
| TF09 | P1, оценка риска | Идентичность замороженной цены неполно связана с маршрутом | КОД, эксплуатация/production не проверялись | E2E04; TP27/TX13 |
| TF10 | P2 | Остановки черновика Android теряются при пересоздании | КОД; runtime lifecycle не выполнен | E2E05; TP17 |
| TF11 | P2 | Финальные Android-экраны начинают оценку заново | КОД; серверное восстановление в чеке уже существует | E2E08; TP68/TD59; QA003 |
| TF12 | P2 | Некоторые кнопки PWA меньше проектной области нажатия | БРАУЗЕР: DOM measurements, не native dp | E2E09; TX24–26 |

## TF01 — полный расчёт опций до согласия

КОД: [android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt:2338](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt#L2338); [android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt:2350](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt#L2350); [android/app/src/main/java/com/yuldash/app/data/ApiClient.kt:2636](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/data/ApiClient.kt#L2636). Входы расчёта не включают `orderOptions`, API-метод оценки
также их не принимает. Создание уже передаёт `options`: [android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt:3145](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt#L3145). Сервер умеет учитывать опции
при оценке и создании: [backend/app/routers/instant.py:396](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/backend/app/routers/instant.py#L396). Поэтому на уровне запросов расчёт и заказ описывают разные услуги.
Реальная сумма на конкретном тарифе/флаге и поведение Android на устройстве не воспроизводились.

Acceptance: опции входят в расчёт и его отпечаток; включение/снятие требует актуальной оценки.
Кресло/животное/багаж, поздний ответ старого расчёта, смена класса, offline проверены до финального согласия.
Android/PWA и backend показывают один breakdown без скрытой доплаты. Не менять ставки для устранения дефекта.

## TF02 — поездка, начисление и получение денег различаются

КОД: финальная карточка пишет «Чистыми»/«Получено» и «Заплатил»: [webapp/src/screens/TaxiDriverCompletedScreen.tsx:65](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/webapp/src/screens/TaxiDriverCompletedScreen.tsx#L65), [webapp/src/screens/TaxiDriverCompletedScreen.tsx:116](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/webapp/src/screens/TaxiDriverCompletedScreen.tsx#L116); Android:
[android/app/src/main/java/com/yuldash/app/TaxiDriverCompletedScreen.kt:258](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/TaxiDriverCompletedScreen.kt#L258), [android/app/src/main/java/com/yuldash/app/TaxiDriverCompletedScreen.kt:300](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/TaxiDriverCompletedScreen.kt#L300). Завершение и комиссия: [backend/app/routers/instant.py:879](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/backend/app/routers/instant.py#L879). Отметка получения наличных отдельна:
[backend/app/routers/instant.py:1524](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/backend/app/routers/instant.py#L1524); чек отдельно возвращает `paid`: [backend/app/routers/instant.py:1322](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/backend/app/routers/instant.py#L1322). В backend уже есть долг, жалоба о неоплате,
компенсации/возвраты; эта находка **не** утверждает их отсутствие. В отдельном браузерном fixture водитель пишет «Заплатил 250₽», затем чек paid=false сообщает «Оплата не отмечена». Пассажирский итог такого слова не показывает; ветка «Получено» в этом прогоне не наблюдалась.
Значение `net` не является чистой
прибылью после топлива, налогов и амортизации без отдельного расчёта.

Acceptance: при `done + paid=false` видны ожидаемый расчёт и верная инструкция по способу оплаты;
«получено/заплатил» — только по соответствующему факту. После cash/SBP mark/provider success — серверный
результат с защитой от дубля. Для спора/неоплаты не показывать гарантированную выплату. Обе роли/платформы,
промо/долг/возврат/комиссия и повтор после неизвестного ответа сверены отдельно.

## TF03 — остановка должна повторять намерение, а не переключать его

КОД: [backend/app/routers/instant.py:1180](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/backend/app/routers/instant.py#L1180) вызывает [backend/app/instant_service.py:1908](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/backend/app/instant_service.py#L1908); если остановка не начата — начинает, иначе завершает.
Запрос не содержит желаемого состояния/версии/ключа намерения. Android вызывает его без отдельного
подтверждённого результата: [android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt:6917](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt#L6917). Два последовательных одинаковых запроса локально дают сначала
standing=true, затем false; это доказательство семантики повтора, **не** нагрузочный или PostgreSQL-прогон.

Acceptance: желаемое состояние/версия или устойчивая квитанция намерения обеспечивают один интервал при
повторе транспорта/двух клиентах. После потерянного ответа — сверка текущего интервала, без новой платы
или ложного «Поехали». Момент начала/конца/округление, повтор конца, два устройства и гонка done/stop
проверены отдельно; UI busy сам по себе не защищает сервер.

## TF04 — ошибки изменений маршрута и оплаты должны оставаться видимыми

КОД: [android/app/src/main/java/com/yuldash/app/TaxiTripScreen.kt:181](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/TaxiTripScreen.kt#L181) игнорирует Result `setWaypoints`, а picker закрывается до ответа ([android/app/src/main/java/com/yuldash/app/TaxiTripScreen.kt:426](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/TaxiTripScreen.kt#L426)).
[android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt:6917](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt#L6917) игнорирует результат отказа от адреса/подтверждения оплаты/stop. При отметке ознакомления с уже изменённым адресом
навигатор открывается независимо от успеха ack ([android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt:6900](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt#L6900)). Это проверка прочитанного пути, не утверждение
о каждой команде во всех версиях приложения.

Acceptance: sending/error/unknown/reconcile для каждого действия; выбранные данные сохраняются до
результата. При 503/409/timeout нет ложного success: ошибка ack не помечает ознакомление успешным; ошибка accept крупной смены не изображается применённым маршрутом. Обычная смена адреса уже применена до ack; сам факт ошибки ack не запрещает навигацию по актуальному маршруту. Смена оплаты уже записана до payment/ack; ack означает ознакомление, а не принятие способа или получение денег.
Поздний ответ другого заказа/аккаунта не действует. Дубликат waypoint и противоположное действие не возникают от повтора. Подтверждение новой цены пассажиром и согласие водителя в предусмотренной pending-ветке проверяются отдельно, без новой универсальной политики.

## TF05 — ошибка чтения не означает отсутствующий активный заказ

КОД: [webapp/src/screens/InstantDriverTripScreen.tsx:253](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/webapp/src/screens/InstantDriverTripScreen.tsx#L253) в catch очищает сохранённый ID на любую ошибку. В браузерном case
`restore503-driver-ru-light-390` был сохранён тестовый id 901, первый GET вернул 503, после чего ключ исчез
и показан кабинет. Это реальный UI с подставным API, водитель offline выбран специально; серверная
поездка не создавалась. Нет основания утверждать потерю самой строки заказа в БД.

Acceptance: временная ошибка/timeout/offline сохраняет якорь и последнее подтверждённое состояние с
повтором; успешный retry восстанавливает **тот же ID**. Окончательные not-found/revoked-access/terminal
обрабатываются по своей политике. Отдельно проверить reload при online driver и при пустом offer,
смену аккаунта и поздний отказ старого GET; не возвращать чужой заказ.

## TF06 — актуальность состояния видна отдельно от наличия интернета

КОД: PWA passenger [webapp/src/screens/InstantOrderScreen.tsx:251](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/webapp/src/screens/InstantOrderScreen.tsx#L251), driver [webapp/src/screens/InstantDriverTripScreen.tsx:414](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/webapp/src/screens/InstantDriverTripScreen.tsx#L414) поглощают ошибку обновления. Android driver [android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt:6850](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt#L6850)
имеет только onSuccess; у Android пассажира есть отдельная обработка потери обновления ([android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt:1682](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt#L1682)).
Два PWA `poll503-*` воспроизводят старый accepted без подсказки. Отдельный `context.offline` показывает
штатный offline banner: наличие этого banner не покрывает отказ API при `navigator.onLine=true`.

Acceptance: согласованный порог stale и время последнего подтверждения, понятные повтор/восстановление;
не показывать достоверное текущее ETA/ожидание на старых данных. После восстановления — один авторитетный
итог. Проверить отмену/неявку/done другой стороной, пока первый клиент не получает обновления. Новый
числовой порог stale и политика автоблокировки не назначаются этим документом.

## TF07 — продление входа во время поездки

КОД: [android/app/src/main/java/com/yuldash/app/data/InstantLocationSocket.kt:115](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/data/InstantLocationSocket.kt#L115) завершает канал при 1008 вместо обновления ключа; экран затем отключает tracking ([android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt:6864](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt#L6864)).
Это известная незакрытая область W1-FIN-26, сохранена её связь. Соседний rideshare LocationSocket имеет
другой механизм; его проверки не принимают InstantLocationSocket автоматически.

Acceptance: отвергнутый ключ не повторяется, общий refresh один, reconnect с новым только для того же
владельца/заказа; отказ refresh/revoke/logout/terminal окончателен. После auth точка не посылается до
разрешённого состояния. Нужны unit плюс оба клиента с коротким сроком тестового ключа, затем два
физических телефона/GPS/фон; в этой работе последние проверки не выполнены.

## TF08 — предзаказ пересчитывается по сохранённому маршруту

КОД: создание учитывает `round_trip` и `waypoints` и сохраняет их ([backend/app/routers/instant.py:553](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/backend/app/routers/instant.py#L553)). Активация
([backend/app/instant_service.py:3242](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/backend/app/instant_service.py#L3242)) вызывает estimate с from/to/category/options, без этих двух входов; затем записывает
новые distance/eta/price. Обновление цены в момент подачи само по себе является действующим правилом,
дефект — неполный состав входов. Полезно проверить все пути активации: ручной, lazy GET и worker.

Acceptance: пересчёт сохраняет смысл согласованного маршрута/остановок/возврата, кроме явно согласованной
изменяемости цены. В итоговой цене, расстоянии, ETA и waypoint projection нет разных маршрутов. Обычный
и круговой предзаказ с 0/1/несколькими остановками, busy passenger и повтор активации проверены на сервере
и в обоих клиентах. Конкретные суммы/потери не утверждаются без соответствующего расчёта.

## TF09 — идентичность цены и маршрута

КОД: набор входов ключа заморозки ([backend/app/price_freeze.py:109](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/backend/app/price_freeze.py#L109)) неполон по отношению к маршруту, который рассчитывают
estimate/create ([backend/app/routers/instant.py:396](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/backend/app/routers/instant.py#L396)). Это требует локальной проверки согласованности всей котировки.
Риск денежный; в документацию не включён пригодный для злоупотребления сценарий запросов. Production
и возможность эксплуатации не проверялись, исторический тест заморозки не доказывает этот новый случай.

Acceptance: один снимок относится ко всем значимым входам маршрута, времени, опций и класса;
значимое изменение не наследует несовместимый расчёт. Проверить ordered waypoints, return, даты,
TTL, округление координат, изменение тарифа, резервный расчёт, цену вниз/вверх и breakdown. До решения
не расширять обещание фиксированной цены и не менять тарифы. Подробное синтетическое воспроизведение,
если нужно, хранить в локальном приватном доказательстве, а не в публичном примере эксплуатации.

## TF10 — черновик остановок Android

КОД: [android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt:2269](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/InstantOrderScreen.kt#L2269) хранит stops через remember, соседние адреса/время/options/comment — rememberSaveable.
Lifecycle-исход на устройстве не воспроизведён. Это не утверждение о потере уже сохранённого заказа.

Acceptance: rotation/восстановление процесса до заказа сохраняют упорядоченные остановки и контекст
владельца; после нового расчёта можно подтвердить прежний смысл поездки. A→B не наследует адреса A,
а успешный заказ не оставляет черновик для случайного второго заказа.

## TF11 — собственная оценка на итоговом экране

КОД: [android/app/src/main/java/com/yuldash/app/TaxiCompletedScreen.kt:89](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/TaxiCompletedScreen.kt#L89) и [android/app/src/main/java/com/yuldash/app/TaxiDriverCompletedScreen.kt:84](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/TaxiDriverCompletedScreen.kt#L84) начинают stars=0/sent=false; DTO уже содержит myStars/canRate ([android/app/src/main/java/com/yuldash/app/data/ApiClient.kt:5735](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/data/ApiClient.kt#L5735)).
Чек восстанавливает собственные звёзды ([android/app/src/main/java/com/yuldash/app/TaxiReceiptScreen.kt:352](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/android/app/src/main/java/com/yuldash/app/TaxiReceiptScreen.kt#L352)), поэтому нельзя утверждать, что оценка в БД потеряна.

Acceptance: initial/restored/unknown submit используют серверные own rating и canRate. Повтор такси
обновляет существующую запись; правило доставки «второй голос запрещён» сюда не переносится.
Проверить обе роли, перезагрузку, истечение окна, ошибку запроса, смену аккаунта и переход итог→чек.

## TF12 — область нажатия и альтернативное управление

БРАУЗЕР: chat button 46×46 CSS px и роль листа ожидания 44 px high; исходники [webapp/src/screens/InstantOrderScreen.tsx:1617](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/webapp/src/screens/InstantOrderScreen.tsx#L1617), [webapp/src/ui.css:4207](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/webapp/src/ui.css#L4207),
[webapp/src/components/WaitlistForm.tsx:101](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/webapp/src/components/WaitlistForm.tsx#L101), [webapp/src/ui.css:2138](https://github.com/bairasbai/yuldash/blob/1b02947b56aa279f7721f475d26c0dd8018cda75/webapp/src/ui.css#L2138). Проект требует не меньше 48 для области нажатия. Измерены DOM rectangles,
**не** Android dp и не весь набор критериев доступности.

Acceptance: интерактивная область минимум 48 px в PWA/48 dp Android по правилам проекта, без перекрытия
соседних целей; повторить измерение RU/BA × темы ×320/390. Отдельно проверить фокус, скринридер,
текстовый маршрут, контраст≥4.5:1, системный крупный текст и отсутствие скрытого CTA за клавиатурой.
Последние критерии этим измерением не считаются выполненными.

## Порядок передачи исполнителю

1. Сначала свежий main/PR/состав и назначение конкретных файлов. Этот документ не разрешает править продукт.
2. Сгруппировать работу по существующим E2E: цена/расчёт TF01/02/03/08/09; надёжность TF04/05/06/07/10;
   итог и доступность TF11/12. За каждым изменением один ответственный, общие файлы — по очереди.
3. Для денежных/схемных/личных данных уточнить план и получить положенную независимую проверку до кода;
   ставки, миграции production и запуск требуют отдельного допуска. Новый TX-план не дублирует общие RS-механизмы.
4. Сначала воспроизведение на старом коде, затем исправление и проверка по всей цепочке. Для гонок —
   PostgreSQL с независимыми сессиями/барьером; SQLite и два последовательных запроса не доказывают гонку.
5. Сохранить исходные журналы, SHA/окружение/границы, независимую приёмку другой семьёй и обновить единый
   реестр. Локальные положительные наблюдения не закрывают все десять TAXI-E2E.
