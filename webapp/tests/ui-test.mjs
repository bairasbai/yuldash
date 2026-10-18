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

/** CSS без комментариев. В пояснении «Отдельно от --danger: …;» разбор видел переменную и молча
 *  проглатывал следующую за ним настоящую — --danger-btn пропадал из палитры, и его пары не считались. */
const code = (css) => css.replace(/\/\*[\s\S]*?\*\//g, "");

/** Тело первого блока, который начинается с selector, — до парной закрывающей скобки. */
function blockBody(css, selector) {
  const start = css.indexOf(selector);
  if (start < 0) return null;
  const open = css.indexOf("{", start);
  for (let j = open, depth = 0; j < css.length; j++) {
    if (css[j] === "{") depth++;
    else if (css[j] === "}" && --depth === 0) return css.slice(open + 1, j);
  }
  return null;
}

const indexCode = code(index);
const lightBlock = blockBody(indexCode, ":root {");
// Тёмных веток две, и обе живые: системная тема (media) и выбранная вручную (data-theme). Значения
// в них переписаны дважды — проверяем каждую, а не одну за обе.
const darkSystemBlock = blockBody(blockBody(indexCode, "@media (prefers-color-scheme: dark)") ?? "", ":root:not([data-theme])");
const darkBlock = blockBody(indexCode, ':root[data-theme="dark"]');
const light = lightBlock ? varsOf(lightBlock) : {};
const dark = { ...light, ...(darkBlock ? varsOf(darkBlock) : {}) };
const darkSystem = { ...light, ...(darkSystemBlock ? varsOf(darkSystemBlock) : {}) };
const THEMES = [
  ["светлая", light],
  ["тёмная", dark],
  ["тёмная (системная)", darkSystem],
];

check(Object.keys(light).length > 30, "светлая палитра прочитана", `переменных: ${Object.keys(light).length}`);
check(darkBlock !== null, "тёмная палитра найдена");
check(darkSystemBlock !== null, "тёмная палитра системной темы найдена");
check(!!light["--danger-btn"], "палитра читается целиком: --danger-btn не пропал за комментарием");

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
  ["--on-accent", "--green-btn", 4.5, "белая надпись на зелёной заливке (--green-btn)"],
  ["--on-filled", "--green2", 4.5, "надпись на главной кнопке (.btn-primary)"],
  ["--on-filled", "--danger-btn", 4.5, "надпись на красной кнопке («Отменить заказ»)"],
  ["--taxi-ink", "--taxi", 4.5, "подпись на жёлтой кнопке такси"],
  ["--taxi-text", "--taxi-bg", 4.5, "номер машины в бейдже"],
  ["--canon-gold-ink", "--canon-gold-soft", 4.5, "текст на жёлтой плашке"],
  ["--green", "--surface", 4.5, "подпись активной вкладки («Карта»)"],
  ["--gold-ink", "--canon-gold", 3.0, "значок активной вкладки на золотой плашке"],
];

for (const [theme, p] of THEMES) {
  for (const [fg, bg, min, what] of PAIRS) {
    check(!!p[fg] && !!p[bg], `${theme}: ${what} — оба токена есть`, `${fg} / ${bg}`);
    if (!p[fg] || !p[bg]) continue;
    const c = contrast(p[fg], p[bg]);
    if (c === null) continue; // не сплошной цвет (градиент, rgba) — считать нечем
    check(c >= min, `${theme}: ${what}`, `${c.toFixed(2)} (нужно ≥ ${min})`);
  }
}

// ---------------- правила ui.css: чем написано и на чём ----------------
//
// Пары выше — про токены, а человек читает ПРАВИЛО. Пара --canon-gold-ink / --canon-gold-soft была
// зелёной, а плашка «Пассажир в машине» писала --warn на --canon-gold-soft — 4.07 при норме 4.5;
// красная кнопка в тёмной теме — --on-filled на --danger-btn, 3.42; «Поднять» — белое на --green2,
// 3.19 (живая проверка 17.10.2026). Ни одну из этих пар список выше не видел.
//
// Здесь каждое правило ui.css, где и фон, и цвет надписи заданы токенами, считается во всех трёх
// темах. Порог 4.5 — текст. 3.0 — значок или крупный текст (≥ 19px жирным) — такие названы поимённо.
// Исключения — тоже поимённо и с причиной; устаревшее исключение валит набор.
const ICON_OR_LARGE = {
  ".nav-item.is-active .nav-pill": "значок вкладки на золотой плашке",
  ".invite-driver__ic": "значок в кружке",
  ".update-banner__ic": "значок в кружке",
};
const NOT_A_TEXT_PAIR = {
  ".btn-primary:disabled, .btn-danger:disabled": "выключенная кнопка: WCAG не требует контраста у неактивного",
  ".pay-method-row__check": "галочка рисуется только в .is-checked — там фон --taxi, а не этот",
  ".progress-rail__dot":
    "значок рисуется только в .is-done / .is-active на --rail-accent (зеркало MobilityProgressRail; белая галочка на жёлтом такси, 2.19, — задача в docs/tasks.md «Белая галочка на жёлтой рельсе такси»)",
  ".login-hero": "надпись лежит на фото под затемнением .login-hero__shade; зелёный — подложка, пока фото не пришло",
};
/** Правила, без которых проверка теряет смысл: их пара обязана считаться (не rgba, не color-mix). */
const MUST_COUNT = [
  ".notice, .chat__queued",
  ".offer-card__note",
  ".offer-note",
  ".btn-danger",
  ".abtn--danger",
  ".my-trip-card__primary",
  ".btn-primary",
  ".nav-item.is-active .nav-pill",
  ".segmented__item.is-selected",
  ".chat-tab__badge",
  ".tchat__badge",
  ".sos-112",
];

const tokenOf = (body, prop) =>
  body.match(new RegExp(`(?:^|[;{\\s])${prop}:\\s*var\\((--[\\w-]+)\\)\\s*(?:!important\\s*)?;`))?.[1] ?? null;
const RULE_PAIRS = [];
for (const m of code(ui).matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
  const selector = m[1].trim().replace(/\s+/g, " ");
  const bg = tokenOf(m[2], "background") ?? tokenOf(m[2], "background-color");
  const fg = tokenOf(m[2], "color");
  if (bg && fg) RULE_PAIRS.push({ selector, bg, fg });
}

const counted = new Set();
const LOW = [];
for (const { selector, bg, fg } of RULE_PAIRS) {
  if (selector in NOT_A_TEXT_PAIR) continue;
  const min = selector in ICON_OR_LARGE ? 3.0 : 4.5;
  for (const [theme, p] of THEMES) {
    const c = p[fg] && p[bg] ? contrast(p[fg], p[bg]) : null;
    if (c === null) continue; // rgba / color-mix: что под ним — по одному правилу не узнать
    counted.add(selector);
    if (c < min) LOW.push(`${selector} — ${theme}: ${fg} на ${bg} = ${c.toFixed(2)} (нужно ≥ ${min})`);
  }
}
check(RULE_PAIRS.length > 40, "правила с фоном и цветом надписи найдены", `правил: ${RULE_PAIRS.length}, посчитано: ${counted.size}`);
check(LOW.length === 0, "каждая надпись на своём фоне читается во всех трёх темах", LOW.slice(0, 20).join("\n    "));
for (const selector of MUST_COUNT) {
  check(counted.has(selector), `пара «${selector}» посчитана во всех темах (фон и цвет — сплошные токены)`);
}
for (const [selector, why] of Object.entries({ ...ICON_OR_LARGE, ...NOT_A_TEXT_PAIR })) {
  check(RULE_PAIRS.some((r) => r.selector === selector), `исключение «${selector}» ещё живо (${why})`);
}

// Правила без своего фона: его даёт тот, кто вокруг. Называем поимённо.
const CONTEXT = [
  [".nav-item.is-active .nav-item__label", "--surface", 4.5, "подпись активной вкладки — на панели (98 % --surface)"],
  [".nav-item__label", "--surface", 4.5, "подпись вкладки — на панели"],
  [".btn-ghost", "--surface", 4.5, "второе действие шторки («Не отменять», «Закрыть») — на .sheet"],
  [".act-card--warn .btn-ghost", "--warn-bg", 4.5, "«Не смогу ехать» на тёплой карточке водителя такси"],
  [".act-card--warn .btn-soft", "--warn-bg", 4.5, "рамочная кнопка на тёплой карточке"],
];
const ruleColor = (selector) => {
  for (const m of code(ui).matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    if (m[1].split(",").map((s) => s.trim().replace(/\s+/g, " ")).includes(selector) && tokenOf(m[2], "color")) return tokenOf(m[2], "color");
  }
  return null;
};
check(tokenOf(code(ui).match(/\.sheet \{([^}]*)\}/)?.[1] ?? "", "background") === "--surface", "фон шторки — --surface (на нём считаем её кнопки)");
for (const [selector, bg, min, what] of CONTEXT) {
  const fg = ruleColor(selector);
  check(!!fg, `${what}: цвет задан токеном`, selector);
  for (const [theme, p] of THEMES) {
    const c = fg ? contrast(p[fg], p[bg]) : null;
    check(c !== null && c >= min, `${theme}: ${what}`, c === null ? "не посчитать" : `${fg} = ${c.toFixed(2)} (нужно ≥ ${min})`);
  }
}

// Тёплая плашка: правка только для тёмной темы — в светлой кнопка остаётся тем же --green2, что везде
// (приёмка PR181: два разных зелёных на одной кнопке и лишняя перемена в светлой теме).
check(light["--green-on-warm"] === light["--green2"], "светлая: --green-on-warm = --green2 — на тёплых карточках ничего не поменялось", `${light["--green-on-warm"]} / ${light["--green2"]}`);

// ---------------- чип центрует надпись сам ----------------
//
// <button> центрует содержимое по высоте даром, <span> — нет. Чип-статус «Пассажир в машине» — <span
// class="chip chip--on">, и галочка с надписью липли к верху чипа высотой 44 (живая проверка 17.10.2026).
{
  const chip = code(ui).match(/(?:^|\})\s*\.chip \{([^}]*)\}/)?.[1] ?? "";
  check(/display:\s*inline-flex/.test(chip) && /align-items:\s*center/.test(chip), ".chip: надпись и значок по центру высоты в любом теге (inline-flex + align-items: center)");
  check(/gap:\s*var\(--space-/.test(chip), ".chip: зазор значок—слово со шкалы (во flex пробел между ними пропадает)");
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
