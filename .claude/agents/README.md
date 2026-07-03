# 👥 Команда агентов Юлдаша

Специализированные суб-агенты Claude Code под разработку Юлдаша. Настроены под зоны §12 CLAUDE.md и уроки из эталонных репо (`docs/android-references.md`).

## Как звать
- **Сам** (принудительно): «через агента `yuldash-compose-ui` сделай экран X».
- **Авто:** Claude сам подберёт агента под задачу по полю `description`.
- **Параллельно** (изоляция): запускай нескольких через worktree (§12.C) — лид сводит и собирает **один раз**.

## Ростер (зона → файлы → эталон)

| Агент | Зона §12 | Файлы | Эталон |
| --- | --- | --- | --- |
| **yuldash-compose-ui** | 🎨 UI | `MainActivity.kt`, `*Screen.kt`, `UiKit.kt`, `CanonTokens.kt`, `AppText.kt` | nowinandroid, tivi, awesome-android-ui |
| **yuldash-android-architect** | 🧠 state/data | `YuldashViewModel.kt`, `Domain.kt`, `Mocks.kt`, `data/` | architecture-samples, nowinandroid, Pokedex |
| **yuldash-backend** | 🔌 backend | `backend/` | REST/realtime |
| **yuldash-security-privacy** | 🔒 сквозная | ключи, гео, телефон, авторизация, SOS, платежи | Signal |
| **yuldash-maps-geo** | 🗺 карта | `TripLocationService.kt`, `data/*Socket.kt`, `GeocoderClient.kt`, `data/TripLocationBus.kt` | uamp |
| **yuldash-qa-verify** | 🧪 гейт | сборка, тесты, эмулятор | architecture-samples, Pokedex |
| **yuldash-bashkir-linguist** | 🌐 язык | строки BA → `docs/tasks.md` | CLAUDE.md §3/§9 |

## Золотое правило параллели (§12)
**Один файл — один агент в один момент.** Весь UI в `MainActivity.kt` — если его правят двое, последний затирает первого. Параллелить безопасно только непересекающиеся зоны: UI ↔ `backend/` ↔ `docs/` ↔ `res/`. **Сборка одна и эмулятор один** — их держит один агент за раз (обычно `yuldash-qa-verify`).

## Пересечения (согласовывать)
- `MapScreen.kt`: визуал — `compose-ui`, гео/маршрут/камера — `maps-geo`.
- Контракты `data/*Socket.kt` ↔ `backend/`: architect и backend синхронят форму сообщений + `docs/route-tracking-contract.md`.
- `security-privacy` — сквозной: подключается к любой зоне, где личные данные/ключи; замки §12 соблюдает.

## Общий стандарт «готово» (у всех)
Два языка (`appText`) · токены `Canon*` · все состояния (загрузка/пусто/ошибка) · анимации уровня iPhone · зелёная сборка · обновлён «второй мозг» (`docs/`). Детали — в каждом агенте и в `CLAUDE.md`.
