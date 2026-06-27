# 🧪 QA и эмулятор Юлдаш

> Команды для сборки, запуска на эмуляторе и проверки. PowerShell.
> Перенесено из старого `CONTINUE_FOR_AI.md`.

## 🧪 Автотесты бэкенда (pytest, 2026-06-27)
- Файлы: `backend/tests/` (`conftest.py` — изолированная SQLite, прод не трогает; `test_api.py` — 9 тестов).
- Покрывают: вход/доступ, поездки, овербукинг, доступ к чату (посторонний→403), блокировки, `/secure/docs`, `/push/register`, WS отклоняет не-участника.
- На сервере: `ssh root@85.239.52.55 "cd /opt/yuldash && ./.venv/bin/python -m pytest tests/ -q"` (последний прогон: 9 passed).
- CI: `.github/workflows/ci.yml` — авто-прогон при push (заработает, когда проект будет на GitHub).
- ⚠️ Локально без deps не запустятся (нет fastapi/jose в системном python) — гонять на сервере или `pip install -r backend/requirements-dev.txt`.

## Сборка (JBR из Android Studio)

```powershell
cd C:\Users\Bayra\Yuldash\android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug --no-daemon
```

Последняя проверенная сборка: `BUILD SUCCESSFUL`.

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
