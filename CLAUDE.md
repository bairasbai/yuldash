@AGENTS.md

# CLAUDE.md — только особенности Claude Code (проект Юлдаш)

> Ядро правил — `AGENTS.md` выше (его же читает Codex). Разделы §0–§13 живут там: ссылка «`CLAUDE.md` §N» в коде, тестах и `docs/` = `AGENTS.md` §N. Общее правило добавляй в `AGENTS.md`, сюда — только то, что есть лишь у Claude Code.

## Режимы по команде Александра
- **`/grill-me` — обратный режим.** Если Александр сам написал `/grill-me`, правило «без лишних вопросов» (⭐) на эту сессию отменяется: работает скилл `grill-me` — допрос перед кодом, вопросы раундами, код только после «поехали». Это его осознанный выбор для крупных задач, а не повод спрашивать по своей инициативе. Сам этот режим не включай.
- **`/gotovo`** — гейт «готово» (⭐ п.5): по изменённым частям (Android / бэкенд / PWA / лендинг) гоняет те же проверки, что CI (лендинг — только здесь), и отдаёт таблицу с выводом. Бери перед любым отчётом «сделано».

## Автоматика в `.claude/settings.json`
- После каждой правки хук `.claude/hooks/yuldash-post-edit.mjs` подсказывает: мёртвые импорты в `backend/app/` (ruff `--select F`, как CI), `Color(0x…)` и одноязычный `Text("…")` в новых строках экранов. Подсказку хука чини сразу, не жди CI.
- Запрещено (deny): правки `mobile/**`; чтение `local.properties`, `google-services.json`, `keystore.properties`, `*.jks`, `*.keystore` — в любой папке, в т.ч. в основном чекауте из worktree. Хук `.claude/hooks/yuldash-secrets-guard.mjs` не даёт команде Bash/PowerShell прочитать или скопировать эти файлы. Отказ — граница, не обходи.

## Скиллы и субагенты проекта
- Свои скиллы: `yuldash-ui` (выжимка дизайн-системы, сам при работе с UI), `orchestration` (§12 целиком, сам при параллельной работе), `gotovo`.
- Дизайн и анимации — набор Эмиля Ковальски, подгружаются сами:
  - `apple-design` — принципы Apple: пружины, перехват жеста, инерция, типографика;
  - `emil-design-eng` — полировка UI;
  - `emil-animate` / `find-animation-opportunities` / `improve-animations` / `review-animations` — построить, найти, улучшить, проверить движение;
  - `animation-vocabulary` — как называется эффект.
- `ui-ux-pro-max` — офлайн-база (98 UX-правил, палитры, типографика, гайды Jetpack Compose), работает и в облачных сессиях. Поиск от корня репозитория: `python ".claude/skills/ui-ux-pro-max/scripts/search.py" "<запрос>" --stack jetpack-compose`. Подробности — `.claude/skills/ui-ux-pro-max/UPSTREAM.md`.
- Глобальные дизайн-скиллы (`impeccable` и стилевые) — только для `web/` и `webapp/`. Экраны `android/` — `yuldash-ui` и `docs/design.md`.
- Все эти скиллы написаны под веб и стоят ниже ядра (§4): принцип и число брать можно, код — нет; длительности — `CanonMotion`, цвета — `Canon*`, двуязычие и `CanonSourceGuardTest` важнее. Полный список и правки — `.claude/skills/README.md`.
- Субагенты: `yuldash-qa-verify` (сборка, тесты, эмулятор — один за раз), `yuldash-security-privacy` (ключи, гео, телефоны, вход, SOS, платежи), `yuldash-bashkir-linguist` (живой башкирский → `docs/tasks.md`). Ростер — `.claude/agents/README.md`.
- Параллельная работа — нативные `Agent` с `isolation: "worktree"` и `Workflow`, а не «два окна вручную» (скилл `orchestration`).
