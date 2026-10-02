# Карточка: `backend/app/safety_logic.py`

- Статус: verified
- Лист: leaf-3.1
- Проверял: Sonnet 5 (leaf-3.1); принимал: Opus 5.5

## Назначение

Доменное ядро безопасности/справедливости, НЕ про сам SOS (красная кнопка и её эскалация живут
в `routers/safety.py` + `sos_escalate.py`), а про всё вокруг неё: двусторонний разбор споров
«Справедливость» (`Incident`) с лестницей наказаний и щитом рейтинга, гейт паузы аккаунта
(`ensure_active`/`account_paused`, намеренно НЕ закрывающий SOS), «встречный шаг» как условие
автоматического наказания (`trip_really_happened` — защита от жалобы на постороннего),
владение приватными фото-доказательствами (`guard_own_evidence` — лица/травмы/номера машин),
совместимость пары для поездки (`may_ride_together`, `have_met` — приватность чёрного списка),
правило «только женщины». Роутеры остаются тонкими: `routers/incidents.py` (не в моей зоне)
вызывает `apply_incident_resolution` и константы `INCIDENT_TYPES`/`SEVERE_TYPES`;
`routers/safety.py` (моя зона) вызывает `have_met`/`trip_really_happened`; ещё 9 роутеров вне
моей зоны (`bookings`, `instant`, `parcels`, `requests`, `rides`, `coupons`, `courier`, `ads`,
`auth`) используют `ensure_active`/`account_paused`/`may_ride_together`/`GENDERS` и т.п.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `INCIDENT_TYPES`, `SEVERE_TYPES`, `ACTIVE_INCIDENT_STATUSES`, `CANCEL_REASONS`, `RATING_TAGS(+MAX)` | 23–55 | Закрытые перечни для `Incident`/`Rating` — произвольная строка от клиента в БД не попадает | Используются `routers/incidents.py` (вне зоны) и `rating_service.py` | ок |
| `clean_tags` | 58–66 | CSV меток оценки → только известные, без дублей, максимум 5, порядок сохранён | — | ок |
| `clamp` | 69–70 | Обрезка строки до лимита + strip, пусто вместо `None` | — | ок |
| `is_own_media_url` | 73–84 | Ссылка указывает на НАШ хост (`/media/` или `/secure/evidence/`), не на чужой — защита от деанона через внешний URL | — | ок |
| `evidence_name` | 87–93 | Имя файла-доказательства из приватной ссылки, пусто если не эвиденс | — | ок |
| `is_own_evidence_name` | 96–104 | Имя файла начинается с `{user_id}_` — наследие без префикса (до 2026-08-08) не считается «своим» | — | ок |
| `guard_own_evidence` | 107–142 | 403, если среди НОВЫХ ссылок есть чужое приватное фото (лицо/травма/номер); `already` защищает ранее сохранённые (в т.ч. наследие) от внезапного «стало чужим»; публичные `/media/` не проверяет | Зовут `routers/incidents.py` (подача спора, объяснение, апелляция-эвиденс) и `routers/parcels.py` (фото взял/отдал) — обе вне моей зоны | ок, тест R2/M2 |
| `csv_from_urls` / `urls_from_csv` | 145–162 | Список URL ↔ CSV; принимает только свои URL (чужой хост молча отброшен — владение здесь НЕ проверяется, для этого есть `guard_own_evidence`, звать ДО) | — | ок |
| `get_or_create_safety_profile` | 166–185 | Ленивое создание `SafetyProfile` 1:1 с `User`, защита от гонки уникальным индексом + `IntegrityError` fallback; `lock=True` → `with_for_update` | — | ок |
| `recompute_standing` | 188–205 | Пересчёт `standing` (suspended→limited→warned→good) + затухание страйков/замечаний по окну `safety_strike_decay_days` | Чистая функция, не коммитит | ок |
| `refresh_standing` | 208–217 | Ленивый пересчёт при чтении + сохранение, если что-то изменилось (снятая истёкшая пауза, затухание) | — | ок |
| `is_suspended` | 220–222 | `suspended_until` в будущем? | — | ок |
| `account_paused` | 225–234 | Активна ли пауза §2 ПРЯМО СЕЙЧАС; нет строки профиля → `False` без создания записи (горячий путь) | Не бросает — для мест с молчаливым отказом (лента офферов) | ок |
| `suspended_user_ids` | 237–250 | Все id на паузе ПРЯМО СЕЙЧАС — один запрос на ленту вместо N | Ленивый пересчёт не нужен: условие `suspended_until > now` само перестаёт работать | ок |
| `trip_really_happened` | 253–290 | Был ли ВСТРЕЧНЫЙ шаг второй стороны (не просто номер брони): попутка — водитель подтвердил (`confirmed_at` или статус confirmed/onboard/done); такси — водитель назначен; доставка — курьер принял (`courier_id`) | Без привязки (все три `None`) → `False` | ок, тест R1(booking/order уже в проекте)+R-TRH-PARCEL/M1 — закрыл дыру покрытия для доставки |
| `ensure_active` | 293–305 | 403, если аккаунт на паузе §2 — гейт стоит ПОШТУЧНО на каждой ручке (вне моей зоны); намеренно НЕ закрывает SOS и завершение начатой поездки | — | ок, тест R3 (поведенчески подтверждено со стороны `routers/safety.py`: `/sos` не падает на паузе) |
| `may_ride_together` | 308–323 | Можно ли свести ДВОИХ в поездку: пауза любой стороны ИЛИ блокировка — отказ; само- пара и пустые id → `True` | Зовут `automatch.py`, `routers/requests.py`, `instant_service.py` (вне зоны) | ок |
| `active_incidents_count` / `incidents_last_hour` | 326–337 | Счётчики для гейтов `routers/incidents.py` (лимит открытых споров / частота подачи) | — | ок (используется вне зоны) |
| `completed_trips_for` | 340–358 | Число СОСТОЯВШИХСЯ поездок (closed + поездка уже выехала) — единое правило для витрины доверия | — | ок |
| `_participant_terminal_bookings` | 362–379 | Внутренний: терминальные брони пользователя (как пассажир или водитель), последние `limit` | — | ок |
| `is_late_cancel` | 382–391 | Поздняя отмена: водитель уже выехал/подъезжает, ИЛИ момент отмены в окне до выезда | — | ок |
| `reliability_for` | 394–425 | «Надёжность» 0–100 = completed / (completed+failed) по последним N терминальным броням; неявка засчитывается ТОЛЬКО по RESOLVED инциденту (защита оболганного) | Новичок без броней → 100 (нейтрально) | ок |
| `cancel_upcoming_after_suspension` | 428–522 | Пауза снимает уже НАЗНАЧЕННЫЕ будущие поездки (и как водителя, и как пассажира), шлёт уведомления задетым; НЕ трогает уже начатые (`depart_at` прошёл) и `onboard`; `cancelled_by=None` (не вина человека, решение разбора) | Коммитит сам, уведомления — после commit (сбой не откатывает отмену) | ок |
| `_escalation_days` | 526–537 | Длина паузы по лестнице §2: 1-я→3д, 2-я→7д, 3-я+→30д, считая прошлые suspend/ban этого человека | — | ок |
| `_exclude_linked_ratings` | 540–590 | Щит рейтинга: снять оценку-месть из среднего В ОБЕ СТОРОНЫ по спорной поездке (попутка/такси/доставка), текст отзыва тоже гасится (`text_published=False`), пересчёт `DriverProfile.rating` | — | ок (используется `apply_incident_resolution`, вне моей зоны напрямую не вызывается) |
| `apply_incident_resolution` | 593–692 | Применить решение админа: откат СВОЕГО прошлого вклада этого же спора (`applied_warning/applied_strike/applied_suspended_until`) перед повторным наложением — повторное решение (после апелляции) не наказывает дважды и не оставляет чужую/старую паузу; дни паузы: явные от админа > ban(3650д) > лестница; удалённый обвинённый (`gone`) — лестница вхолостую, но решение в споре сохраняется; коммитит сам, доводит паузу до конца (`cancel_upcoming_after_suspension`) | Зовётся ТОЛЬКО из `routers/incidents.py::resolve_incident` (вне зоны); гейт «уже resolved/closed» стоит в роутере, повторный вызов достижим через ветку `appealed` | ок — поведение подтверждено существующими `test_appeal_upheld_does_not_double_punish`, `test_appeal_overturned_removes_strike`, `test_pause_reaches_tomorrows_ride.py` (апелляция+пересмотр) |
| `GENDER_*`, `MSG_*` константы | 695–734 | Тексты двуязычных отказов «только женщины» — три исхода (female/male/не указан) | — | ок, двуязычно (RU/BA) |
| `gender_of` / `is_female` | 737–743 | Единственная точка чтения `User.gender` | — | ок |
| `is_verified_female_driver` | 746–757 | Женщина за рулём, ПОДТВЕРЖДЁННАЯ модератором (`DriverProfile.gender_verified`) — самодекларации недостаточно для обещания безопасности | — | ок |
| `guard_women_only_publish` | 760–782 | Поставить отметку поездке может только подтверждённая женщина-водитель | — | ок |
| `guard_women_only` | 785–802 | Три исхода доступа к «только женщины»: female→пропуск, male→отказ с контекстным текстом, пусто→просьба указать пол (не молчаливый отказ) | — | ок |
| `women_only_in_force` | 805–825 | Действует ли отметка ПРЯМО СЕЙЧАС — подтверждение может сгореть (документы отклонены / пол переписан) независимо от поездки | — | ок |
| `have_met` | 828–892 | Пересекались ли двое хоть в одной сделке (попутка/такси/доставка/отклик-на-заявку, любой статус, включая отменённые) — единственное условие, при котором чёрный список раскрывает настоящее имя | Используется `routers/safety.py::list_blocks` (моя зона) | ок, тест R-BLOCK-NAME-PRIVACY/M5 (мутация в `routers/safety.py`, см. его карточку) |

## Связи

- `routers/safety.py` (моя зона): `have_met` (приватность `/blocks`), `trip_really_happened`
  (требование фото по жалобе «грязно»).
- `routers/incidents.py` (ВНЕ зоны): `INCIDENT_TYPES`, `SEVERE_TYPES`, `active_incidents_count`,
  `apply_incident_resolution`, `guard_own_evidence`, `csv_from_urls`, `completed_trips_for`,
  `ensure_active`.
- `quality.py` (ВНЕ зоны): `trip_really_happened` (гейт авто-паузы по тяжёлой категории
  `Report` — `escalate_severe`).
- 9 роутеров вне зоны (`bookings`, `instant`, `parcels`, `requests`, `rides`, `coupons`,
  `courier`, `ads`, `auth`, `automatch.py`, `rating_service.py`, `instant_service.py`,
  `trust_service.py`, `visibility.py`, `services.py`) читают `ensure_active`/`account_paused`/
  `may_ride_together`/`suspended_user_ids`/`GENDERS`/`women_only_in_force`/`is_verified_female_driver`.
- Таблицы: `SafetyProfile`, `Incident`, `Rating`, `Booking`/`Ride`/`InstantOrder`/`ParcelDelivery`
  (для `trip_really_happened`/`have_met`/`reliability_for`), `DriverProfile`.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R-TRH-PARCEL | `trip_really_happened(parcel_id=...)` требует НАЗНАЧЕННОГО курьера, не просто заявку | `backend/tests/walk/l3_1/test_l3_1_trip_really_happened_parcel.py::test_посылку_без_курьера_встречей_не_считаем`, `::test_курьер_принял_посылку_встреча_засчитана`, `::test_несуществующая_посылка_не_встреча`, `::test_пустой_вызов_без_какой_либо_привязки_не_встреча` | да — M1 |
| R-EVIDENCE-OWNER | `guard_own_evidence` не даёт приложить ЧУЖОЕ приватное фото-доказательство (лицо/травма/номер) к своему спору/доставке | `backend/tests/test_audit_20260808.py::test_stranger_cannot_attach_and_read_someone_elses_photo`, `::test_evidence_upload_is_bound_to_the_uploader`, `::test_own_photo_still_works_end_to_end`, `::test_courier_cannot_pass_off_a_foreign_photo_as_proof` | да — M2 |
| R-BLOCK-NAME-PRIVACY | `have_met` — единственное условие раскрытия настоящего имени в чёрном списке | `backend/tests/test_blocklist_is_not_a_phonebook.py::test_перебор_не_выгружает_имена`, `::test_имя_попутчика_в_списке_видно`, `backend/tests/test_block_stays_a_secret.py` | да — M5 (мутация и тест живут в `routers/safety.py::list_blocks`, см. его карточку) |
| R-TRIP-BOOKING/ORDER | `trip_really_happened` для попутки требует подтверждения водителем, для такси — назначения; голый номер брони/заказа без встречного шага не считается | `backend/tests/test_the_punishment_reaches_the_right_person.py::test_встреча_засчитывается_по_встречному_шагу`, `::test_заказ_такси_без_водителя_не_встреча`, `::test_отменённая_после_подтверждения_остаётся_встречей`, `backend/tests/test_report_cannot_pause_a_stranger.py` (3 теста) | покрыто существующим набором, отдельной новой поломки не заводил (риск уже закрыт) |
| R-APPEAL-NO-DOUBLE-PUNISH | `apply_incident_resolution` при пересмотре после апелляции откатывает СВОЙ прошлый вклад перед повторным наложением — «оставлено в силе» не удваивает страйк, «отменено» снимает паузу полностью | `backend/tests/test_audit_hardening.py::test_appeal_upheld_does_not_double_punish`, `::test_appeal_overturned_removes_strike`, `backend/tests/test_pause_reaches_tomorrows_ride.py` (апелляция возвращает `suspended_until=None`) | покрыто существующим набором |
| R-SOS-OPEN-ON-PAUSE | `ensure_active`/`account_paused` НЕ закрывают SOS и завершение начатой поездки | `backend/tests/test_the_pause_does_not_take_away_defence.py::test_sos_на_паузе_работает`, `backend/tests/test_suspension_reaches_everywhere.py` (строка 372: `/sos` на паузе отвечает 200) | покрыто существующим набором |

## Найденные ошибки

Подтверждённых ошибок в этом файле не найдено. Единственная закрытая дыра ПОКРЫТИЯ (не
дыра в логике): `trip_really_happened(parcel_id=...)` — третья дверь того же правила 2026-08-08
(волна 158) — нигде не была проверена тестом напрямую, хотя код написан правильно (требует
`courier_id`, как и задумано). Доказательство: `backend/tests/walk/l3_1/test_l3_1_trip_really_happened_parcel.py`
проходит на НЕИЗМЕНЁННОМ коде; мутация M1 (снять проверку `courier_id`) — KILLED.

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M1 | `trip_really_happened`: `return bool(p and p.courier_id)` → `return bool(p)` (доставка без курьера тоже считается встречей) | `test_l3_1_trip_really_happened_parcel.py::test_посылку_без_курьера_встречей_не_считаем` | KILLED |
| M2 | `guard_own_evidence`: `if name and not is_own_evidence_name(...)​:` → `if False:` (проверка владения приватным фото отключена) | `test_audit_20260808.py::test_stranger_cannot_attach_and_read_someone_elses_photo` | KILLED |

(M5, тоже проверяющая экспорт `have_met` этого файла, числится в `routers/safety.py` — там же
находится изменённая строка `знакомы = ...`.) Полный прогон:
`python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-3.1.json` (из корня) —
сводный итог в карточке `routers/safety.py`.

## Остаток и ограничения

- **ВНЕ ЗОНЫ.** `apply_incident_resolution`, `INCIDENT_TYPES`/`SEVERE_TYPES`,
  `active_incidents_count`, `guard_own_evidence`, `ensure_active` вызываются только из
  `routers/incidents.py` и `routers/parcels.py` — эти роутеры не трогал (не в OWNS), проверил
  только САМИ функции safety_logic.py и то, что существующие тесты, идущие через эти роутеры,
  зелёные.
- `_exclude_linked_ratings`, `_escalation_days`, `_participant_terminal_bookings` — внутренние
  (с `_`), напрямую не вызываются ни из какого роутера МОЕЙ зоны; проверены чтением и косвенно
  тестами `apply_incident_resolution`/`reliability_for`. Отдельных нарочных поломок не заводил —
  в руководстве «минимум одна на файл» выполнено (M1, M2), а эти функции не про деньги/вход/
  личные данные впрямую (кроме `_exclude_linked_ratings`, которая уже покрыта
  `test_resolve_exclude_rating_shields_target` из `test_incidents.py`).
- `reliability_for`, `completed_trips_for`, `is_late_cancel` — витринные метрики доверия,
  формально разобраны, но отдельных новых тестов/поломок не заводил: они не входят в фокус
  «SOS и безопасность поездки» этого листа и не тронуты изменениями, а существующий набор
  (`test_release_blockers.py` и др.) их уже использует как побочный эффект большого числа
  сценариев.
- Женское правило (`guard_women_only*`, `women_only_in_force`, `GENDERS`) — вне фокуса «SOS»,
  разобрано и прочитано целиком, но без новых тестов/поломок: существующий `test_audit_20260808.py`
  (`test_woman_can_book_a_women_only_ride` и соседние пять тестов) уже покрывает ровно эти
  функции.
