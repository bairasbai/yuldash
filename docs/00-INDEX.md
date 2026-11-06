# 🧠 Второй мозг Юлдаш — карта знаний

> Навигация обновлена 06.11.2026. Здесь — куда идти за ответом; статусы проверок ведутся **только** в [audit-blocks.md](audit-blocks.md), планы и переводы — в [tasks.md](tasks.md). Сверяй текущую версию Git перед продолжением: старая приёмка не доказывает работу изменённого кода.

## Начать работу

1. Прочитай [правила проекта](../AGENTS.md) и относящийся к задаче раздел [надёжности](agent-reliability.md).
2. Сверь ветку, коммит, незакоммиченные файлы и [владельцев общих ресурсов](locks.md).
3. Ищи нужный экран/файл/ID в [карте кода](architecture.md); большие документы и код открывай только в найденном месте.
4. Проверь [актуальный блок аудита](audit-blocks.md) и [план задачи](tasks.md). Для попутки последний опубликованный срез `db2bc532` описан в [INTEGRATION-20261106](audit-blocks.md#integration-20261106); это ограниченная приёмка, не готовность всего приложения.

## Как пользоваться (агенту)

- Новый экран и его связи → [architecture.md](architecture.md).
- Решение и причина → [decisions.md](decisions.md).
- Ошибка и вывод → [lessons.md](lessons.md).
- План/прогресс и перевод на подтверждение → [tasks.md](tasks.md).
- Статус и доказательства проверки → [audit-blocks.md](audit-blocks.md) по [правилам аудита](audit-rules.md).
- Для обслуживания и передачи работы → [agent-workflow.md](agent-workflow.md). Не переносить старый результат автоматически на новую версию.

## 🔴 Открытые задачи — в этом порядке

Единственная текущая очередь проверок — [реестр](audit-blocks.md), текущая разработка — [tasks.md](tasks.md). Здесь нет второго списка, который мог бы расходиться с ними. Реальные телефоны, внешние провайдеры и другие неподтверждённые пункты закрываются только своими доказательствами.

## Файлы мозга

| Задача | Источник |
| --- | --- |
| Где устроены экраны, навигация и данные | [architecture.md](architecture.md), [system-design.md](system-design.md) — общая схема; реализацию сверять с кодом |
| Стиль, шкалы, карта и анимации | [design.md](design.md), [map-design.md](map-design.md), [android-references.md](android-references.md) |
| Русский/башкирский и согласованные термины | [glossary-ba.md](glossary-ba.md), [glossary.md](glossary.md); новые переводы — в [tasks.md](tasks.md) |
| Серверный код и контракты | [backend.md](backend.md), [README сервера](../backend/README.md), [route-tracking-contract.md](route-tracking-contract.md) |
| Как и чем проверять | [qa.md](qa.md), [testing.md](testing.md), [testing-prompt.md](testing-prompt.md), [гейт готовности](../.claude/skills/gotovo/SKILL.md) |
| Что считается полной приёмкой | [audit-rules.md](audit-rules.md), [составы и цепочки доказательств](audit-traceability/README.md) |
| Два настоящих телефона | [e2e-2phones-checklist.md](e2e-2phones-checklist.md), [wave1-phone-check.md](wave1-phone-check.md) |
| Окружения, сервер и миграции | [server.md](server.md), [staging.md](staging.md), [deploy-migrations.md](deploy-migrations.md), [release-runbook.md](release-runbook.md) — сначала границы применимости |
| Уведомления и способы входа | [fcm-setup.md](fcm-setup.md), [telegram-setup.md](telegram-setup.md) — настройка не доказывает живую доставку/вход |
| Доверие, безопасность и личные данные | [trust-safety.md](trust-safety.md), [trust-levels-backend.md](trust-levels-backend.md), [правила проекта](../AGENTS.md) |
| Денежный учёт и продуктовые планы | [payments-ledger-backend.md](payments-ledger-backend.md), [monetization.md](monetization.md), [launch-readiness.md](launch-readiness.md); старые расчёты не являются текущим тарифом или разрешением на платежи |
| Реклама и партнёры | [ads-system.md](ads-system.md), [partner-sales.md](partner-sales.md) |

## Продолжение аудита, качества и безопасности

Начинай с текущего [реестра](audit-blocks.md), [карты кода](architecture.md) и правил проверки выше. Августовские handoff и старые слова «проверено и чисто» — история, которую можно использовать после проверки применимости. Они не предписывают заново выполнять уже завершённые шаги и не отменяют новые OPEN.

## Исторические материалы

- [Прежнее полное оглавление и новости июня–октября](archive/index-history-through-2026-10.md): вся прежняя информация сохранена, включая ссылки на подробные аудиты и расчёты.
- [Июльский разбор старых веток](archive/branch-cleanup-2026-07.md).
- [Передача работы 08.08.2026](archive/handoff-2026-08-08.md).
- [Передача работы 24.09.2026](archive/HANDOFF-2026-09-24.md).
- [Аудиторский журнал](audit-journal.md), [подробный исторический отчёт](audit-main-full-report.md), [дизайн-аудит](audit-design-review.md). Даты/версии и ссылки на доказательства сохраняют свой исходный смысл.

## Проект в двух словах

Юлдаш — попутки между своими, русский и башкирский. Рабочие части: `android/` (Kotlin/Compose), `backend/` (FastAPI), `webapp/` (PWA), `web/` (лендинг). Старый Flutter `mobile/` не изменяется. Конкретную готовность, ограничения и следующий проверяемый шаг смотри в реестре, а не в старой новости.
