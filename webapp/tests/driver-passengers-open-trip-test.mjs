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

// Два настоящих компонента остаются смонтированы. Стенд переключает их hook-контексты
// и передаёт новые props, как React: повторного входа в кабинет/пересоздания child нет.
const CABINET_BOOKINGS = `import { ApiError } from "../api/client";
  export const fetchDriverBookings=async(signal)=>{globalThis.__cabReads++;return globalThis.__cabRows.map(r=>({...r}));};
  const change=(action,id,status)=>{globalThis.__cabWrites.push([action,id]);if(globalThis.__cabFail===action)throw new ApiError(500,"Сервер устал");
    const booking=globalThis.__cabRows.find(r=>r.booking_id===id);
    if(action==="reject"&&booking)globalThis.__cabRides=globalThis.__cabRides.map(r=>r.id===booking.ride_id?{...r,seats_left:Math.min(r.seats_total,r.seats_left+(booking.seats??1))}:r);
    globalThis.__cabRows=globalThis.__cabRows.map(r=>r.booking_id===id?{...r,status}:r);return {};};
  export const confirmBooking=async id=>change("confirm",id,"confirmed");
  export const cancelBooking=async id=>change("reject",id,"cancelled");
  export const markNoShow=async()=>({});export const markArriving=async()=>({});export const rateBooking=async()=>({});`;
const CABINET_DRIVER = `import { ApiError } from "../api/client";
  export const fetchDriverStatus=async()=>({online:false,docs_status:"pending"});
  export const fetchDriverRides=(status,signal)=>{globalThis.__cabRideReads++;const rows=globalThis.__cabRides.map(r=>({...r}));
    if(globalThis.__cabHoldRides)return new Promise((resolve,reject)=>globalThis.__cabRideLoads.push({status,signal,rows,resolve,reject}));
    return globalThis.__cabRidesFail?Promise.reject(new ApiError(500,"Сервер устал")):Promise.resolve(rows);};
  export const fetchMySchedules=async()=>[];
  export const setDriverOnline=async()=>({});export const setDriverGender=async()=>({});export const setTipsSbp=async()=>({});
  export const fetchRideTips=async()=>({});export const createSchedule=async()=>({});export const deleteSchedule=async()=>({});
  const change=(action,id,status)=>{globalThis.__cabWrites.push([action,id]);if(globalThis.__cabFail===action)throw new ApiError(500,"Сервер устал");
    globalThis.__cabRides=globalThis.__cabRides.map(r=>r.id===id?{...r,status}:r);
    globalThis.__cabRows=globalThis.__cabRows.map(r=>r.ride_id!==id?r:{...r,status:status==="done"&&r.status==="pending"?"cancelled":status});return {};};
  export const cancelRide=async id=>change("drop",id,"cancelled");export const completeRide=async id=>change("finish",id,"done");`;
const CABINET_CLIENT = `export class ApiError extends Error{constructor(s,m){super(m);this.status=s;}}`;
const cabSettle = async h => { globalThis.__sh = h.state; await h.settle(); };
const pendingCard = (tree, id) => all(tree).find(n => n.key === id && n.props.className === "act-card");
const rideCard = (tree, id) => all(tree).find(n => n.key === id && n.props.className === "driver-ride");
const freeSeats = (tree, lang) => ofType(tree, "CabinetMetric").find(n => n.props.label === (lang === "ba" ? "Буш" : "Свободно"))?.props.value;

async function openCabinet(lang, action = "confirm", secondPending = false) {
  globalThis.__cabRows = [
    { ...ROWS[4], booking_id: 77, ride_id: 7, status: action === "finish" || action === "drop" ? "confirmed" : "pending" },
    { ...ROWS[2], booking_id: 88, ride_id: 8, my_stars: 4 },
    ...(action === "finish" || action === "drop" || secondPending ? [{ ...ROWS[4], booking_id: 78, ride_id: 7 }] : []),
  ];
  globalThis.__cabRides = [{ id: 7, from_city: "Сибай", to_city: "Акъяр", depart_at: "2030-10-19T10:00:00Z", seats_total: secondPending ? 3 : 2, seats_left: action === "finish" || action === "drop" ? 0 : 1, price: 700, status: "active" }];
  globalThis.__cabReads = 0; globalThis.__cabWrites = []; globalThis.__cabFail = ""; globalThis.__cabRoutes = [];
  globalThis.__cabRideReads = 0; globalThis.__cabHoldRides = false; globalThis.__cabRidesFail = false; globalThis.__cabRideLoads = [];
  const mocks = { "/i18n/lang": langMock(lang), "/api/bookings": CABINET_BOOKINGS, "/api/client": CABINET_CLIENT };
  const Cabinet = await bundleComponent("src/screens/DriverCabinetScreen.tsx", { mocks: {
    ...mocks, "/api/driver": CABINET_DRIVER,
    "react-router-dom": `export const useNavigate=()=>path=>globalThis.__cabRoutes.push(path);`,
    "/auth/AuthProvider": `export const useAuth=()=>({user:{id:1,rating:4.8}});`,
    "/features": `export const useFeatures=()=>({taxi:false});`, "/featureRules": `export const isOpen=()=>false;`,
  } });
  const Passengers = await bundleComponent("src/components/DriverPassengers.tsx", { real: ["/utils/format"], mocks });
  const parent = mount(Cabinet); await cabSettle(parent);
  const firstChild = ofType(parent.tree, "DriverPassengers")[0]; assert.ok(firstChild);
  const child = mount(Passengers, firstChild.props); await cabSettle(child);
  return { parent, child, sync: async () => {
    await cabSettle(parent);
    const next = ofType(parent.tree, "DriverPassengers")[0];
    assert.ok(next, "успешное действие не скрывает кабинет и блок пассажиров");
    assert.equal(next.key, firstChild.key, "изменение списка не пересоздаёт формы оценок через React key");
    child.props = next.props;
    // Смена props тоже требует render. settle сам замечает только useState-dirty;
    // без этой отметки стенд оставлял child со старым reloadKey, в отличие от React.
    child.state.dirty = true;
    await cabSettle(child);
  }, close: () => { parent.unmount(); child.unmount(); } };
}

await t.test("кабинет водителя передаёт «Открыть поездку» на /trip/{id}", async () => {
  const h = await openCabinet("ru", "finish");
  button(rowOf(h.child.tree, 77), "Открыть поездку").props.onClick();
  assert.deepEqual(globalThis.__cabRoutes, ["/trip/77"]);
  h.close();
});

/** Кнопка «Отклонить» с вопросом в карточке брони (поверхностная отрисовка — узел с её свойствами). */
const declineOf = (card) => {
  const node = ofType(card, "DeclineBookingButton")[0];
  assert.ok(node, "доступно действие reject");
  return node;
};

for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => lang === "ba" ? ba : ru;
  const perform = (h, action) => {
    const labels = { confirm: L("Подтвердить", "Раҫлау"), reject: L("Отклонить", "Кире ҡағыу"),
      finish: L("Завершить рейс", "Рейсты тамамлау"), drop: L("Снять поездку", "Сәфәрҙе алыу") };
    const target = action === "confirm" || action === "reject" ? pendingCard(h.parent.tree, 77) : h.parent.tree;
    // «Отклонить» — кнопка с вопросом «Отклонить бронь?» (DeclineBookingButton, приёмка 18.10 п.6; сам
    // вопрос — driver-decline-confirm-test): здесь это ответ «Да, отклонить».
    if (action === "reject") { declineOf(target).props.onDecline(); return; }
    const actionButton = button(target, labels[action]); assert.ok(actionButton, `доступно действие ${action}`);
    actionButton.props.onClick();
  };
  for (const action of ["confirm", "reject", "finish", "drop"]) {
    await t.test(`${lang}: ${action} обновляет пассажиров без повторного входа и сохраняет выбранную оценку`, async () => {
      const previousWindow = globalThis.window;
      globalThis.window = { confirm: () => true };
      const h = await openCabinet(lang, action);
      try {
        button(rowOf(h.child.tree, 88), L("Изменить", "Үҙгәртеү")).props.onClick(); await cabSettle(h.child);
        stars(rowOf(h.child.tree, 88))[1].props.onClick(); await cabSettle(h.child);
        const beforeReads = globalThis.__cabReads, beforeReload = h.child.props.reloadKey, beforeRideReads = globalThis.__cabRideReads;
        perform(h, action); await h.sync();
        assert.equal(h.child.props.reloadKey, beforeReload + 1, "успешное действие передаёт новое поколение списка");
        assert.ok(globalThis.__cabReads > beforeReads, "список перечитан после успешного изменения");
        assert.equal(globalThis.__cabRideReads, beforeRideReads + 1, "после успеха перечитан и список поездок");
        assert.deepEqual(globalThis.__cabWrites, [[action, action === "finish" || action === "drop" ? 7 : 77]]);
        assert.equal(pendingCard(h.parent.tree, 77), undefined);
        if (action === "finish" || action === "drop") assert.equal(pendingCard(h.parent.tree, 78), undefined, "ожидающая бронь закрытого рейса больше не предлагает подтверждение");
        if (action === "confirm") {
          assert.equal(freeSeats(h.parent.tree, lang), "1", "место зарезервировано при создании: подтверждение не списывает его второй раз");
          assert.ok(text(rideCard(h.parent.tree, 7)).includes(L("1 из 2 занято", "2-нән 1 банд")));
          assert.ok(button(rowOf(h.child.tree, 77), L("Открыть поездку", "Сәфәрҙе асыу")), "подтверждённый пассажир сразу в «Едут с тобой»");
          assert.equal(stars(rowOf(h.child.tree, 77)).length, 0);
        } else if (action === "finish") {
          assert.equal(stars(rowOf(h.child.tree, 77)).length, 5, "завершённый пассажир сразу доступен для оценки");
          assert.equal(rowOf(h.child.tree, 78), undefined, "неподтверждённого пассажира не записываем в состоявшуюся поездку");
        } else assert.equal(rowOf(h.child.tree, 77), undefined, "отменённый пассажир не остаётся среди едущих");
        if (action === "reject") {
          assert.equal(freeSeats(h.parent.tree, lang), "2", "отказ освобождает место и сразу обновляет общий счётчик");
          assert.ok(text(rideCard(h.parent.tree, 7)).includes(L("0 из 2 занято", "2-нән 0 банд")), "карточка поездки тоже показывает свободные места");
        }
        if (action === "finish" || action === "drop") {
          assert.equal(freeSeats(h.parent.tree, lang), "0");
          assert.equal(rideCard(h.parent.tree, 7), undefined, "закрытый рейс не возвращается в активные после перечитывания");
        }
        assert.equal(stars(rowOf(h.child.tree, 88))[1].props["aria-pressed"], true, "несохранённые две звезды другой поездки остаются выбранными");
        assert.ok(button(rowOf(h.child.tree, 88), L("Отправить оценку", "Баһаны ебәреү")));
      } finally { h.close(); globalThis.window = previousWindow; }
    });
    await t.test(`${lang}: ошибка ${action} сохраняет пассажиров и не вызывает ложное обновление`, async () => {
      const previousWindow = globalThis.window;
      globalThis.window = { confirm: () => true };
      const h = await openCabinet(lang, action);
      try {
        const beforeReads = globalThis.__cabReads, beforeReload = h.child.props.reloadKey;
        globalThis.__cabFail = action;
        perform(h, action); await h.sync();
        assert.equal(h.child.props.reloadKey, beforeReload, "ошибка не меняет поколение списка");
        assert.equal(globalThis.__cabReads, beforeReads, "неуспешное действие не объявляет список изменённым");
        assert.ok(text(h.parent.tree).includes("Сервер устал"), "причина отказа остаётся видна");
        if (action === "confirm" || action === "reject") assert.ok(pendingCard(h.parent.tree, 77));
        else {
          assert.ok(rowOf(h.child.tree, 77), "едущий пассажир не исчезает после отказа сервера");
          assert.ok(pendingCard(h.parent.tree, 78));
        }
      } finally { h.close(); globalThis.window = previousWindow; }
    });
  }
  await t.test(`${lang}: ошибка перечитывания мест после успешного отказа сохраняет последний полученный список`, async () => {
    const h = await openCabinet(lang);
    try {
      assert.equal(freeSeats(h.parent.tree, lang), "1");
      globalThis.__cabRidesFail = true;
      perform(h, "reject"); await h.sync();
      assert.equal(globalThis.__cabRides[0].seats_left, 2, "сервер уже освободил место");
      assert.equal(pendingCard(h.parent.tree, 77), undefined, "успешный отказ остаётся применённым");
      assert.equal(freeSeats(h.parent.tree, lang), "1", "сетевая ошибка не превращает известный остаток в ноль");
      assert.ok(text(rideCard(h.parent.tree, 7)).includes(L("1 из 2 занято", "2-нән 1 банд")));
      assert.equal(ofType(h.parent.tree, "LoadingList").length, 0, "кабинет не скрывается повторной полной загрузкой");
      assert.equal(globalThis.__cabRideReads, 2, "нет бесконечного перечитывания после ошибки");
    } finally { h.close(); }
  });
}

await t.test("новое действие отменяет перечитывание мест: поздний старый ответ не затирает актуальный остаток", async () => {
  const h = await openCabinet("ru", "reject", true);
  try {
    globalThis.__cabHoldRides = true;
    declineOf(pendingCard(h.parent.tree, 77)).props.onDecline(); await h.sync();
    const [old] = globalThis.__cabRideLoads;
    assert.ok(old && !old.signal.aborted);
    declineOf(pendingCard(h.parent.tree, 78)).props.onDecline(); await h.sync();
    const current = globalThis.__cabRideLoads[1];
    assert.equal(old.signal.aborted, true);
    assert.ok(current && !current.signal.aborted);
    current.resolve(current.rows); await h.sync();
    assert.equal(freeSeats(h.parent.tree, "ru"), "3");
    old.resolve(old.rows); await h.sync();
    assert.equal(freeSeats(h.parent.tree, "ru"), "3", "поздний остаток 2 не заменяет новый остаток 3");
    assert.ok(text(rideCard(h.parent.tree, 7)).includes("0 из 3 занято"));
    assert.equal(globalThis.__cabRideReads, 3, "только начальная загрузка и два успешных действия");
    h.close(); assert.equal(current.signal.aborted, true, "уход с экрана отменяет запрос мест");
  } finally { h.close(); }
});

await t.test("перечитывание отменяет прошлый запрос: поздний старый ответ не возвращает устаревших пассажиров", async () => {
  globalThis.__cabPendingLoads = [];
  const Passengers = await bundleComponent("src/components/DriverPassengers.tsx", { mocks: {
    "/i18n/lang": langMock("ru"), "/api/client": CABINET_CLIENT,
    "/api/bookings": `export const fetchDriverBookings=signal=>new Promise(resolve=>globalThis.__cabPendingLoads.push({signal,resolve}));
      export const markNoShow=async()=>({});export const markArriving=async()=>({});export const rateBooking=async()=>({});`,
  } });
  const h = mount(Passengers, { onOpenTrip() {}, reloadKey: 0 }); h.render();
  const [old] = globalThis.__cabPendingLoads;
  h.props = { ...h.props, reloadKey: 1 }; h.render();
  const current = globalThis.__cabPendingLoads[1];
  assert.equal(old.signal.aborted, true, "предыдущий effect отменён при обновлении списка");
  assert.ok(current && !current.signal.aborted);
  current.resolve([{ ...ROWS[0], booking_id: 77 }]); await cabSettle(h);
  assert.ok(rowOf(h.tree, 77));
  old.resolve([ROWS[3]]); await cabSettle(h);
  assert.ok(rowOf(h.tree, 77), "поздний ответ отменённого запроса не затирает новый список");
  assert.equal(globalThis.__cabPendingLoads.length, 2, "перерисовки не запускают бесконечные запросы");
  h.unmount(); assert.equal(current.signal.aborted, true, "при уходе с экрана запрос отменяется");
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
