// ================================================================
//  Словарь башкирских терминов соблюдается?
//
//  Решения носителя записаны в docs/glossary-ba.md. Этот набор проверяет,
//  что они не размылись обратно: одно слово — один перевод, и никакого
//  русского корня с башкирскими окончаниями («водителгә»).
//
//  Читает исходники напрямую — сборка тут не нужна.
// ================================================================
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB = join(HERE, "..", "src");
// Android из того же checkout: лишние переходы вверх смешивали разные ветки.
const APP = join(HERE, "..", "..", "android", "app", "src", "main", "java", "com", "yuldash", "app");

/** Утверждённые термины: русский → единственно верный башкирский. */
const GLOSSARY = {
  "Водитель": "Йөрөтөүсе",
  "Добавить": "Өҫтәргә",
  "Назад": "Артҡа",
  "Наличными": "Наличный менән",
  "Водительские права": "Водитель праваһы",
  "Отзывы": "Кире бәйләнеш",
};

/**
 * Русские корни, которых не должно быть внутри башкирских строк.
 * Исключение — фразы из словаря, где носитель оставил слово намеренно.
 */
const FORBIDDEN_STEMS = [
  { stem: /водител/i, right: "Йөрөтөүсе", allow: ["Водитель праваһы"] },
];

// Обе половины могут быть пустыми: так делают «обёртки» вокруг значения,
// когда предлог в русском стоит до, а послелог в башкирском — после.
const PAIR = /appText\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"/g;

function walk(dir, exts, out = []) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return out; // приложения рядом нет — проверим только сайт
  }
  for (const name of entries) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, exts, out);
    else if (exts.some((e) => name.endsWith(e))) out.push(p);
  }
  return out;
}

const files = [
  ...walk(WEB, [".ts", ".tsx"]).map((p) => ["сайт", p]),
  ...walk(APP, [".kt"]).map((p) => ["приложение", p]),
];

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};

// Собираем все пары один раз.
const pairs = [];
for (const [where, p] of files) {
  const src = readFileSync(p, "utf8");
  PAIR.lastIndex = 0;
  let m;
  while ((m = PAIR.exec(src))) {
    pairs.push({
      where,
      file: p.split(/[\\/]/).pop(),
      line: src.slice(0, m.index).split("\n").length,
      ru: m[1].trim(),
      ba: m[2].trim(),
    });
  }
}
check(pairs.length > 500, "надписи найдены", `пар: ${pairs.length}, файлов: ${files.length}`);

// 1. Каждый термин словаря переводится ровно так, как решил носитель.
for (const [ru, want] of Object.entries(GLOSSARY)) {
  const hits = pairs.filter((x) => x.ru === ru);
  const wrong = hits.filter((x) => x.ba !== want);
  check(
    wrong.length === 0,
    `«${ru}» → «${want}»`,
    wrong.length
      ? `нарушений ${wrong.length}: ${wrong.slice(0, 2).map((x) => `${x.file}:${x.line} «${x.ba}»`).join(", ")}`
      : `мест: ${hits.length}`
  );
}

// 2. Русский корень внутри башкирской строки.
for (const { stem, right, allow } of FORBIDDEN_STEMS) {
  const wrong = pairs.filter((x) => stem.test(x.ba) && !allow.includes(x.ba));
  check(
    wrong.length === 0,
    `русский корень ${stem.source} не встречается в башкирском (нужно «${right}»)`,
    wrong.length ? `${wrong.length}: ${wrong.slice(0, 2).map((x) => `${x.file}:${x.line}`).join(", ")}` : ""
  );
}

// 3. Ни одна надпись не осталась без второй половины.
//
// Исключение — «обёртка вокруг значения». Порядок слов в языках разный:
// по-русски предлог идёт ДО («До 5 авг»), по-башкирски послелог ПОСЛЕ
// («5 авг ҡәҙәр»). Поэтому фраза намеренно разбита на две части:
//     appText("До ", "")  {дата}  appText("", " ҡәҙәр")
// Обе половины на месте, просто в разных концах. Считаем это нормой, если
// рядом (в пределах пяти строк того же файла) есть зеркальная пара.
const halves = pairs.filter((x) => (x.ru && !x.ba) || (!x.ru && x.ba));
const empty = halves.filter((x) => {
  if (!x.ru) return false; // пустая русская половина — вторая часть обёртки
  return !halves.some(
    (y) => y.file === x.file && !y.ru && y.ba && Math.abs(y.line - x.line) <= 5
  );
});
check(
  empty.length === 0,
  "у каждой надписи есть башкирская половина",
  empty.length
    ? `пусто: ${empty.map((x) => `${x.file}:${x.line} «${x.ru}»`).join(", ")}`
    : `обёрток «предлог + значение»: ${halves.length / 2}`
);

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
