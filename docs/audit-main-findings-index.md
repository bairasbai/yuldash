# Указатель находок аудита main

Рабочая навигация по обозначениям в заголовках подробных отчётов. Это не счётчик подтверждённых ошибок: повторные доказательства сгруппированы, статус и версия указаны в исходном разделе; некоторые старые находки уже исправлены в main317. Замечания без отдельного номера здесь не перечислены. Полный статический проход кода завершён; [итог и ограничения](C:/Users/Bayra/Yuldash/docs/audit-main-result.md).

[Приоритет исправлений](C:/Users/Bayra/Yuldash/docs/audit-main-fix-order.md) · [Полный документ](C:/Users/Bayra/Yuldash/docs/audit-main-full-report.md)

| Код | Заголовок исходного раздела | Доказательства и исправление |
|---|---|---|
| AND01 | AND-01 · P1 · Временный сбой обновления входа уничтожает сессию и офлайн-данные | [audit-2026-09-05-android.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-android.md) |
| AND02 | AND-02 · P1 · Старый запрос может повториться от имени нового аккаунта | [audit-2026-09-05-android.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-android.md) |
| AND03 | AND-03 · P1 · Очередь теряет новое сообщение при отправке предыдущего | [audit-2026-09-05-android.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-android.md) |
| AND04 | AND-04 · P1 · Очередь навсегда выбрасывает сообщения при временных HTTP-ошибках | [audit-2026-09-05-android.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-android.md) |
| AND05 | AND-05 · P1 · Выход не очищает сохранённые формы экранов | [audit-2026-09-05-android.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-android.md) |
| AND06 | AND-06 · P2 · Три разных уведомления используют один id | [audit-2026-09-05-android.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-android.md) |
| AND07 | AND-07 · P2 · Лимит повторов геосокета не работает | [audit-2026-09-05-android.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-android.md) |
| AND08 | AND-08 · P2 · Остаток поездки считается по числу точек и не обнуляется в пункте назначения | [audit-2026-09-05-android.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-android.md) |
| AND09 | AND-09 · P1 · Микрофон не останавливается при уходе с записи | [audit-2026-09-05-android.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-android.md) |
| AND10 | AND-10 · P1 · Помощь на дороге отправляет старое место с карты | [audit-2026-09-05-android.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-android.md) |
| AND11 | AND-11 · P2 · Реальная лента содержит выдуманные показатели | [audit-2026-09-05-android.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-android.md) |
| AND12 | AND-12 · P1 · После «Забыл вещь» чат всё равно запрещает писать | [audit-2026-09-05-android.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-android.md) |
| AND13 | AND-13 · P2 · Чтение цены вслух не обновляет готовность и может выбрать другой язык | [audit-2026-09-05-android.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-android.md) |
| AND14 | AND-14 · P2 · Ответ обновления старого периода подменяет заработок нового периода | [android-next.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-next.md) |
| AND15 | AND-15 · P2 · Фильтр листа ожидания показывает ответ предыдущего фильтра | [android-next.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-next.md) |
| AND16 | AND-16 · P2 · Ошибка отправки ответа поддержки уничтожает набранный текст без уведомления | [android-next.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-next.md) |
| AND17 | AND-17 · P2 · «Живая» сводка такси скрывает потерю связи и бессрочно показывает старые показатели | [android-next.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-next.md) |
| AND18 | AND-18 · P2 · Ошибка загрузки месяца превращается в ложное «За этот месяц доставок нет» | [android-batch4.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch4.md) |
| AND19 | AND-19 · P2 · Промокод сообщает об оплаченной поездке ещё до поиска водителя | [android-batch4.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch4.md) |
| AND20 | AND-20 · P2 · Предзаказы остаются экраном ошибки после успешного автоматического восстановления | [android-batch5.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch5.md) |
| AND21 | AND-21 · P1 · Ошибка обновления откликов добавляет два одинаковых ключа LazyColumn | [android-batch5.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch5.md) |
| AND22 | AND-22 · P2 · Обратный отсчёт предзаказа принимает UTC за время телефона | [android-batch5.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch5.md) |
| AND23 | AND-23 · P2 · Ошибка фото по жалобе скрыта, если плановый фотоконтроль не нужен | [android-batch5.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch5.md) |
| AND24 | AND-24 · P2 · Откат удаления адреса восстанавливает чужое успешно удалённое изменение | [android-batch6.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch6.md) |
| AND25 | AND-25 · P2 · Повторная заявка таксиста сбрасывает комплектацию автомобиля | [android-batch6.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch6.md) |
| AND26 | AND-26 · P2 · Заявку можно отправить со старой фотографией во время её замены | [android-batch6.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch6.md) |
| AND27 | AND-27 · P1 · После пройденной остановки удаляется другая остановка | [android-batch6.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch6.md) |
| AND28 | AND-28 · P2 · «Водитель предупреждён» показывается даже при неудачной отправке | [android-batch6.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch6.md) |
| AND29 | AND-29 · P1 · Изменение места встречи сохраняет старые координаты | [android-batch6.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch6.md) |
| AND30 | AND-30 · P2 · Поездка публикуется без выбранного времени с молчаливым сроком через3часа | [android-batch6.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch6.md) |
| AND31 | AND-31 · P2 · Завершённая апелляция продолжает показываться как ожидающая разбора | [android-batch7.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch7.md) |
| AND32 | AND-32 · P2 · Блокировка повторного подтверждения платежа снимается сразу после запуска запроса | [android-batch7.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch7.md) |
| AND33 | AND-33 · P1 · Долг и платёж с одинаковым номером конфликтуют в одном LazyColumn | [android-batch7.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch7.md) |
| AND34 | AND34 — P2: подтверждение вручения опережает загрузку выбранного фото | [android-batch8.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch8.md) |
| AND35 | AND35 — P1: переход из экрана курьера останавливает передачу геолокации активной доставки | [android-batch8.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch8.md) |
| AND36 | AND36 — P2: повторное открытие деталей стирает сохраненный код посадки при сетевой ошибке | [android-batch8.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch8.md) |
| AND37 | AND37 — P2: голосовые сообщения не появляются у получателя в открытой активной поездке | [android-batch8.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch8.md) |
| AND38 | AND38 — P2: офлайн-паспорт указывает СБП независимо от согласованного способа оплаты | [android-batch8.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch8.md) |
| AND39 | AND39 — P2: неудачное выключение чаевых остается выключенным только на экране | [android-batch8.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch8.md) |
| AND40 | AND40 — P2: повтор после ошибки модерации создает дубликат нового объявления | [android-batch8.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch8.md) |
| AND41 | AND41 — P2: форма обжалования допускает текст, который сервер всегда отвергает | [android-batch8.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch8.md) |
| AND42 | AND42 [P2] Телефон близкого из формы не попадает в созданную заявку | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| AND43 | AND43 [P1] Новый доверенный контакт нельзя удалить на сервере в текущей сессии | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| AND44 | AND44 [P2] Ошибка добавления контакта не убирает его из списка экрана | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| AND45 | AND45 [P2] Фильтры карты скрывают подходящие поездки за первой страницей | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| AND46 | AND46 [P2] Карточка отклика сама отменяет ожидающий ответ запрос | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| AND47 | AND47 [P2] «Найти попутку» открывает собственные брони вместо поиска маршрута | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| AND48 | AND48 [P2] Заказ за близкого и повтор маршрута создают локальную заявку без серверного id | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| AND49 | AND49 [P2] Экран ожидания откликов не получает новый отклик до повторного входа | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| AND50 | AND50 [P2] Создание посылки молча уменьшает введённую цену и объявленную ценность | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| AND51 | AND51 [P2] Проверка «Ты доехал?» наследует время и флаги предыдущего заказа | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| AND52 | AND52 [P2] Выдача геолокации на открытом экране заказа не запускает получение позиции | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| AND53 | AND53 [P2] Цена на кнопке заказа не включает выбранные платные опции | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| AND54 | AND54 [P2] Заказ разрешён по старой цене во время пересчёта изменённого маршрута | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| AND55 | AND55 [P2] После согласия на новый адрес навигатор открывает старую точку | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| AND56 | AND56 [P2] Сменить адрес можно после ошибки расчёта новой цены | [android-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch9.md) |
| BE01 | BE-01 · P1 · Отозванный access-токен оживает после нового входа | [audit-2026-09-05-backend.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-backend.md) |
| BE02 | BE-02 · P1 · В режиме S3 публичный маршрут подписывает приватные документы | [audit-2026-09-05-backend.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-backend.md) |
| BE03 | BE-03 · P1 · Выход со всех устройств не отключает Web Push | [audit-2026-09-05-backend.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-backend.md) |
| BE04 | BE-04 · P2 · Проверка владельца FCM обходится пропуском X-Device-Id | [audit-2026-09-05-backend.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-backend.md) |
| BE05 | BE-05 · P1 · Подписка Web Push допускает внутренние HTTPS-адреса | [audit-2026-09-05-backend.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-backend.md) |
| BE06 | BE-06 · P1 · Сбой ответа платёжного провайдера стирает локальный платёж | [audit-2026-09-05-backend.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-backend.md) |
| BE07 | BE-07 · P1 · Одна оплата может активироваться повторно из устаревшей сессии | [audit-2026-09-05-backend.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-backend.md) |
| BE08 | BE-08 · P1 · Автоподбор принимает отклик вместо обычного PWA-пассажира | [audit-2026-09-05-backend.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-backend.md) |
| BE09 | BE-09 · P1 · Новый курьер наследует чужую подачу и время ожидания | [backend-next.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-next.md) |
| BE10 | BE-10 · P2 · Телефон курьера остаётся доступен отправителю после закрытия окна контактов | [backend-next.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-next.md) |
| BE11 | BE-11 · P2 · Отказ принять фото оставляет посылку принятой | [backend-next.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-next.md) |
| BE12 | BE-12 · P2 · Создание курьерского заказа обходит ограничения числа посылок | [backend-next.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-next.md) |
| BE13 | BE-13 · P2 · Повторная готовность к новой смене не обновляет время подтверждения | [backend-batch3.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-batch3.md) |
| BE14 | BE-14 · P2 · Воркер снимает рекламу, которую уже успели продлить | [backend-batch3.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-batch3.md) |
| BE15 | BE-15 · P2 · Повторная подписка меняет день ожидаемой поездки | [backend-batch4.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-batch4.md) |
| BE16 | BE-16 · P2 · В CSV листа ожидания попадают пользовательские формулы | [backend-batch4.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-batch4.md) |
| BE17 | BE-17 · P1 · Блокировка за непредоставленное фото документа сама снимается | [backend-batch5.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-batch5.md) |
| BE18 | BE-18 · P1 · Очистка аудио скрывает название GPS-поля, но оставляет координаты в файле | [backend-batch5.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-batch5.md) |
| BE19 | BE-19 · P2 · Для времени вне полученного прогноза показывается последний доступный час | [backend-batch5.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-batch5.md) |
| BE20 | BE-20 · P1 · Семейный статус позволяет завершить ещё не принятую бронь | [backend-batch6.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-batch6.md) |
| BE21 | BE-21 · P2 · Перестановка двух документов удаляет оба используемых файла | [backend-batch6.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-batch6.md) |
| BE22 | BE-22 · P2 · Истёкшую рекламу невозможно продлить штатной кнопкой | [backend-batch6.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-batch6.md) |
| BE23 | BE23 — P1: повторное подтверждение жалобы создаёт возврат неоплаченной комиссии | [backend-batch7.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-batch7.md) |
| BE24 | BE24 — P1: очистка завершает только что начавшийся предзаказ | [backend-batch7.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-batch7.md) |
| BE25 | BE25 — P2: кеш поездок игнорирует offset и загрязняется другой страницей | [backend-batch7.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-batch7.md) |
| BE26 | BE26 — P2: Alembic не запускается с percent-encoded паролем БД | [backend-gaps.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-gaps.md) |
| BE27 | BE27 — P2: preflight ломается на комментариях из штатного .env.example | [backend-gaps.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-gaps.md) |
| BE28 | BE28 — P2: встречная цена0 принимается, но поездка оформляется по первоначальной цене | [backend-tests-batch2.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-tests-batch2.md) |
| BE29 | BE29 [P2] Раздел «Мои данные» неверно обещает, что точная геолокация не хранится | [backend-tests-lead-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-tests-lead-review.md) |
| BE30 | BE30 — P1: похожий номер другой поездки подавляет зимнюю тревогу | [backend-completion-lead.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-completion-lead.md) |
| BE31 | BE31 — P2: повторный вопрос в поддержку больше не попадает в очередь напоминания | [backend-completion-lead.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-completion-lead.md) |
| BE32 | BE32 — P1: «сухой прогон» фонового задания изменяет скидки | [backend-completion-lead.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-completion-lead.md) |
| BE33 | BE33 — P2: после одного сбоя Redis общий лимит больше не восстанавливается | [backend-completion-web.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-completion-web.md) |
| BE34 | BE34 — P2: прерывание между INCR и EXPIRE оставляет бессрочный запрет запросов | [backend-completion-web.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-completion-web.md) |
| BE35 | BE35 · Отменённый счёт YooKassa остаётся pending и блокирует повторную оплату | [backend-completion-android.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-completion-android.md) |
| DELTA01 | DELTA-01 — P1: во время поездки водитель теряет действия смены маршрута, оплаты и остановки | [android-main-delta-findings.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-main-delta-findings.md) |
| DELTA02 | DELTA-02 — P2: после восстановления экрана незавершённое действие остаётся заблокированным | [android-main-delta-findings.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-main-delta-findings.md) |
| DELTA03 | DELTA-03 — P2: разбор отменённого заказа скрыт из-за отсутствующего id пассажира | [android-main-delta-findings.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-main-delta-findings.md) |
| DELTA04 | DELTA-04 — P2: изменение только текста уже отправленного отзыва не сохраняется | [android-main-delta-findings.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-main-delta-findings.md) |
| DELTA05 | DELTA-05 [P2] После потери связи старые машины продолжают отображаться как актуальные | [android-batch10.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch10.md) |
| DELTA06 | DELTA06: локальная проверка реального BookingScreen на317 | [android-lead-addendum.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-lead-addendum.md) · [android-batch10.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch10.md) |
| DELTA07 | DELTA-07 · P1 · Единственная последовательная кнопка блокирует завершение поездки водителем | [android-batch10.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch10.md) |
| DELTA08 | DELTA-08 · P1 · Предзаказ снова теряет выбранные условия пассажира | [android-batch10.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch10.md) |
| DELTA09 | DELTA-09 · P2 · Ошибка создания такси скрыта в свёрнутой шторке | [android-batch10.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch10.md) |
| DELTA10 | DELTA-10 · P2 · Удалено прекращение фонового ожидания машины | [android-batch10.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-batch10.md) |
| INS01 | INS-01 — P1: блокировка пассажира освобождается до проверки и создания заказа | [instant-router-lead-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/instant-router-lead-review.md) |
| INS02 | INS-02 — P1: подбор перезаписывает уже отменённый или принятый заказ | [instant-router-lead-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/instant-router-lead-review.md) |
| INS03 | INS-03 — P1: активация предзаказа пересчитывает цену без остановок и обратного пути | [instant-router-lead-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/instant-router-lead-review.md) |
| INS04 | INS-04 — P1: новый кандидат получает старый маршрут и цену при смене адреса | [instant-router-lead-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/instant-router-lead-review.md) |
| LEGACY01 | LEGACY-01 · P2 · Ответ SMS после ухода с экрана обращается к уничтоженному состоянию | [audit-2026-09-05-tools-legacy.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-tools-legacy.md) |
| LEGACY02 | LEGACY-02 · P2 · Переключатель башкирского не переводит интерфейс Flutter | [audit-2026-09-05-tools-legacy.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-tools-legacy.md) |
| MIG01 | MIG-01 · P2 · Откат сохранённых мест падает на SQLite | [migrations.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/migrations.md) |
| MIG02 | MIG-02 · P2 · Старые жалобы получают категорию с лишними кавычками | [migrations.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/migrations.md) |
| MIG03 | MIG-03 · P2 · Обновление старой SQLite падает при добавлении ограничений | [migrations.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/migrations.md) |
| MIG04 | MIG-04 · P2 · Значение false после обновления SQLite читается как true | [migrations.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/migrations.md) |
| OPS01 | OPS-01 · P1 · Ошибка перезапуска обходит автоматический откат | [ops-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/ops-review.md) |
| OPS02 | OPS-02 · P1 · Репетиция восстановления отвергает исправную зашифрованную копию | [ops-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/ops-review.md) |
| OPS03 | OPS-03 · P1 · Старый скрипт резервирования скрывает сбой pg_dump | [ops-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/ops-review.md) |
| OPS04 | OPS-04 · P1 · Старый путь отправки копий не применяет добавленное шифрование | [ops-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/ops-review.md) |
| RES01 | RES01 — русские подписи встроены в иллюстрацию оплаты (P3) | [android-resource-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/android-resource-review.md) |
| SVC01 | SVC-01 — P2: веб-уведомление теряет адрес конкретного события | [services-lead-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/services-lead-review.md) |
| SVC02 | SVC-02 — P1: число оповещённых близких вычисляется до отправки SMS | [services-lead-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/services-lead-review.md) |
| SVC03 | SVC-03 — P2: подписки на маршруты отправляют русский push независимо от языка | [services-lead-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/services-lead-review.md) |
| TEST01 | TEST01 — ещё один тест с синхронным ожиданием собственной блокировки PostgreSQL | [backend-tests-lead-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-tests-lead-review.md) · [backend-tests-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-tests-batch9.md) |
| TEST02 | TEST02 — подтверждённая ошибка теста: тавтология вместо проверки времени поездки | [backend-tests-android-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/backend-tests-android-review.md) |
| TOOL01 | TOOL-01 · P2 · Проверка контраста проходит даже при отсутствии обязательного токена | [audit-2026-09-05-tools-legacy.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-tools-legacy.md) |
| TOOL02 | TOOL-02 · P2 · Синтаксическая проверка принимает аварийный останов компилятора за успех | [audit-2026-09-05-tools-legacy.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-tools-legacy.md) |
| TOOL03 | TOOL-03 — P2: импорт приграничных сёл пропускает точки внутри заданного радиуса | [auxiliary-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/auxiliary-review.md) |
| TOOL04 | TOOL-04 — P2: нагрузочный сценарий быстрого заказа не вызывает существующее создание заказа | [auxiliary-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/auxiliary-review.md) |
| TOOL05 | TOOL-05 — P2: скрипт релиза сообщает успех после неудачного копирования APK | [auxiliary-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/auxiliary-review.md) |
| TOOL06 | TOOL-06 — P2: неудачная установка не останавливает запуск прежней версии приложения | [auxiliary-review.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/auxiliary-review.md) |
| WEB01 | WEB-01 — P1. Исходники промороликов содержат неразрешённый конфликт Git | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB02 | WEB-02 — P1. Очередь исходящих может потерять новое действие во время отправки | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB03 | WEB-03 — P1. Автоматическое истечение сессии не очищает данные прошлого аккаунта | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB04 | WEB-04 — P2. Холодный старт без сети блокирует офлайн-паспорт поездки | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB05 | WEB-05 — P2. Карта не рисует уже полученные точки после загрузки SDK | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB06 | WEB-06 — P2. Экран возврата из банка утверждает факт списания без подтверждения | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB07 | WEB-07 — P1. Отмена такси ошибочно считается успешной при отказе сервера | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB08 | WEB-08 — P1. Очередь стирает сообщения и статусы при временном HTTP 503 | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB09 | WEB-09 — P1. Защита от бесконечной перезагрузки чанка сбрасывается до его загрузки | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB10 | WEB-10 — P2. Оценка попутки показывает успех при любой ошибке | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB11 | WEB-11 — P1. Разовый сбой восстановления навсегда забывает активный заказ водителя | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB12 | WEB-12 — P2. Тумблеры уведомлений и звука не управляют соответствующим поведением | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB13 | WEB-13 — P2. Пример nginx запрещает собственные голосовые функции | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB14 | WEB-14 — P2. События аналитики отправляются дважды на одно действие | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB15 | WEB-15 — P2. Редактирование поездки не позволяет установить нулевую цену | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB16 | WEB-16 — P1. Выбор клиники разлогинивает даже вошедшего пользователя | [audit-2026-09-05-web.md](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md) |
| WEB17 | WEB-17 — P1: вошедший пользователь получает гостевую ленту поездок, блокировки не действуют | [web-next.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-next.md) |
| WEB18 | WEB-18 — P1: сохранение карты в PWA стирает токен выплаты | [web-next.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-next.md) |
| WEB19 | WEB-19 — P1: повтор вывода после потери ответа создаёт вторую выплату | [web-batch3.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch3.md) |
| WEB20 | WEB-20 — P2: ошибка одной разблокировки отменяет успешный результат другой в интерфейсе | [web-batch4.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch4.md) |
| WEB21 | WEB-21 — P2: два фильтра сохраняются, но никогда не применяются | [web-batch4.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch4.md) |
| WEB22 | WEB-22 — P1: запрос обратного звонка теряется, интерфейс обещает звонок | [web-batch4.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch4.md) |
| WEB23 | WEB-23 — P1: запрет localStorage останавливает запуск до создания React | [web-batch5.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch5.md) |
| WEB24 | WEB-24 — P2: лента сообщества выдаёт созданные брони за состоявшиеся поездки | [web-batch6.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch6.md) |
| WEB25 | WEB-25 — P1: после смены города сохраняется точка посадки из прежнего города | [web-batch6.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch6.md) |
| WEB26 | WEB-26 — P2: при смене рекламы в том же слоте новый показ не учитывается | [web-batch7.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch7.md) |
| WEB27 | WEB-27 — P1: микрофон начинает записывать после ухода из чата | [web-batch7.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch7.md) |
| WEB28 | WEB-28 — P1: pending-платёж без банковской ссылки отображается как оплаченный | [web-batch7.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch7.md) |
| WEB29 | WEB29 · P1 · Повтор SSH-деплоя теряет тело удалённого скрипта и сообщает ложный успех | [web-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch9.md) |
| WEB30 | WEB30 · P1 · Сбой между переименованиями убирает сайт; повтор удаляет резервную копию | [web-batch9.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch9.md) |
| WEB31 | WEB31 · P2 · Сайт показывает фиксированный рейтинг4.9 независимо от отзывов | [web-batch13.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch13.md) |
| WEB32 | WEB32 · P2 · Башкирская версия страницы размечена как русская | [web-batch13.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch13.md) |
| WEB33 | WEB33 · P2 · После расширения окна с открытым мобильным меню страница перестаёт прокручиваться | [web-batch14.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch14.md) |
| WEB34 | WEB34 · P2 · Проверки контракта и Android-переводов молча смотрят за пределы репозитория | [web-batch15.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch15.md) |
| WEB35 | WEB35 · P2 · Сторож состояний загрузки ищет невидимый backspace вместо границы слова | [web-batch15.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch15.md) |
| WEB36 | WEB36 · P1 · Поздний ответ старой сессии восстанавливает её после выхода или перезаписывает новую | [web-batch16.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch16.md) |
| WEB37 | WEB37 · P2 · Сообщение с фото обходит запрет сторонних адресов через slash/backslash | [web-batch16.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch16.md) |
| WEB38 | WEB38 · P2 · После ошибки загрузки карт повторный вход зависает на загрузке | [web-batch17.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch17.md) |
| WEB39 | WEB39 · P1 · Платного курьера невозможно заказать: клиент не передаёт города | [web-batch17.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch17.md) |
| WEB40 | WEB40 · P1 · Снятие неподтверждённой галочки записывает согласие на сервер | [web-batch18.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch18.md) |
| WEB41 | WEB41 · P2 · Ответ сервера стирает новый черновик сообщения администратора | [web-batch18.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch18.md) |
| WEB42 | WEB42 · P2 · Публичные условия комиссии расходятся с расчётом сервера | [web-batch19.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch19.md) |
| WEB43 | WEB43 · P1 · Открытая лента SOS не получает новые сигналы | [web-batch19.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch19.md) |
| WEB44 | WEB-44 (P1): запоздавшая история чата стирает уже показанное живое сообщение | [web-batch20.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch20.md) |
| WEB45 | WEB-45 (P2): очистка необязательных полей заявки молча не сохраняется | [web-batch20.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch20.md) |
| WEB46 | WEB-46 (P1): SOS сообщает об отправке близким вопреки ответу сервера | [web-batch20.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch20.md) |
| WEB47 | WEB-47 (P2): ошибка загрузки голоса молча превращает заявку в пустую текстовую | [web-batch20.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch20.md) |
| WEB48 | WEB-48 (P2): галочка подтверждения пола включена, а одобрение отправляет false | [web-batch21.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch21.md) |
| WEB49 | WEB-49 (P1): категории опасных жалоб превращаются для модератора в «Другое» | [web-batch21.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch21.md) |
| WEB50 | WEB-50 (P1): оплатить рекламу через кабинет невозможно без запроса реквизитов вручную | [web-batch21.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch21.md) |
| WEB51 | WEB-51 (P2): повторная проверка водителя отправляет старые фото во время загрузки замены | [web-batch21.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch21.md) |
| WEB52 | WEB-52 (P2): ошибки решений в общей очереди модерации остаются без сообщения | [web-batch21.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch21.md) |
| WEB53 | WEB-53 (P2): возврат посылки исчезает из активных доставок администратора | [web-batch22.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch22.md) |
| WEB54 | WEB-54 (P1): фотографии доказательств спора запрашиваются без обязательного токена | [web-batch22.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch22.md) |
| WEB55 | WEB-55 (P2): смена дня или «Показать ещё» отключает географический фильтр при включённой кнопке | [web-batch22.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch22.md) |
| WEB56 | WEB-56 (P2): при фотоконтроле по жалобе ошибка загрузки или отправки не показывается | [web-batch22.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch22.md) |
| WEB57 | WEB-57 (P2): политика PWA обещает другой способ хранения токенов | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB58 | WEB-58 (P1): список заказов курьера не обновляется после изменения рабочего города и при появлении заказов | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB59 | WEB-59 (P2): неудачное принятие посылки удаляет её из выдачи без объяснения | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB60 | WEB-60 (P1): цена одного нового адреса показывается рядом с другим адресом | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB61 | WEB-61 (P1): после перезагрузки PWA пропадает ответ на уже отправленную зимнюю проверку | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB62 | WEB-62 (P1): отправителю посылки предлагают открыть спор, но действие не подключено | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB63 | WEB-63 (P2): цена доставки названа сбором сервиса | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB64 | WEB-64 (P1): водитель не может управлять ближайшими рейсами, если у него больше12 будущих поездок | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB65 | WEB-65 (P1): после подтверждения брони статус обновляется, а телефон и место встречи остаются закрытыми | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB66 | WEB-66 (P2): правка и «удалить у всех» не обновляют открытый чат собеседника | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB67 | WEB-67 (P1): отменённый пассажиром заказ у водителя превращается в экран «В пути» | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB68 | WEB-68 (P1): таймер предложения отправляет отказ во время принятия заказа | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB69 | WEB-69 (P1): заказ разрешён по старой цене во время пересчёта | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB70 | WEB-70 (P1): без геолокации невозможно выбрать точку подачи вручную | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB71 | WEB-71 (P1): итог «К оплате» игнорирует применённый промокод | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB72 | WEB-72 (P2): счётчик ожидания продолжает расти после посадки | [web-batch23.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch23.md) |
| WEB73 | WEB-73 (P2): объяснение блокировки курьера почти не видно в тёмной теме | [web-batch24.md](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/web-batch24.md) |
