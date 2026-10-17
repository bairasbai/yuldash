// ================================================================
//  Паритет с Android, шаг 4 (17.10.2026): одно действие — одно событие аналитики.
//
//  Было: «Опубликовать поездку» отправлял `publish_ride` дважды подряд, «Отозвать доступ»
//  в «Поделиться поездкой» — `revoke_share` дважды, «на линии» курьера — тоже дважды.
//  В воронке публикаций сайт выглядел вдвое продуктивнее приложения — решения по таким цифрам
//  принимать нельзя. В Android на каждое действие ровно одно событие.
//
//  Читает исходники (комментарии выброшены): одинаковый вызов track(...) два раза подряд —
//  поломка, по всему src/; и у трёх найденных мест событие ровно одно.
// ================================================================
import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { ROOT, checker } from "./shallow-harness.mjs";

const t = checker();

function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? files(p) : /\.(tsx?|jsx?)$/.test(name) ? [p] : [];
  });
}
const code = (p) =>
  readFileSync(p, "utf8")
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "))
    .split("\n")
    .map((l) => (l.trim().startsWith("//") ? "" : l));

await t.test("нигде нет одного и того же track(...) два раза подряд", () => {
  const hits = [];
  for (const p of files(ROOT + "src")) {
    const lines = code(p);
    let prev = "";
    lines.forEach((line, i) => {
      const cur = line.trim();
      if (!cur) return;
      if (/^track(Once)?\(/.test(cur) && cur === prev) hits.push(`${p.replace(ROOT, "")}:${i + 1}  ${cur}`);
      prev = cur;
    });
  }
  assert.equal(hits.length, 0, "двойные события:\n" + hits.join("\n"));
});

for (const [rel, call] of [
  ["src/screens/CreateRideScreen.tsx", 'track("publish_ride"'],
  ["src/components/ShareTripCard.tsx", 'track("revoke_share")'],
  ["src/screens/CourierScreen.tsx", 'track(online ? "courier_offline" : "courier_online")'],
]) {
  await t.test(`${rel}: ${call} — ровно один раз`, () => {
    const n = code(ROOT + rel).join("\n").split(call).length - 1;
    assert.equal(n, 1, `найдено ${n}`);
  });
}

t.done();
