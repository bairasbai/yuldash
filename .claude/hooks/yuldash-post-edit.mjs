#!/usr/bin/env node
// PostToolUse-хук Юлдаша: сразу после правки ловит то, что иначе всплывёт только в CI
// через 10–40 минут. Ничего не правит сам — только говорит агенту, что поправить.
//
//   backend/app/**.py    → ruff --select F (мёртвые импорты/переменные) — тот же гейт, что в CI
//                          (CI гоняет ruff только по app/; в tests/ свои старые F-ошибки — не наши).
//   android/app/src/main → захардкоженный Color(0x…) и одноязычный Text("…") в НОВЫХ строках.
//                          Хук — быстрая подсказка сразу после правки; договор держат тесты, но только
//                          при прогоне: цвет — ColorSourceGuardTest, кегль/радиус/длительность/тень —
//                          CanonSourceGuardTest, padding(N.dp) — храповик SpacingRatchetGuardTest,
//                          один язык (Text, text =, contentDescription, Toast) — BilingualSourceGuardTest.
//
// Любая неожиданность (нет ruff, кривой JSON) → молча выходим: хук не должен мешать работе.
import { spawnSync } from 'node:child_process';

let raw = '';
for await (const chunk of process.stdin) raw += chunk;
let input;
try { input = JSON.parse(raw); } catch { process.exit(0); }

const ti = input.tool_input ?? {};
const file = String(ti.file_path ?? '').replace(/\\/g, '/');
if (!file) process.exit(0);

// Новые строки: то, чего не было в старом тексте. Write — весь файл.
const pairs = [
  [ti.old_string ?? '', ti.new_string ?? ti.content ?? ''],
  ...(Array.isArray(ti.edits) ? ti.edits.map(e => [e.old_string ?? '', e.new_string ?? '']) : []),
];
const added = pairs.flatMap(([oldText, newText]) => {
  const before = new Set(String(oldText).split('\n').map(l => l.trim()));
  return String(newText).split('\n').filter(l => l.trim() && !before.has(l.trim()));
});

const problems = [];
let oneLanguageText = false;

if (/(^|\/)backend\/app\/.+\.py$/.test(file)) {
  const args = ['check', '--select', 'F', '--output-format', 'concise', '--no-cache', file];
  let r = spawnSync('ruff', args, { encoding: 'utf8', timeout: 20000 });
  if (r.error) r = spawnSync('python', ['-m', 'ruff', ...args], { encoding: 'utf8', timeout: 20000 });
  if (!r.error && r.status === 1) {
    const out = (r.stdout || '').split('\n').filter(l => l.includes(':')).slice(0, 10);
    if (out.length) problems.push(`ruff (как в CI, --select F):\n${out.join('\n')}`);
  }
}

// Код строки без хвостового `// комментария`; `//` внутри строки в кавычках («https://…») — не комментарий.
function stripComment(code) {
  let quote = '';
  for (let i = 0; i < code.length; i++) {
    const c = code[i];
    if (quote) {
      if (c === '\\') i++;
      else if (c === quote) quote = '';
    } else if (c === '"' || c === "'") quote = c;
    else if (c === '/' && code[i + 1] === '/') return code.slice(0, i);
  }
  return code;
}

const isScreenKotlin = /(^|\/)android\/app\/src\/main\/.+\.kt$/.test(file)
  && !/\/(Canon[^/]*|[^/]*Theme[^/]*)\.kt$/.test(file);
if (isScreenKotlin) {
  for (const line of added) {
    const code = line.trim();
    if (code.startsWith('//')) continue;
    // Цвет — по коду без хвостового комментария: `foo() // было Color(0x…)` — не нарушение.
    if (/\bColor\(\s*0x/i.test(stripComment(code))) {
      problems.push(`цвет захардкожен → возьми Canon* из CanonTokens.kt (нет нужного — заведи там):\n  ${code}`);
    }
    // Имя бренда и госномер («Х123УХ102») одинаковы на обоих языках — их не трогаем.
    const literal = code.match(/(?<![\w.])Text\(\s*(?:text\s*=\s*)?"([^"]*)"/)?.[1] ?? '';
    if (/[а-яё]/.test(literal.replace(/Юлдаш/g, ''))) {
      problems.push(`надпись на одном языке → appText("рус", "баш"), черновой башкирский — в docs/tasks.md:\n  ${code}`);
      oneLanguageText = true;
    }
  }
}

if (problems.length) {
  const reason = [
    `Проверка правки ${file.split('/').pop()}:`,
    ...problems.slice(0, 10).map(p => `• ${p}`),
    ...(oneLanguageText
      ? ['Если надпись не видна пользователю (превью, тест, лог) — оставь и скажи об этом в отчёте.']
      : []),
  ].join('\n');
  process.stdout.write(JSON.stringify({ decision: 'block', reason }));
}
process.exit(0);
