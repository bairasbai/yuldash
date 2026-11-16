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

## Фактический результат первого запуска

[Run 43896683745](https://github.com/bairasbai/yuldash/actions/runs/43896683745), commit `5499f31a7b419bd370802975e151f8bde2202996`, 16.11.2026 15:28 UTC. Ubuntu24.04 image20261111.127.1, runner2.338.2. Checkout PASS; preflight FAIL: `PermissionError: [Errno 13] Permission denied: '/dev/kvm'` при os.open(O_RDWR). KVM device exists, но доступ не получен. APK build, emulator boot, install и instrumentation SKIPPED. Это не доказательство несовместимости APK или отсутствия virtualization у GitHub.

Права устройства не изменялись, software fallback и повтор не запускались. Для продолжения требуется отдельное согласование конкретного временного предоставления runner-user доступа к /dev/kvm; затем новый preflight и ограниченный smoke. Main и repository settings не менялись. Журнал прочитан напрямую через job logs API, archives не скачивались. Actions token был Contents:read/Metadata:read. Документационный коммит не должен запускать повтор (paths filter).

## Разрешённый повтор: PASS ограниченного offline smoke

Байрас отдельно разрешил предоставление доступа к /dev/kvm только в одноразовом GitHub runner. [Run 43902023837](https://github.com/bairasbai/yuldash/actions/runs/43902023837), commit `017715d87cfa10e77b4e5cf5ddcc9ab34551c744`, 16.11.2026 15:57–16:05 UTC: SUCCESS. Применён только ACL `u:$(id -un):rw` к устройству; никаких изменений main, repository settings или личного компьютера.

Прямые job logs `133420841622` подтверждают:
- KVM_API_VERSION=12 и emulator accel-check: KVM installed and usable.
- BUILD SUCCESSFUL in 5m45s; обоим APK проверено отсутствие INTERNET/sharedUserId до установки.
- BOOT=PASS, ABI=x86_64, API=35; обе установки Success, повторная проверка installed permissions PASS.
- Exact test `com.yuldash.app.TaxiDriverCancelledInstrumentedTest#freePassengerCancel_showsNoCompensationAndRecoveryAction`: tests=1, failures=0, skipped=0; instrumentation protocol проверен, SMOKE=PASS.
- Эмулятор завершён, cleanup выполнен. APK/CI archives не скачивались.

Хеши временных offline APK из лога: debug `f89efcefb4d2fbab6edd05d79afdc1ecd1d21c56ad88032d7b1f70fe9f51e6e7`; androidTest `66e6a58fab1ad1c48077906658b2888ca8068f2176e32559778e29f52a1ec660`.

Предел доказательства: один синтетический сценарий экрана отмены такси, RU, с временным no-INTERNET manifest overlay и plain Application. Это не проверка штатной сетевой сборки, полного такси, попуток, Amio, GPS/MapKit/FCM/оплаты/физического телефона. Audit acceptance статусы не повышены. Warning Node20 actions forced Node24 отмечен в логе, но job завершился success; будущие обновления actions делать отдельным проверенным шагом.
