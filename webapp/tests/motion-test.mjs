// ================================================================
//  Движение: приложение, PWA и сайт — на ОДНОЙ шкале, один в один.
//
//  Александр спросил прямо: «приложение и сайт правда 1 в 1?». По дизайну
//  сводили уже дважды — и оба раза расходились обратно, потому что сверка
//  была разовой: посчитали руками, записали в docs/ и забыли.
//
//  Этот набор делает сверку постоянной. Он проверяет две вещи:
//
//   1. ЧИСЛА СОВПАДАЮТ. Одиннадцать ступеней CanonMotion и два каскадных
//      сдвига читаются прямо из `CanonTokens.kt` и из CSS каждой веб-поверхности.
//      Поменял 180 на 200 в приложении и забыл сайт — набор упадёт и покажет,
//      где именно разошлось.
//
//   2. СЫРЫХ ЧИСЕЛ НЕТ. Ни `transition: .3s`, ни `animation: pulse 1.4s`,
//      ни tailwind-класс `duration-300`. Иначе шкала снова станет украшением:
//      она есть, но половина движения идёт мимо неё — ровно так и было
//      в приложении до 2026-09-05 (разбор — docs/lessons.md).
//
//  Исключение ровно одно: `.001ms !important` (в любой записи) в блоке
//  `@media (prefers-reduced-motion: reduce)`. Это не дизайнерская длительность,
//  а способ выключить анимации в CSS — числу там взяться неоткуда.
// ================================================================
import { readdirSync, readFileSync, statSync, existsSync } from "node:fs";
import { join, dirname, relative } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, "..", "..");

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "\n    " + extra : ""}`);
};

/** Ступени шкалы: имя в Kotlin → имя переменной в CSS. */
const STEPS = [
  ["QUICK_MS", "--motion-quick"],
  ["NORMAL_MS", "--motion-normal"],
  ["SLOW_MS", "--motion-slow"],
  ["ENTRY_MS", "--motion-entry"],
  ["COUNT_MS", "--motion-count"],
  ["SCENE_MS", "--motion-scene"],
  ["TICK_MS", "--motion-tick"],
  ["PULSE_MS", "--motion-pulse"],
  ["CINEMA_MS", "--motion-cinema"],
  ["DRIFT_MS", "--motion-drift"],
  ["AMBIENT_MS", "--motion-ambient"],
  ["CASCADE_IN_MS", "--cascade-in"],
  ["CASCADE_OUT_MS", "--cascade-out"],
];

/** Веб-поверхности, которые обязаны повторять шкалу приложения. */
const SURFACES = [
  ["PWA", "webapp/src/index.css"],
  ["сайт", "web/app/globals.css"],
  ["standalone-герой", "web/hero.html"],
];

// ---------------------------------------------------------------- 1. числа совпадают
const kotlinPath = join(ROOT, "android/app/src/main/java/com/yuldash/app/CanonTokens.kt");
const kotlin = existsSync(kotlinPath) ? readFileSync(kotlinPath, "utf8") : "";
check(kotlin.length > 0, "нашёлся CanonTokens.kt — шкала приложения есть с чем сверять");

const app = {};
for (const [kt] of STEPS) {
  const m = kotlin.match(new RegExp(`\\b${kt}\\s*=\\s*(\\d+)`));
  if (m) app[kt] = Number(m[1]);
}
const parsed = Object.keys(app).length;
check(
  parsed === STEPS.length,
  `в приложении разобраны все ${STEPS.length} ступеней`,
  parsed === STEPS.length ? "" : `разобрано только ${parsed}`
);

for (const [label, rel] of SURFACES) {
  const p = join(ROOT, rel);
  if (!existsSync(p)) {
    check(false, `${label}: файл ${rel} не найден`);
    continue;
  }
  const css = readFileSync(p, "utf8");
  const diff = [];
  for (const [kt, cssVar] of STEPS) {
    const m = css.match(new RegExp(`${cssVar}\\s*:\\s*(\\d+)ms`));
    if (!m) diff.push(`${cssVar} — нет вовсе`);
    else if (Number(m[1]) !== app[kt]) diff.push(`${cssVar} = ${m[1]}ms, а в приложении ${kt} = ${app[kt]}ms`);
  }
  check(diff.length === 0, `${label}: шкала совпадает с приложением`, diff.join("\n    "));
}

// ---------------------------------------------------------------- 2. сырых чисел нет
/** Файлы поверхности, в которых вообще может встретиться длительность. */
function sources(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    if (name === "node_modules" || name === ".next" || name === "dist") continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) sources(p, out);
    else if (/\.(css|ts|tsx|js|jsx|html)$/.test(name)) out.push(p);
  }
  return out;
}

const RAW = [
  // transition: opacity .4s / animation: pulse 1400ms / animation-delay: -8s
  /(?:transition|animation)(?:-duration|-delay)?\s*:[^;{}]*?\d+(?:\.\d+)?m?s\b/g,
  // tailwind: duration-300, duration-[1200ms], delay-150
  /\b(?:duration|delay)-\[?\d+/g,
];

/** Определение самой шкалы — не нарушение: числу положено жить именно там. */
const isTokenLine = (l) => /^\s*--(?:motion|loop|cascade)[\w-]*\s*:/.test(l);
/** Выключение анимаций в CSS: `0.001ms !important`. Не дизайнерская длительность. */
const isReducedMotionReset = (l) => /(?:0)?\.001ms\s*!important/.test(l);

const offenders = [];
for (const dir of [join(ROOT, "webapp/src"), join(ROOT, "web/app"), join(ROOT, "web/components")]) {
  for (const file of sources(dir)) scan(file);
}
for (const single of ["web/hero.html", "web/tailwind.config.ts"]) {
  const p = join(ROOT, single);
  if (existsSync(p)) scan(p);
}

function scan(file) {
  const lines = readFileSync(file, "utf8").split("\n");
  lines.forEach((line, i) => {
    const code = line.split("/*")[0];
    if (isTokenLine(code) || isReducedMotionReset(code)) return;
    for (const re of RAW) {
      re.lastIndex = 0;
      const m = re.exec(code);
      if (m) {
        offenders.push(`${relative(ROOT, file).replace(/\\/g, "/")}:${i + 1}  ${m[0].trim().slice(0, 70)}`);
        return;
      }
    }
  });
}

check(
  offenders.length === 0,
  "во всех веб-поверхностях длительности только со шкалы",
  offenders.slice(0, 20).join("\n    ")
);

// ---------------------------------------------------------------- 3. разбор вообще работает
// Страховка от зелёного цвета на пустом списке: если поиск сломается, проверки
// выше пройдут «успешно», ничего не проверив.
let uses = 0;
for (const dir of [join(ROOT, "webapp/src"), join(ROOT, "web/app"), join(ROOT, "web/components")]) {
  for (const file of sources(dir)) {
    uses += (readFileSync(file, "utf8").match(/var\(--(?:motion|loop|cascade)/g) || []).length;
  }
}
check(uses > 100, "шкала реально используется, а не лежит мёртвым списком", uses > 100 ? "" : `ссылок на шкалу всего ${uses}`);
check(
  RAW[0].test("transition: opacity 0.4s ease;"),
  "поиск сырых длительностей ловит заведомо плохую строку"
);
check(
  RAW[1].test("transition-all duration-300"),
  "поиск ловит и tailwind-класс с числом"
);

console.log("");
process.exit(bad ? 1 : 0);
