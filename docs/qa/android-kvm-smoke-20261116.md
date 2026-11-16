# Android KVM offline smoke, 16.11.2026

Разрешённый Байрасом ограниченный эксперимент. База ecfc6aaeecf2cb8f6498eb9eb198dcef5e8679f8. Только отдельная ветка ci/android-kvm-smoke-20261116, без main/production/реальных данных, без изменения repository settings.

План: preflight KVM → offline debug APK + test APK → API35 x86_64 AVD boot (300 s) → install → ровно один fixture test. Общий timeout30 минут, без матрицы и повторов без диагностики. Стандартный Ubuntu24.04 runner публичного репозитория; larger runners не используются. APK/log archives не загружаются; verdict читается напрямую в job logs. Сборка и артефакты временны.

Обычный debug в репозитории НЕ меняется: только внутри job создаются manifest overlays, удаляющие INTERNET у обоих APK. aapt2 проверяет permissions и отсутствие sharedUserId до install, проверяются также установленные пакеты. Используется существующий StorageAuditRunner с plain Application и новый AVD. Это smoke offline fixture, не проверка штатной сетевой сборки, MapKit/FCM/GPS/фоновых служб. Пользовательские/production credentials не передаются; local.properties не читается.

Test: TaxiDriverCancelledInstrumentedTest#freePassengerCancel_showsNoCompensationAndRecoveryAction. Данные синтетические, нет входа, кликов, внешних intents и сети. Parser требует exact identity, numtests1, successful status и finish; пропуск не pass.

Запуск: push только в указанную feature-ветку и только при изменении workflow/script. Не workflow_dispatch (его регистрация потребовала бы default branch) и не PR, который запустил бы общий CI. Публикация этого пакета запускает эксперимент; обновление только документации его не повторяет.

Источники actions закреплены SHA: checkout v4 11d5960a326750d5838078e36cf38b85af677262, setup-java v4 cf277c60eb25467037889841efdb72551f06f6c3, setup-android v3 9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407, ReactiveCircus android-emulator-runner v2 a421e43855164a8197daf9d8d40fe71c6996bb0d. Checkout credentials не сохраняются; token contents:read. KVM права не изменяются: недоступен → fail, не software fallback.

- [GitHub runners/KVM](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
- [Push workflow](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#push)
- [Billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions)
- [Emulator action](https://github.com/ReactiveCircus/android-emulator-runner/tree/a421e43855164a8197daf9d8d40fe71c6996bb0d)

Статус до публикации: YAML parsed, bash syntax checked; runtime ещё не выполнен. Финальный verdict определяется job logs на точном SHA, не прежним CI. Никакие audit acceptance статусы не повышаются.
