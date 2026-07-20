# 🧪 QA и эмулятор Юлдаш

> Команды для сборки, запуска на эмуляторе и проверки. PowerShell.
> Перенесено из старого `CONTINUE_FOR_AI.md`.

## 🧪 Автотесты бэкенда (pytest, 2026-06-27)
- Файлы: `backend/tests/` (`conftest.py` — изолированная SQLite, прод не трогает; `test_api.py` — 9 тестов).
- Покрывают: вход/доступ, поездки, овербукинг, доступ к чату (посторонний→403), блокировки, `/secure/docs`, `/push/register`, WS отклоняет не-участника.
- На сервере: `ssh root@85.239.52.55 "cd /opt/yuldash && ./.venv/bin/python -m pytest tests/ -q"` (последний прогон: 9 passed).
- CI: `.github/workflows/ci.yml` — авто-прогон при push (заработает, когда проект будет на GitHub).
- ⚠️ Локально без deps не запустятся (нет fastapi/jose в системном python) — гонять на сервере или `pip install -r backend/requirements-dev.txt`.

## ☁️ Сборка APK в облаке (GitHub CI) — ОСНОВНОЙ способ сейчас

> Работа идёт в облачных сессиях Claude Code. Там **Android APK не собрать**: сетевая
> политика окружения блокирует `dl.google.com` (403) — это и Android SDK, и Google-репозиторий
> (AGP/AndroidX/Compose/Firebase). Поэтому сборку приложения делает **GitHub Actions** на своих
> раннерах (у них доступ к Google есть). Бэкенд в облаке собирается/тестируется обычно (pypi открыт).

**Как это работает (автоматически):**
- Workflow `.github/workflows/android.yml` (job `build`) при каждом пуше в `android/**` собирает
  `:app:assembleDebug` на `ubuntu-latest`: ставит JDK 17 (`setup-java`) + Android SDK (`setup-android`),
  качает Gradle 8.13 и зависимости, компилирует, пакует APK. ~9 минут.
- Бэкенд — отдельный `.github/workflows/ci.yml` (job `backend-tests`, pytest).
- ⚠️ Фильтр по путям: пуш ТОЛЬКО в `docs/`/`backend/` не запускает Android-джобу (экономия минут) — это норма.

**Собралось или нет:** смотри галочку у джобы **build** в PR или у коммита (зелёная ✔ = APK собран).
Красная → открой лог джобы (ищи строки `e: …Kotlin` — ошибки компиляции) и чини.

**Скачать готовый APK (поставить на телефон):**
1. GitHub → репозиторий `bairasbai/yuldash` → вкладка **Actions**.
2. Открой нужный прогон workflow **Android** (по коммиту/PR).
3. Внизу страницы прогона блок **Artifacts** → скачай **`app-debug-apk`** (внутри `app-debug.apk`).
4. Перекинь на телефон, разреши «установку из неизвестных источников», поставь. Это **debug**-сборка
   (не для Play, но для проверки на устройстве — то что нужно).

**Пересобрать вручную:** любой коммит с изменением в `android/**` триггерит заново; либо на странице
прогона кнопка **Re-run jobs**.

**Релиз для Play (подписанный AAB/APK) — НЕ в CI:** нужен keystore (`android/yuldash.jks` +
`keystore.properties`, оба НЕ в git, только у Александра). Собирается локально (см. ниже). В облаке/CI
релиз не делаем — нет секретов подписи (при желании позже можно добавить их в GitHub Secrets).

## Сборка локально (JBR из Android Studio) — для релиза/подписи и офлайна

> В облаке Claude так НЕ собрать (Google заблокирован политикой). Это для машины Александра с Android SDK.
> Для обычной проверки debug-APK проще взять артефакт из CI (раздел выше).

```powershell
cd C:\Users\Bayra\Yuldash\android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug --no-daemon
```

Релиз (подписанный, для Play): `.\gradlew.bat :app:assembleRelease :app:bundleRelease` (нужны `yuldash.jks` + `keystore.properties`).

Последняя проверенная сборка: `BUILD SUCCESSFUL` (в т.ч. на GitHub CI, см. выше).

## ADB и эмулятор

```powershell
$adb='C:\Users\Bayra\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices
```

Прошлый эмулятор: `emulator-5554` (Pixel 5, API 37.0, экран `1080x2340` — координаты тапов ниже под этот размер).

Установить debug APK:

```powershell
& $adb -s emulator-5554 install -r C:\Users\Bayra\Yuldash\android\app\build\outputs\apk\debug\app-debug.apk
```

Запустить приложение:

```powershell
& $adb -s emulator-5554 shell monkey -p com.yuldash.app 1
```

Очистить данные (чтобы проверить первый запуск + онбординг заново):

```powershell
& $adb -s emulator-5554 shell pm clear com.yuldash.app
& $adb -s emulator-5554 shell monkey -p com.yuldash.app 1
```

## Диагностика

UI-dump (снимок экрана в XML):

```powershell
& $adb -s emulator-5554 shell uiautomator dump /sdcard/window.xml
& $adb -s emulator-5554 pull /sdcard/window.xml C:\Users\Bayra\Yuldash\android\smoke-window.xml
```

Поиск текста в dump:

```powershell
Select-String -Path C:\Users\Bayra\Yuldash\android\smoke-window.xml -Pattern "Куда поедем|Мои заявки|Уведомлений нет"
```

Проверить отсутствие краша в логах:

```powershell
& $adb -s emulator-5554 logcat -d -t 500 | Select-String -Pattern 'FATAL EXCEPTION|Process: com.yuldash.app'
```

Нет вывода → fatal-краша по фильтру нет.

## Smoke-тест (проходился, координаты для 1080x2340)

> Координаты тапов хрупкие — при изменении UI могут промахнуться. Сверяйся с UI-dump.

1. Логин: `input tap 540 1380` → главная (`Куда поедем`, `Ближайшие поездки`).
2. Вкладка «Заявка»: `input tap 540 2250` → `Мои заявки`, `Посмотреть отклики`, `Создать новую`.
3. «Посмотреть отклики»: `input tap 540 930` → экран `Чат`.
4. Системный таб в чате: `input tap 930 460` → `Уведомления` + кнопка `Очистить всё`.
5. «Очистить всё»: `input tap 850 310` → `Уведомлений нет`. (Старая `850 225` промахивалась; кнопка ~`[642,253][1036,363]`.)

Пример тапа целиком:

```powershell
$adb='C:\Users\Bayra\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5554 shell input tap 540 1380
```

## Проверки по коду

Пустые обработчики (должно быть пусто):

```powershell
rg "onClick\s*=\s*\{\s*\}|onSecondary\s*=\s*\{\s*\}|onSelect\s*=\s*\{\s*\}|TODO|NotImplemented|throw UnsupportedOperationException" C:\Users\Bayra\Yuldash\android\app\src\main\java
```

Старая привязка нижней навигации к `onBack()` (должно быть пусто):

```powershell
rg "bottomBar = \{ YuldashBottomBar\(selectedTab = HomeTab\.(Chat|Profile), onSelect = \{ onBack\(\) \}\)" C:\Users\Bayra\Yuldash\android\app\src\main\java
```
