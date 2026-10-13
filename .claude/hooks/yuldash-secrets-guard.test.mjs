#!/usr/bin/env node
// Таблица «запретить / пропустить» для yuldash-secrets-guard.mjs. Гонять после любой правки .claude/hooks:
//   node .claude/hooks/yuldash-secrets-guard.test.mjs
// D — хук обязан запретить, A — обязан пропустить. Случаи D — реальные обходы, найденные приёмками 13.10.2026.
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const hook = join(dirname(fileURLToPath(import.meta.url)), 'yuldash-secrets-guard.mjs');
const B = 'Bash';
const P = 'PowerShell';
const cases = [
  // — прямое чтение и копирование —
  ['D', B, 'cat android/local.properties'],
  ['D', P, 'Get-Content C:\\Users\\Bayra\\Yuldash\\android\\local.properties | Select-Object -Index 19'],
  ['D', P, 'Copy-Item ..\\..\\..\\android\\local.properties android\\'],
  ['D', B, `python -c "print(open('android/local.properties').read())"`],
  ['D', B, 'wc -c android/yuldash.jks'],
  ['D', B, 'cp /c/Users/Bayra/Yuldash/android/app/google-services.json android/app/'],
  ['D', B, 'head -3 android/keystore.properties'],
  ['D', B, 'sort android/local.properties'],
  ['D', B, 'jq . android/app/google-services.json'],
  ['D', P, 'Rename-Item android\\local.properties x.txt'],
  // — внутри присваиваний, условий, подстановок, вложенных оболочек —
  ['D', P, '$c = Get-Content android\\local.properties -Raw'],
  ['D', P, 'if (Test-Path android\\local.properties) { Get-Content android\\local.properties }'],
  ['D', B, 'echo $(cat android/local.properties)'],
  ['D', B, 'bash -c "cat android/local.properties"'],
  ['D', B, `powershell -Command "Get-Content android\\local.properties"`],
  ['D', P, "[IO.File]::ReadAllText('android\\local.properties')"],
  // — маски —
  ['D', B, 'cat android/*.properties'],
  ['D', P, 'Get-Content android\\local*'],
  ['D', B, 'head -n 5 android/*.kt android/local.propertie?'],
  ['D', P, 'Select-String -Path android\\* -Pattern MAPKIT'],
  // — рекурсивный поиск и поиск без учёта .gitignore —
  ['D', B, 'grep -rn MAPKIT android/'],
  ['D', B, 'grep -R "MAPKIT" .'],
  ['D', B, 'grep -rn X docs/../android'],
  ['D', P, 'findstr /s MAPKIT android\\*'],
  ['D', B, 'rg -uu MAPKIT android'],
  ['D', B, 'rg --unrestricted DECOY android'],
  ['D', B, 'rg -nu DECOY android'],
  ['D', B, 'git grep --no-index -n X android'],
  ['D', B, 'git grep --untracked --no-exclude-standard X'],
  ['D', P, 'Select-String -Pattern MAPKIT -Path android\\local.properties'],
  // — список файлов по конвейеру —
  ['D', P, 'Get-ChildItem -Recurse android | Select-String MAPKIT'],
  ['D', P, 'Get-ChildItem android -File | Get-Content'],
  ['D', P, 'Get-ChildItem android\\*.properties | ForEach-Object { Get-Content $_ }'],
  ['D', P, 'Get-ChildItem android -Filter *.properties | Select-String X'],
  ['D', B, 'find android -type f | xargs cat'],
  ['D', B, 'find . -exec cat {} \\;'],
  // — git, который выводит содержимое или кладёт ключ в git —
  ['D', B, 'git show HEAD:android/local.properties'],
  ['D', B, 'git diff --no-index /tmp/a android/local.properties'],
  ['D', B, 'git add -f android/local.properties'],
  // — копирование и упаковка папок, сравнение —
  ['D', B, 'diff android/local.properties /tmp/x'],
  ['D', B, 'tar czf /tmp/x.tgz android/local.properties'],
  ['D', B, 'tar cf - android | tar xOf -'],
  ['D', B, 'cp -r android /tmp/x'],
  ['D', P, 'Copy-Item -Recurse android C:\\tmp\\x'],
  ['D', P, 'robocopy android C:\\tmp\\x'],
  ['D', P, 'Compress-Archive -Path android -DestinationPath x.zip'],

  // — обычная работа: должно проходить —
  ['A', B, "git diff --name-only $(git merge-base origin/main HEAD) -- '*local.properties' '*google-services.json' '*.jks'"],
  ['A', B, "git ls-files --others --exclude-standard -- '*local.properties'"],
  ['A', B, 'git commit -q -m "chore: stop copying local.properties into worktrees"'],
  ['A', B, 'git check-ignore -v android/local.properties'],
  ['A', B, 'git status --short'],
  ['A', B, 'git grep -n Canon'],
  ['A', B, 'ls android | grep local.properties'],
  ['A', B, 'rg -n "local.properties" AGENTS.md'],
  ['A', B, 'grep -n "local.properties" AGENTS.md'],
  ['A', P, 'Select-String -Path .gitignore -Pattern local.properties'],
  ['A', B, 'rg -n MAPKIT android'],
  ['A', B, 'grep -rn "Canon" android/app/src/main'],
  ['A', B, 'grep -rn "appText" docs/ .claude/'],
  ['A', P, 'Get-ChildItem -Recurse android/app/src -Filter *.kt | Select-String Canon'],
  ['A', B, 'find . -name "*.kt" | xargs grep -l Canon'],
  ['A', B, 'find android/app/src -name "*.kt" -exec grep -l Canon {} +'],
  ['A', P, '.\\gradlew.bat :app:assembleDebug --no-daemon'],
  ['A', P, 'Set-Location "$(git rev-parse --show-toplevel)\\android"'],
  ['A', P, 'Test-Path android\\local.properties'],
  ['A', B, 'python -m pytest tests/ -q --maxfail=25'],
  ['A', B, 'npm run build --prefix web'],
  ['A', B, 'cat docs/qa.md'],
  ['A', B, 'cp docs/a.md docs/b.md'],
  ['A', B, 'tar czf /tmp/docs.tgz docs/'],
];

let bad = 0;
for (const [want, tool, command] of cases) {
  const r = spawnSync('node', [hook], { input: JSON.stringify({ tool_name: tool, tool_input: { command } }), encoding: 'utf8' });
  const got = /"permissionDecision":"deny"/.test(r.stdout) ? 'D' : 'A';
  const ok = got === want && r.status === 0;
  if (!ok) bad++;
  console.log(`${ok ? 'ok ' : 'НЕ!'} ${got === 'D' ? 'ЗАПРЕТ ' : 'пропуск'} | ${command}`);
}
const junk = spawnSync('node', [hook], { input: 'not json', encoding: 'utf8' });
if (junk.status !== 0 || junk.stdout) bad++;
console.log(`кривой JSON → код ${junk.status}, вывод «${junk.stdout}»`);
console.log(bad ? `ОШИБОК: ${bad} из ${cases.length + 1}` : `все ${cases.length + 1} случаев верно`);
process.exit(bad ? 1 : 0);
