// ================================================================
//  Паритет с Android, шаг 3 (17.10.2026): отмена брони — как в приложении.
//
//  Было на сайте: на «Брони» одна кнопка «Отменить бронь» для ЛЮБОГО статуса — и для ждущей,
//  и для подтверждённой, и «в пути» (человек уже в машине); системное окно браузера вместо
//  своего; без причины — `cancelBooking` слал запрос без тела, водитель не узнавал, почему место
//  освободилось.
//
//  Стало как в Android (BookingActiveTripScreen.kt):
//   • «Бронь»: отменить можно только ЖДУЩУЮ бронь и только пассажиру — своим окном
//     «Отменить бронь?» на двух языках;
//   • экран поездки: подтверждённую — «Отменить поездку», подпись «Отмена бесплатна до начала
//     поездки», окно с причиной (по желанию) → POST /bookings/{id}/cancel {reason};
//     сервер ответил «уже завершена» — так и говорим, а не «отменено»;
//   • «в пути» (после «Я сел») кнопки отмены нет.
//
//  Исполняем настоящие BookingScreen, ActiveTripScreen и api/bookings.ts (shallow-harness.mjs).
// ================================================================
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { build } from "esbuild";
import { ROOT, all, bundleComponent, button, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";
import { openActiveTrip } from "./active-trip-fixture.mjs";

const t = checker();
globalThis.document ??= { visibilityState: "visible", addEventListener() {}, removeEventListener() {} };
const L = (lang, ru, ba) => (lang === "ba" ? ba : ru);

// ---------------------------------------------------------------- api: причина уходит в теле
const api = await build({
  entryPoints: [ROOT + "src/api/bookings.ts"], bundle: true, write: false, format: "esm", platform: "node", logLevel: "silent",
  plugins: [{ name: "client", setup(b) {
    b.onResolve({ filter: /\/client$/ }, () => ({ path: "client", namespace: "c" }));
    b.onLoad({ filter: /.*/, namespace: "c" }, () => ({
      contents: `export const apiGet=async()=>null;export const apiPost=async(p,b)=>{globalThis.__posts.push([p,b]);return{status:"cancelled"}};`,
      loader: "js",
    }));
  } }],
});
const B = await import("data:text/javascript;base64," + Buffer.from(api.outputFiles[0].text).toString("base64"));

await t.test("api: cancelBooking шлёт {reason} — с кодом причины и пустой, если причину не выбрали", async () => {
  globalThis.__posts = [];
  await B.cancelBooking(7, "found_other");
  await B.cancelBooking(8);
  assert.deepEqual(globalThis.__posts, [
    ["/bookings/7/cancel", { reason: "found_other" }],
    ["/bookings/8/cancel", { reason: "" }],
  ]);
});

await t.test("причины — три кода Android, все из белого списка сервера CANCEL_REASONS", () => {
  const codes = B.CANCEL_REASONS.map((r) => r.code);
  assert.deepEqual(codes, ["plans_changed", "found_other", "driver_no_response"]);
  const py = ROOT + "../backend/app/safety_logic.py";
  if (existsSync(py)) {
    const set = readFileSync(py, "utf8").match(/CANCEL_REASONS[^{]*\{([\s\S]*?)\}/)?.[1] ?? "";
    for (const c of codes) assert.ok(set.includes(`"${c}"`), `сервер не знает код ${c} — свёл бы его к «other»`);
  }
  const kt = ROOT + "../android/app/src/main/java/com/yuldash/app/BookingActiveTripScreen.kt";
  if (existsSync(kt)) {
    const src = readFileSync(kt, "utf8");
    for (const r of B.CANCEL_REASONS) {
      assert.ok(src.includes(`"${r.code}" to appText("${r.ru}", "${r.ba}")`), "подпись причины не как в Android: " + r.code);
    }
  }
});

// ---------------------------------------------------------------- «Бронь»: только ждущая, только пассажир
async function openBooking(lang, role, status) {
  const Screen = await bundleComponent("src/screens/BookingScreen.tsx", {
    mocks: {
      "react-router-dom": `export const Navigate="Navigate";export const useNavigate=()=>p=>globalThis.__bk.nav.push(p);export const useParams=()=>({id:"42"});`,
      "/i18n/lang": langMock(lang),
      "/api/bookings": `export const fetchBookingDetails=async()=>({...globalThis.__bk.details});export const cancelBooking=async(...a)=>{globalThis.__bk.cancels.push(a);return{id:42,status:"cancelled"};};`,
      "/api/client": `export class ApiError extends Error{}`,
      "/analytics": `export const track=(e)=>globalThis.__bk.tracked.push(e);`,
      "/booking/OwnBookingsProvider": `export const useRefreshOwnBookings=()=>()=>{globalThis.__bk.refreshed++;};`,
      "/utils/bookingLink": `export const bookingLinkPath=()=>null;`,
    },
  });
  globalThis.__bk = {
    nav: [], cancels: [], tracked: [], refreshed: 0,
    details: { booking_id: 42, ride_id: 7, role, status, contact_unlocked: status !== "pending", from_city: "Уфа", to_city: "Сибай",
      depart_at: "2030-01-01T10:00:00", seats: 1, price: 500, pay_method: "cash", driver_name: "Ринат", driver_verified: true,
      driver_phone: "", driver_car: "Lada", pickup: "" },
  };
  const h = mount(Screen);
  h.render();
  await h.settle();
  return h;
}

for (const lang of ["ru", "ba"]) {
  const cancelLabel = L(lang, "Отменить бронь", "Бронде кире алырға");
  await t.test(`${lang}: «Бронь», ждёт водителя — «Отменить бронь» открывает своё окно, а не окно браузера`, async () => {
    const realConfirm = globalThis.window;
    let browserConfirms = 0;
    globalThis.window = { confirm: () => { browserConfirms++; return false; } };
    try {
      const h = await openBooking(lang, "passenger", "pending");
      const btn = button(h.tree, cancelLabel);
      assert.ok(btn, "кнопка отмены есть");
      btn.props.onClick();
      await h.settle();
      assert.equal(browserConfirms, 0, "системное окно браузера вместо своего");
      assert.deepEqual(globalThis.__bk.cancels, [], "до подтверждения в окне запрос не уходит");
      const dlg = ofType(h.tree, "AlertDialog")[0];
      assert.ok(dlg, "открылось окно");
      assert.equal(dlg.props.title, L(lang, "Отменить бронь?", "Бронде кире алырғамы?"));
      assert.equal(dlg.props.text, L(lang, "Место освободится, а водитель получит уведомление.", "Урын бушаясаҡ, ә йөрөтөүсе хәбәр аласаҡ."));
      assert.equal(dlg.props.confirm.label, L(lang, "Да, отменить", "Эйе, кире алырға"));
      assert.equal(dlg.props.dismiss.label, L(lang, "Оставить бронь", "Бронде ҡалдырырға"));
      dlg.props.dismiss.onClick();
      await h.settle();
      assert.equal(ofType(h.tree, "AlertDialog").length, 0, "«Оставить бронь» закрывает окно");
      assert.deepEqual(globalThis.__bk.cancels, []);
      button(h.tree, cancelLabel).props.onClick();
      await h.settle();
      ofType(h.tree, "AlertDialog")[0].props.confirm.onClick();
      await h.settle();
      assert.deepEqual(globalThis.__bk.cancels, [[42]], "подтвердил — бронь отменена");
      assert.deepEqual(globalThis.__bk.tracked, ["booking_cancel"]);
      assert.equal(globalThis.__bk.refreshed, 1, "«моя бронь» в ленте перечитана");
    } finally {
      globalThis.window = realConfirm;
    }
  });

  for (const status of ["confirmed", "onboard"]) {
    await t.test(`${lang}: «Бронь», ${status} — кнопки «Отменить бронь» нет (подтверждённую отменяют на экране поездки)`, async () => {
      const h = await openBooking(lang, "passenger", status);
      assert.equal(button(h.tree, cancelLabel), undefined);
      assert.ok(button(h.tree, L(lang, "Открыть поездку", "Сәфәрҙе асырға")), "путь к экрану поездки остаётся");
    });
  }
}

await t.test("«Бронь»: водителю отмены нет, даже если экран открылся у него", async () => {
  const h = await openBooking("ru", "driver", "pending");
  assert.equal(button(h.tree, "Отменить бронь"), undefined);
});

await t.test("«Бронь»: системного окна браузера в коде больше нет", () => {
  const src = readFileSync(ROOT + "src/screens/BookingScreen.tsx", "utf8").replace(/\/\/.*$/gm, "").replace(/\/\*[\s\S]*?\*\//g, "");
  assert.ok(!/window\.confirm\(/.test(src));
});

// ---------------------------------------------------------------- экран поездки: подтверждённая — с причиной
for (const lang of ["ru", "ba"]) {
  const tripCancel = L(lang, "Отменить поездку", "Сәфәрҙе кире алыу");
  await t.test(`${lang}: экран поездки, подтверждена — «Отменить поездку» и подпись «бесплатна до начала»`, async () => {
    const { h } = await openActiveTrip({ lang, role: "passenger", status: "confirmed" });
    assert.ok(button(h.tree, tripCancel), "кнопка есть");
    assert.ok(
      text(h.tree).includes(L(lang, "Отмена бесплатна до начала поездки — место вернётся в поездку.", "Сәфәр башланғанға тиклем кире алыу бушлай — урын кире ҡайта."))
    );
  });

  await t.test(`${lang}: окно «Отменить поездку?» — три причины, выбранная уходит на сервер`, async () => {
    const { h, at } = await openActiveTrip({ lang, role: "passenger", status: "confirmed" });
    button(h.tree, tripCancel).props.onClick();
    await h.settle();
    const dlg = () => ofType(h.tree, "AlertDialog").find((d) => d.props.title === L(lang, "Отменить поездку?", "Сәфәрҙе кире аларғамы?"));
    assert.ok(dlg(), "окно открылось");
    assert.equal(dlg().props.text, L(lang, "Бронь будет отменена, место освободится для других.", "Брон кире алына, урын башҡаларға бушай."));
    assert.ok(text(dlg()).includes(L(lang, "Причина (по желанию):", "Сәбәбе (теләгәнсә):")));
    const reasons = all(dlg()).filter((n) => n.type === "button" && String(n.props.className).includes("adlg__reason"));
    assert.deepEqual(reasons.map((r) => text(r)), [
      L(lang, "Планы поменялись", "Пландар үҙгәрҙе"),
      L(lang, "Нашёл другой вариант", "Башҡа юл таптым"),
      L(lang, "Водитель не отвечает", "Йөрөтөүсе яуап бирмәй"),
    ]);
    assert.deepEqual(at.posts, [], "пока не подтвердил — запроса нет");
    reasons[1].props.onClick();
    await h.settle();
    const picked = all(dlg()).filter((n) => n.type === "button" && n.props["aria-pressed"] === true);
    assert.equal(picked.length, 1, "выбрана одна причина");
    assert.equal(text(picked[0]), L(lang, "Нашёл другой вариант", "Башҡа юл таптым"));
    assert.equal(dlg().props.confirm.label, L(lang, "Да, отменить", "Эйе, кире алырға"));
    assert.equal(dlg().props.dismiss.label, L(lang, "Назад", "Артҡа"));
    dlg().props.confirm.onClick();
    await h.settle();
    assert.deepEqual(at.posts.filter(([p]) => p.endsWith("/cancel")), [["/bookings/42/cancel", { reason: "found_other" }]]);
    assert.equal(dlg(), undefined, "окно закрылось");
    assert.ok(
      text(h.tree).includes(L(lang, "Договорились ехать? Заверши поездку в приложении — так работает защита и SOS 💚", "Барырға һөйләштегеҙме? Сәфәрҙе ҡушымтала тамамла — шулай яҡлау һәм SOS эшләй 💚")),
      "отмена после открытого телефона — мягкое напоминание, как в Android"
    );
    assert.equal(button(h.tree, tripCancel), undefined, "после отмены кнопки нет");
    assert.ok(at.tracked.some(([e]) => e === "booking_cancel"));
    assert.equal(at.refreshed, 1);
  });
}

await t.test("без причины — уходит пустая строка; повторное касание снимает выбор", async () => {
  const { h, at } = await openActiveTrip({ role: "passenger", status: "confirmed",
    cancel: () => ({ id: 42, status: "cancelled", contact_then_cancel: false }) });
  button(h.tree, "Отменить поездку").props.onClick();
  await h.settle();
  const reason = () => all(h.tree).find((n) => n.type === "button" && text(n) === "Планы поменялись");
  reason().props.onClick();
  await h.settle();
  reason().props.onClick();
  await h.settle();
  ofType(h.tree, "AlertDialog")[0].props.confirm.onClick();
  await h.settle();
  assert.deepEqual(at.posts.filter(([p]) => p.endsWith("/cancel")), [["/bookings/42/cancel", { reason: "" }]]);
  assert.ok(text(h.tree).includes("Поездка отменена"));
});

await t.test("сервер не отменил (поездку уже завершили) — «уже завершена», а не «отменено»", async () => {
  const { h, at } = await openActiveTrip({ role: "passenger", status: "confirmed", cancel: () => ({ id: 42, status: "done" }) });
  button(h.tree, "Отменить поездку").props.onClick();
  await h.settle();
  ofType(h.tree, "AlertDialog")[0].props.confirm.onClick();
  await h.settle();
  assert.ok(text(h.tree).includes("Поездка уже завершена — отменить её нельзя."));
  assert.ok(!text(h.tree).includes("Поездка отменена"));
  assert.ok(!at.tracked.some(([e]) => e === "booking_cancel"), "несостоявшаяся отмена не считается отменой");
});

await t.test("сеть упала — окно остаётся, ответ сервера или «Не удалось отменить»", async () => {
  const { h } = await openActiveTrip({ role: "passenger", status: "confirmed", cancel: () => { throw new Error("offline"); } });
  button(h.tree, "Отменить поездку").props.onClick();
  await h.settle();
  ofType(h.tree, "AlertDialog")[0].props.confirm.onClick();
  await h.settle();
  assert.ok(text(h.tree).includes("Не удалось отменить"));
  assert.ok(button(h.tree, "Отменить поездку"), "бронь цела — кнопка на месте");
});

for (const [role, status, why] of [
  ["passenger", "onboard", "«в пути» — человек уже в машине"],
  ["passenger", "pending", "ждущую отменяют на «Брони»"],
  ["driver", "confirmed", "причины — пассажирские (у водителя «Не вышел» и «Снять поездку»)"],
]) {
  await t.test(`экран поездки: ${role}, ${status} — «Отменить поездку» нет (${why})`, async () => {
    const { h } = await openActiveTrip({ role, status });
    assert.equal(button(h.tree, "Отменить поездку"), undefined);
  });
}

t.done();
