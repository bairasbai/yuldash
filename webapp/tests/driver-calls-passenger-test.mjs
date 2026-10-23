// ================================================================
//  Водитель звонит пассажиру после подтверждения (решение Александра 23.10.2026).
//
//  Было: в попутке водитель не видел номер пассажира вовсе, а подсказка обещала «открывается обоим».
//  Стало: сервер отдаёт `passenger_phone` только водителю и только пока связь открыта; экран поездки
//  водителя показывает строку «Пассажир · Позвонить», как пассажиру — «Водитель · Позвонить».
//  Сверяем по исходнику (экран тяжёлый для поверхностной отрисовки).
// ================================================================
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { ROOT, checker } from "./shallow-harness.mjs";

const t = checker();
const src = readFileSync(ROOT + "src/screens/ActiveTripScreen.tsx", "utf8").replace(/\r\n/g, "\n");
const api = readFileSync(ROOT + "src/api/bookings.ts", "utf8");

await t.test("звонок пассажиру — только водителю, только при открытой связи и не после отмены", () => {
  assert.ok(
    /st !== "cancelled" && details\.role === "driver" && details\.contact_unlocked && details\.passenger_phone/.test(src),
    "условие показа строки звонка"
  );
  assert.ok(src.includes("href={`tel:${details.passenger_phone}`}"), "звонок идёт на номер пассажира");
  assert.ok(src.includes('appText("Пассажир", "Пассажир")'), "подпись строки на двух языках");
});

await t.test("тип деталей брони знает поле passenger_phone", () => {
  assert.ok(/passenger_phone\?: string;/.test(api));
});

await t.test("экран брони пассажира говорит «Водитель отклонил бронь», если сервер так решил", () => {
  const booking = readFileSync(ROOT + "src/screens/BookingScreen.tsx", "utf8");
  assert.ok(booking.includes('d.status === "cancelled" && d.declined'), "условие отказа");
  assert.ok(booking.includes("Водитель отклонил бронь"), "текст отказа");
  assert.ok(/declined\?: boolean;/.test(api), "поле в типе деталей");
});

t.done();
