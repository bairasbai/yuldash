// ================================================================
//  Отмена, когда пассажир уже в машине, — честная (решение Александра 23.10.2026). Сайт.
//
//  Было: водитель снял поездку на трассе — экран поездки пассажира стирал машину и госномер
//  (как при любой отмене) и прятал SOS и «Застрял на трассе» (они только у живой поездки). Пуш вёл на
//  «Бронь» — там «Отменена», и всё. Стало (зеркало Android CancelledInTransit.kt):
//   • по признаку сервера `cancelled_in_transit` экран поездки показывает «Поездку отменили в
//     пути»: SOS с бронью, «Застрял на трассе», «Сообщить о проблеме» (окно 6 ч), машина и
//     госномер; телефона водителя нет;
//   • «Бронь» такого пассажира уводит на экран поездки;
//   • «Снять поездку?» у водителя предупреждает, сколько пассажиров этой поездки уже в машине.
//  Обычная отмена — как раньше: машина стирается, помощи «в пути» нет.
//
//  Приёмка PR207 (24.10.2026):
//   • №1 — при отмене в пути на экране ТОЛЬКО помощь: ни чата с водителем, ни карты, ни живого статуса;
//   • №3 — вкладка помнит отмену (номер брони, машина, госномер — без телефона): ушёл на SOS и вернулся
//     без связи — экран помощи на месте, а не «Не удалось загрузить»;
//   • №4 — признак приходит из любой ручки: только /details или только /role — машина не стирается,
//     а если её нет — детали брони дочитываются один раз.
//
//  Исполняем настоящие экраны и компонент на стенде shallow-harness.mjs.
// ================================================================
import assert from "node:assert/strict";
import { ROOT, all, bundleComponent, button, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";
import { openActiveTrip } from "./active-trip-fixture.mjs";
import { build } from "esbuild";

const t = checker();
globalThis.document ??= { visibilityState: "visible", addEventListener() {}, removeEventListener() {} };
// sessionStorage вкладки (в node его нет; без него экран молча ничего не помнит — как в приватном режиме).
const session = new Map();
globalThis.sessionStorage = {
  getItem: (k) => (session.has(k) ? session.get(k) : null),
  setItem: (k, v) => { session.set(k, String(v)); },
  removeItem: (k) => { session.delete(k); },
  key: (i) => [...session.keys()][i] ?? null,
  get length() { return session.size; },
  clear: () => session.clear(),
};
const L = (lang, ru, ba) => (lang === "ba" ? ba : ru);

const IN_TRANSIT = {
  status: "cancelled", contact_unlocked: false, driver_phone: "", cancelled_in_transit: true,
  driver_car: "Лада Веста", driver_car_color: "серая", driver_plate: "М555ОР102",
};
const TRIP_IN_TRANSIT = { status: "cancelled", cancelled_in_transit: true, peer_id: 9, problem_report_sec: 3600 };
const { cancelled_in_transit: _roleFlag, ...TRIP_NO_FLAG } = TRIP_IN_TRANSIT;
const { cancelled_in_transit: _detailsFlag, ...DETAILS_NO_FLAG } = IN_TRANSIT;
const card = (tree) => ofType(tree, "CancelledInTransitCard")[0];
/** Компоненты на экране (заглушки с заглавной буквы), по порядку. */
const parts = (tree) => all(tree).filter((n) => typeof n.type === "string" && /^[A-Z]/.test(n.type)).map((n) => n.type);
const tick = async (h) => { await h.calls("useVisibleInterval").at(-1)[1](); await h.settle(); };
const detailsGets = (at) => at.gets.filter((p) => p === "/bookings/42/details").length;
const NO_NET = () => { throw new Error("нет сети"); };

// ---------------------------------------------------------------- экран поездки
await t.test("экран поездки: отменили в пути — карточка помощи с машиной и госномером, без телефона", async () => {
  const { h } = await openActiveTrip({ status: "cancelled", details: IN_TRANSIT, trip: TRIP_IN_TRANSIT });
  const c = card(h.tree);
  assert.ok(c, "карточки «Поездку отменили в пути» нет");
  assert.equal(c.props.bookingId, 42);
  assert.equal(c.props.car, "серая Лада Веста");
  assert.equal(c.props.plate, "М555ОР102");
  assert.equal(c.props.driverId, 9, "водитель для разбора — из /role");
  assert.equal(c.props.problemReportSec, 3600);
  assert.ok(!JSON.stringify(h.tree).includes("tel:"), "телефона водителя после отмены нет");
});

await t.test("экран открыт «в пути» — отмена пришла опросом: машина и номер остаются, телефон пропадает", async () => {
  const { h, at } = await openActiveTrip({ status: "onboard",
    details: { driver_car: "Лада Веста", driver_car_color: "серая", driver_plate: "М555ОР102" } });
  assert.ok(JSON.stringify(h.tree).includes("tel:+79990000000"), "в пути телефон водителя виден");
  assert.equal(card(h.tree), undefined);
  // Сервер: водитель снял поездку, пассажир в машине.
  Object.assign(at.trip, TRIP_IN_TRANSIT);
  Object.assign(at.details, IN_TRANSIT);
  const tick = h.calls("useVisibleInterval").at(-1)[1];
  await tick();
  await h.settle();
  const c = card(h.tree);
  assert.ok(c, "после отмены в пути карточки помощи нет");
  assert.equal(c.props.car, "серая Лада Веста");
  assert.equal(c.props.plate, "М555ОР102");
  assert.ok(!JSON.stringify(h.tree).includes("tel:"), "телефон водителя остался после отмены");
  assert.ok(h.calls("dropTripPass").length > 0, "офлайн-паспорт с телефоном удаляется, как при любой отмене");
});

await t.test("обычная отмена — как раньше: карточки нет, машина и номер стёрты", async () => {
  const { h } = await openActiveTrip({ status: "cancelled",
    details: { ...IN_TRANSIT, cancelled_in_transit: false }, trip: { ...TRIP_IN_TRANSIT, cancelled_in_transit: false } });
  assert.equal(card(h.tree), undefined);
  assert.ok(!JSON.stringify(h.tree).includes("М555ОР102"), "госномер обычной отмены не показываем");
});

await t.test("старый сервер без признака — обычная отмена", async () => {
  const { h } = await openActiveTrip({ status: "cancelled", details: { driver_plate: "М555ОР102" } });
  assert.equal(card(h.tree), undefined);
});

await t.test("водителю отмена в пути карточку помощи не показывает — отменил он сам", async () => {
  const { h } = await openActiveTrip({ role: "driver", status: "cancelled", details: IN_TRANSIT, trip: TRIP_IN_TRANSIT });
  assert.equal(card(h.tree), undefined);
});

// ---------------------------------------------------------------- приёмка PR207, №1: только помощь
await t.test("отменили в пути — на экране только помощь: ни чата с водителем, ни карты, ни живого статуса", async () => {
  session.clear();
  const ROUTE = { from_lat: 54.73, from_lng: 55.95, to_lat: 52.71, to_lng: 58.66 };
  // Сразу открыли уже отменённую в пути поездку.
  const opened = await openActiveTrip({ status: "cancelled", details: { ...IN_TRANSIT, ...ROUTE }, trip: TRIP_IN_TRANSIT });
  assert.deepEqual(parts(opened.h.tree), ["SubHeader", "CancelledInTransitCard"], parts(opened.h.tree).join(", "));
  // Экран был открыт в пути — чат и карта были; отмена пришла опросом — их нет.
  const { h, at } = await openActiveTrip({ status: "onboard", details: { ...ROUTE, driver_plate: "М555ОР102" } });
  assert.ok(ofType(h.tree, "TripChatCard").length === 1 && ofType(h.tree, "YandexMap").length === 1, "в пути чат и карта есть");
  Object.assign(at.trip, TRIP_IN_TRANSIT);
  Object.assign(at.details, IN_TRANSIT);
  await tick(h);
  for (const live of ["TripChatCard", "YandexMap", "StatusPill", "ShareTripCard", "RoadsideHelp", "BookingSegmentNote"]) {
    assert.equal(ofType(h.tree, live).length, 0, `после отмены в пути осталось живое: ${live}`);
  }
  assert.deepEqual(parts(h.tree), ["SubHeader", "CancelledInTransitCard"], parts(h.tree).join(", "));
  assert.ok(!all(h.tree).some((n) => n.props?.className === "trip-banner"), "живой баннер статуса остался");
});

// ---------------------------------------------------------------- №4: признак из одной ручки
await t.test("признак только в деталях брони (/role его не знает) — помощь, машина и номер на месте", async () => {
  session.clear();
  const { h } = await openActiveTrip({ status: "cancelled", details: IN_TRANSIT, trip: TRIP_NO_FLAG });
  await tick(h); // ещё один опрос /role без признака экран помощи не гасит
  const c = card(h.tree);
  assert.ok(c, "признак из /details потерялся");
  assert.equal(c.props.car, "серая Лада Веста");
  assert.equal(c.props.plate, "М555ОР102");
  assert.ok(!JSON.stringify(h.tree).includes("tel:"));
});

await t.test("признак только в опросе /role — машина и номер из деталей брони не стираются", async () => {
  session.clear();
  const { h, at } = await openActiveTrip({ status: "cancelled", details: DETAILS_NO_FLAG, trip: TRIP_IN_TRANSIT });
  const c = card(h.tree);
  assert.ok(c, "признак из /role потерялся");
  assert.equal(c.props.car, "серая Лада Веста", "машину стёрли как при обычной отмене");
  assert.equal(c.props.plate, "М555ОР102", "госномер стёрли как при обычной отмене");
  assert.equal(detailsGets(at), 1, "машина уже известна — детали брони второй раз не просим");
});

await t.test("признак только в /role, а машины в деталях нет — детали брони дочитываются, и только один раз", async () => {
  session.clear();
  const EMPTY = { ...DETAILS_NO_FLAG, driver_car: "", driver_car_color: "", driver_plate: "" };
  let n = 0;
  const { h, at } = await openActiveTrip({ status: "cancelled", details: EMPTY, trip: TRIP_IN_TRANSIT,
    intercept: (p, at) => (p === "/bookings/42/details" && ++n === 2 ? { ...at.details, ...IN_TRANSIT } : undefined) });
  assert.equal(detailsGets(at), 2, "детали брони не дочитали — у пассажира нет машины и номера");
  assert.equal(card(h.tree)?.props.plate, "М555ОР102");
  await tick(h);
  assert.equal(detailsGets(at), 2, "машина уже есть — больше не просим");
  // Сервер машину так и не прислал — просим один раз, а не на каждом опросе.
  const never = await openActiveTrip({ status: "cancelled", details: EMPTY, trip: TRIP_IN_TRANSIT });
  await tick(never.h);
  await tick(never.h);
  assert.equal(detailsGets(never.at), 2, "детали брони переспрашиваются на каждом опросе");
  assert.ok(card(never.h.tree), "экран помощи есть и без машины");
});

// ---------------------------------------------------------------- №3: вкладка помнит отмену в пути
await t.test("SOS и назад без сети — экран помощи с машиной и госномером на месте; телефона в памяти нет", async () => {
  session.clear();
  const first = await openActiveTrip({ status: "cancelled", details: IN_TRANSIT, trip: TRIP_IN_TRANSIT });
  assert.ok(card(first.h.tree));
  first.h.unmount(); // ушёл на SOS — экран поездки закрылся
  assert.deepEqual([...session.keys()], ["yuldash.inTransit.42"]);
  assert.deepEqual(JSON.parse(session.get("yuldash.inTransit.42")), { owner: 1, car: "серая Лада Веста", plate: "М555ОР102" },
    "вкладка помнит только номер брони, машину и госномер");
  // Назад — а связи нет.
  const { h } = await openActiveTrip({ status: "cancelled", details: IN_TRANSIT, trip: TRIP_IN_TRANSIT, intercept: NO_NET });
  const c = card(h.tree);
  assert.ok(c, "без сети экран помощи пропал — только «Не удалось загрузить»");
  assert.equal(c.props.bookingId, 42);
  assert.equal(c.props.car, "серая Лада Веста");
  assert.equal(c.props.plate, "М555ОР102");
  assert.equal(ofType(h.tree, "ErrorState").length, 1, "«Повторить» на месте");
  assert.ok(!JSON.stringify(h.tree).includes("tel:"));
});

await t.test("вернулся, а ответ сервера ещё идёт — сразу экран помощи, а не заглушка загрузки", async () => {
  session.clear();
  (await openActiveTrip({ status: "cancelled", details: IN_TRANSIT, trip: TRIP_IN_TRANSIT })).h.unmount();
  const { h } = await openActiveTrip({ intercept: () => new Promise(() => {}) });
  assert.equal(card(h.tree)?.props.plate, "М555ОР102", "пока грузимся, помощи нет");
  assert.equal(ofType(h.tree, "LoadingList").length, 0);
});

await t.test("другой вход в той же вкладке чужую отмену в пути не видит", async () => {
  session.clear();
  (await openActiveTrip({ status: "cancelled", details: IN_TRANSIT, trip: TRIP_IN_TRANSIT })).h.unmount();
  const { h } = await openActiveTrip({ userId: 2, intercept: NO_NET });
  assert.equal(card(h.tree), undefined, "чужая машина и госномер на экране другого человека");
  assert.ok(!JSON.stringify(h.tree).includes("М555ОР102"));
});

await t.test("обычная отмена вкладке не запоминается", async () => {
  session.clear();
  (await openActiveTrip({ status: "cancelled", details: DETAILS_NO_FLAG, trip: TRIP_NO_FLAG })).h.unmount();
  assert.equal(session.size, 0);
});

await t.test("выход из аккаунта стирает память об отмене в пути", async () => {
  session.clear();
  session.set("yuldash.inTransit.42", JSON.stringify({ owner: 1, car: "серая Лада Веста", plate: "М555ОР102" }));
  session.set("yuldash.lang", "ba");
  const clearPersonalLocal = await bundleComponent("src/utils/privacy.ts", {
    mocks: { "/storageHealth": "export const reportStorageFailure=()=>{};" },
    exportName: "clearPersonalLocal",
  });
  clearPersonalLocal();
  assert.deepEqual([...session.keys()], ["yuldash.lang"], "машина и госномер пережили выход");
});

// ---------------------------------------------------------------- сама карточка
async function openCard(lang, props) {
  const Card = await bundleComponent("src/components/CancelledInTransitCard.tsx", {
    real: ["/utils/sosLink"],
    mocks: {
      "react-router-dom": `export const useNavigate=()=>p=>globalThis.__cit.nav.push(p);`,
      "/i18n/lang": langMock(lang),
    },
  });
  globalThis.__cit = { nav: [] };
  const h = mount(Card, { bookingId: 42, car: "серая Лада Веста", plate: "М555ОР102", driverId: 9, driverName: "Ринат",
    problemReportSec: 3600, ...props });
  h.render();
  await h.settle();
  return h;
}

for (const lang of ["ru", "ba"]) {
  await t.test(`${lang}: карточка — что делать, машина, SOS с бронью, «Застрял на трассе», разбор без готового типа`, async () => {
    const h = await openCard(lang);
    const s = text(h.tree);
    assert.ok(s.includes(L(lang, "Поездку отменили в пути", "Сәфәр юлда кире алынды")));
    assert.ok(s.includes(L(lang, "Если нужна помощь — нажми SOS или «Застрял на трассе». Машина и госномер — ниже.",
      "Ярҙам кәрәк булһа — SOS йәки «Юлда ҡалдым» төймәһенә баҫ. Машина һәм дәүләт номеры — түбәндә.")));
    assert.ok(s.includes("серая Лада Веста") && s.includes("М555ОР102"));
    const sos = button(h.tree, L(lang, "SOS — нужна помощь", "SOS — ярҙам кәрәк"));
    assert.ok(sos, "кнопки SOS нет");
    sos.props.onClick();
    assert.deepEqual(globalThis.__cit.nav, ["/sos?booking=42"], "SOS без брони — дежурный не узнает машину");
    const roadside = ofType(h.tree, "RoadsideHelp")[0];
    assert.deepEqual(roadside?.props.target, { kind: "booking", id: 42 });
    button(h.tree, L(lang, "Сообщить о проблеме", "Проблема тураһында хәбәр итергә")).props.onClick();
    await h.settle();
    const form = ofType(h.tree, "FileIncidentCard")[0];
    assert.ok(form, "разбор не открылся");
    assert.equal(form.props.respondentId, 9);
    assert.equal(form.props.bookingId, 42);
    assert.equal(form.props.initialType, undefined, "что случилось в дороге, знает человек — тип не подставляем");
    form.props.onFiled();
    await h.settle();
    assert.ok(text(h.tree).includes(L(lang, "Разбор открыт.", "Ҡарау асылды.")));
  });
}

await t.test("окно разбора закрыто или водитель неизвестен — «Сообщить о проблеме» нет", async () => {
  assert.equal(button((await openCard("ru", { problemReportSec: 0 })).tree, "Сообщить о проблеме"), undefined);
  assert.equal(button((await openCard("ru", { driverId: undefined })).tree, "Сообщить о проблеме"), undefined);
});

await t.test("водитель не указал машину — так и пишем, а не пустое место", async () => {
  const h = await openCard("ru", { car: "", plate: "" });
  assert.ok(text(h.tree).includes("Водитель не указал машину и госномер"));
  assert.ok(button(h.tree, "SOS — нужна помощь"), "SOS есть и без машины");
});

// ---------------------------------------------------------------- оценка водителя (решение 24.10.2026)
const RATE_HINT = ["Как ехалось до отмены? Оценка поможет другим выбрать водителя.",
  "Кире алынғансы нисек барҙың? Баһаң башҡаларға йөрөтөүсе һайларға ярҙам итер."];

for (const lang of ["ru", "ba"]) {
  await t.test(`${lang}: можно оценить — звёзды ниже помощи, после оценки «Спасибо» и «Твоя оценка»`, async () => {
    const h = await openCard(lang, { canRate: true, myStars: 0 });
    const rate = ofType(h.tree, "ReceiptRateDriver")[0];
    assert.ok(rate, "блока оценки нет");
    assert.equal(rate.props.bookingId, 42);
    assert.equal(rate.props.hint, L(lang, ...RATE_HINT));
    assert.equal(rate.props.canAct(), true);
    // Помощь — выше звёзд: SOS и «Застрял» рисуются раньше блока оценки.
    const order = all(h.tree).map((n) => n.type);
    assert.ok(order.indexOf("RoadsideHelp") < order.indexOf("ReceiptRateDriver"), "оценка выше помощи");
    rate.props.onRated(4);
    await h.settle();
    assert.equal(ofType(h.tree, "ReceiptRateDriver").length, 0, "звёзды после оценки остались");
    const s = text(h.tree);
    assert.ok(s.includes(L(lang, "Спасибо! Оценка учтена.", "Рәхмәт! Баһа иҫәпкә алынды.")), s);
    assert.ok(s.includes(L(lang, "Твоя оценка", "Һинең баһаң")) && s.includes(L(lang, "4 из 5", "5-тән 4")), s);
  });
}

await t.test("нельзя оценить (срок, пауза, старый сервер) — блока нет", async () => {
  const h = await openCard("ru", { canRate: false });
  assert.equal(ofType(h.tree, "ReceiptRateDriver").length, 0);
  assert.ok(!text(h.tree).includes("Твоя оценка"));
  const old = await openCard("ru", {});
  assert.equal(ofType(old.tree, "ReceiptRateDriver").length, 0, "без поля can_rate звёзд нет");
});

await t.test("уже оценил — «Твоя оценка», без звёзд и без «Спасибо»", async () => {
  const h = await openCard("ru", { canRate: true, myStars: 3 });
  assert.equal(ofType(h.tree, "ReceiptRateDriver").length, 0);
  const s = text(h.tree);
  assert.ok(s.includes("Твоя оценка") && s.includes("3 из 5"), s);
  assert.ok(!s.includes("Спасибо! Оценка учтена."));
});

await t.test("экран поездки передаёт карточке оценку из деталей брони", async () => {
  session.clear();
  const { h } = await openActiveTrip({ status: "cancelled", details: { ...IN_TRANSIT, can_rate: true, my_stars: 2 },
    trip: TRIP_IN_TRANSIT });
  const c = card(h.tree);
  assert.equal(c.props.canRate, true);
  assert.equal(c.props.myStars, 2);
  const plain = await openActiveTrip({ status: "cancelled", details: IN_TRANSIT, trip: TRIP_IN_TRANSIT });
  assert.equal(card(plain.h.tree).props.canRate, false, "старый сервер без can_rate — без звёзд");
});

// ---------------------------------------------------------------- «Бронь» уводит на экран поездки
async function openBooking(details) {
  const Screen = await bundleComponent("src/screens/BookingScreen.tsx", {
    real: ["/utils/bookingLink"],
    mocks: {
      "react-router-dom": `export const Navigate="Navigate";export const useNavigate=()=>p=>{};export const useParams=()=>({id:"42"});`,
      "/i18n/lang": langMock("ru"),
      "/api/bookings": `export const fetchBookingDetails=async()=>(${JSON.stringify(details)});export const cancelBooking=async()=>({});`,
      "/api/client": `export class ApiError extends Error{}`,
      "/booking/OwnBookingsProvider": `export const useRefreshOwnBookings=()=>()=>{};`,
    },
  });
  const h = mount(Screen);
  h.render();
  await h.settle();
  return h.tree;
}
const BOOKING = { booking_id: 42, ride_id: 7, role: "passenger", from_city: "Уфа", to_city: "Сибай", depart_at: "2030-01-01T10:00:00",
  seats: 1, price: 500, pay_method: "cash", driver_name: "Ринат", driver_verified: true, driver_phone: "", pickup: "" };

await t.test("«Бронь»: пассажира, которого высадили в пути, уводит на экран поездки (туда ведёт пуш)", async () => {
  const tree = await openBooking({ ...BOOKING, ...IN_TRANSIT });
  assert.equal(tree.type, "Navigate");
  assert.equal(tree.props.to, "/trip/42");
  assert.equal(tree.props.replace, true, "«назад» не возвращает на ссылку, с которой увели");
});

await t.test("«Бронь»: обычная отмена — остаётся на «Брони», как раньше", async () => {
  const tree = await openBooking({ ...BOOKING, status: "cancelled", contact_unlocked: false, driver_car: "Лада" });
  assert.notEqual(tree.type, "Navigate");
  assert.equal(ofType(tree, "SubHeader")[0]?.props.title, "Детали брони", "рисуется экран «Бронь»");
});

const lib = await build({ entryPoints: [ROOT + "src/utils/bookingLink.ts"], bundle: true, write: false, format: "esm", platform: "node", logLevel: "silent" });
const { bookingLinkPath } = await import("data:text/javascript;base64," + Buffer.from(lib.outputFiles[0].text).toString("base64"));
await t.test("правило ссылки: признак отмены в пути меняет путь только пассажиру отменённой брони", () => {
  assert.equal(bookingLinkPath("passenger", "cancelled", 42, true), "/trip/42");
  assert.equal(bookingLinkPath("passenger", "cancelled", 42), null);
  assert.equal(bookingLinkPath("passenger", "done", 42, true), null);
  assert.equal(bookingLinkPath("driver", "cancelled", 42, true), "/driver", "водителю — кабинет, как раньше");
});

// ---------------------------------------------------------------- «Снять поездку?» у водителя
const RIDE = { id: 7, from_city: "Сибай", to_city: "Уфа", depart_at: "2030-01-01T10:00:00", seats_total: 4, seats_left: 1,
  price: 500, status: "active" };
const row = (id, rideId, status) => ({ booking_id: id, ride_id: rideId, status, passenger_name: "Гульнара", passenger_rating: null,
  route: "Сибай → Уфа", my_stars: 0 });

async function dropRideText(lang, bookings) {
  const Screen = await bundleComponent("src/screens/DriverCabinetScreen.tsx", {
    real: ["/featureRules", "/utils/rideCancelWarning"],
    mocks: {
      "react-router-dom": `export const useNavigate=()=>p=>{};`,
      "/features": `export const useFeatures=()=>({rides:true,taxi:false,courier:false,parcels:false,ads:false,business:false,ready:true});`,
      "/i18n/lang": langMock(lang),
      "/api/client": `export class ApiError extends Error{}`,
      "/api/driver": `export const fetchDriverStatus=async()=>({online:false,docs_status:"verified",gender:""});
        export const fetchDriverRides=async()=>[${JSON.stringify(RIDE)}];export const fetchMySchedules=async()=>[];
        export const setDriverOnline=async()=>({});export const setDriverGender=async()=>({});export const setTipsSbp=async()=>({});
        export const fetchRideTips=async()=>({});export const createSchedule=async()=>({});export const deleteSchedule=async()=>({});
        export const cancelRide=async(id)=>{globalThis.__drop.cancelled.push(id);return{};};export const completeRide=async()=>({});`,
      "/api/bookings": `export const fetchDriverBookings=async()=>(${JSON.stringify(bookings)});export const cancelBooking=async()=>({});export const confirmBooking=async()=>({});`,
      "/auth/AuthProvider": `export const useAuth=()=>({user:{id:1,rating:4.8}});`,
    },
  });
  globalThis.__drop = { asked: [], cancelled: [] };
  const realWindow = globalThis.window;
  globalThis.window = { confirm: (m) => { globalThis.__drop.asked.push(m); return false; }, setTimeout, clearTimeout };
  try {
    const h = mount(Screen);
    h.render();
    await h.settle();
    const drop = button(h.tree, L(lang, "Снять поездку", "Сәфәрҙе алыу"));
    assert.ok(drop, "кнопки «Снять поездку» нет");
    drop.props.onClick();
    await h.settle();
    assert.deepEqual(globalThis.__drop.cancelled, [], "не подтвердил — поездка не снята");
    assert.equal(globalThis.__drop.asked.length, 1);
    return globalThis.__drop.asked[0];
  } finally {
    globalThis.window = realWindow;
  }
}

const WARN_RU = "В машине пассажиры: 2. Если довёз — нажми «Завершить рейс». Если снимаешь — им придёт уведомление, " +
  "а близкие, с кем они поделились поездкой, увидят по ссылке, что поездку отменили в пути.";
await t.test("ru: в машине пассажиры — окно называет число и что будет; чужая поездка не считается", async () => {
  const q = await dropRideText("ru", [row(1, 7, "onboard"), row(2, 7, "onboard"), row(3, 7, "confirmed"), row(4, 8, "onboard")]);
  assert.ok(q.startsWith("Снять поездку? Брони отменятся у 3 чел., им придёт уведомление."), q);
  assert.ok(q.endsWith(WARN_RU), q);
});

await t.test("ba: то же предупреждение по-башкирски", async () => {
  const q = await dropRideText("ba", [row(1, 7, "onboard"), row(2, 7, "onboard")]);
  assert.ok(q.includes("Машинала юлаусылар: 2. Илтеп еткерһәң — «Рейсты тамамлау» төймәһенә баҫ."), q);
});

await t.test("никого в машине (или старый сервер без ride_id) — прежний вопрос без предупреждения", async () => {
  const q = await dropRideText("ru", [row(3, 7, "confirmed"), { ...row(5, 0, "onboard"), ride_id: undefined }]);
  assert.equal(q, "Снять поездку? Брони отменятся у 3 чел., им придёт уведомление.");
});

t.done();
