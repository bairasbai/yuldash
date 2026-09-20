# Android: повреждённые данные входа — 19.09.2026

Проверка продолжает аудит Android-хранилища. Изменения в рабочей ветке `codex/push-routing-audit`, без коммитов и публикации. Сверен локальный main `527dbb0d2c3973899e3b6db0c6e9c4026f3da174`: `ApiClient.init` напрямую читает token/user_role через getString, без обработки неверного типа. Новая отметка auth_store_state относится к незакоммиченным исправлениям предыдущей группы, а не к старому main.

## Проблемы и исправления

1. Если вместо строки роли/ключа сохранено значение другого типа, обычный SharedPreferences вызывает ClassCastException. Приложение может упасть при запуске; поля в памяти могут быть присвоены лишь частично. У EncryptedSharedPreferences обнаружено другое поведение: getString возвращает null, и аккаунт оставался активным с потерянной ролью. Теперь политика проверяет все поля выбранной сессии до передачи в ApiClient, различая отсутствующую строку и присутствующее значение другого типа.
2. Повреждённая отметка источника раньше оставляла prefs=null: приложение становилось гостевым, но последующий вход нельзя было сохранить. Теперь повреждённая сессия очищается, записывается logout и возвращается проверенное пустое хранилище для нового входа.
3. Ошибка расшифровки отдельной записи может возникать после успешного открытия самого хранилища. Она обрабатывается как повреждение сессии. После очистки поля проверяются снова: постоянно нечитаемое хранилище не возвращается клиенту. В этом случае новый вход всё ещё может быть недоступен; физический отказ Keystore не объявляется исправленным.

Очистка затрагивает поля из SessionKeys.CLEARED_ON_LOGOUT. Ошибки сохранения не скрываются и не считаются успешным восстановлением. Исправные данные выбранного secure не сбрасываются из-за повреждённого устаревшего plain.

Продуктовое изменение ограничено `android/.../data/AuthStorePolicy.kt`. Новые проверки — `AuthStorageCorruptionTest.kt`, дополнительная фаза corruption — в `AuthStorageProcessInstrumentedTest.kt`. Интерфейс и пользовательские надписи не менялись.

## Доказательства

Файлы находятся в `C:\Users\Bayra\Yuldash\test-results`:

- `android-auth-corruption-red-20260919.log/.xml`: 6 новых тестов, все 6 упали до исправления. Четыре типа результата: ClassCastException, SecurityException, невозможность записи нового входа (IllegalStateException), сохранение повреждённого аккаунта вместо гостевого состояния (assertion).
- `android-auth-corruption-green-20260919.log`: первый профиль и assembleDebug прошли.
- `android-auth-corruption-persistent-red-20260919.log/.xml`: после добавления двух проверок 7 из 8 прошли; постоянная нечитаемость выявила недостающую повторную проверку после очистки. Она добавлена.
- `android-auth-corruption-final-green-20260919.log`: промежуточный профиль 80 тестов / 8 классов и оба APK прошли, но это ещё не окончательный результат.
- `android-storage-corruption-verify-corruption-20260919.log`: на реальном EncryptedSharedPreferences эмулятора повреждённая роль не вызывала исключение и оставляла вход активным. Это RED уже после первоначального исправления. Добавлены проверка типа присутствующего значения и девятый модульный тест модели такого поведения. Три прежних сценария хранения в том же запуске прошли.

Окончательный профиль: **81 тест / 8 классов, 0 failures/errors/skipped**; новые проверки повреждений **9/9**. Сумма по XML: 3 интеграции + 12 авторизации + 18 временных отказов + 10 повторов refresh + 4 границ сессии + 9 повреждений + 16 политики хранилища + 9 очистки = 81. Лог `android-auth-corruption-verified-green-20260919.log`, подсчёт `android-auth-corruption-verified-counts-20260919.json`, XML `android-auth-corruption-verified-xml-20260919`. `assembleDebug` и `assembleDebugAndroidTest` успешны, итоговый запуск Gradle занял 1 минуту 6 секунд. Полный набор всех Android-тестов этой группы не запускался.

На эмуляторе API 35 с настоящими EncryptedSharedPreferences/Android Keystore **4 сценария / 8 фаз прошли**. Для нового сценария процесс 4094 записал числовую роль вместо строки в зашифрованное хранилище; после force-stop процесс 4151 успешно запустил ApiClient, подтвердил гостевое состояние и очистку повреждённых полей, затем сохранил новый тестовый ключ и загрузил его повторным init. Дополнительно повторены перенос роли (4207→4260), выбор актуального аккаунта (4314→4368) и выход (4419→4471). Итог `android-storage-corruption-final-20260919.json`, логи `android-storage-corruption-final-*.log`, хеши APK `android-auth-corruption-apk-hashes-20260919.json`.

Команда из `android/`:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.yuldash.app.data.AuthStorageCorruptionTest --tests com.yuldash.app.data.AuthStorePolicyTest --tests com.yuldash.app.data.ApiClientAuthStorageIntegrationTest --tests 'com.yuldash.app.data.ApiClient*Auth*Test' --tests 'com.yuldash.app.data.ApiClientRefresh*Test' --tests com.yuldash.app.SessionKeysGuardTest :app:assembleDebug :app:assembleDebugAndroidTest '-PstorageAuditRunner=com.yuldash.app.StorageAuditRunner' --no-daemon
```

Фазы запускались сохранённым `run-android-storage-green-20260919.ps1 -Stage corruption-final -Scenarios @('corruption','migration','authority','logout')`. Внешняя сеть при окончательном прогоне выключена (`Active default network: none`), полный интерфейс не запускался. Эмулятор — временный read-only, без сохранения снимка.

## Границы

Повреждения создаются намеренно: неверные типы в настоящих SharedPreferences, модель SecurityException для зашифрованной записи. Это не доказательство физического повреждения ключей на телефоне. Общий процент проверенности приложения не вычислялся; прошлый полный прогон 1756 тестов относится к предыдущей версии. Настоящие платежи, FCM и все пользовательские маршруты этой группой не проверяются.
