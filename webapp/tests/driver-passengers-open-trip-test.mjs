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
//   • «Пассажиры — оцените после поездки» — только завершённые, звёзды и возврат к поездке;
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
      "/api/bookings": `export const fetchDriverBookings=async()=>globalThis.__dpRows;export const markNoShow=async()=>({});export const markArriving=async()=>({});export const rateBooking=async()=>({});`,
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
    assert.ok(button(rowOf(tree, 3), L("Открыть поездку", "Сәфәрҙе асыу")), "завершённую можно открыть снова ради квитанции");
  });

  await t.test(`${lang}: водитель возвращается к завершённой поездке до и после оценки`, async () => {
    const rows = [{ ...ROWS[2], booking_id: 42, my_stars: 0 }, { ...ROWS[2], booking_id: 43, my_stars: 5 }];
    const { h, opened } = await open(lang, rows);
    for (const id of [42, 43]) {
      const action = button(rowOf(h.tree, id), L("Открыть поездку", "Сәфәрҙе асыу"));
      assert.ok(action, `у завершённой брони ${id} доступен возврат к квитанции`);
      action.props.onClick();
    }
    assert.deepEqual(opened, [42, 43], "открывается та же бронь, без отправки оценки");
    h.unmount();
  });

  await t.test(`${lang}: во всём блоке звёзды есть только у завершённой — едущих нет и в разделе оценки`, async () => {
    const { tree } = await open(lang);
    const rated = all(tree).filter((n) => n.key != null && String(n.props.className ?? "").includes("list-row") && stars(n).length > 0);
    assert.deepEqual(rated.map((n) => n.key), [3], "строки со звёздами: " + rated.map((n) => n.key).join(", "));
    assert.equal(stars(tree).length, 5, "звёзд ровно пять — у одной завершённой поездки");
    const rows = all(tree).filter((n) => n.key != null && String(n.props.className ?? "").includes("list-row"));
    assert.deepEqual(rows.map((n) => n.key).sort(), [1, 2, 3], "каждая бронь — ровно в одном разделе");
  });

  await t.test(`${lang}: отменённых и ждущих ответа в этом блоке нет`, async () => {
    const { tree } = await open(lang);
    assert.equal(rowOf(tree, 4), undefined, "отменённая не видна");
    assert.equal(rowOf(tree, 5), undefined, "ждущая — в «Ждут твоего ответа» выше, не здесь");
    assert.ok(!text(tree).includes("Отменивший") && !text(tree).includes("Ждущий"));
  });
}

// После выхода из завершённой поездки пассажир возвращается к своему чеку из истории.
// Исполняется настоящий MyRidesScreen; карточка остаётся поверхностным узлом с его обработчиками.
const passengerRows = ["done", "cancelled", "pending", "confirmed", "onboard"].map((status, index) => ({
  id: 70 + index, ride_id: 900 + index, status, from_city: "Сибай", to_city: "Акъяр",
  pickup_stop: "Ургаза", dropoff_stop: "Бурибай", ride_has_stops: true, seats: 1, price: 450,
  depart_at: "2026-10-18T15:00:00Z",
}));
const routes = globalThis.__historyRoutes = [];
globalThis.__historyBookings = passengerRows;
for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => lang === "ba" ? ba : ru;
  const Screen = await bundleComponent("src/screens/MyRidesScreen.tsx", {
    real: ["/components/MyTripCard"],
    mocks: {
      "/i18n/lang": langMock(lang),
      "react-router-dom": `export const useNavigate=()=>path=>globalThis.__historyRoutes.push(path);`,
      "/api/bookings": `export const fetchMyBookings=async()=>globalThis.__historyBookings;`,
      "/auth/AuthProvider": `export const useAuth=()=>({status:'authed'});`,
      "/components/PartnerAd": `export default 'PartnerAdCard';export const usePartnerAds=()=>[];`,
    },
  });
  const openPassenger = async segment => {
    routes.length = 0;
    const h = mount(Screen); await h.settle();
    button(h.tree, L(segment === "history" ? "История" : "Все", segment === "history" ? "Тарих" : "Бөтәһе")).props.onClick();
    await h.settle(); return h;
  };
  const cardOf = (h, id) => all(h.tree).find(n => n.props.booking?.id === id);
  await t.test(`${lang}: пассажир из истории открывает квитанцию по ID брони и сохраняет повтор маршрута`, async () => {
    const h = await openPassenger("history");
    const card = cardOf(h, 70); assert.ok(card);
    assert.equal(card.props.secondaryAction, L("Квитанция", "Квитанция"));
    card.props.onSecondary();
    assert.deepEqual(routes, ["/receipt/70"], "переход использует booking id 70, а не ride id 900");
    assert.equal(card.props.primaryAction, L("Повторить маршрут", "Маршрутты ҡабатлау"));
    card.props.onPrimary(); assert.deepEqual(routes, ["/receipt/70", "/request"]);
    assert.equal(cardOf(h, 71).props.secondaryAction, "", "у отменённой брони нет квитанции");
    h.unmount();
  });
  await t.test(`${lang}: квитанция доступна во «Все», активные переходы и отмена не изменены`, async () => {
    const h = await openPassenger("all");
    assert.equal(cardOf(h, 70).props.secondaryAction, L("Квитанция", "Квитанция"));
    assert.equal(cardOf(h, 71).props.secondaryAction, "");
    for (const id of [72, 73, 74]) {
      const card = cardOf(h, id), active = id !== 72;
      assert.equal(card.props.secondaryAction, active ? L("Чат", "Чат") : L("Написать", "Яҙыу"));
      card.props.onPrimary(); card.props.onSecondary();
    }
    assert.deepEqual(routes, ["/booking/72", "/chat", "/trip/73", "/trip/73/chat", "/trip/74", "/trip/74/chat"]);
    h.unmount();
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
