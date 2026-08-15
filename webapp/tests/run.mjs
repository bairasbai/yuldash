// ================================================================
//  Проверки логики, которую нельзя увидеть глазами.
//
//  Запуск:  npm run check
//
//  Здесь не «тесты ради тестов»: каждый набор появился из настоящей поломки,
//  которая доходила до человека и которую не ловили ни сборка, ни типы.
//  Время сервера, очередь без сети, черновики форм, чистка при выходе,
//  возврат из банка, тексты ошибок, язык дат, повтор по видимости экрана.
//
//  Без новых зависимостей: модуль собирается тем же esbuild, что и сайт,
//  браузерные вещи (localStorage, document) подделываются в самом наборе.
// ================================================================
import { execFileSync } from "node:child_process";
import { mkdirSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { build } from "esbuild";

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, ".bundles");

/** набор → что для него собрать (модуль сайта → имя файла сборки). */
const SUITES = [
  { test: "st-test", src: "src/utils/serverTime.ts", out: "st-bundle.mjs", bundle: true },
  { test: "outbox-test", src: "src/utils/outbox.ts", out: "outbox-bundle.mjs", bundle: true },
  { test: "draft-test", src: "src/utils/formDraft.ts", out: "draft-bundle.mjs", bundle: true },
  { test: "privacy-test", src: "src/utils/privacy.ts", out: "privacy-bundle.mjs", bundle: true },
  { test: "pay-test", src: "src/utils/pendingPayment.ts", out: "pay-bundle.mjs", bundle: true },
  { test: "client-test", src: "src/api/client.ts", out: "client-bundle.mjs", bundle: true },
  { test: "fmt-test", src: "src/utils/format.ts", out: "fmt-bundle.mjs", bundle: true },
  // Хук трогает react — оставляем импорт нетронутым, набор подменяет его сам.
  { test: "vis-test", src: "src/utils/useVisibleInterval.ts", out: "vis-bundle.mjs", bundle: false },
  // Словарь читает исходники напрямую — собирать нечего.
  { test: "glossary-test", src: null, out: null, bundle: false },
  // Правила оформления читаются из CSS напрямую.
  { test: "ui-test", src: null, out: null, bundle: false },
  // Вес того, что скачивает человек: смотрим файлы, не сборку.
  { test: "budget-test", src: null, out: null, bundle: false },
  // Сверка описаний ответа с моделями сервера (если сервер лежит рядом).
  { test: "contract-test", src: null, out: null, bundle: false },
  // Сторож трёх поломок, которые уже случались и легко возвращаются.
  { test: "guard-test", src: null, out: null, bundle: false },
  // Правила, защищающие человека: чистка при выходе, уход на оплату, отказы.
  { test: "privacy-guard-test", src: null, out: null, bundle: false },
];

// Значения, которые Vite подставляет при сборке сайта.
const ENV = '{"VITE_API_BASE":"https://yulbash.ru","DEV":false}';

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });

let failed = 0;

/** Запустить набор и напечатать итог. */
function runSuite(s) {
  try {
    const out = execFileSync("node", [join(HERE, `${s.test}.mjs`)], {
      cwd: HERE,
      stdio: "pipe",
      env: { ...process.env },
    }).toString();
    const passed = (out.match(/^✓/gm) || []).length;
    console.log(`  ✓ ${s.test} — ${passed} проверок`);
  } catch (e) {
    console.log(`  ✗ ${s.test} — ПРОВАЛ`);
    // Показываем, ЧТО именно не сошлось, а не только факт провала.
    const detail = String(e.stdout || "") + String(e.stderr || "");
    console.log(
      detail
        .split("\n")
        .filter((l) => l.trim())
        .slice(-12)
        .map((l) => "      " + l)
        .join("\n")
    );
    failed++;
  }
}

for (const s of SUITES) {
  // Набор без сборки (читает исходники сам) — сразу к запуску.
  if (!s.src) {
    runSuite(s);
    continue;
  }

  // Собираем тем же esbuild, что стоит за Vite — как модуль, а не как команду:
  // на Windows запуск .cmd из Node требует оболочки, а та съедает кавычки в --define.
  try {
    await build({
      absWorkingDir: join(HERE, ".."),
      entryPoints: [s.src],
      bundle: s.bundle,
      format: "esm",
      define: { "import.meta.env": ENV },
      outfile: join(OUT, s.out),
      logLevel: "silent",
    });
  } catch (e) {
    console.log(`  ✗ ${s.test} — не собрался`);
    console.log(String(e.message).trim().split("\n").slice(0, 5).join("\n"));
    failed++;
    continue;
  }

  runSuite(s);
}

console.log("");
if (failed) {
  console.log(`ПРОВАЛЕНО НАБОРОВ: ${failed}`);
  process.exit(1);
}
console.log("ВСЕ НАБОРЫ ПРОШЛИ");
