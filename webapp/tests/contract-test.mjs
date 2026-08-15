// ================================================================
//  Описание ответа сервера — обещание компилятору, а не документация.
//
//  Строгая проверка типов не даёт обратиться к пустому значению, но только
//  пока НАШИ описания честны. Описания пишутся руками по коду сервера, и стоит
//  один раз указать «поле всегда есть» — компилятор перестаёт сторожить именно
//  там, где сервер пришлёт пусто.
//
//  Сверяем механически: имя интерфейса = имя модели сервера, поле Optional
//  на сервере обязано допускать null у нас.
//
//  Сервера рядом нет (например, в другой машине) — набор просто пропускается.
// ================================================================
import { readdirSync, readFileSync, statSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const API = join(HERE, "..", "src", "api");
const MODELS = join(HERE, "..", "..", "..", "..", "..", "backend", "app", "models.py");

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};

if (!existsSync(MODELS)) {
  console.log("✓ сервер рядом не найден — сверка контракта пропущена");
  console.log("\nВСЁ СОШЛОСЬ");
  process.exit(0);
}

// ---------------- модели сервера ----------------
const py = readFileSync(MODELS, "utf8");
/** Модель → поле → «может ли прийти пустым». */
const server = {};
let current = null;
for (const line of py.split("\n")) {
  const cls = line.match(/^class (\w+)\(/);
  if (cls) {
    current = cls[1];
    server[current] = {};
    continue;
  }
  if (!current) continue;
  const f = line.match(/^ {4}(\w+):\s*(.+)/);
  if (!f) continue;
  const [, name, decl] = f;
  if (name.startsWith("_")) continue;
  server[current][name] = /Optional\[/.test(decl) || /=\s*None\b/.test(decl);
}
check(Object.keys(server).length > 20, "модели сервера прочитаны", `классов: ${Object.keys(server).length}`);

// ---------------- наши описания ----------------
// id в SQLModel всегда Optional (это первичный ключ), но в ответе он всегда есть.
const SKIP = new Set(["id"]);

const problems = [];
let checkedIfaces = 0;
for (const file of readdirSync(API).filter((f) => f.endsWith(".ts"))) {
  const src = readFileSync(join(API, file), "utf8");
  let iface = null;
  src.split("\n").forEach((line, i) => {
    const dec = line.match(/^export (?:interface|type) (\w+)/);
    if (dec) {
      iface = dec[1];
      if (server[iface]) checkedIfaces++;
      return;
    }
    if (!iface || !server[iface]) return;
    const f = line.match(/^ {2}(\w+)(\??):\s*([^;]+);/);
    if (!f) return;
    const [, name, optional, type] = f;
    if (SKIP.has(name) || optional === "?" || /null|undefined/.test(type)) return;
    if (!server[iface][name]) return; // на сервере поле обязательное — всё честно
    problems.push(`${file}:${i + 1} ${iface}.${name}: ${type.trim()}`);
  });
}

check(checkedIfaces > 5, "описания сверены с моделями по имени", `совпало имён: ${checkedIfaces}`);
check(
  problems.length === 0,
  "тип не обещает значение там, где сервер может прислать пусто",
  problems.slice(0, 4).join("; ")
);

// ---------------- ошибки сервера ----------------
//
// Сервер отдаёт двуязычные объекты {ru, ba} через herr(). Клиент обязан их
// разбирать, иначе человек читает «[object Object]» вместо объяснения.
// Комментарии выкидываем: в них разобрана СТАРАЯ ошибка («раньше было
// String(detail)»), и без этого проверка ругалась на объяснение самой себя.
const client = readFileSync(join(API, "client.ts"), "utf8")
  .replace(/\/\*[\s\S]*?\*\//g, "")
  .replace(/\/\/.*/g, "");
check(/typeof detail === "object"/.test(client), "двуязычный ответ сервера разбирается");
check(/Array\.isArray\(detail\)/.test(client), "ошибка проверки формы разбирается");
check(
  !/String\(\s*(?:body\?\.)?detail\s*\)/.test(client),
  "тело ошибки не превращается в строку вслепую"
);

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
