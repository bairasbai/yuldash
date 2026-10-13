# 🧪 QA и эмулятор Юлдаш

> Команды для сборки, запуска на эмуляторе и проверки. PowerShell.
> Перенесено из старого `CONTINUE_FOR_AI.md`.

## 🧪 Автотесты
- Что гонять перед «готово» и какими командами — гейт `/gotovo` (`.claude/skills/gotovo/SKILL.md`).
- Полный набор (тысячи тестов Android и бэкенда, порог покрытия) гоняет CI — `.github/workflows/ci.yml`: на каждый PR и на push в `main`.

## Сборка (JBR из Android Studio)

```powershell
Set-Location "$(git rev-parse --show-toplevel)\android"   # свой чекаут; в worktree — его, не основной
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"         # local.properties не нужен и не копируется
.\gradlew.bat :app:assembleDebug --no-daemon
```

## Локальный стенд (эмулятор ↔ свой сервер)

Отладочная сборка ходит на `http://10.0.2.2:8000` — это компьютер глазами эмулятора
(`android/app/build.gradle.kts`, переменная `YULDASH_DEBUG_API_BASE_URL`).

1. Сервер — из `backend/` своего чекаута: `python -m uvicorn app.main:app --port 8000`
   (подробно — `backend/README.md`).
2. На экране «Нет связи с сервером», а в логе сервера ни одного запроса — обычно брандмауэр Windows.
   Обход: `& $adb reverse tcp:8000 tcp:8000` и сборка с
   `$env:YULDASH_DEBUG_API_BASE_URL='http://localhost:8000'`. Незашифрованный HTTP в debug разрешён
   только для `10.0.2.2`, `localhost`, `127.0.0.1`, `::1`
   (`android/app/src/debug/res/xml/network_security_config.xml`) — любой другой адрес режется молча.
3. Стендовые машины такси «пропали» (`POST /instant/presence` → 200, а машин ноль) — антифрод отбросил
   «телепорт» быстрее 200 км/ч и заклинил. Перезапусти стендовый сервер; машина в скрипте должна
   кружить вокруг точки, а не ехать по прямой.
4. Карта: ключ MapKit лежит в одном месте — `android/local.properties` основной папки
   `C:\Users\Bayra\Yuldash`, копий не делаем (решение Александра: не мусорить). В worktree карта
   пустая — всё остальное проверяется там. Нужна именно карта — собери и проверь её из основной
   папки, когда твоя ветка туда влита. Сам ключ агент не читает и не копирует (§11 `AGENTS.md`).

Подробности и история — `docs/lessons.md`, поиск «Локальный стенд».


## ADB и эмулятор

```powershell
$adb='C:\Users\Bayra\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices
```

Прошлый эмулятор: `emulator-5554` (Pixel 5, API 37.0, экран `1080x2340` — координаты тапов ниже под этот размер).

Установить debug APK:

```powershell
& $adb -s emulator-5554 install -r "$(git rev-parse --show-toplevel)\android\app\build\outputs\apk\debug\app-debug.apk"   # APK своего чекаута
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
& $adb -s emulator-5554 pull /sdcard/window.xml "$env:TEMP\smoke-window.xml"
```

Поиск текста в dump:

```powershell
Select-String -Path "$env:TEMP\smoke-window.xml" -Pattern "Куда поедем|Мои заявки|Уведомлений нет"
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
rg "onClick\s*=\s*\{\s*\}|onSecondary\s*=\s*\{\s*\}|onSelect\s*=\s*\{\s*\}|TODO|NotImplemented|throw UnsupportedOperationException" android\app\src\main\java   # из корня своего чекаута
```

Старая привязка нижней навигации к `onBack()` (должно быть пусто):

```powershell
rg "bottomBar = \{ YuldashBottomBar\(selectedTab = HomeTab\.(Chat|Profile), onSelect = \{ onBack\(\) \}\)" android\app\src\main\java
```
