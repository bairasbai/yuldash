# Tried/SQL commit: два различимых исхода, только диагностика

База 3fdcf93; test/friday-taxi-tried-rollback-postgres-20261118. Runtime не менять. Нынешняя SQLite диагностика выполняет real UPDATE/Redis EXEC, но заменяет commit исключением; новый пакет докажет конкретные PostgreSQL исходы, не устранение ограничения.

## План до кода

1. Только существующий guarded disposable PG runner. В собственной explicit connection создать TEMP tables с deferred FK/unique constraint, выполнить preparation commit. В транзакции настоящего matcher создать нарушение отложенного constraint; actual Redis EXEC выполняется, actual COMMIT отвергается самим PostgreSQL. Не подменять commit в этом case. После rollback независимая Session подтверждает searching/старый revision/нет offer, Redis member+TTL сохранились, transport не вызывался.
2. В том же lifecycle повторная попытка использует настоящий exclude из _tried_set и настоящее candidates фильтрование (не константный candidates, игнорирующий exclude). Доказать отсутствие выбора ранее попытанного водителя после DB rollback. Доступность остальных eligibility/GEO подготовить fixture или изолировать только внешний presence источник, не убрать tested exclude.
3. Второй outcome: wrapper выполняет настоящий commit, затем бросает synthetic exception (lost acknowledgement модель). Независимый SQL confirms offered+token/recipient, Redis history сохраняется; caller rollback после ошибки не отменяет уже committed offer. Это НЕ реальный network timeout/не вероятностная оценка.
4. Legacy NULL и nonNULL lifecycle в обоих outcomes, собственные ключи/заказы/temporary tables cleanup. Actual PG, fakeredis EXEC, transport spies. PASS наблюдает известные ограничения, не runtime защиту.
5. Независимый план/code review, адресные SQLite tests без новой runtime логики, последовательные авторский и независимый disposable PG, отдельная публикация. Без нового backend FULL при неизменном runtime; старый FULL7333 не выдавать за новые PG тесты.

## Почему пока без компенсации

SREM/DELETE не безопасны: duplicate/prior membership, concurrent writer того же lifecycle, uncertain EXEC/COMMIT outcomes. SQL read затем SREM не атомарны. Перенос EXEC послеcommit открывает decline-before-history. Не добавлять operation token/outbox/новую схему без полного протокола.

Будущее решение: tried как best-effort attempted candidates (нынешняя доступность может страдать после rollback) либо строгая committed-offer history с отдельным источником истины. Этот пакет не принимает новую продуктовую политику, не меняет default flag, production, real existing DB или paused операции. Shared other projects untouched.

## Pre-code review

Journal принял 795fce7 с условиями: одна physical connection/PID для TEMP setup и matcher; явный ON COMMIT PRESERVE ROWS, deferred FK, setupcommit до нарушения. INSERT succeeds, actual EXEC observed, actual COMMIT SQLSTATE23503, не fixture/flush failure и не mocked commit. После rollback independent connection/PID читает SQL, transport без вызовов. Retry same captured key/lifecycle, real candidates исключает driver; baseline без exclude должен реально возвращать его как eligible. Retained member проверять ДО retry, поскольку его настоящий expiry cleanup может удалить ключ. LostACK explicitly model realcommit→raise, после rollback independent SQL сохраняет offered/token/recipient. Cleanup только собственной TEMP connection; real Redis/network/exactlyonce не заявлять.

## Кандидат и первые результаты

6020ebb: новый четырёх-case PG файл, runner расширен с 21 до 25. Независимый static review PASS до первого запуска: runtime/schema unchanged, Ruff F/bash syntax/diff PASS. SQLite regressions: 44 PASS, 4 skipped, exit 0, 1.84 с; новые PG cases именно skipped, не заявлены пройденными SQLite.

Авторский guarded disposable PostgreSQL: 25 PASS, exit 0, 3.34 с, `/tmp/taxi-tried-rollback-pg.log` и `.exit`, cleanup подтверждён отсутствием временных кластеров. Actual COMMIT PostgreSQL отвергает deferred FK (23503), независимый observer видит searching, но fakeredis tried сохраняется; настоящий candidates/exclude на retry исключает единственного поставленного eligible driver, и retry истекает. Положительный контроль без exclude находил этого водителя. Lost ACK case отдельно моделирует successful actual commit + Python exception, после rollback offered сохраняется. Эти PASS подтверждают два разных исхода и границу доступности, НЕ исправление и НЕ реальный network timeout. Независимый runtime повтор тестов ожидается.


Независимая приёмка 6020ebb: PG25 PASS, exit 0, 3.94 с; SQLite scoped182 PASS/4SKIP, exit 0, 4.03 с. Ruff F/diff PASS, P0/P1/P2 не обнаружены. PG cleanup подтверждён, ресурс передан Amio. Лог `/tmp/taxi-tried-rollback-independent-pg.log`. Detect-secrets четырёх файлов: 0 findings. Runtime/schema приложения неизменны, новый FULL не выполнялся, старый FULL7333 относится к базе. Новые TEMP tables существовали только в собственном disposable соединении. Реальная Redis сеть/COMMIT network timeout/компенсация/новая tried политика не проверены и не внедрены.
