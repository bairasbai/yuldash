// Приёмка попутки 24.10.2026: подписи, найденные на настоящем стенде (Chromium + свой сервер).
//  1) Шапка чата у водителя называла собеседника «Пассажир», хотя сервер отдаёт имя (приложение его показывает).
//  2) Звёзды оценки в поездке читались экранным чтецом как «1 звёзд», «2 звёзд».
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";

let passed = 0;
const check = (label, fn) => { fn(); passed++; console.log(`✓ ${label}`); };
const src = (p) => readFileSync(new URL(`../src/${p}`, import.meta.url), "utf8");

const chat = src("screens/TripChatScreen.tsx");
check("driver chat header uses the passenger name from booking details", () =>
  assert.match(chat, /details\.role === "driver"\s*(\/\/[^\n]*\n\s*)?\?\s*details\.passenger_name\?\.trim\(\) \|\| appText\("Пассажир", "Юлсы"\)/));
check("booking details type carries passenger_name", () =>
  assert.match(src("api/bookings.ts"), /passenger_name\?: string;/));

const trip = src("screens/ActiveTripScreen.tsx");
check("trip rating stars are labelled with a Russian plural", () => {
  assert.ok(!trip.includes("`${n} звёзд`"), "literal «N звёзд» label is back");
  assert.match(trip, /aria-label=\{appText\(`\$\{n\} \$\{pluralRu\(n, "звезда", "звезды", "звёзд"\)\}`, `\$\{n\} йондоҙ`\)\}/);
});

const fmt = await build({
  entryPoints: [fileURLToPath(new URL("../src/utils/format.ts", import.meta.url))],
  bundle: true, write: false, format: "esm", platform: "node", define: { "import.meta.env": "{}" },
});
const { pluralRu } = await import("data:text/javascript;base64," + Buffer.from(fmt.outputFiles[0].text).toString("base64"));
check("1/2/5 stars read as «1 звезда», «2 звезды», «5 звёзд»", () => {
  const word = (n) => `${n} ${pluralRu(n, "звезда", "звезды", "звёзд")}`;
  assert.deepEqual([1, 2, 3, 4, 5].map(word), ["1 звезда", "2 звезды", "3 звезды", "4 звезды", "5 звёзд"]);
});

console.log(`Rideshare acceptance labels: ${passed} passed`);
