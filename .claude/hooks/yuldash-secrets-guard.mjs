#!/usr/bin/env node
// PreToolUse-хук Юлдаша: сетка от СЛУЧАЙНОГО чтения или копирования файлов с ключами командой терминала
// (Bash / PowerShell) — local.properties (ключ MapKit и др.), google-services.json, keystore.properties,
// *.jks, *.keystore.
//
// Это сетка, а не стена: по тексту команды нельзя предусмотреть всё. Граница — запрет Read(//**/…) в
// settings.json и классификатор Claude Code; хук закрывает частые пути, которых они не видят: PowerShell,
// скрипты, рекурсивный поиск и копирование папок, маски вроде local*, чтение списка файлов через «|».
// Полностью этот класс утечек уйдёт, когда ключи переедут из папки проекта (решение Александра).
//
// Пропускаем: проверки «есть ли файл / в .gitignore ли он» (Test-Path, ls, git status / ls-files /
// check-ignore / diff --name-only), git commit / log, поиск rg и инструмент Grep (они пропускают файлы из
// .gitignore), рекурсивный поиск и копирование в папках, где ключей не бывает (docs/, .claude/,
// android/app/src/, backend/app/ …). Таблица «запретить / пропустить» — yuldash-secrets-guard.test.mjs.
//
// Любая неожиданность (кривой JSON, нет команды) → молча выходим: хук не должен мешать работе.

const SECRET = /(local\.properties|google-services\.json|keystore\.properties|\.jks\b|\.keystore\b)/i;
const SECRET_NAMES = ['local.properties', 'google-services.json', 'keystore.properties', 'release.jks', 'upload.keystore'];
const SAFE_DIR = /^(\.\/)?(docs|\.claude|android\/app\/src|backend\/(app|tests|alembic)|web\/(src|app|components|lib|public)|webapp\/(src|tests|public))(\/|$)/i;

const set = s => new Set(s.split(' '));
// Выводят, сортируют, режут или конвертируют содержимое.
const READERS = set('cat tac type more less head tail sed awk strings xxd od hexdump base64 wc sort nl cut dd jq yq openssl ' +
  'fold fmt pr rev iconv get-content gc import-csv import-clixml');
// Копируют, переносят, упаковывают, сравнивают, отправляют.
const COPIERS = set('cp copy xcopy robocopy mv move ren rename rename-item copy-item move-item scp rsync ln mklink ' +
  'tar zip 7z compress-archive curl invoke-webrequest iwr invoke-restmethod irm certutil keytool diff cmp fc');
const SCRIPTS = set('python python3 py node ruby perl php deno bun');
// Читают файлы, пришедшие по «|» (список файлов из ls / Get-ChildItem / find).
const PIPE_READERS = set('get-content gc select-string sls xargs cat type foreach-object % sort');
const LISTERS = set('get-childitem gci ls dir find fd');
const SHELLS = set('bash sh zsh powershell pwsh cmd');
// Рекурсивное копирование/упаковка — источник папка, а не файл.
const ALWAYS_RECURSIVE = set('tar 7z robocopy compress-archive rsync');

const deny = why => {
  process.stdout.write(JSON.stringify({
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'deny',
      permissionDecisionReason:
        `${why} Файлы с ключами (local.properties, google-services.json, keystore.properties, *.jks, *.keystore) ` +
        'не читаем и не копируем (§11 AGENTS.md). Искать по коду — инструментом Grep или rg (они пропускают файлы ' +
        'из .gitignore), без «..» в путях. Сборка в worktree — с ANDROID_HOME (§5). Нужен ключ — скажи Александру.',
    },
  }));
  process.exit(0);
};

const norm = p => p.replace(/\\/g, '/');
const base = p => norm(p).replace(/\/+$/, '').split('/').pop() ?? '';
const unquote = t => t.replace(/^["'`]|["'`]$/g, '');
const isFlag = t => /^-/.test(t) || /^\/[a-z?](:.*)?$/i.test(t);
// Маска (* ?), которая захватит файл с ключом: local*, *.properties, android/*, local.propertie?
const globHitsSecret = t => {
  const b = base(t);
  if (!/[*?]/.test(b)) return false;
  const re = new RegExp('^' + b.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.') + '$', 'i');
  return SECRET_NAMES.some(n => re.test(n));
};
const mentionsSecret = t => SECRET.test(t) || globHitsSecret(t);
// Похоже на папку (нет расширения) и не из безопасных папок.
const unsafeDir = t => {
  const p = norm(t);
  if (SAFE_DIR.test(p)) return false;
  return p === '.' || p === './' || p.endsWith('/') || !/\.[a-z0-9]{1,8}$/i.test(base(p));
};

// Делим по ; & && || и переводу строки (вне кавычек), затем — по | на звенья конвейера.
function statements(s) {
  const out = []; let cur = ''; let q = '';
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (q) { if (c === q) q = ''; cur += c; continue; }
    if (c === '"' || c === "'") { q = c; cur += c; continue; }
    if (c === ';' || c === '\n' || c === '&') { out.push(cur); cur = ''; continue; }
    cur += c;
  }
  out.push(cur);
  return out.map(st => {
    const stages = []; let part = ''; let qq = '';
    for (const c of st) {
      if (qq) { if (c === qq) qq = ''; part += c; continue; }
      if (c === '"' || c === "'") { qq = c; part += c; continue; }
      if (c === '|') { stages.push(part); part = ''; continue; }
      part += c;
    }
    stages.push(part);
    return stages.map(x => x.trim()).filter(Boolean);
  }).filter(x => x.length);
}
const tokens = s => (s.match(/"[^"]*"|'[^']*'|\S+/g) ?? []).map(unquote);
// Все слова звена, включая слова внутри кавычек, $( … ), { … }: так ловим `$c = Get-Content …`,
// `if (…) { Get-Content … }`, `echo $(cat …)`.
const words = s => s.toLowerCase().split(/[\s"'`(){}$;,=|@]+/).map(w => w.split(/[\\/]/).pop().replace(/\.exe$/, '')).filter(Boolean);

function checkSearcher(verb, args, stage) {
  let paths = [];
  if (verb === 'select-string' || verb === 'sls') {
    let hasPattern = false; const pos = [];
    for (let i = 0; i < args.length; i++) {
      const a = args[i].toLowerCase();
      if (a === '-path' || a === '-literalpath') { paths.push(args[++i] ?? ''); continue; }
      if (a === '-pattern') { hasPattern = true; i++; continue; }
      if (isFlag(args[i])) continue;
      pos.push(args[i]);
    }
    paths.push(...(hasPattern ? pos : pos.slice(1)));
  } else {
    let patternGiven = false; const pos = [];
    for (let i = 0; i < args.length; i++) {
      const a = args[i];
      if (/^(-e|--regexp|-f|--file)$/.test(a)) { patternGiven = true; i++; continue; }
      if (isFlag(a)) continue;
      pos.push(a);
    }
    paths = patternGiven ? pos : pos.slice(1);
  }
  if (paths.some(p => mentionsSecret(p))) deny('Поиск по файлу с ключом выводит ключ.');
  if (paths.some(p => norm(p).includes('..'))) deny('Пути с «..» не разбираю.');
  const recursive = (/^[ef]?grep$/.test(verb) && args.some(f => /^-[a-zA-Z]*[rR][a-zA-Z]*$|^--(dereference-)?recursive$/.test(f)))
    || (verb === 'findstr' && args.some(f => /^\/s$/i.test(f)));
  if (recursive && !(paths.length && paths.every(p => SAFE_DIR.test(norm(p))))) {
    deny('Рекурсивный поиск по папке, где могут лежать ключи, выведет их, не называя файл.');
  }
  void stage;
}

function checkGit(args, stage) {
  const rest = [...args];
  while (rest.length && (rest[0] === '-C' || rest[0] === '-c')) rest.splice(0, 2);
  const sub = (rest.find(a => !isFlag(a)) ?? '').toLowerCase();
  const subArgs = rest.slice(rest.findIndex(a => !isFlag(a)) + 1);
  const anySecret = subArgs.some(a => mentionsSecret(a));
  if (['show', 'cat-file', 'blame', 'annotate', 'archive'].includes(sub) && anySecret) deny(`git ${sub} выводит содержимое файла с ключом.`);
  if ((sub === 'diff' || sub === 'difftool') && anySecret && !subArgs.some(a => /^--(name-only|name-status|stat|numstat|shortstat)$/.test(a))) {
    deny('git diff выводит содержимое файла с ключом.');
  }
  if (sub === 'grep' && subArgs.some(a => /^--(no-index|untracked|no-exclude-standard)$/.test(a))) {
    deny('git grep по неотслеживаемым файлам заглянет в файлы с ключами.');
  }
  if ((sub === 'add' || sub === 'stage') && subArgs.some(a => /^(-f|--force|-[a-zA-Z]*f[a-zA-Z]*)$/.test(a))
      && subArgs.some(a => !isFlag(a) && (mentionsSecret(a) || unsafeDir(a)))) {
    deny('git add -f положит файл с ключом в git.');
  }
  void stage;
}

function checkStatement(stages, depth) {
  // Конвейер: список файлов из ls / Get-ChildItem / find по папке с ключами → читатель файлов.
  for (let i = 0; i < stages.length - 1; i++) {
    const t = tokens(stages[i]);
    const v = (t[0] ?? '').split(/[\\/]/).pop().replace(/\.exe$/i, '').toLowerCase();
    if (!LISTERS.has(v)) continue;
    // Пути — откуда берём файлы; маски -Filter/-Include/-name — какие. Маска вроде *.kt сама
    // отсекает файлы с ключами; маска *.properties или её отсутствие — нет.
    const paths = []; const masks = [];
    for (let k = 1; k < t.length; k++) {
      if (/^-(filter|include|name|iname)$/i.test(t[k])) { masks.push(t[++k] ?? ''); continue; }
      if (/^-(path|literalpath)$/i.test(t[k])) { paths.push(t[++k] ?? ''); continue; }
      if (/^-(exclude|depth|maxdepth|type)$/i.test(t[k])) { k++; continue; }
      if (!isFlag(t[k])) paths.push(t[k]);
    }
    const safeMasks = masks.length > 0 && masks.every(m => !mentionsSecret(m) && !/^\*?\.?\*$/.test(m));
    const unsafe = !safeMasks && (paths.length === 0
      || paths.some(p => mentionsSecret(p) || norm(p).includes('..') || !SAFE_DIR.test(norm(p))));
    const downstreamReads = stages.slice(i + 1).some(s => words(s).some(w => PIPE_READERS.has(w)));
    if (unsafe && downstreamReads) deny('Конвейер читает файлы из папки, где могут лежать ключи.');
  }
  for (const stage of stages) checkStage(stage, depth);
}

function checkStage(stage, depth) {
  const t = tokens(stage);
  if (!t.length) return;
  // .NET-чтение — где угодно в звене.
  if (/(ReadAll(Text|Lines|Bytes)|OpenText|OpenRead|File\]?::(Read|Copy|Open))/i.test(stage) && (SECRET.test(stage) || t.some(globHitsSecret))) {
    deny('Команда читает файл с ключом через .NET.');
  }
  let i = 0;
  while (i < t.length && (/^\w+=/.test(t[i]) || /^(sudo|env|command|exec|time|nohup|&|\.)$/i.test(t[i]))) i++;
  const verb = (t[i] ?? '').split(/[\\/]/).pop().replace(/\.exe$/i, '').toLowerCase();
  const args = t.slice(i + 1);

  // bash -c "…", powershell -Command "…", cmd /c "…" — проверяем вложенную команду целиком.
  if (SHELLS.has(verb) && depth < 3) {
    const k = args.findIndex(a => /^(-c|-command|\/c|\/k)$/i.test(a));
    if (k >= 0) { checkCommand(args.slice(k + 1).join(' '), depth + 1); return; }
  }
  if (verb === 'git') { checkGit(args, stage); return; }
  if (verb === 'rg') {
    if (args.some(a => /^--(no-ignore[\w-]*|unrestricted)$/.test(a) || /^-[a-zA-Z]*u[a-zA-Z]*$/.test(a))) {
      deny('rg без учёта .gitignore заглянет в файлы с ключами.');
    }
    // Первый не-флаг — образец поиска (если нет -e), остальные — пути.
    const usesE = args.some(a => /^(-e|--regexp|-f|--file)$/.test(a));
    const pos = args.filter((a, k) => !isFlag(a) && !/^(-e|--regexp|-f|--file|-g|--glob|-t|--type)$/.test(args[k - 1] ?? ''));
    if ((usesE ? pos : pos.slice(1)).some(a => mentionsSecret(a))) deny('Поиск по файлу с ключом выводит ключ.');
    return;
  }
  if (/^([ef]?grep|findstr|select-string|sls)$/.test(verb)) { checkSearcher(verb, args, stage); return; }
  if (verb === 'find' && args.some(a => /^-(exec|execdir|ok)$/.test(a))) {
    const paths = args.slice(0, args.findIndex(a => isFlag(a))).filter(Boolean);
    if (paths.length === 0 || paths.some(p => !SAFE_DIR.test(norm(p)))) deny('find -exec читает файлы в папке, где могут лежать ключи.');
  }

  const w = words(stage);
  const reads = w.some(x => READERS.has(x) || COPIERS.has(x) || SCRIPTS.has(x));
  if (!reads) return;
  if (SECRET.test(stage) || t.some(globHitsSecret)) deny('Команда прочитает, скопирует или упакует файл с ключом.');
  // У tar первый аргумент без дефиса — связка опций (czf, xvf), а не путь.
  const pathArgs = t.filter((a, j) => j > i && !isFlag(a) && !(verb === 'tar' && j === i + 1 && /^[a-zA-Z]+$/.test(a)));
  if (pathArgs.some(a => norm(a).includes('..'))) deny('Пути с «..» не разбираю.');
  const recursive = w.some(x => ALWAYS_RECURSIVE.has(x))
    || (w.some(x => COPIERS.has(x)) && t.some(a => /^(-[a-zA-Z]*[rRa][a-zA-Z]*|--recursive|-recurse|\/s|\/e)$/i.test(a)))
    || (w.includes('zip') && t.some(a => /^-[a-zA-Z]*r/.test(a)));
  if (recursive && pathArgs.some(a => a !== '-' && unsafeDir(a))) deny('Копирование или упаковка папки, где могут лежать ключи.');
}

function checkCommand(command, depth) {
  for (const stages of statements(command)) checkStatement(stages, depth);
}

let raw = '';
for await (const chunk of process.stdin) raw += chunk;
let input;
try { input = JSON.parse(raw); } catch { process.exit(0); }
const cmd = String(input.tool_input?.command ?? '');
if (cmd.trim()) checkCommand(cmd, 0);
process.exit(0);
