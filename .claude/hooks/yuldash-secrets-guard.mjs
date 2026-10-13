#!/usr/bin/env node
// PreToolUse-хук Юлдаша: не даёт команде терминала (Bash / PowerShell) прочитать или скопировать
// файлы с ключами — local.properties (ключ MapKit), google-services.json, keystore.properties, *.jks.
//
// Почему мало deny-правил Read(...) в settings.json: они не видят PowerShell (Get-Content, Copy-Item)
// и скрипты (python -c open(...)). Сборке эти файлы не нужны: в worktree хватает ANDROID_HOME (§5 AGENTS.md).
// Проверить, что файл есть или в .gitignore, можно: Test-Path, ls, git check-ignore — их не трогаем.
//
// Любая неожиданность (кривой JSON, нет команды) → молча выходим: хук не должен мешать работе.

let raw = '';
for await (const chunk of process.stdin) raw += chunk;
let input;
try { input = JSON.parse(raw); } catch { process.exit(0); }

const cmd = String(input.tool_input?.command ?? '');
const SECRET = /(local\.properties|google-services\.json|keystore\.properties|\.jks\b|\.keystore\b)/i;
if (!SECRET.test(cmd)) process.exit(0);

// Глаголы чтения, вывода и копирования — в bash и PowerShell.
const READS = /(^|[\s;|&(`])(cat|type|more|less|head|tail|sed|awk|grep|egrep|rg|findstr|strings|xxd|od|base64|wc|Get-Content|gc|Select-String|sls|Import-Csv|cp|copy|xcopy|robocopy|mv|move|Copy-Item|Move-Item|scp|curl|Invoke-WebRequest|iwr|certutil|keytool|python3?|py|node|ruby|perl|php)(?=[\s.(]|$)/i;
if (!READS.test(cmd)) process.exit(0);

process.stdout.write(JSON.stringify({
  hookSpecificOutput: {
    hookEventName: 'PreToolUse',
    permissionDecision: 'deny',
    permissionDecisionReason:
      'Файлы с ключами (local.properties, google-services.json, keystore.properties, *.jks) не читаем и не копируем ' +
      '(§11 AGENTS.md, CLAUDE.md). Сборка в worktree — с ANDROID_HOME (§5). Нужен ключ — скажи Александру.',
  },
}));
process.exit(0);
