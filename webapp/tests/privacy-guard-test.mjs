// ================================================================
//  Три правила, которые защищают человека, а не код.
//
//  1. Каждый ключ хранилища явно отнесён к человеку или к устройству.
//     Забыть про новый ключ легко — и тогда на общем телефоне следующий
//     вошедший наследует чужое (мы так нашли согласия 152-ФЗ, роль,
//     историю поиска и номер активного заказа).
//  2. Уход на сайт банка запоминает платёж. Иначе человек возвращается,
//     а сайт не знает, за что он платил.
//  3. Запрос разрешения имеет непустой обработчик отказа. Молчащая кнопка
//     выглядит поломкой, а не запретом.
// ================================================================
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = join(HERE, "..", "src");

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};

function listSrc(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) listSrc(p, out);
    else if (/\.(ts|tsx)$/.test(name)) out.push(p);
  }
  return out;
}

const files = listSrc(SRC).map((p) => ({
  name: p.split(/[\\/]/).pop(),
  code: readFileSync(p, "utf8"),
}));

// ---------------- 1. Ключи хранилища ----------------
//
// Настройки устройства остаются следующему человеку намеренно: язык, тема,
// размер шрифта, «видел онбординг». Всё остальное обязано быть в чистке.
const DEVICE_KEYS = new Set([
  "yuldash.lang",
  "yuldash.theme",
  "yuldash.fontScale",
  "yuldash.simpleMode",
  "yuldash.introSeen",
  "yuldash.onboarded",
  "yuldash.install.dismissed",
  "yuldash.chatSafetySeen",
  "yuldash.chunkReload",
  "yuldash.pref.notifications",
  "yuldash.pref.sounds",
]);
// Эти чистятся своим кодом, не через privacy.ts.
const CLEARED_ELSEWHERE = new Set([
  "yuldash.token",
  "yuldash.refresh",
  "yuldash.outbox",
  "yuldash.draft.",
]);

const privacy = files.find((f) => f.name === "privacy.ts")?.code ?? "";
check(privacy.length > 0, "список чистки найден");

const allKeys = new Set();
for (const f of files) {
  for (const m of f.code.matchAll(/["'`](yuldash\.[\w.]*)/g)) allKeys.add(m[1]);
}
check(allKeys.size > 10, "ключи хранилища собраны", `всего: ${allKeys.size}`);

const unclassified = [...allKeys].filter((k) => {
  if (DEVICE_KEYS.has(k)) return false;
  if ([...CLEARED_ELSEWHERE].some((c) => k.startsWith(c))) return false;
  // В privacy.ts ключ может быть целиком или как префикс («yuldash.winterAsk.»).
  return !privacy.includes(k) && !privacy.includes(k.replace(/\.$/, ""));
});
check(
  unclassified.length === 0,
  "каждый ключ хранилища отнесён к человеку или к устройству",
  unclassified.join(", ")
);

// ---------------- 2. Уход на сайт банка ----------------
//
// `window.location.href = ...` закрывает наш сайт. Всё, что жило в памяти
// страницы, исчезает — значит номер платежа надо запомнить ДО ухода.
const leaves = [];
for (const f of files) {
  for (const m of f.code.matchAll(/window\.location\.href\s*=\s*([\w.?]+)/g)) {
    const target = m[1];
    if (!/confirmation|payment|pay/i.test(target)) continue;
    const around = f.code.slice(Math.max(0, m.index - 400), m.index);
    if (/rememberPayment/.test(around)) continue;
    leaves.push(`${f.name}:${f.code.slice(0, m.index).split("\n").length}`);
  }
}
check(
  leaves.length === 0,
  "уход на оплату запоминает платёж",
  leaves.join(", ")
);

// ---------------- 3. Отказ в разрешении ----------------
//
// Второй аргумент getCurrentPosition / watchPosition — обработчик отказа.
// Пустой `() => {}` означает: человек нажал, ничего не произошло, объяснения нет.
const mute = [];
for (const f of files) {
  for (const m of f.code.matchAll(
    /(getCurrentPosition|watchPosition)\s*\(([\s\S]{0,600}?)\)\s*;/g
  )) {
    if (/\(\s*\)\s*=>\s*\{\s*\}/.test(m[2])) {
      mute.push(`${f.name}:${f.code.slice(0, m.index).split("\n").length} ${m[1]}`);
    }
  }
}
check(
  mute.length === 0,
  "отказ в геолокации не проходит молча",
  mute.join(", ")
);

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
