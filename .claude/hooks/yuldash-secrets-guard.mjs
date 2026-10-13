#!/usr/bin/env node
// PreToolUse-хук Юлдаша: сетка от СЛУЧАЙНОГО чтения, копирования или удаления файлов с ключами командой
// терминала (Bash / PowerShell) — local.properties (ключ MapKit и др.), google-services.json,
// keystore.properties, *.jks, *.keystore; ключ карты есть и в сгенерированном BuildConfig.java и в APK.
//
// Это сетка, а не стена: по тексту команды нельзя предусмотреть всё (склейка строк, хитрые переменные).
// Граница — запрет Read(//**/…) в settings.json и классификатор Claude Code; хук закрывает частые пути,
// которых они не видят: PowerShell, скрипты, рекурсивный поиск и копирование папок, маски вроде local* и
// *.properties, rg -g, чтение списка файлов через «|», ссылки на файл, удаление и перезапись (rm, Remove-Item, >),
// поиск в папке сборки (BuildConfig.java), git clean -x. Полностью этот класс
// утечек уйдёт, когда ключи переедут из папки проекта (решение Александра).
//
// Пропускаем: проверки «есть ли файл / в .gitignore ли он» (Test-Path, ls, git status / ls-files /
// check-ignore / diff --name-only), git commit / log, поиск rg без -g по ключевым маскам и инструмент Grep
// (они пропускают файлы из .gitignore), поиск и копирование в папках, где ключей не бывает (docs/,
// .claude/, android/app/src/, backend/app/ …). Таблица «запретить / пропустить» — yuldash-secrets-guard.test.mjs.
//
// Любая неожиданность (кривой JSON, нет команды) → молча выходим: хук не должен мешать работе.

const SECRET = /(local\.properties|google-services\.json|keystore\.properties|BuildConfig\.java|\.jks\b|\.keystore\b)/i;
const SECRET_NAMES = ['local.properties', 'google-services.json', 'keystore.properties', 'release.jks', 'upload.keystore'];
const SAFE_DIR = /^(\.\/)?(docs|\.claude|android\/app\/src|backend\/(app|tests|alembic)|web\/(src|app|components|lib|public)|webapp\/(src|tests|public))(\/|$)/i;

const set = s => new Set(s.split(' '));
// Выводят, сортируют, режут, конвертируют или исполняют содержимое.
const READERS = set('cat tac type more less head tail sed awk strings xxd od hexdump base64 wc sort nl cut dd jq yq openssl ' +
  'fold fmt pr rev iconv bat source xargs get-content gc import-csv import-clixml');
// Копируют, переносят, ссылаются, упаковывают, сравнивают, отправляют.
const COPIERS = set('cp copy xcopy robocopy mv move ren rename rename-item copy-item move-item new-item scp rsync ln mklink ' +
  'tar zip 7z compress-archive curl invoke-webrequest iwr invoke-restmethod irm certutil keytool diff cmp fc');
const COPY_FAMILY = set('cp copy xcopy robocopy mv move copy-item move-item rsync scp');
const SCRIPTS = set('python python3 py node ruby perl php deno bun');
// Удаляют или перезаписывают — удаление данных ждёт «да» Александра (§8).
const WRITERS = set('rm del erase unlink shred truncate remove-item ri set-content sc clear-content clc out-file add-content tee');
// Читают файлы, пришедшие по «|» (список файлов из ls / Get-ChildItem / find). ForEach-Object — только
// если внутри блока есть читатель (см. pipeReads).
const PIPE_READERS = set('get-content gc select-string sls xargs');
const BLOCK_READERS = /\b(get-content|gc|cat|type|select-string|sls|readall(text|lines|bytes)|copy-item|cp)\b/i;
// Папка сборки: там сгенерированный BuildConfig.java с ключом карты и Sentry DSN.
const BUILD_DIR = /(^|[\\/])(build|\.gradle)([\\/]|$)/i;
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
        'не читаем, не копируем, не ссылаемся на них и не удаляем (§11 AGENTS.md). Искать по коду — инструментом Grep ' +
        'или rg (они пропускают файлы из .gitignore; rg — без -g по *.properties / *.json), без «..» в путях. ' +
        'Сборка в worktree — с ANDROID_HOME (§5). Нужен ключ — скажи Александру.',
    },
  }));
  process.exit(0);
};

const norm = p => p.replace(/\\/g, '/');
const base = p => norm(p).replace(/\/+$/, '').split('/').pop() ?? '';
const unquote = t => t.replace(/^["'`]|["'`]$/g, '');
const isFlag = t => /^-/.test(t) || /^\/[a-z?](:.*)?$/i.test(t);
const hasDotDot = p => /(^|[\\/])\.\.([\\/]|$)/.test(p);
// {a,b} → a, b (как в bash): android/{gradle,local}.properties
const expandBraces = (t, budget = 32) => {
  const m = t.match(/\{([^{}]*,[^{}]*)\}/);
  if (!m || budget <= 0) return [t];
  return m[1].split(',').flatMap(v => expandBraces(t.replace(m[0], v), budget - 1)).slice(0, 64);
};
// Маска (* ?), которая захватит файл с ключом: local*, *.properties, *.json, android/*, local.propertie?
const globHitsSecret = t => {
  const b = base(t);
  if (!/[*?]/.test(b)) return false;
  const re = new RegExp('^' + b.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.') + '$', 'i');
  return SECRET_NAMES.some(n => re.test(n));
};
const mentionsSecret = t => expandBraces(t).some(v => SECRET.test(v) || globHitsSecret(v));
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
const words = s => s.toLowerCase().split(/[\s"'`(){}$;,=|@<>]+/).map(w => w.split(/[\\/]/).pop().replace(/\.exe$/, '')).filter(Boolean);
const firstWord = s => (tokens(s)[0] ?? '').split(/[\\/]/).pop().replace(/\.exe$/i, '').toLowerCase();
const readsSomething = s => firstWord(s) !== 'git' && words(s).some(w => READERS.has(w) || COPIERS.has(w) || SCRIPTS.has(w));
// Звено конвейера читает файлы, пришедшие по «|»: Get-Content, Select-String, xargs, или ForEach-Object
// с читателем внутри блока. `| sort`, `| ForEach-Object { $_.Name }` получают имена — это не чтение.
const pipeReads = s => words(s).some(w => PIPE_READERS.has(w))
  || (/\b(foreach-object|%)\s*\{/i.test(s) && BLOCK_READERS.test(s.slice(s.indexOf('{'))));

function checkSearcher(verb, args) {
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
  if (paths.some(hasDotDot)) deny('Пути с «..» не разбираю.');
  if (paths.some(p => BUILD_DIR.test(norm(p)))) deny('Поиск по папке сборки выведет ключ из сгенерированного BuildConfig.java.');
  const recursive = (/^[ef]?grep$/.test(verb) && args.some(f => /^-[a-zA-Z]*[rR][a-zA-Z]*$|^--(dereference-)?recursive$/.test(f)))
    || (verb === 'findstr' && args.some(f => /^\/s$/i.test(f)));
  if (recursive && !(paths.length && paths.every(p => SAFE_DIR.test(norm(p))))) {
    deny('Рекурсивный поиск по папке, где могут лежать ключи, выведет их, не называя файл.');
  }
}

function checkRg(args) {
  if (args.some(a => /^--(no-ignore[\w-]*|unrestricted)$/.test(a) || /^-[a-zA-Z]*u[a-zA-Z]*$/.test(a))) {
    deny('rg без учёта .gitignore заглянет в файлы с ключами.');
  }
  // -g / --glob / --iglob отменяют .gitignore для подходящих файлов: -g '*.properties' выведет ключ.
  const globs = [];
  for (let k = 0; k < args.length; k++) {
    const a = args[k]; let m;
    if (/^(-g|--glob|--iglob)$/.test(a)) { globs.push(args[++k] ?? ''); continue; }
    if ((m = a.match(/^--i?glob=(.*)$/))) { globs.push(m[1]); continue; }
    if ((m = a.match(/^-g(.+)$/))) globs.push(m[1]);
  }
  const namesOnly = args.some(a => /^(--files|-l|--files-with-matches|--files-without-match)$/.test(a));
  const broad = g => /^(\*\*\/)?\*$|^\*\*$/.test(g);
  if (!namesOnly && globs.filter(g => !g.startsWith('!')).flatMap(g => expandBraces(g)).some(g => mentionsSecret(g) || broad(g))) {
    deny('rg -g/--glob отменяет .gitignore — с такой маской он выведет файл с ключом.');
  }
  // Первый не-флаг — образец поиска (если нет -e), остальные — пути.
  const usesE = args.some(a => /^(-e|--regexp|-f|--file)$/.test(a));
  const valueFlag = /^(-e|--regexp|-f|--file|-g|--glob|--iglob|-t|--type|-T|--type-not|-m|--max-count|-A|-B|-C|--context)$/;
  const pos = args.filter((a, k) => !isFlag(a) && !valueFlag.test(args[k - 1] ?? ''));
  const rgPaths = usesE ? pos : pos.slice(1);
  if (rgPaths.some(a => mentionsSecret(a))) deny('Поиск по файлу с ключом выводит ключ.');
  // Папку, названную явно, rg читает без учёта .gitignore: rg X android/app/build выведет BuildConfig.java.
  if (rgPaths.some(a => BUILD_DIR.test(norm(a)))) deny('Поиск по папке сборки выведет ключ из сгенерированного BuildConfig.java.');
}

function checkGit(args) {
  const rest = [...args];
  while (rest.length && (rest[0] === '-C' || rest[0] === '-c')) rest.splice(0, 2);
  const idx = rest.findIndex(a => !isFlag(a));
  const sub = (rest[idx] ?? '').toLowerCase();
  const subArgs = rest.slice(idx + 1);
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
  // Удаление данных — только с «да» Александра (§8): git clean -x/-X сотрёт файлы с ключами и keystore.
  if (sub === 'clean' && subArgs.some(a => /^-[a-zA-Z]*[xX][a-zA-Z]*$/.test(a))) {
    deny('git clean -x удалит файлы с ключами и keystore — удаление данных ждёт «да» Александра (§8).');
  }
  if (sub === 'stash' && subArgs.some(a => /^(-a|--all)$/.test(a))) {
    deny('git stash -a уберёт файлы с ключами из рабочей папки — ждёт «да» Александра (§8).');
  }
}

function checkStatement(stages, depth) {
  // Конвейер: список файлов из ls / Get-ChildItem / find по папке с ключами → читатель файлов.
  for (let i = 0; i < stages.length - 1; i++) {
    const t = tokens(stages[i]);
    if (!LISTERS.has(firstWord(stages[i]))) continue;
    // Пути — откуда берём файлы; маски -Filter/-Include/-name — какие. Маска вроде *.kt сама отсекает
    // файлы с ключами; *.properties, *.json или её отсутствие — нет. Список через запятую делим.
    const paths = []; const masks = [];
    for (let k = 1; k < t.length; k++) {
      if (/^-(filter|include|name|iname)$/i.test(t[k])) {
        let v = t[++k] ?? '';
        while (v.endsWith(',') && k + 1 < t.length) v += t[++k];
        masks.push(...v.split(',').map(x => x.trim()).filter(Boolean));
        continue;
      }
      if (/^-(path|literalpath)$/i.test(t[k])) { paths.push(t[++k] ?? ''); continue; }
      if (/^-(exclude|depth|maxdepth|type)$/i.test(t[k])) { k++; continue; }
      if (!isFlag(t[k])) paths.push(...t[k].split(',').map(x => x.trim()).filter(Boolean));
    }
    const safeMasks = masks.length > 0 && masks.every(m => !mentionsSecret(m) && !/^\*?\.?\*$/.test(m));
    const unsafe = paths.some(p => mentionsSecret(p) || hasDotDot(p)) || masks.some(m => mentionsSecret(m))
      || (!safeMasks && (paths.length === 0 || paths.some(p => !SAFE_DIR.test(norm(p)))));
    const downstreamReads = stages.slice(i + 1).some(pipeReads);
    if (unsafe && downstreamReads) deny('Конвейер читает файлы из папки, где могут лежать ключи.');
  }
  // Имя файла с ключом уходит по «|» в читателя: echo …local.properties | xargs cat, Get-Item … | Get-Content.
  for (let i = 0; i < stages.length - 1; i++) {
    if (firstWord(stages[i]) !== 'git' && mentionsSecret(stages[i]) && stages.slice(i + 1).some(pipeReads)) {
      deny('Имя файла с ключом передаётся по «|» в команду, которая его прочитает.');
    }
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
  // . file / source file — исполнить файл с ключом в текущей оболочке.
  if ((t[0] === '.' || /^source$/i.test(t[0])) && t.length > 1 && mentionsSecret(t[1])) deny('Команда исполнит файл с ключом.');
  let i = 0;
  while (i < t.length && (/^\w+=/.test(t[i]) || /^(sudo|env|command|exec|time|nohup|&)$/i.test(t[i]))) i++;
  const verb = (t[i] ?? '').split(/[\\/]/).pop().replace(/\.exe$/i, '').toLowerCase();
  const args = t.slice(i + 1);

  // bash -c "…", powershell -Command "…", cmd /c "…" — проверяем вложенную команду целиком.
  if (SHELLS.has(verb) && depth < 3) {
    const k = args.findIndex(a => /^(-c|-command|\/c|\/k)$/i.test(a));
    if (k >= 0) { checkCommand(args.slice(k + 1).join(' '), depth + 1); return; }
  }
  if (verb === 'git') { checkGit(args); return; }
  // gh читает с диска только --body-file / -F; остальное — текст описания PR, он не опасен.
  if (verb === 'gh') {
    const k = args.findIndex(a => /^(--body-file|-F)$/.test(a));
    if (k >= 0 && mentionsSecret(args[k + 1] ?? '')) deny('gh отправит файл с ключом на GitHub.');
    return;
  }
  // Удаление и перезапись файла с ключом — удаление данных, ждёт «да» Александра (§8).
  if (words(stage).some(x => WRITERS.has(x)) && (t.some(a => mentionsSecret(a)) || SECRET.test(stage))) {
    deny('Удаление или перезапись файла с ключом ждёт «да» Александра (§8).');
  }
  if (verb === 'rg') { checkRg(args); return; }
  if (/^([ef]?grep|findstr|select-string|sls)$/.test(verb)) { checkSearcher(verb, args); return; }
  if (verb === 'find' && args.some(a => /^-(exec|execdir|ok)$/.test(a))) {
    const firstFlag = args.findIndex(a => isFlag(a));
    const paths = (firstFlag < 0 ? args : args.slice(0, firstFlag)).filter(Boolean);
    if (paths.length === 0 || paths.some(p => !SAFE_DIR.test(norm(p)))) deny('find -exec читает файлы в папке, где могут лежать ключи.');
  }

  const w = words(stage);
  if (!w.some(x => READERS.has(x) || COPIERS.has(x) || SCRIPTS.has(x))) return;
  if (t.some(a => mentionsSecret(a)) || SECRET.test(stage)) deny('Команда прочитает, скопирует, упакует или сошлётся на файл с ключом.');
  // У tar первый аргумент без дефиса — связка опций (czf, xvf), а не путь.
  const pathArgs = t.filter((a, j) => j > i && !isFlag(a) && !(verb === 'tar' && j === i + 1 && /^[a-zA-Z]+$/.test(a)));
  if (pathArgs.some(hasDotDot)) deny('Пути с «..» не разбираю.');
  // Рекурсия: cp/scp/rsync/zip -r -R -a (короткие связки вроде -rf), PowerShell -Recurse, xcopy /s /e.
  const recursive = w.some(x => ALWAYS_RECURSIVE.has(x))
    || (['cp', 'scp', 'rsync', 'zip'].includes(verb) && t.some(a => /^-[a-zA-Z]{1,4}$/.test(a) && /[rRa]/.test(a)))
    || t.some(a => /^(--recursive|-recurse)$/i.test(a))
    || (verb === 'xcopy' && t.some(a => /^\/[se]$/i.test(a)));
  // У копирования последний путь — куда; проверяем, откуда.
  const sources = COPY_FAMILY.has(verb) && pathArgs.length > 1 ? pathArgs.slice(0, -1) : pathArgs;
  if (recursive && sources.some(a => a !== '-' && unsafeDir(a))) deny('Копирование или упаковка папки, где могут лежать ключи.');
}

const KEY_NAME = '(local\\.properties|google-services\\.json|keystore\\.properties|BuildConfig\\.java|\\.jks\\b|\\.keystore\\b)';

function checkCommand(raw, depth) {
  // Тело heredoc (<<'EOF' … EOF) — это текст (описание PR, коммита), а не команды: вырезаем.
  const command = raw.replace(/<<-?\s*(['"]?)(\w+)\1[^\n]*\n[\s\S]*?\n\s*\2\b/g, '<<$2');
  // Чтение через перенаправление: $(<file), while read … < file.
  if (new RegExp(`(^|[^<])<\\s*\\(?\\s*["']?[^\\s|;&]*${KEY_NAME}`, 'i').test(command)) deny('Команда читает файл с ключом через «<».');
  // Перезапись через перенаправление: … > android/local.properties.
  if (new RegExp(`>{1,2}\\s*["']?[^\\s|;&]*${KEY_NAME}`, 'i').test(command)) {
    deny('Перезапись файла с ключом ждёт «да» Александра (§8).');
  }
  const all = statements(command);
  // Имя файла в переменной, чтение — в соседней команде: $p='…local.properties'; Get-Content $p.
  // Передачей считаем только присваивание — упоминание имени в поиске или проверке ею не считается.
  const flat = all.map(st => st.join(' | '));
  const assignsSecret = flat.some(s => /^\s*(\$?[\w:]+\s*=|export\s|set\s)/i.test(s) && mentionsSecret(s));
  if (assignsSecret && flat.some(s => readsSomething(s) && !mentionsSecret(s))) {
    deny('Имя файла с ключом передаётся в соседнюю команду, которая его прочитает.');
  }
  for (const stages of all) checkStatement(stages, depth);
}

let raw = '';
for await (const chunk of process.stdin) raw += chunk;
let input;
try { input = JSON.parse(raw); } catch { process.exit(0); }
const cmd = String(input.tool_input?.command ?? '');
if (cmd.trim()) checkCommand(cmd, 0);
process.exit(0);
