#!/usr/bin/env node
// PreToolUse-хук Юлдаша: не даёт команде терминала (Bash / PowerShell) прочитать или скопировать
// файлы с ключами — local.properties (ключ MapKit и др.), google-services.json, keystore.properties, *.jks, *.keystore.
//
// Почему мало deny-правил Read(...) в settings.json: они не видят PowerShell (Get-Content, Copy-Item),
// скрипты (python -c open(...), [IO.File]::ReadAllText) и рекурсивный поиск (grep -rn KEY android/
// выводит ключ, не называя файл). Сборке эти файлы не нужны: в worktree хватает ANDROID_HOME (§5 AGENTS.md).
//
// Что пропускаем: проверки «есть ли файл / в .gitignore ли он» (Test-Path, ls, git check-ignore,
// git status, git ls-files, git diff --name-only), поиск rg и инструмент Grep (они сами пропускают файлы
// из .gitignore), рекурсивный grep по папкам, где ключей не бывает (docs/, .claude/, android/app/src/, …).
//
// Любая неожиданность (кривой JSON, нет команды) → молча выходим: хук не должен мешать работе.

let raw = '';
for await (const chunk of process.stdin) raw += chunk;
let input;
try { input = JSON.parse(raw); } catch { process.exit(0); }

const cmd = String(input.tool_input?.command ?? '');
if (!cmd.trim()) process.exit(0);

// Имя файла с ключом или маска, которая его захватит.
const SECRET = /(local\.properties|google-services\.json|keystore\.properties|\.jks\b|\.keystore\b)/i;
const SECRET_GLOB = /(\*\.properties|local\.\*|keystore\.\*|google-services\.\*|\*services\.json|\*\.jks|\*\.keystore)/i;
const mentionsSecret = s => SECRET.test(s) || SECRET_GLOB.test(s);

// Команды, которые выводят, копируют или упаковывают содержимое файла.
const READERS = new Set(('cat type more less head tail sed awk strings xxd od base64 wc get-content gc import-csv ' +
  'cp copy xcopy robocopy mv move copy-item move-item scp curl invoke-webrequest iwr certutil keytool ' +
  'diff cmp tar zip 7z compress-archive python python3 py node ruby perl php').split(' '));
const SEARCHERS = new Set('grep egrep fgrep findstr select-string sls rg'.split(' '));
// Папки, где ключей не бывает, — рекурсивный поиск по ним безопасен.
const SAFE_DIR = /^(\.\/)?(docs|\.claude|android\/app\/src|backend\/(app|tests|alembic)|web\/(src|app|components|lib|public)|webapp\/(src|tests|public))(\/|$)/i;

function deny(why) {
  process.stdout.write(JSON.stringify({
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'deny',
      permissionDecisionReason:
        `${why} Файлы с ключами (local.properties, google-services.json, keystore.properties, *.jks, *.keystore) ` +
        'не читаем и не копируем (§11 AGENTS.md). Искать по коду — инструментом Grep или rg (они пропускают файлы ' +
        'из .gitignore). Сборка в worktree — с ANDROID_HOME (§5). Нужен ключ — скажи Александру.',
    },
  }));
  process.exit(0);
}

// Методы .NET и подобные — где угодно в команде.
if (/(ReadAll(Text|Lines|Bytes)|OpenText|OpenRead|File\.Read|File\.Copy)\s*\(/i.test(cmd) && mentionsSecret(cmd)) {
  deny('Команда читает файл с ключом через .NET.');
}
// Get-ChildItem -Recurse … | Select-String / Get-Content — рекурсивное чтение без имени файла.
if (/-Recurse\b/i.test(cmd) && /\b(Select-String|sls|Get-Content|gc|cat|type)\b/i.test(cmd)) {
  deny('Рекурсивное чтение файлов через PowerShell может вывести ключ.');
}

const tokenize = s => (s.match(/"[^"]*"|'[^']*'|\S+/g) ?? []).map(t => t.replace(/^["']|["']$/g, ''));
const norm = p => p.replace(/\\/g, '/');

for (const segment of cmd.split(/\|\||&&|[|;&\n]/)) {
  const tokens = tokenize(segment);
  while (tokens.length && (/^\w+=/.test(tokens[0]) || /^(sudo|env|command|exec|time|nohup)$/i.test(tokens[0]))) tokens.shift();
  if (!tokens.length) continue;
  const verb = tokens[0].split(/[\\/]/).pop().replace(/\.exe$/i, '').toLowerCase();
  const args = tokens.slice(1);

  if (verb === 'git') {
    const sub = (args.find(a => !a.startsWith('-')) ?? '').toLowerCase();
    const listingOnly = ['status', 'ls-files', 'check-ignore', 'log', 'rev-parse', 'branch', 'add', 'rm', 'restore']
      .includes(sub) || (sub === 'diff' && args.some(a => /^--(name-only|name-status|stat)$/.test(a)));
    if (!listingOnly && mentionsSecret(segment)) deny(`git ${sub} выводит содержимое файла с ключом.`);
    continue;
  }

  if (SEARCHERS.has(verb)) {
    const flags = args.filter(a => a.startsWith('-') || /^\/[a-z]$/i.test(a));
    const positional = args.filter(a => !(a.startsWith('-') || /^\/[a-z]$/i.test(a)));
    const usesE = flags.some(f => /^-e$|^--regexp/.test(f));
    const paths = (usesE ? positional : positional.slice(1)).map(norm);
    if (paths.some(mentionsSecret)) deny('Поиск по файлу с ключом выводит ключ.');
    if (verb === 'rg') {
      if (flags.some(f => /^-u+$|^--no-ignore|^-uu/.test(f))) deny('rg без учёта .gitignore заглянет в файлы с ключами.');
      continue;
    }
    // grep: -r / -R в любой связке (-rn, -Rl); findstr: /s. У Select-String рекурсии нет — её даёт
    // Get-ChildItem -Recurse, это поймано выше.
    const recursive = (/^[ef]?grep$/.test(verb) && flags.some(f => /^-[a-zA-Z]*[rR][a-zA-Z]*$|^--(dereference-)?recursive$/.test(f)))
      || (verb === 'findstr' && flags.some(f => /^\/s$/i.test(f)));
    if (recursive && !(paths.length && paths.every(p => SAFE_DIR.test(p)))) {
      deny('Рекурсивный поиск по папке, где могут лежать ключи, выведет их, не называя файл.');
    }
    continue;
  }

  if (READERS.has(verb) && mentionsSecret(segment)) deny(`«${verb}» прочитает или скопирует файл с ключом.`);
}
process.exit(0);
