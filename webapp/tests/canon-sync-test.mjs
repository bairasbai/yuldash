// ================================================================
//  Токены: приложение и сайт — одна шкала, один в один.
//
//  Зачем. Дизайн сводили с Android уже трижды, и каждый раз он расходился обратно:
//  сверка была разовой — посчитали руками, записали в docs/ и забыли. Здесь сверка
//  постоянная: набор читает `android/.../CanonTokens.kt` (эталон) и `webapp/src/index.css`
//  и падает, если хоть одно значение разошлось. Поменял цвет в приложении и забыл
//  сайт — увидишь здесь, а не через месяц глазами.
//
//  Что сверяется:
//   1. ЦВЕТА. Каждая переменная в index.css, подписанная `/* CanonX … */`, должна равняться
//      значению CanonX в Kotlin: светлая тема — светлому, тёмные блоки — тёмному. Подпись
//      «(dark)»/«dark» у светлой переменной значит «это тёмное значение Canon» — так
//      помечены зелёные акценты, которые сайт берёт из тёмной палитры.
//   2. РАДИУСЫ, ОТСТУПЫ, КЕГЛИ. --radius-* ↔ Canon*Shape, --space-* ↔ CanonSpace,
//      --font-*/--line-*/--weight-* ↔ CanonDisplay…CanonButton (кегль, межстрочный, вес).
//   3. СЫРЫХ ЧИСЕЛ НЕТ. font-weight числом (кроме @font-face и самих токенов) и
//      font-size в px — запрещены совсем. Цвета и радиусы числом — «храповик»: их не больше,
//      чем в BUDGET ниже; чинишь — опускай число, поднять нельзя. Цель — ноль.
// ================================================================
import { readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, "..", "..");
const KT = readFileSync(join(ROOT, "android/app/src/main/java/com/yuldash/app/CanonTokens.kt"), "utf8");
const INDEX_CSS = readFileSync(join(ROOT, "webapp/src/index.css"), "utf8");
const UI_CSS = readFileSync(join(ROOT, "webapp/src/ui.css"), "utf8");

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "\n    " + extra : ""}`);
};

// ---------- Kotlin: эталон ----------
/** Color(0xAARRGGBB) → CSS: #rrggbb при непрозрачном, иначе rgba(r, g, b, a). */
function ktColor(hex8) {
  const a = parseInt(hex8.slice(0, 2), 16);
  const r = parseInt(hex8.slice(2, 4), 16);
  const g = parseInt(hex8.slice(4, 6), 16);
  const b = parseInt(hex8.slice(6, 8), 16);
  return a === 255 ? { r, g, b, a: 1 } : { r, g, b, a: a / 255 };
}
const kt = { colors: {}, radius: {}, space: {}, type: {} };
for (const m of KT.matchAll(
  /internal val (Canon\w+): Color @Composable get\(\) = if \(appIsDark\(\)\) Color\(0x([0-9A-Fa-f]{8})\) else Color\(0x([0-9A-Fa-f]{8})\)/g
)) {
  kt.colors[m[1]] = { dark: ktColor(m[2]), light: ktColor(m[3]) };
}
for (const m of KT.matchAll(/internal val (Canon\w+): Color = Color\(0x([0-9A-Fa-f]{8})\)/g)) {
  const c = ktColor(m[2]);
  kt.colors[m[1]] = { dark: c, light: c };
}
for (const m of KT.matchAll(/internal val (Canon\w+Shape) = RoundedCornerShape\((\d+)\.dp\)/g)) kt.radius[m[1]] = Number(m[2]);
const spaceBlock = KT.match(/internal object CanonSpace \{([\s\S]*?)\n\}/);
for (const m of (spaceBlock ? spaceBlock[1] : "").matchAll(/val (\w+) = (\d+)\.dp/g)) kt.space[m[1]] = Number(m[2]);
for (const m of KT.matchAll(
  /internal val (Canon\w+) = TextStyle\(fontSize = (\d+)\.sp, lineHeight = (\d+)\.sp, fontWeight = FontWeight\.(\w+)\)/g
)) {
  kt.type[m[1]] = { size: Number(m[2]), line: Number(m[3]), weight: { Normal: 400, Medium: 500, SemiBold: 600, Bold: 700 }[m[4]] };
}
check(Object.keys(kt.colors).length >= 30, `CanonTokens.kt прочитан: ${Object.keys(kt.colors).length} цветов, ${Object.keys(kt.radius).length} радиуса, ${Object.keys(kt.space).length} отступов, ${Object.keys(kt.type).length} кеглей`);

// ---------- CSS: разбор блоков ----------
/** Вырезает тело первого блока, начинающегося с selector — до парной закрывающей скобки. */
function blockBody(css, selector) {
  const start = css.indexOf(selector);
  if (start < 0) return "";
  let i = css.indexOf("{", start);
  let depth = 0;
  for (let j = i; j < css.length; j++) {
    if (css[j] === "{") depth++;
    else if (css[j] === "}" && --depth === 0) return css.slice(i + 1, j);
  }
  return "";
}
/** `--name: value; /* comment *\/` → [{name, value, comment}] */
function vars(body) {
  const out = [];
  // Комментарий ищем только на той же строке ([ \t]*): \s* съедал бы перенос и отступ
  // следующей строки, и её переменная выпадала бы из разбора.
  for (const m of body.matchAll(/^\s*(--[\w-]+):\s*([^;\n]+);[ \t]*(?:\/\*([^*]*)\*\/)?/gm)) {
    out.push({ name: m[1], value: m[2].trim(), comment: (m[3] || "").trim() });
  }
  return out;
}
function cssColor(v) {
  let m = v.match(/^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$/i);
  if (m) {
    let h = m[1];
    if (h.length === 3) h = h.split("").map((c) => c + c).join("");
    const r = parseInt(h.slice(0, 2), 16), g = parseInt(h.slice(2, 4), 16), b = parseInt(h.slice(4, 6), 16);
    const a = h.length === 8 ? parseInt(h.slice(6, 8), 16) / 255 : 1;
    return { r, g, b, a };
  }
  m = v.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)$/);
  if (m) return { r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4] };
  return null;
}
const same = (x, y) => x && y && x.r === y.r && x.g === y.g && x.b === y.b && Math.abs(x.a - y.a) < 0.006;
const fmt = (c) => (c.a === 1 ? "#" + [c.r, c.g, c.b].map((n) => n.toString(16).padStart(2, "0")).join("") : `rgba(${c.r}, ${c.g}, ${c.b}, ${c.a.toFixed(3)})`);

const light = vars(blockBody(INDEX_CSS, ":root {"));
const darkMedia = vars(blockBody(blockBody(INDEX_CSS, "@media (prefers-color-scheme: dark)"), ":root:not([data-theme])"));
const darkManual = vars(blockBody(INDEX_CSS, ':root[data-theme="dark"]'));

// ---------- 1. Цвета ----------
/** Какой Canon-цвет и какую тему подписали в комментарии. */
function annotated(v, defaultTheme) {
  const m = v.comment.match(/\b(Canon[A-Z]\w*)\b/);
  if (!m || !kt.colors[m[1]]) return null;
  // Слово сразу после имени решает: «CanonGold light (dark override ниже)» — светлое значение.
  const after = v.comment.slice(v.comment.indexOf(m[1]) + m[1].length).trim();
  const theme = /^\(?(dark|тёмн)/i.test(after) ? "dark" : /^\(?(light|светл)/i.test(after) ? "light" : defaultTheme;
  return { canon: m[1], theme };
}
let colorPairs = 0;
const lightAnnot = new Map(); // --var → Canon (чтобы тёмные блоки без подписи тоже сверялись)
for (const v of light) {
  const a = annotated(v, "light");
  if (!a) continue;
  lightAnnot.set(v.name, a.canon);
  const want = kt.colors[a.canon][a.theme];
  const got = cssColor(v.value);
  colorPairs++;
  check(same(got, want), `светлая ${v.name} = ${a.canon} (${a.theme})`, same(got, want) ? "" : `CSS ${v.value} ≠ Kotlin ${fmt(want)}`);
}
for (const [label, list] of [["тёмная (система)", darkMedia], ["тёмная (вручную)", darkManual]]) {
  for (const v of list) {
    const a = annotated(v, "dark") ?? (lightAnnot.has(v.name) ? { canon: lightAnnot.get(v.name), theme: "dark" } : null);
    if (!a) continue;
    // Светлая переменная подписана тёмным значением Canon («(dark)»): в тёмной теме её
    // переопределение сверяем с тем же тёмным значением — другого у Kotlin нет.
    const want = kt.colors[a.canon][a.theme];
    const got = cssColor(v.value);
    colorPairs++;
    check(same(got, want), `${label} ${v.name} = ${a.canon} (${a.theme})`, same(got, want) ? "" : `CSS ${v.value} ≠ Kotlin ${fmt(want)}`);
  }
}
check(colorPairs >= 60, `сверено пар цветов: ${colorPairs} (ожидается ≥ 60)`);
check(darkMedia.length > 0 && darkMedia.length === darkManual.length,
  `тёмные блоки одинаковой длины: система ${darkMedia.length}, вручную ${darkManual.length}`);

// ---------- 2. Радиусы, отступы, кегли ----------
const lightMap = Object.fromEntries(light.map((v) => [v.name, v.value]));
const px = (v) => (v && /^(\d+)px$/.test(v) ? Number(RegExp.$1) : NaN);
for (const [css, kotlin] of [["--radius-card", "CanonCardShape"], ["--radius-item", "CanonItemShape"], ["--radius-field", "CanonFieldShape"], ["--radius-tiny", "CanonTinyShape"]]) {
  check(px(lightMap[css]) === kt.radius[kotlin], `${css} = ${kotlin} (${kt.radius[kotlin]}px)`, `CSS ${lightMap[css]}`);
}
for (const [name, dp] of Object.entries(kt.space)) {
  check(px(lightMap[`--space-${name}`]) === dp, `--space-${name} = CanonSpace.${name} (${dp}px)`, `CSS ${lightMap[`--space-${name}`]}`);
}
const weightVal = (v) => {
  // токен может ссылаться на другой токен: var(--weight-bold) → 700
  let cur = v, guard = 0;
  while (cur && cur.startsWith("var(") && guard++ < 5) cur = lightMap[cur.slice(4, -1)];
  return Number(cur);
};
const TYPE = [["CanonDisplay", "display"], ["CanonTitle", "title"], ["CanonHeading", "heading"], ["CanonBody", "body"],
  ["CanonBodyStrong", "body-strong"], ["CanonCaption", "caption"], ["CanonMicro", "micro"], ["CanonButton", "button"]];
for (const [kotlin, css] of TYPE) {
  const t = kt.type[kotlin];
  if (!t) { check(false, `${kotlin} найден в Kotlin`); continue; }
  const sizeVar = lightMap[`--font-${css}`] ?? lightMap[`--font-${css.replace("-strong", "")}`];
  const lineVar = lightMap[`--line-${css}`] ?? lightMap[`--line-${css.replace("-strong", "")}`];
  check(px(sizeVar) === t.size && px(lineVar) === t.line, `${kotlin}: кегль ${t.size}/${t.line}`, `CSS ${sizeVar}/${lineVar}`);
  // У Caption своего токена веса нет: он обычный, как Body — сверяем с --weight-body.
  const weightVar = lightMap[`--weight-${css}`] !== undefined ? `--weight-${css}` : "--weight-body";
  check(weightVal(lightMap[weightVar]) === t.weight, `${kotlin}: вес ${t.weight} (${weightVar})`, `CSS ${weightVar} = ${lightMap[weightVar]}`);
}

// ---------- 3. Сырые числа ----------
function walk(dir, exts, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, exts, out);
    else if (exts.some((e) => name.endsWith(e))) out.push(p);
  }
  return out;
}
const sources = [join(ROOT, "webapp/src/ui.css"), ...walk(join(ROOT, "webapp/src"), [".tsx"])];
const rel = (p) => relative(ROOT, p).replaceAll("\\", "/");
const rawWeights = [], rawSizes = [];
for (const p of sources) {
  const s = readFileSync(p, "utf8");
  s.split("\n").forEach((line, i) => {
    if (/font-weight:\s*\d{3}\b|fontWeight:\s*\d{3}\b/.test(line)) rawWeights.push(`${rel(p)}:${i + 1}`);
    if (/font-size:\s*\d+px|fontSize:\s*["']\d+px/.test(line)) rawSizes.push(`${rel(p)}:${i + 1}`);
  });
}
check(rawWeights.length === 0, "font-weight — только токены --weight-*", rawWeights.slice(0, 5).join(", "));
check(rawSizes.length === 0, "font-size — только токены --font-*", rawSizes.slice(0, 5).join(", "));

// Храповик: цвета и радиусы числом. Число можно только уменьшать. Цель — 0.
const BUDGET = { colors: 57, radii: 5 };
const colorLines = UI_CSS.split("\n").filter((l) => /#[0-9a-fA-F]{3,8}\b|rgba?\(/.test(l)).length;
const radiusLines = UI_CSS.split("\n").filter((l) => /border-radius:\s*\d+px/.test(l) && !/border-radius:\s*0px/.test(l)).length;
check(colorLines <= BUDGET.colors, `цвета числом в ui.css: ${colorLines} (бюджет ${BUDGET.colors}, цель 0)`);
check(radiusLines <= BUDGET.radii, `радиусы числом в ui.css: ${radiusLines} (бюджет ${BUDGET.radii}, цель 0)`);

console.log("");
if (bad) {
  console.log(`ПРОВАЛЕНО: ${bad}`);
  process.exit(1);
}
console.log("Токены сайта совпадают с CanonTokens.kt");
