# Код пассажира после параллельного ответа статуса

02.11.2026, Root. Узкое дополнение принятого offline4. Все изменения только в текущей ветке; main/push/prod/деньги не разрешены.

## Подтверждённая граница

Source11: 877 входов, aggregate `5be727b066d78eb3c050c5d054aa2f2969c38afe2af0b6198544304ceaae4f0e`; основной код совпадает с source10. Actual offline-target03: original1, 515=511PASS+4FAIL. Три Contacts RED — CODE expected1/actual2 (394), DETAILS expected1/actual2 (460,634). Journey223 — отдельная причина, этим планом не закрывается.

По коду первый DETAILS может начать чтение раньше параллельного ROLE, а завершить позже него. SnapshotAuthorityGate допускает совместимый первый DETAILS. Passenger attachCode затем повторно предъявляет старый DETAILS proof в acceptRead; более новый ROLE уже сделал его непригодным для повторного принятия, и Hooks.code повторяет DETAILS/CODE. Барьер HTTP-ответа не упорядочивает момент выдачи read-ticket. Полный фактический порядок ещё подтвердить тестом с управляемыми HTTP-барьерами и безопасным журналом; одного чтения исходников недостаточно.

## Что требуется

1. Сохранить существующие точные счётчики Contacts и прежние 15с, authority/private/history/disk assertions. Добавить узкое воспроизведение DETAILS-start → ROLE-complete → DETAILS-complete → CODE на настоящих MockWebServer/API; получить настоящий RED до main-правки. Не подменять proof/permit из произвольного DTO, кеша или тестового конструктора.
2. Свежий независимый агент проверяет план до main-кода. После freeze и завершения текущей R8 Root разрешает только необходимый тест и затем узкое изменение пассажирской ветки attachCode. Кандидат решения — принимать фактически полученный CODE proof для обновления fingerprint уже проверенной текущей DETAILS, сохраняя original DETAILS identity, clock/stamp и срок действия. Это кандидат, а не требование принять небезопасный proof; итог определяется проверкой инвариантов.
3. Не ослаблять current owner/origin/session/process, booking/epoch, exact projection identity, fresh/private DETAILS, роль passenger, порядок новых/старых ответов, terminal/403 и raw disk ограничения. Код водителя остаётся только в памяти, не сохраняется в TripPass. CODE не предоставляет доступ сам по себе, не продлевает TTL, не восстанавливает старую DETAILS после отмены/смены аккаунта/истечения срока. Нужны положительный race, неизменный исходный TTL и отрицательные late/epoch/owner случаи; существующие соответствующие тесты повторить.
4. Те же RED → GREEN без изменения ожиданий, затем affected515/guards/assembleDebug/compileAndroidTest. Journey отдельно расследовать с рабочей redacted диагностикой. Общий offline принимается только после полного зелёного набора, требуемой R8, настоящего нового процесса/HTTP/UI и свежей независимой приёмки. Если меняется main после R8 source11, результат R8 честно исторический до повторной сборки.

Источник исходного результата: test-results/legacy-reconcile-20261102/offline-target03-results.json и архив XML SHA `3b283b7d08ac9665a43b0225f93edb45033664101cfc4b0c61926ab67675065f`. Статусы — только docs/audit-blocks.md.
