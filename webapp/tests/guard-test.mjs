// ================================================================
//  Три поломки, которые уже случались и легко возвращаются.
//
//  1. Время сервера через `new Date(...)` — уезжает на часовой пояс (5 часов
//     в Уфе). Так «взять заказ» у таксиста всегда считался истёкшим.
//  2. Пустой `catch {}` после действия человека — он жмёт кнопку, ничего
//     не происходит, и понять причину неоткуда.
//  3. Экран меняется до ответа сервера, а при отказе не возвращается —
//     человек видит картинку, которой на сервере нет.
//
//  Проверка читает исходники; комментарии выкидываются, чтобы объяснения
//  прошлых ошибок не принимались за сами ошибки.
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

/** Код без комментариев — но с сохранением номеров строк. */
function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "))
    .replace(/\/\/[^\n]*/g, (m) => " ".repeat(m.length));
}

const files = listSrc(SRC).map((p) => ({
  path: p,
  name: p.split(/[\\/]/).pop(),
  code: stripComments(readFileSync(p, "utf8")),
}));
check(files.length > 50, "исходники прочитаны", `файлов: ${files.length}`);

const lineOf = (code, idx) => code.slice(0, idx).split("\n").length;

// ---------------- 1. Время сервера ----------------
//
// Сервер отдаёт наивный UTC («2026-08-14T10:00:00»), и `new Date()` читает
// такую строку как МЕСТНОЕ время. Для серверных полей нужен serverDate/serverMs.
//
// Разрешено:
//  • `new Date()` — «сейчас»;
//  • `new Date(when)` — значение из поля выбора даты, оно и есть местное;
//  • `new Date(iso + "T00:00:00")` — день без времени;
//  • сам модуль serverTime.
// Ловим snake_case — так называются поля, пришедшие с сервера («depart_at»,
// «created_at», «scheduled_at»). Локальные переменные формы (schedAt, when)
// содержат МЕСТНОЕ время человека, и `new Date` для них верен.
const SERVER_FIELD = /new Date\(\s*(?:\w+[.?]+)?(\w+_at|iso|timestamp)\b\s*\)/;
const timeProblems = [];
for (const f of files) {
  if (f.name === "serverTime.ts") continue;
  f.code.split("\n").forEach((line, i) => {
    const m = line.match(SERVER_FIELD);
    if (!m) return;
    timeProblems.push(`${f.name}:${i + 1} ${m[0]}`);
  });
}
check(
  timeProblems.length === 0,
  "время сервера читается через serverDate/serverMs, а не new Date",
  timeProblems.slice(0, 3).join("; ")
);

// Заодно: сам разбор должен трактовать наивную строку как UTC.
const st = files.find((f) => f.name === "serverTime.ts");
check(!!st && /\$\{raw\.replace\(" ", "T"\)\}Z|`\$\{[^`]*\}Z`/.test(st.code),
  "наивное время достраивается до UTC");

// ---------------- 2. Молчаливая неудача ----------------
//
// Молчать иногда правильно: звук оффера не критичен, отказ уже прошёл,
// 401 разберёт слой авторизации. Неправильно — молчать НЕ ПОДУМАВ.
//
// Поэтому правило не «catch обязан говорить», а «catch обязан объяснить,
// почему он молчит». Голый `catch {}` без единого слова — это не решение,
// а недосмотр: перечитать такое место через полгода невозможно.
const bareCatch = [];
for (const f of files) {
  const raw = readFileSync(f.path, "utf8");
  for (const m of raw.matchAll(/catch\s*(?:\(\s*\w*\s*\))?\s*\{\s*\}/g)) {
    bareCatch.push(`${f.name}:${raw.slice(0, m.index).split("\n").length}`);
  }
}
check(
  bareCatch.length === 0,
  "нет пустого catch без объяснения, почему молчим",
  bareCatch.slice(0, 5).join(", ")
);

// ---------------- 3. Оптимизм без отката ----------------
//
// Меняем экран до ответа сервера — обязаны вернуть, если он отказал.
// Флаги занятости (setBusy) сюда не относятся: их снимает finally.
// Перечислять «что не данные» бесполезно: имён флагов бесконечно много
// (Busy, BusyId, PayBusy, Bargaining…). Перечисляем наоборот — что ТОЧНО данные,
// то есть меняет смысл на экране и обязано вернуться при отказе сервера:
// списки, счётчики, выбранные значения.
const IS_DATA =
  /(Items|Rows|List|Count|Unread|Places|Watches|Zone|Messages|Balance|Coupons|Schedules|Contacts|Cards|Stars|Rating)$/i;
const noRollback = [];
for (const f of files) {
  for (const m of f.code.matchAll(/async (?:function )?(\w+)[^{]*\{/g)) {
    const start = f.code.indexOf("{", m.index + m[0].length - 1);
    let depth = 0;
    let end = start;
    for (let i = start; i < f.code.length; i++) {
      if (f.code[i] === "{") depth++;
      else if (f.code[i] === "}" && --depth === 0) {
        end = i;
        break;
      }
    }
    const body = f.code.slice(start, end + 1);
    if (!body.includes("await ")) continue;
    const head = body.split("await ")[0];
    const pre = [...head.matchAll(/\bset([A-Z]\w+)\(/g)]
      .map((x) => x[1])
      .filter((n) => IS_DATA.test(n));
    if (!pre.length) continue;
    const catchBlock = body.match(/catch[^{]*\{([\s\S]*?)\n\s*\}/);
    const rescue = catchBlock ? catchBlock[1] : "";
    // Откат бывает двух видов: вернуть прежнее значение вручную…
    const after = [...rescue.matchAll(/\bset([A-Z]\w+)\(/g)].map((x) => x[1]);
    // …или перечитать правду с сервера — это тоже полноценный откат.
    const reloads = /\b(load|reload|refresh|refetch)\s*\(/.test(rescue);
    if (reloads || pre.some((n) => after.includes(n))) continue;
    noRollback.push(`${f.name}:${lineOf(f.code, m.index)} ${m[1]}() меняет ${pre[0]}`);
  }
}
check(
  noRollback.length === 0,
  "список или счётчик, изменённый до ответа сервера, умеет откатываться",
  noRollback.slice(0, 4).join("; ")
);

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
