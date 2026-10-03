# Карточка: `backend/app/trust_service.py`

- Статус: verified
- Лист: leaf-3.2
- Проверял: Sonnet 5 (leaf-3.2); принимал: Opus 5.5

## Назначение
Вычисление уровня доверия «между своими» (L0..L3), гейт права звать своих (`can_invite`),
двуязычные описания уровней для витрины и реестр согласий 152-ФЗ. Используется `routers/trust.py`
и фильтром «только для своих» в `rides.py`/`requests.py` (чужая зона, только читает наши функции).

## Функции и разбор
| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `record_login_consents` | 31–60 | При каждом входе пишет согласия offer/privacy/age18 | Ошибка побочной записи не роняет сам вход (try/except, rollback своего savepoint) | ок |
| `base_level` | 67–74 | L0 (аноним/телефон) → L1 (имя И фото) → L2 (`User.verified`) | Нужны ОБА поля для L1, не одно | ок |
| `trust_level` | 81–102 | Итог = max(вычисленный, дарованный). Дарованный L3 ЗАМОРОЖЕН на время паузы «Справедливости» | `account_paused` проверяется отдельным запросом; базовый уровень пауза не трогает | ок |
| `can_invite` | 105–123 | Может ли звать своих: НЕ на паузе И уровень ≥ L2 | Пауза проверяется ОТДЕЛЬНО от уровня — иначе разжалованный «свой» с паузой продолжал бы звать людей | ок |
| `trust_summary` | 172–194 | Формирует ответ `GET /me/trust`: уровень, польза, что даёт следующий, `can_invite` | **Было:** считала `can_invite` отдельной формулой `level>=MIN_INVITER_LEVEL` БЕЗ проверки паузы — исправлено, см. «Найденные ошибки» | ок (после фикса) |
| `record_consent` | 199–214 | Идемпотентная запись согласия — время ПЕРВОГО не перезаписывается | Повтор → возвращает старую запись | ок |
| `list_consents` | 217–218 | Список согласий пользователя | Только свои (вызывающий роутер фильтрует по user_id) | ок |

## Связи
`routers/trust.py` (уровень/инвайты/согласия), `rides.py`/`requests.py` (фильтр `only_trusted`,
читают `trust_level`), `account.py` (удаление стирает `Trust`/`Consent`, отвязывает `invited_by`) —
все три ЧУЖИЕ файлы, не трогал.

## Важные правила и тесты
| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R-TS1 | L1 нужны ОБА поля (имя И фото), не одно | `backend/tests/test_trust.py::test_level_l1_needs_both_name_and_photo` | да — M05 |
| R-TS2 | Дарованный «свой» (L3) замораживается на время паузы | `backend/tests/test_the_inner_circle_notices_punishment.py::test_отстранённый_свой_закрытых_поездок_не_видит` | да — M06 |
| R-TS3 | `can_invite` требует уровень ≥ L2 (MIN_INVITER_LEVEL) | `backend/tests/test_trust.py::test_level_l2_verified` | да — M07 |
| R-TS4 | `can_invite` отдельно спрашивает паузу — наказанный проверенный не зовёт | `backend/tests/test_the_inner_circle_notices_punishment.py::test_отстранённый_никого_в_круг_не_приводит` | да — M08 |
| R-TS5 🆕 | Витрина `GET /me/trust` отвечает `can_invite` ТО ЖЕ, что реальный гейт `POST /invites` | `backend/tests/walk/l3_2/test_l3_2_trust_pause_invite.py::test_paused_l2_cannot_invite_and_trust_says_so` | да — M09 |
| R-TS6 | Согласие (152-ФЗ) не перезаписывает таймстамп первого раза | `backend/tests/test_trust.py::test_consent_idempotent_keeps_first_timestamp` | да — M10 |

## Найденные ошибки
| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| B-L3.2-2 | **Экран доверия обещал то, чего не выполнял.** `GET /me/trust` считал `can_invite` по формуле «уровень ≥ 2», СОВСЕМ не спрашивая про паузу «Справедливости» — а настоящая проверка в `POST /invites` (функция `can_invite()`) паузу спрашивает. Человек со статусом «Проверен» (L2), отправленный на паузу после жалобы, открывал профиль и видел «можешь позвать своего» — жал кнопку и получал отказ 403. Не дыра безопасности (сам `POST /invites` защищён верно), но сервер сам себе противоречил в одном и том же запросе | `test_l3_2_trust_pause_invite.py::test_paused_l2_cannot_invite_and_trust_says_so`: создать L2-пользователя, поставить `SafetyProfile.suspended_until` в будущее → `POST /invites` → 403 (верно), следом `GET /me/trust` → `can_invite: true` (до фикса) | `trust_summary` (строка ~192) теперь зовёт САМУ функцию `can_invite(session, user)` вместо повторения её условия по памяти | До: `can_invite=True` при паузе (тест падал: `assert True is False`). После: `can_invite=False`, совпадает с реальным гейтом; 30/30 тестов `test_trust.py` + `test_the_inner_circle_notices_punishment.py` + новый файл — зелёные |

## Проверка нарочной поломкой
| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M05 | `and` → `or` в условии L1 (имя ИЛИ фото) | `test_trust.py::test_level_l1_needs_both_name_and_photo` | KILLED (replay 2026-10-03, 31/31) |
| M06 | Заморозка дарованного L3 при паузе отключена (`granted = 0` → `pass`) | `test_the_inner_circle_notices_punishment.py::test_отстранённый_свой_закрытых_поездок_не_видит` | KILLED (replay 2026-10-03, 31/31) |
| M07 | `can_invite`: `>=` → `>` (L2 теряет право звать) | `test_trust.py::test_level_l2_verified` | KILLED (replay 2026-10-03, 31/31) |
| M08 | Проверка паузы в `can_invite` отключена (`return False` → `pass`) | `test_the_inner_circle_notices_punishment.py::test_отстранённый_никого_в_круг_не_приводит` | KILLED (replay 2026-10-03, 31/31) |
| M09 | `trust_summary.can_invite` снова считается по памяти (возврат найденной ошибки) | `test_l3_2_trust_pause_invite.py::test_paused_l2_cannot_invite_and_trust_says_so` | KILLED (replay 2026-10-03, 31/31) |
| M10 | `record_consent`: идемпотентность отключена (`if existing: return existing` → `if False:`) | `test_trust.py::test_consent_idempotent_keeps_first_timestamp` | KILLED (replay 2026-10-03, 31/31) |

Итог прогона — общая строка `MUTATIONS KILLED k/k` по всем 31 поломкам листа в отчёте ведущему.

## Остаток и ограничения
Файл целиком прочитан, все функции разобраны. Отдельно НЕ проверял (вне зоны, см. «Связи»): как
именно `rides.py`/`requests.py` применяют `trust_level` к фильтру ленты (чужой файл) — читал
`docs/trust-levels-backend.md`, поведение описано, глубже не лез.
