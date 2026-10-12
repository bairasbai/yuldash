# Передача Claude Code — срез 12.10.2026

Пользователь поручил закончить текущий этап, проверить, влить в main, отправить на GitHub и пока остановиться. После доставки новые пункты Codex не начинает. Claude может продолжить по новому явному поручению. Полная готовность к релизу не подтверждена.

## Начать здесь

1. Прочитать AGENTS.md, docs/00-INDEX.md, docs/architecture.md и **docs/audit-blocks.md**. Последний — единственный реестр очереди; этот файл является инструкцией запуска. Историческая передача 01.10.2026 не описывает текущий срез.
2. Проверить git status, HEAD/main/origin/main и существующие worktrees локально. Исходная база этого этапа `d07909a6982e818c371e943b13fe1c5ee12a9c20`, ветка `codex/b02-cache-invalidation-20261012`. Итоговый SHA и подтверждение удалённого main — в `test-results/b02-cache-invalidation-git-publication-20261012.json`; этот документ создаётся до commit, поэтому заранее SHA результата не присвоен. На другом компьютере найти коммит добавления этого файла через git log, не откатывать более новый main.
3. Проверить `python -X utf8 tools/audit_inventory.py --root C:/Users/Bayra/Yuldash --check`. Canonical snapshot1897. В общей папке ожидается только известная чужая .gitignore delta (exit1); чистый publication checkout даёт exit0. Любую другую дельту исследовать.
4. Выбрать первый доступный незакрытый критерий реестра после проверки доступности окружения. Прямой локальный остаток данного участка — direct consumers старого Result/onSuccess и late owner callbacks остальных setters/referral; затем uncertain generic mutation reply. Не считать это новой параллельной очередью.

## Что завершено в этом этапе

В ApiClient.kt только invalidate/cachedGet: версия каждого ключа переживает удаление кеша. До изменения14из33 новых тестов воспроизводили rollback; после33 прошли. Семь real public API mutations выполнялись только на loopback. Background language child наблюдался и был реально joined, publicUnit не выдавался за Result. Старый same-owner Result сохранён; новая версия запрещает общий cache/onValue от снимка до successful invalidation.

Итог **402 JVM в31классе +90 native API35 =492 разных ID**, из них66new (33+33),426retained (369+57). Все0failures/errors/skips/staleXML. Полный исторический набор не запускался. Обе debug APK успешны; source/run/native/hash доказательства разные и не заменяют друг друга.

- Runtime/source/команда: `test-results/b02-cache-invalidation-final-20261012/result.json`, `test-results/b02-cache-invalidation-native-20261012/result.json` и их XML/raw instrumentation.log.
- Версия и frozenfiles: `test-results/b02-cache-invalidation-final-checkpoint-20261012.json`; independent scoped receipt: `test-results/b02-cache-invalidation-independent-20261012.json`.
- APK SHA256: app `273ca12149f8bb3eee66f433d0a0fd3cec136817706d1c6c67f7a9f6c7562604`; tests `fb1318d3bb8bc7155b3e1f46a87cd675c930eda3284658bf508956631fcc8381`.
- Все31PNG декодированы,7selectedrootviewed; новые33native проверяют API/jobs и не являются новым native App reentry UI.

`test-results/` не коммитится. На этой машине доказательства доступны локально. Если файлы отсутствуют в другой копии, не выдавать старый отчёт за новую проверку: восстановить профиль и сохранить новые свидетельства.

## Границы, которые остаются открытыми

QA-B02-003 и весь B02 остаются частичными. Direct caller всё ещё может получить старый Result вне guarded onValue; late generic mutation owner callbacks и reconciliation после неизвестного servercommit не закрыты. Исторические14сбоев не объявлены единственной установленной причиной. TTL80/100s — synthetic cache age, не elapsed/exact90s; clock reversal, diskflush/failure, новые native reentry/launcher/processdeath/phone, реальные providers/production, CI, release/R8/нагрузка и критерии остальных блоков остаются по реестру. Проценты общей готовности не назначать.

## Папки, чужие правки и общие ресурсы

Рабочая папка `C:/Users/Bayra/Yuldash`; `main` находится в `C:/Users/Bayra/Yuldash-audit-publication-20261001`. Перед новой работой создавать свою `codex/` ветку, учитывать все existing worktrees. Не использовать reset --hard/clean/stash или принудительную публикацию для устранения чужих файлов.

Сохранены40чужих entries:39RAWfiles и1directorypresence. Верхние status entries: `.claude/agents/yuldash-bashkir-linguist.md`, `.gitignore`, `$SCRATCH/`, `.claude/.proven-config-version`, `.claude/proven-config.json`, `docs/YULDASH-FULL-OVERVIEW.md`, `docs/archive/`. Их не коммитить и не удалять; manifest `test-results/b02-cache-invalidation-foreign-before-20261012.json`.

Одна сборка/один эмулятор/один автор файла. Root-owned emulator5580/Pixel_API35 после проверки остановлен; активного Gradle run нет при доставке. Старые записи замков являются историей; актуальный результат и STOP в конце docs/tasks.md. Не трогать mobile/ и ключи.

## Окружение и следующий прогон

Сборка: Studio JBR, Gradle wrapper; версии/dependencies не менялись. JAVA_HOME=`C:/Program Files/Android/Android Studio/jbr`. Для JVM использован JAVA_TOOL_OPTIONS=`-Djava.nio.channels.spi.SelectorProvider=sun.nio.ch.WindowsSelectorProvider`, max-workers2; debugAPIloopback5198, тесты отдельно переопределяют URL на свой MockWebServer/rawServerSocket. Настоящий yulbash.ru не использовать как тестовый стенд.

Профиль и точные selectors находятся в final result.args. Повторять после изменения зависимости/нового сбоя/итоговой интеграции, не при каждом чтении. Сохранённые runner/native/deliver helpers привязаны к этому immutable snapshot: после изменения source нельзя использовать их исторические пути/счётчики как свежий результат. Для нового прогона выделить новую phase/папку, включить оба APK и сохранить новый source map/свежие XML.

GitHub — строго по одному запросу. Перед ним `node C:/Users/Bayra/.claude/hooks/github-api-guard.js --status`; ПАУЗА/403/429/auth/rate-limit→stop без обхода/повтора. Не watch/poll. Этот этап отправляет один обычный push и один ls-remote; local-main сам по себе не доказывает remote.

## Короткое поручение для Claude

«Продолжи аудит Юлдаш из docs/audit-handoff-claude-20261012.md. Сначала проверь текущие git refs, чужие изменения и inventory, затем первый незакрытый локальный пункт docs/audit-blocks.md. Не перепроверяй неизменённый полный набор без причины и не закрывай B02/релиз без критериев. После необходимых проверок продолжай действующий workflow main/push; на production не выкатывай.»
