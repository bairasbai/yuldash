// ================================================================
//  Вес того, что скачивает человек при первом заходе.
//
//  Аудитория Юлдаша — сёла, где мобильный интернет медленный и платный.
//  Один забытый PNG на 2 МБ возвращает первый заход к семи мегабайтам,
//  и это не видно ни в типах, ни в сборке — только в счёте за трафик.
//
//  Смотрим исходные файлы, а не dist: проверка должна работать
//  до сборки и без неё.
// ================================================================
import { readdirSync, statSync, readFileSync } from "node:fs";
import { join, dirname, extname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const WEBAPP = join(HERE, "..");
const PUBLIC = join(WEBAPP, "public");

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};

const KB = 1024;

/** Ни один файл в public не должен быть тяжелее этого. */
const MAX_ONE_FILE_KB = 200;
/** Все картинки public вместе. */
const MAX_IMAGES_KB = 600;

function listFiles(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) listFiles(p, out);
    else out.push(p);
  }
  return out;
}

const files = listFiles(PUBLIC);
check(files.length > 0, "папка public прочитана", `файлов: ${files.length}`);

// 1. Тяжёлые файлы поимённо.
const heavy = files
  .map((p) => ({ p, kb: Math.round(statSync(p).size / KB) }))
  .filter((f) => f.kb > MAX_ONE_FILE_KB)
  .sort((a, b) => b.kb - a.kb);
check(
  heavy.length === 0,
  `нет файла тяжелее ${MAX_ONE_FILE_KB} КБ`,
  heavy.map((f) => `${f.p.split(/[\\/]/).pop()} = ${f.kb} КБ`).join(", ")
);

// 2. Общий вес картинок.
const IMG = [".png", ".jpg", ".jpeg", ".webp", ".gif", ".avif", ".ico"];
const imgKb = files
  .filter((p) => IMG.includes(extname(p).toLowerCase()))
  .reduce((s, p) => s + statSync(p).size, 0) / KB;
check(
  imgKb <= MAX_IMAGES_KB,
  `картинки вместе не тяжелее ${MAX_IMAGES_KB} КБ`,
  `${Math.round(imgKb)} КБ`
);

// 3. Фотографии не должны лежать PNG-ом.
//
// PNG хранит фотографию почти без сжатия: так три картинки весили 4,86 МБ
// вместо 335 КБ. Для иконок и логотипов PNG допустим — их отличает размер.
const bigPng = files.filter(
  (p) => extname(p).toLowerCase() === ".png" && statSync(p).size > 100 * KB
);
check(
  bigPng.length === 0,
  "фотографии не в PNG (для них WebP)",
  bigPng.map((p) => `${p.split(/[\\/]/).pop()} = ${Math.round(statSync(p).size / KB)} КБ`).join(", ")
);

// 4. Ссылки на картинки в коде не должны вести в никуда.
//
// Так после перевода в WebP могли остаться ссылки на удалённые PNG —
// и человек увидел бы битую картинку на первом же экране.
const SRC = join(WEBAPP, "src");
function listSrc(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) listSrc(p, out);
    else if (/\.(tsx?|css)$/.test(name)) out.push(p);
  }
  return out;
}
const publicNames = new Set(files.map((p) => p.split(/[\\/]/).pop()));
const broken = [];
for (const p of listSrc(SRC)) {
  const src = readFileSync(p, "utf8");
  for (const m of src.matchAll(/["'(]\/([\w.-]+\.(?:png|jpe?g|webp|gif|avif|svg|ico))["')]/g)) {
    if (!publicNames.has(m[1])) broken.push(`${p.split(/[\\/]/).pop()} → /${m[1]}`);
  }
}
check(broken.length === 0, "все ссылки на картинки ведут на существующие файлы", broken.slice(0, 3).join(", "));

// 5. Неиспользуемые картинки — их скачивают все, а видит никто.
const usedNames = new Set();
for (const p of listSrc(SRC)) {
  const src = readFileSync(p, "utf8");
  for (const m of src.matchAll(/[\w.-]+\.(?:png|jpe?g|webp|gif|avif|svg|ico)/g)) usedNames.add(m[0]);
}
// Картинку может упоминать не только код: манифест (иконки приложения),
// index.html (favicon, иконка для экрана «Домой») и настройка офлайн-кеша.
for (const extra of [
  join(WEBAPP, "index.html"),
  join(PUBLIC, "manifest.webmanifest"),
  join(WEBAPP, "vite.config.ts"),
]) {
  try {
    const src = readFileSync(extra, "utf8");
    for (const m of src.matchAll(/[\w.-]+\.(?:png|jpe?g|webp|gif|avif|svg|ico)/g)) usedNames.add(m[0]);
  } catch {
    /* файла нет — не страшно */
  }
}
const unused = files
  .filter((p) => IMG.includes(extname(p).toLowerCase()))
  .map((p) => p.split(/[\\/]/).pop())
  .filter((n) => !usedNames.has(n));
check(
  unused.length === 0,
  "нет картинок, которые никто не показывает",
  unused.join(", ")
);

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
