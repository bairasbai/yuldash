// ================================================================
//  Паритет с Android, шаг 4 (17.10.2026): такси выключено на сервере (/config/features,
//  taxi=false) — такси-пунктов нет нигде: ни в профиле, ни в кабинете пассажира, ни в кабинете
//  водителя. Включат — появятся сами.
//
//  Было: «Быстрый заказ», «Мои предзаказы», «Мои поездки на такси», линия, готовность к работе
//  висели всегда — человек жал и упирался в «Такси скоро» или отказ сервера.
//  Android: ProfileScreen.kt, taxiOn = rememberFeatures().isOn(Feature.Taxi) в обоих кабинетах.
//  Анкета таксиста и сроки документов остаются — набор идёт и до запуска (как в Android).
//
//  Исполняем настоящие экраны (shallow-harness.mjs) и настоящий featureRules.ts.
// ================================================================
import assert from "node:assert/strict";
import { build } from "esbuild";
import { ROOT, all, bundleComponent, checker, langMock, mount, ofType } from "./shallow-harness.mjs";

const t = checker();
const lib = await build({ entryPoints: [ROOT + "src/featureRules.ts"], bundle: true, write: false, format: "esm", platform: "node", logLevel: "silent" });
const R = await import("data:text/javascript;base64," + Buffer.from(lib.outputFiles[0].text).toString("base64"));

const flags = (taxi) => `export const useFeatures=()=>({rides:true,taxi:${taxi},courier:false,parcels:false,ads:false,business:false,ready:true});`;
const ROUTER = `export const useNavigate=()=>p=>{};export const Link="Link";`;

await t.test("featureRules: условие «taxi», как у остальных волн (неизвестное = выключено)", () => {
  assert.equal(R.isOpen("taxi", R.FEATURES_OFF), false, "ответа сервера нет — такси закрыто");
  assert.equal(R.isOpen("taxi", { ...R.FEATURES_OFF, taxi: true }), true);
  assert.equal(R.isOpen("taxi", { ...R.FEATURES_OFF, courier: true, parcels: true, ads: true, business: true }), false, "другие флаги такси не открывают");
  assert.equal(R.isOpen("taxi", R.parseFeatures({ taxi: "true" })), false, "не-булево — выключено");
});

async function render(rel, taxi, mocks) {
  // profileRole — чистая подпись роли (без браузера): собираем по-настоящему, а не пустышкой.
  const Screen = await bundleComponent(rel, { real: ["/featureRules", "/utils/profileRole"], mocks: { "/features": flags(taxi), ...mocks } });
  const h = mount(Screen);
  h.render();
  await h.settle();
  return h.tree;
}

// ---------------------------------------------------------------- профиль
const PROFILE = {
  "react-router-dom": ROUTER,
  "/auth/AuthProvider": `export const useAuth=()=>({user:{id:1,role:"user",name:"Айгуль"},isAuthed:true,status:"authed",logout:async()=>{}});`,
  "/i18n/lang": langMock("ru"),
  "/api/notifications": `export const fetchNotifUnread=async()=>0;`,
  "/api/support": `export const fetchSupportUnread=async()=>0;`,
};
for (const taxi of [false, true]) {
  await t.test(`профиль: такси ${taxi ? "включено" : "выключено"} — плитки «Быстрый заказ», «Я на линии (такси)», «Мои предзаказы» ${taxi ? "есть" : "спрятаны"}`, async () => {
    const tree = await render("src/screens/ProfileScreen.tsx", taxi, PROFILE);
    const keys = all(tree).filter((n) => n.type === "button" && String(n.props.className).includes("list-row--link")).map((n) => n.key);
    for (const k of ["taxi", "taxi-drive", "scheduled"]) assert.equal(keys.includes(k), taxi, `плитка ${k}`);
    for (const k of ["driver", "cabinet", "promo"]) assert.ok(keys.includes(k), `попутки и промокод на месте: ${k}`);
  });
}

// ---------------------------------------------------------------- кабинет пассажира
const PASSENGER = {
  "react-router-dom": ROUTER,
  "/i18n/lang": langMock("ru"),
  "/api/bookings": `export const fetchMyBookings=async()=>[];`,
  "/api/requests": `export const fetchMyRequests=async()=>[];`,
  "/api/safety": `export const fetchMyRestrictions=async()=>({items:[]});`,
  "/auth/AuthProvider": `export const useAuth=()=>({user:{id:1,rating:4.9}});`,
};
for (const taxi of [false, true]) {
  await t.test(`кабинет пассажира: такси ${taxi ? "включено" : "выключено"} — «Быстрый заказ», предзаказы и поездки на такси ${taxi ? "есть" : "спрятаны"}`, async () => {
    const tree = await render("src/screens/PassengerCabinetScreen.tsx", taxi, PASSENGER);
    const quick = all(tree).filter((n) => n.type === "button" && n.props.className === "quick-order");
    assert.equal(quick.length, taxi ? 1 : 0, "зелёная карточка «Быстрый заказ»");
    const titles = ofType(tree, "SettingsNavRow").map((n) => n.props.title);
    assert.equal(titles.includes("Мои предзаказы"), taxi);
    assert.equal(titles.includes("Мои поездки на такси"), taxi);
    for (const keep of ["Найти поездку", "Создать заявку", "Безопасность поездки"]) assert.ok(titles.includes(keep), "попутки на месте: " + keep);
  });
}

// ---------------------------------------------------------------- кабинет водителя
const DRIVER = {
  "react-router-dom": ROUTER,
  "/i18n/lang": langMock("ru"),
  "/api/client": `export class ApiError extends Error{}`,
  "/api/driver": `export const fetchDriverStatus=async()=>({online:false,docs_status:"verified",gender:""});
    export const fetchDriverRides=async()=>[];export const fetchMySchedules=async()=>[];
    export const setDriverOnline=async()=>({});export const setDriverGender=async()=>({});export const setTipsSbp=async()=>({});
    export const fetchRideTips=async()=>({});export const createSchedule=async()=>({});export const deleteSchedule=async()=>({});
    export const cancelRide=async()=>({});export const completeRide=async()=>({});`,
  "/api/bookings": `export const fetchDriverBookings=async()=>[];export const cancelBooking=async()=>({});export const confirmBooking=async()=>({});`,
  "/auth/AuthProvider": `export const useAuth=()=>({user:{id:1,rating:4.8}});`,
};
for (const taxi of [false, true]) {
  await t.test(`кабинет водителя: такси ${taxi ? "включено" : "выключено"} — линия, приоритет, поездки такси, готовность ${taxi ? "есть" : "спрятаны"}`, async () => {
    const tree = await render("src/screens/DriverCabinetScreen.tsx", taxi, DRIVER);
    const switches = ofType(tree, "SettingSwitchRow").map((n) => n.props.title);
    assert.equal(switches.includes("Я на линии"), taxi, "тумблер линии");
    assert.ok(switches.includes("Я — женщина за рулём"), "«женщина за рулём» — про попутки тоже, остаётся");
    assert.equal(ofType(tree, "PriorityCard").length, taxi ? 1 : 0, "приоритет заказов такси");
    const titles = ofType(tree, "SettingsNavRow").map((n) => n.props.title);
    for (const k of ["Мои поездки такси", "Готовность к работе", "Я на линии (такси)", "Что я вожу"]) {
      assert.equal(titles.includes(k), taxi, k);
    }
    for (const keep of ["Стать таксистом Юлдаша", "Документы и сроки", "Мой заработок", "Создать поездку"]) {
      assert.ok(titles.includes(keep), "остаётся: " + keep);
    }
    assert.equal(ofType(tree, "DriverPassengers").length, 1, "«Едут с тобой» — попутки, есть всегда");
  });
}

t.done();
