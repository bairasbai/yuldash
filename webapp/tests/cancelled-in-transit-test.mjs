// ================================================================
//  Отмена, когда пассажир уже в машине, — честная (решение Александра 23.10.2026). Сайт.
//
//  Было: водитель снял поездку на трассе — экран поездки пассажира стирал машину и госномер
//  (как при любой отмене) и прятал SOS и «Застряли» (они только у живой поездки). Пуш вёл на
//  «Бронь» — там «Отменена», и всё. Стало (зеркало Android CancelledInTransit.kt):
//   • по признаку сервера `cancelled_in_transit` экран поездки показывает «Поездку отменили в
//     пути»: SOS с бронью, «Застряли на трассе», «Сообщить о проблеме» (окно 6 ч), машина и
//     госномер; телефона водителя нет;
//   • «Бронь» такого пассажира уводит на экран поездки;
//   • «Снять поездку?» у водителя предупреждает, сколько пассажиров этой поездки уже в машине.
//  Обычная отмена — как раньше: машина стирается, помощи «в пути» нет.
//
//  Исполняем настоящие экраны и компонент на стенде shallow-harness.mjs.
// ================================================================
import assert from "node:assert/strict";
import { ROOT, all, bundleComponent, button, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";
import { openActiveTrip } from "./active-trip-fixture.mjs";
import { build } from "esbuild";

const t = checker();
globalThis.document ??= { visibilityState: "visible", addEventListener() {}, removeEventListener() {} };
const L = (lang, ru, ba) => (lang === "ba" ? ba : ru);

const IN_TRANSIT = {
  status: "cancelled", contact_unlocked: false, driver_phone: "", cancelled_in_transit: true,
  driver_car: "Лада Веста", driver_car_color: "серая", driver_plate: "М555ОР102",
};
const TRIP_IN_TRANSIT = { status: "cancelled", cancelled_in_transit: true, peer_id: 9, problem_report_sec: 3600 };
const card = (tree) => ofType(tree, "CancelledInTransitCard")[0];

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
  await t.test(`${lang}: карточка — что делать, машина, SOS с бронью, «Застряли», разбор без готового типа`, async () => {
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
