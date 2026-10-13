---
name: gotovo
description: Проверка Юлдаша перед словом «готово» — по изменённым частям (Android / бэкенд / PWA / лендинг) запускает те же проверки, что CI (лендинг — только здесь, в CI его нет), и выдаёт таблицу с выводом. Бери перед любым отчётом «сделано» и когда Александр пишет /gotovo.
---

# /gotovo — гейт «готово»

Задача не готова, пока здесь не зелено. Только реальные прогоны с выводом: не смог запустить —
так и пиши, какую проверку и почему. Одна Gradle-сборка и один эмулятор за раз (скилл `orchestration`).
Все команды — из **своего** чекаута: в worktree проверяй его код, а не основной `C:\Users\Bayra\Yuldash`.

## 1. Что изменилось

```bash
git status --short
git diff --name-only $(git merge-base origin/main HEAD)    # коммиты И незакоммиченное
git ls-files --others --exclude-standard                   # новые файлы
```

Разложи файлы по частям — запускай только нужные:

| Часть | Признак |
|---|---|
| Android | `android/**` |
| Бэкенд | `backend/**` |
| PWA | `webapp/**` |
| Лендинг | `web/**` |
| Только `docs/`, `.claude/` | сборка не нужна — переходи к шагу 3 |

## 2. Проверки (как в `.github/workflows/ci.yml`)

**Android** — PowerShell:
```powershell
Set-Location "$(git rev-parse --show-toplevel)\android"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"     # local.properties не нужен и не копируется
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "*GuardTest" --tests "<тесты затронутых экранов>"
```
- `*GuardTest` — ~30 сторожей правил, читают исходники и идут быстро: двуязычие, шкалы `Canon*`,
  контраст, тон «ты», тач-цели, деньги, приватность. Полный набор (3 600+ тестов Robolectric, долго,
  с порогом покрытия) гоняет CI; локально — сторожа и тесты затронутых экранов.
- `compileDebugAndroidTestKotlin` — инструментальные тесты (`androidTest`) не собирает больше никто,
  ни CI, ни unit-прогон: без этой строки они тихо ломаются.
- Трогал зависимости, правила R8/ProGuard, сериализацию или рефлексию — ещё
  `.\gradlew.bat :app:assembleRelease -Pyuldash.unsignedRelease --no-daemon` (как CI: проверка R8).
- Открыта Android Studio на этой папке — добавь `-x bundleDebugClassesToRuntimeJar`, иначе тесты упадут
  с `FileSystemException: файл занят другим процессом`.
- Тестам нужна Java 21 (MapKit собран под неё; `jbr` Android Studio — 21).
- Видимое на экране — эмулятор обязательно (⭐ п.7 `AGENTS.md`; агент `yuldash-qa-verify`, стенд — `docs/qa.md`,
  «Локальный стенд»): открывается, «Назад» работает, есть загрузка / пусто / ошибка, светлая и тёмная тема ×
  русский и башкирский. Эмулятора нет — так и напиши в отчёте.

**Бэкенд** — из `backend/`:
```bash
ruff check app/ --select F
python -m pytest tests/ -q --maxfail=25
```
- Долго — сначала тесты затронутых модулей, потом весь набор. Весь набор в CI идёт с порогом
  покрытия: `--cov=app --cov-fail-under=85`.
- Трогал `alembic/` — `alembic heads` должен показать ровно одну голову.

**PWA** — `npm ci --prefix webapp` (если нет `webapp/node_modules`, в worktree его нет), затем
`npm run check --prefix webapp` и `npm run build --prefix webapp`.
**Лендинг** — `npm ci --prefix web` (если нет `node_modules`), затем `npm run lint --prefix web`
и `npm run build --prefix web`. В CI лендинга нет — эта проверка единственная.

## 3. Правила проекта (не проверяются компилятором)

- Новые надписи — `appText(ru, ba)`; черновой башкирский — в `docs/tasks.md`, «Переводы на проверку».
- Новый экран — `enum Screen` (в `MainActivity.kt`) + ветка в `YuldashApp.kt` или `AppNav*.kt`
  (раздел «Навигация разрезана» в `docs/architecture.md`) + строка в `docs/architecture.md`.
- Второй мозг: решение → `docs/decisions.md`, грабли → `docs/lessons.md`, прогресс → `docs/tasks.md`.
- Меняли `.claude/hooks/` — `node .claude/hooks/yuldash-secrets-guard.test.mjs` зелёный.
- `mobile/` не тронут: `git diff --name-only $(git merge-base origin/main HEAD) -- mobile` — пусто.
- Ключей нет — обе команды выводят пусто (хук-сторож их пропускает):
  `git diff --name-only $(git merge-base origin/main HEAD) -- '*local.properties' '*google-services.json' '*keystore.properties' '*.jks' '*.keystore' '*.env'`
  `git ls-files --others --exclude-standard -- '*local.properties' '*google-services.json' '*keystore.properties' '*.jks' '*.keystore' '*.env'`
  и сканер чист: `detect-secrets scan <изменённые и новые файлы>` → `"results": {}`.

## 4. Независимая приёмка (⭐ п.8 `AGENTS.md`)

- **Когда:** новый экран или функция, деньги, безопасность и личные данные, прод-база, правила агентов.
  Мелкую правку (текст, отступ, одна строка) — не гоняй: дорого и медленно.
- **Кто:** свежий агент сильнейшей модели — у Claude субагент `model: "opus"` (Opus 5.5), у Codex `gpt-6.1-sol`.
  Не автор плана и без его рассуждений — иначе это «проверил сам себя».
- **Бриф** (цель + ограничения + критерий, не пошаговый план): задача Александра дословно; что изменено
  (`git diff $(git merge-base origin/main HEAD)` и новые файлы); вывод проверок шага 2. Проверить против задачи,
  ⭐ «Готово» и §3, §4, §4.5, §8, §11 `AGENTS.md`; по умолчанию «не принято», пока реальные прогоны и чтение кода
  не докажут обратное. Вернуть: «принято / не принято» + находки P0/P1/P2 с `файл:строка` и как чинить.
- **Цикл:** находки → чинишь → приёмка заново, снова свежим агентом. Не сошлось за 2 круга — скажи Александру.
- **План до кода:** для списка «ждёт «да»» (§8) и новых подсистем тот же свежий Opus сначала проверяет план.

## 5. Отчёт

Таблица: часть · команда · результат (строка `BUILD SUCCESSFUL` / `N passed` / код выхода) + итог приёмки (шаг 4).
Красное — не «готово»: чини или прямо скажи Александру, что осталось и почему (по §2 `AGENTS.md` —
простыми словами).
