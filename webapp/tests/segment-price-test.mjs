// RS-02 А/Б: цена и свободные места участка на сайте — та же таблица, что у сервера
// (backend/tests/test_segment_price.py) и Android (SegmentPriceTest/CreateRideStopsTest).
// Сибай → Ургаза → Бурибай → Акъяр за 500 ₽, цены до остановок [300, 400].
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { checker } from "./shallow-harness.mjs";

// pickupStops.ts — TypeScript: собираем esbuild-ом и импортируем из памяти, как делают
// компонентные наборы (bundleComponent), только здесь нам нужны чистые функции.
const bundled = await build({
  entryPoints: [fileURLToPath(new URL("../src/utils/pickupStops.ts", import.meta.url))],
  bundle: true, format: "esm", write: false, platform: "node", logLevel: "silent",
});
const { segmentPriceRub, seatsOnSpan, splitPriceByLegs, legPriceAmounts } =
  await import("data:text/javascript;base64," + Buffer.from(bundled.outputFiles[0].text).toString("base64"));

const t = checker();

const ride = (prices = [300, 400], legs = []) => ({
  id: 1, from_city: "Сибай", to_city: "Акъяр", waypoints: "Ургаза | Бурибай",
  price: 500, seats_left: 3,
  stop_prices: ["Ургаза", "Бурибай"].slice(0, prices.length).map((stop, i) => ({ stop, price: prices[i] })),
  seats_by_leg: legs,
});

await t.test("без цен остановок — полная цена за любой участок", () => {
  const r = ride([]);
  assert.equal(segmentPriceRub(r, "Ургаза", "Акъяр"), 500);
  assert.equal(segmentPriceRub(r, "Сибай", "Ургаза"), 500);
});

await t.test("цены по частям маршрута — cum(высадка) минус cum(посадка)", () => {
  const r = ride();
  assert.equal(segmentPriceRub(r, "Сибай", "Ургаза"), 300);
  assert.equal(segmentPriceRub(r, "Ургаза", "Акъяр"), 200);
  assert.equal(segmentPriceRub(r, "Ургаза", "Бурибай"), 100);
  assert.equal(segmentPriceRub(r, "Сибай", "Акъяр"), 500);
});

await t.test("незнакомые и пустые концы — края маршрута", () => {
  const r = ride();
  assert.equal(segmentPriceRub(r, null, null), 500);
  assert.equal(segmentPriceRub(r, "Ургаза", null), 200);
  assert.equal(segmentPriceRub(r, null, "Ургаза"), 300);
});

await t.test("места на участке — минимум по его звеньям; несошедшийся список — null", () => {
  const r = ride([300, 400], [0, 3, 3]);
  assert.equal(seatsOnSpan(r, "Сибай", "Ургаза"), 0);
  assert.equal(seatsOnSpan(r, "Ургаза", "Акъяр"), 3);
  assert.equal(seatsOnSpan(r, "Сибай", "Акъяр"), 0);
  assert.equal(seatsOnSpan(ride(), "Сибай", "Ургаза"), null);          // списка нет
  assert.equal(seatsOnSpan(ride([300, 400], [1, 2]), "Сибай", "Ургаза"), null);  // длина не сошлась
});

await t.test("деление по расстоянию: шаг 10 ₽, рост шагом в рубль, не помещается — null", () => {
  assert.deepEqual(splitPriceByLegs(500, [30, 20]), [300]);
  assert.deepEqual(splitPriceByLegs(500, [10, 10, 30]), [100, 200]);
  assert.deepEqual(splitPriceByLegs(500, [null, null]), [250]);       // незнакомые — поровну
  assert.deepEqual(splitPriceByLegs(21, [1, 1, 1]), [1, 10]);         // рост важнее круглости
  assert.equal(splitPriceByLegs(2, [1, 1, 1]), null);                 // не помещается
  assert.equal(splitPriceByLegs(500, []), null);
});

await t.test("суммы участков из кумулятивных цен", () => {
  assert.deepEqual(legPriceAmounts([300], 500), [300, 200]);
  assert.deepEqual(legPriceAmounts([300, 400], 500), [300, 100, 100]);
});
