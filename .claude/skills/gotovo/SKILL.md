---
name: gotovo
description: Проверка Юлдаша перед словом «готово» — по изменённым частям (Android / бэкенд / PWA / лендинг) запускает те же проверки, что CI, и выдаёт таблицу с выводом. Бери перед любым отчётом «сделано» и когда Александр пишет /gotovo.
---

# /gotovo — гейт «готово»

Задача не готова, пока здесь не зелено. Только реальные прогоны с выводом: не смог запустить —
так и пиши, какую проверку и почему. Сборка и эмулятор — один агент за раз (скилл `orchestration`).

## 1. Что изменилось

```bash
git status --short
git diff --name-only origin/main...HEAD
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

**Android** — из `android/`, PowerShell:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "*GuardTest" --tests "<тесты затронутых экранов>"
```
`*GuardTest` — ~30 сторожей правил, читают исходники и идут быстро: двуязычие, шкалы `Canon*`,
контраст, тон «ты», тач-цели, деньги, приватность. Полный набор (~1100 тестов Robolectric, долго)
гоняет CI; локально — сторожа и тесты затронутых экранов. Тестам нужна Java 21 (MapKit собран под неё;
`jbr` Android Studio — 21).
На экран — эмулятор (агент `yuldash-qa-verify`): открывается, «Назад» работает, есть
загрузка / пусто / ошибка, обе темы, оба языка.

**Бэкенд** — из `backend/`:
```bash
ruff check app/ --select F
python -m pytest tests/ -q --maxfail=25
```
Долго — сначала тесты затронутых модулей, потом весь набор.

**PWA** — `npm run check --prefix webapp` и `npm run build --prefix webapp`.
**Лендинг** — `npm run lint --prefix web` и `npm run build --prefix web`.

## 3. Правила проекта (не проверяются компилятором)

- Новые надписи — `appText(ru, ba)`; черновой башкирский — в `docs/tasks.md`, «Переводы на проверку».
- Новый экран — `enum Screen` + ветка в `YuldashApp()` + строка в `docs/architecture.md`.
- Второй мозг: решение → `docs/decisions.md`, грабли → `docs/lessons.md`, прогресс → `docs/tasks.md`.
- `mobile/` не тронут, ключей в diff нет: `git diff origin/main...HEAD | grep -iE "api[_-]?key|secret|token"`.

## 4. Отчёт

Таблица: часть · команда · результат (строка `BUILD SUCCESSFUL` / `N passed` / код выхода).
Красное — не «готово»: чини или прямо скажи Александру, что осталось и почему.
