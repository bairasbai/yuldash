// ================================================================
//  Паритет с Android, шаг 3 (17.10.2026): «Едут с тобой» → «Открыть поездку».
//
//  Было: в кабинете водителя один список «Мои пассажиры». Кнопки «Открыть поездку» нет —
//  дойти до экрана поездки (статусы «Выехал / Подъезжаю», чат, точка) водителю с сайта было
//  нечем. А звёзды оценки висели и у тех, кто ещё едет, и у отменённых: сервер такую оценку
//  отклоняет, человек жал звезду и читал «Оценка не ушла».
//
//  Стало как в Android (ProfileScreen.kt, ridingBookings / ratableBookings):
//   • «Едут с тобой» — подтверждённые и «в пути», у каждой «Открыть поездку» → /trip/{id};
//   • «Пассажиры — оцените после поездки» — только завершённые, звёзды только здесь;
//   • отменённых и ждущих ответа в этом блоке нет.
//
//  Исполняем настоящий DriverPassengers; сеть и язык подменены (shallow-harness.mjs).
// ================================================================
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { ROOT, all, bundleComponent, button, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";

const ROWS = [
  { booking_id: 1, passenger_id: 11, passenger_name: "Айгуль", passenger_rating: 4.9, route: "Уфа → Сибай", status: "confirmed", my_stars: 0 },
  { booking_id: 2, passenger_id: 12, passenger_name: "Ринат", passenger_rating: null, route: "Уфа → Сибай", status: "onboard", my_stars: 0 },
  { booking_id: 3, passenger_id: 13, passenger_name: "Гульнара", passenger_rating: 5, route: "Сибай → Уфа", status: "done", my_stars: 4 },
  { booking_id: 4, passenger_id: 14, passenger_name: "Отменивший", passenger_rating: null, route: "Уфа → Сибай", status: "cancelled", my_stars: 0 },
  { booking_id: 5, passenger_id: 15, passenger_name: "Ждущий", passenger_rating: null, route: "Уфа → Сибай", status: "pending", my_stars: 0 },
];

async function open(lang, rows = ROWS) {
  const DriverPassengers = await bundleComponent("src/components/DriverPassengers.tsx", {
    mocks: {
      "/i18n/lang": langMock(lang),
      "/api/bookings": `export const fetchDriverBookings=async()=>globalThis.__dpRows;export const markNoShow=async()=>({});export const rateBooking=async()=>({});`,
      "/api/client": `export class ApiError extends Error{constructor(s,m){super(m);this.status=s;}}`,
    },
  });
  globalThis.__dpRows = rows;
  const opened = [];
  const h = mount(DriverPassengers, { onOpenTrip: (id) => opened.push(id) });
  h.render();
  await h.settle();
  return { h, opened, tree: h.tree };
}

/** Строка брони: div.list-row с key = id брони. */
const rowOf = (tree, id) => all(tree).find((n) => n.key === id && String(n.props.className ?? "").includes("list-row"));
const stars = (node) => all(node).filter((n) => n.type === "button" && String(n.props.className ?? "").includes("rate-star"));

const t = checker();

for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => (lang === "ba" ? ba : ru);
  await t.test(`${lang}: «Едут с тобой» — подтверждённые и «в пути», у каждой «Открыть поездку»`, async () => {
    const { tree, opened } = await open(lang);
    const headers = ofType(tree, "SectionHeader").map((n) => n.props.title);
    assert.ok(headers.includes(L("Едут с тобой", "Һинең менән баралар")), "заголовок раздела: " + headers.join(" | "));
    const head = ofType(tree, "SectionHeader").find((n) => n.props.title === L("Едут с тобой", "Һинең менән баралар"));
    assert.equal(
      head.props.subtitle,
      L("Поездка ещё не закончилась. Оценить сможешь, когда завершишь рейс.", "Сәфәр әле тамамланманы. Рейсты тамамлағас баһалай алаһың.")
    );
    for (const id of [1, 2]) {
      const row = rowOf(tree, id);
      assert.ok(row, `бронь ${id} видна`);
      const open = button(row, L("Открыть поездку", "Сәфәрҙе асыу"));
      assert.ok(open, `у брони ${id} есть «Открыть поездку»`);
      open.props.onClick();
    }
    assert.deepEqual(opened, [1, 2], "«Открыть поездку» ведёт на экран поездки ЭТОЙ брони");
  });

  await t.test(`${lang}: у тех, кто ещё едет, звёзд нет — сервер такую оценку отклонит`, async () => {
    const { tree } = await open(lang);
    assert.equal(stars(rowOf(tree, 1)).length, 0, "подтверждённая — без звёзд");
    assert.equal(stars(rowOf(tree, 2)).length, 0, "«в пути» — без звёзд");
  });

  await t.test(`${lang}: звёзды — только у завершённых, в разделе «оцените после поездки»`, async () => {
    const { tree } = await open(lang);
    const head = ofType(tree, "SectionHeader").find(
      (n) => n.props.title === L("Пассажиры — оцените после поездки", "Пассажирҙар — сәфәрҙән һуң баһалағыҙ")
    );
    assert.ok(head, "раздел оценки есть");
    assert.equal(stars(rowOf(tree, 3)).length, 5, "у завершённой пять звёзд");
    assert.equal(button(rowOf(tree, 3), L("Открыть поездку", "Сәфәрҙе асыу")), undefined, "завершённую не «открывают» отсюда");
  });

  await t.test(`${lang}: отменённых и ждущих ответа в этом блоке нет`, async () => {
    const { tree } = await open(lang);
    assert.equal(rowOf(tree, 4), undefined, "отменённая не видна");
    assert.equal(rowOf(tree, 5), undefined, "ждущая — в «Ждут твоего ответа» выше, не здесь");
    assert.ok(!text(tree).includes("Отменивший") && !text(tree).includes("Ждущий"));
  });
}

await t.test("только завершённые — раздела «Едут с тобой» нет; ничего — блок пустой", async () => {
  const { tree } = await open("ru", [ROWS[2]]);
  assert.ok(!ofType(tree, "SectionHeader").some((n) => n.props.title === "Едут с тобой"));
  const empty = await open("ru", [ROWS[3], ROWS[4]]);
  assert.equal(empty.tree, null, "отменённые и ждущие — блок не рисуется");
});

await t.test("кабинет водителя передаёт «Открыть поездку» на /trip/{id}", () => {
  const cab = readFileSync(ROOT + "src/screens/DriverCabinetScreen.tsx", "utf8");
  assert.match(cab, /<DriverPassengers onOpenTrip=\{\(bookingId\) => navigate\(`\/trip\/\$\{bookingId\}`\)\} \/>/);
});

await t.test("надписи совпадают с Android слово в слово", () => {
  const kt = ROOT + "../android/app/src/main/java/com/yuldash/app/ProfileScreen.kt";
  if (!existsSync(kt)) return console.log("    (Android рядом нет — сверка пропущена)");
  const src = readFileSync(kt, "utf8");
  for (const s of [
    'appText("Едут с тобой", "Һинең менән баралар")',
    'appText("Открыть поездку", "Сәфәрҙе асыу")',
    '"Поездка ещё не закончилась. Оценить сможешь, когда завершишь рейс."',
    '"Сәфәр әле тамамланманы. Рейсты тамамлағас баһалай алаһың."',
    'appText("Пассажиры — оцените после поездки", "Пассажирҙар — сәфәрҙән һуң баһалағыҙ")',
  ]) assert.ok(src.includes(s), "в Android нет: " + s);
});

t.done();
