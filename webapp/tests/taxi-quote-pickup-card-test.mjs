import assert from "node:assert/strict";
import { bundleComponent, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";

const checks = checker();
const response = (category, options, extra = {}) => ({
  price: category === "standard" ? 300 : 400,
  distance_km: 9.5,
  eta_min: 14,
  category,
  options,
  ...extra,
});
const option = (category, price, pickupFields = {}) => ({ category, price, ...pickupFields });
const base = [
  option("standard", 300, { pickup_eta_min: 3, pickup_pending: false, pickup_max_rub: 100 }),
  option("comfort", 400, { pickup_eta_min: 11, pickup_pending: false, pickup_max_rub: 250 }),
];
const pending = [
  base[0],
  option("comfort", 400, { pickup_eta_min: null, pickup_pending: true, pickup_max_rub: 250 }),
];

async function withQuote(language, check) {
  const calls = [];
  const orders = [];
  const scheduled = [];
  globalThis.taxiPickupQuoteFixture = (input) => new Promise((resolve, reject) => calls.push({ input, resolve, reject }));
  globalThis.taxiPickupOrderFixture = async (body) => { orders.push(body); return { id: 81 }; };
  globalThis.taxiPickupScheduledFixture = async (body) => { scheduled.push(body); return { id: 82 }; };
  let view;
  try {
    const screen = await bundleComponent("src/screens/InstantOrderScreen.tsx", {
      exportName: "ComposeView",
      real: ["/utils/format"],
      mocks: {
        "/i18n/lang": langMock(language),
        "/api/client": "export class ApiError extends Error {}",
        "react-router-dom": "export const useNavigate = () => () => {};",
        "/api/instant": [
          "export const instantEstimate = (input) => globalThis.taxiPickupQuoteFixture(input);",
          "export const fetchNearbyDrivers = async () => ({ drivers: [] });",
          "export const ACTIVE_PASSENGER_STATUSES = new Set();",
          "export const createInstantOrder = (body) => globalThis.taxiPickupOrderFixture(body);",
          "export const createScheduledOrder = (body) => globalThis.taxiPickupScheduledFixture(body);",
          ...["fetchTaxiAvailability", "fetchMyOrders",
            "fetchInstantOrder", "cancelInstantOrder", "isUnlocked", "fetchAlternatives", "addAlternative"]
            .map((name) => `export const ${name} = () => { throw Error("Unexpected API call: ${name}"); };`),
        ].join("\n"),
        "/PayMethodPicker": [
          "export default null;",
          'export const rememberedPayMethod = () => "cash";',
          'export const PAY_METHODS_OPEN = [{code: "cash", shortRu: "Наличные", shortBa: "Ҡулаҡса"}];',
        ].join("\n"),
      },
    });
    view = mount(screen, {
      from: { lat: 54.7, lng: 56.0, text: "A" },
      to: { lat: 54.75, lng: 56.04, text: "B" },
      setFrom() {}, setTo() {}, onOrdered() {}, onScheduled() {},
    });
    await view.settle();
    await check(view, calls, orders, scheduled);
  } finally {
    view?.unmount();
    delete globalThis.taxiPickupQuoteFixture;
    delete globalThis.taxiPickupOrderFixture;
    delete globalThis.taxiPickupScheduledFixture;
  }
}

function sheet(view) {
  return ofType(view.tree, "TaxiSheet")[0].props;
}
function cards(view) {
  return ofType(sheet(view).body, "button").filter((node) => node.props.className?.startsWith("taxi-class"));
}
function part(card, name) {
  return text(ofType(card, "span").find((node) => node.props.className === "taxi-class__" + name));
}
function orderButton(view) {
  return ofType(sheet(view).footer, "button").find((node) => node.props.className.includes("__cta"));
}
function optionControl(view) {
  return ofType(sheet(view).extra, "TaxiOptions")[0].props;
}

for (const [language, waiting] of [
  ["ru", "Подача уточняется · до 250 ₽"],
  ["ba", "Килеү хаҡы асыҡлана · 250 ₽-ға тиклем"],
]) {
  await checks.test(`Карточки берут своё время; неизвестная подача с потолком не равна бесплатной (${language})`, () =>
    withQuote(language, async (view, calls) => {
      assert.equal(calls[0].input.category, "standard");
      calls[0].resolve(response("standard", base, { pickup_eta_min: 3, pickup_pending: false }));
      await view.settle();
      assert.deepEqual(cards(view).map((c) => part(c, "eta")), ["3 мин", "11 мин"]);
      assert.deepEqual(cards(view).map((c) => part(c, "pickup")), ["", ""]);
      assert.deepEqual(cards(view).map((c) => part(c, "price")), ["300 ₽", "400 ₽"]);

      optionControl(view).onToggle("seat_1_4");
      view.render();
      assert.deepEqual(cards(view).map((c) => part(c, "eta")), ["", ""]);
      assert.deepEqual(cards(view).map((c) => part(c, "price")), ["…", "…"]);
      assert.deepEqual(calls[1].input.options, ["seat_1_4"]);
      calls[1].resolve(response("standard", pending, { pickup_eta_min: 3, pickup_pending: false }));
      await view.settle();
      assert.deepEqual(cards(view).map((c) => part(c, "eta")), ["3 мин", ""]);
      assert.deepEqual(cards(view).map((c) => part(c, "pickup")), ["", waiting]);

      cards(view)[1].props.onClick();
      view.render();
      assert.deepEqual(cards(view).map((c) => part(c, "pickup")), ["", ""]);
      assert.equal(calls[2].input.category, "comfort");
      calls[2].resolve(response("comfort", pending, { pickup_pending: true, pickup_max_rub: 250 }));
      await view.settle();
      assert.equal(part(cards(view)[1], "pickup"), waiting);
      const cta = orderButton(view);
      assert.equal(cta.props.disabled, false, "Disclosed pending pickup is a valid quote");
      assert.equal(text(cta), language === "ru" ? "Заказать" : "Заказ итеү");
    }));
}

await checks.test("Просроченные ответы после Эконом → Комфорт → Эконом не возвращают старую цену", () =>
  withQuote("ru", async (view, calls) => {
    calls[0].resolve(response("standard", base, { pickup_eta_min: 3 }));
    await view.settle();
    cards(view)[1].props.onClick();
    view.render();
    cards(view)[0].props.onClick();
    view.render();
    assert.deepEqual(cards(view).map((c) => part(c, "price")), ["…", "…"]);
    calls[1].resolve(response("comfort", pending, { pickup_pending: true }));
    await view.settle();
    assert.deepEqual(cards(view).map((c) => part(c, "price")), ["…", "…"]);
    calls[2].resolve(response("standard", [option("standard", 320), base[1]], { price: 319 }));
    await view.settle();
    assert.equal(part(cards(view)[0], "price"), "320 ₽");
  }));

await checks.test("Старый формат не переносит общее время на карточки и не выдумывает потолок", () =>
  withQuote("ru", async (view, calls) => {
    calls[0].resolve(response("standard", [
      option("standard", 300), option("comfort", 400),
    ], { pickup_eta_min: 3, pickup_pending: true, pickup_max_rub: 0 }));
    await view.settle();
    assert.deepEqual(cards(view).map((c) => part(c, "eta")), ["", ""]);
    assert.deepEqual(cards(view).map((c) => part(c, "pickup")), ["Подача уточняется", ""]);
  }));

await checks.test("Заказ требует свежую цену: не отправляется во время расчёта и после ошибки", () =>
  withQuote("ru", async (view, calls, orders) => {
    calls[0].resolve(response("standard", base));
    await view.settle();
    assert.equal(orderButton(view).props.disabled, false);

    cards(view)[1].props.onClick();
    view.render();
    optionControl(view).onToggle("seat_1_4");
    view.render();
    await orderButton(view).props.onClick();
    assert.equal(orders.length, 0, "A direct click must also be guarded without a fresh quote");
    assert.equal(orderButton(view).props.disabled, true);

    calls[2].reject(new Error("Quote unavailable"));
    await view.settle();
    await orderButton(view).props.onClick();
    assert.equal(orders.length, 0, "A failed quote must not allow ordering");
    assert.equal(orderButton(view).props.disabled, true);

    const retry = ofType(sheet(view).extra, "button").find((node) => text(node) === "Повторить расчёт");
    assert.ok(retry, "The passenger can retry without changing their order");
    retry.props.onClick();
    view.render();
    assert.deepEqual(calls[3].input, calls[2].input);
    calls[3].resolve(response("comfort", base));
    await view.settle();
    assert.equal(text(orderButton(view)), "Заказать за 400 ₽");
    assert.equal(orderButton(view).props.disabled, false);
    await orderButton(view).props.onClick();
    assert.deepEqual(orders.map(({ category, options }) => ({ category, options })), [
      { category: "comfort", options: ["seat_1_4"] },
    ]);
  }));

await checks.test("Предзаказ ждёт цену на выбранное время, а не использует расчёт «Сейчас»", () =>
  withQuote("ru", async (view, calls, orders, scheduled) => {
    calls[0].resolve(response("standard", base));
    await view.settle();
    ofType(sheet(view).extra, "button").find((node) => text(node).trim() === "На время").props.onClick();
    view.render();
    assert.equal(orderButton(view).props.disabled, true);
    ofType(sheet(view).extra, "input").find((node) => node.props.type === "datetime-local")
      .props.onChange({ target: { value: "2030-03-01T18:30" } });
    view.render();
    await orderButton(view).props.onClick();
    assert.equal(scheduled.length, 0);
    const quoteCall = calls.at(-1);
    assert.equal(quoteCall.input.scheduled_at, new Date("2030-03-01T18:30").toISOString());
    quoteCall.resolve(response("standard", base));
    await view.settle();
    assert.equal(orderButton(view).props.disabled, false);
    await orderButton(view).props.onClick();
    assert.equal(orders.length, 0);
    assert.equal(scheduled.length, 1);
    assert.equal(scheduled[0].scheduled_at, quoteCall.input.scheduled_at);
  }));

checks.done();
