// ================================================================
//  Правила оформления, которые легко сломать незаметно.
//
//  Контраст, размер кнопок и «только цвета со шкалы» — это не вкусовщина:
//  аудитория Юлдаша это сёла, много пожилых людей, экран часто на солнце
//  или в темноте машины. Сломанный контраст не виден на ноутбуке разработчика
//  и отлично виден бабушке, которая не может прочитать номер машины.
//
//  Читает CSS напрямую — сборка не нужна.
// ================================================================
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = join(HERE, "..", "src");
const index = readFileSync(join(SRC, "index.css"), "utf8");
const ui = readFileSync(join(SRC, "ui.css"), "utf8");

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};

// ---------------- разбор палитры ----------------

/** Переменные из блока: `--name: value;` */
function varsOf(block) {
  const out = {};
  for (const m of block.matchAll(/(--[\w-]+):\s*([^;]+);/g)) out[m[1]] = m[2].trim();
  return out;
}

const lightBlock = index.match(/:root\s*\{([\s\S]*?)\n\}/);
const darkBlock = index.match(/\[data-theme="dark"\][^{]*\{([\s\S]*?)\n\}/);
const light = lightBlock ? varsOf(lightBlock[1]) : {};
const dark = { ...light, ...(darkBlock ? varsOf(darkBlock[1]) : {}) };

check(Object.keys(light).length > 30, "светлая палитра прочитана", `переменных: ${Object.keys(light).length}`);
check(darkBlock !== null, "тёмная палитра найдена");

/** #rrggbb → относительная яркость. */
function lum(hex) {
  let h = String(hex).trim().replace("#", "");
  if (h.length === 3) h = [...h].map((c) => c + c).join("");
  if (h.length < 6 || /[^0-9a-f]/i.test(h.slice(0, 6))) return null;
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
  const f = (c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

function contrast(a, b) {
  const la = lum(a);
  const lb = lum(b);
  if (la === null || lb === null) return null;
  const hi = Math.max(la, lb);
  const lo = Math.min(la, lb);
  return (hi + 0.05) / (lo + 0.05);
}

/**
 * Пары «текст на фоне», которые человек читает каждый день.
 * Порог 4.5 — обычный текст, 3.0 — крупный и элементы управления (WCAG AA).
 */
const PAIRS = [
  ["--text", "--bg", 4.5, "основной текст на фоне"],
  ["--text", "--surface", 4.5, "текст на карточке"],
  ["--muted", "--surface", 4.5, "подпись на карточке"],
  ["--muted", "--bg", 4.5, "подпись на фоне"],
  ["--green", "--surface", 3.0, "зелёный акцент на карточке"],
  ["--warn", "--surface", 3.0, "предупреждение на карточке"],
  ["--danger", "--surface", 3.0, "опасное действие на карточке"],
  ["--on-accent", "--green-btn", 4.5, "надпись на главной кнопке"],
  ["--taxi-ink", "--taxi", 4.5, "подпись на жёлтой кнопке такси"],
  ["--taxi-text", "--taxi-bg", 4.5, "номер машины в бейдже"],
  ["--canon-gold-ink", "--canon-gold-soft", 4.5, "текст на жёлтой плашке"],
];

for (const theme of ["светлая", "тёмная"]) {
  const p = theme === "светлая" ? light : dark;
  for (const [fg, bg, min, what] of PAIRS) {
    if (!p[fg] || !p[bg]) continue;
    const c = contrast(p[fg], p[bg]);
    if (c === null) continue; // не сплошной цвет (градиент, rgba) — считать нечем
    check(c >= min, `${theme}: ${what}`, `${c.toFixed(2)} (нужно ≥ ${min})`);
  }
}

// ---------------- тач-цели ----------------
//
// Правило проекта: всё, что нажимают пальцем, — не меньше 48px.
// Пальцу негде промахнуться в машине на ходу.
const SMALL = [];
for (const m of ui.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
  const sel = m[1].trim();
  const body = m[2];
  if (!/button|\.chip|\.tab|\.nav-item|\.icon-btn|\.rate-star/.test(sel)) continue;
  const h = body.match(/(?:min-height|height):\s*(\d+)px/);
  if (!h) continue;
  const px = Number(h[1]);
  // Мелкие декоративные кружки внутри кнопки — не сама тач-цель.
  if (/__dot|__icon|__avatar|::/.test(sel)) continue;
  if (px < 44) SMALL.push(`${sel.split("\n").pop().trim()} = ${px}px`);
}
check(SMALL.length === 0, "нажимаемое не мельче 44px", SMALL.slice(0, 3).join("; "));

// ---------------- опасный хардкод цвета ----------------
//
// Само по себе число вместо переменной не беда: белый текст на фиксированно
// тёмной шапке белый в любой теме. Беда — когда ТЕКСТ написан числом,
// а ФОН взят переменной, которая в тёмной теме меняется. Тогда фон уезжает,
// текст остаётся, и получается тёмное на тёмном.
//
// Так было с иконкой приглашения: контраст 1.59 вместо 8.79.
const RISKY = [];
for (const m of ui.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
  const sel = m[1].trim();
  const body = m[2];
  const fg = body.match(/(?:^|\n)\s*color:\s*(#[0-9a-f]{3,8})\s*;/i);
  const bg = body.match(/(?:^|\n)\s*background(?:-color)?:\s*var\((--[\w-]+)\)/i);
  if (!fg || !bg) continue;
  const varName = bg[1];
  // Опасно, только если переменная фона в тёмной теме ДРУГАЯ.
  if (!light[varName] || !dark[varName] || light[varName] === dark[varName]) continue;
  const cLight = contrast(fg[1], light[varName]);
  const cDark = contrast(fg[1], dark[varName]);
  if (cDark === null) continue;
  if (cDark < 4.5) {
    const line = ui.slice(0, m.index).split("\n").length;
    RISKY.push(
      `${sel.split("\n").pop().trim()} (ui.css:${line}) — в тёмной ${cDark.toFixed(2)}, в светлой ${cLight?.toFixed(2)}`
    );
  }
}
check(
  RISKY.length === 0,
  "нет текста-числа на фоне, который меняется в тёмной теме",
  RISKY.slice(0, 3).join("; ")
);

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
