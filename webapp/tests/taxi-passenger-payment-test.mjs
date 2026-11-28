import assert from "node:assert/strict";
import { bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const t = checker();
for (const lang of ["ru", "ba"]) {
  const label = (ru, ba) => lang === "ba" ? ba : ru;
  const labels = {
    card: label("Картой", "Карта менән"),
    cash: label("Наличными", "Наличный менән"),
    check: label("Проверить оплату", "Түләүҙе тикшереү"),
    paid: label("Оплата отмечена", "Түләү билдәләнде"),
    pending: label("Не плати повторно.", "Ҡабат түләмә."),
  };
  const Pay = await bundleComponent("src/components/PayTripCard.tsx", {
    mocks: {
      "/i18n/lang": langMock(lang),
      "/api/client": "export class ApiError extends Error {}",
      "/api/wallet": `
        import { ApiError } from "../api/client";
        export const payBooking = async () => { throw Error("not a booking"); };
        export const payInstantOrder = async (id, method) => {
          const f = globalThis.__taxiPay;
          f.calls.push([id, method]);
          const result = f.results.shift();
          if (result.error) throw Object.assign(new ApiError("display error"), result.error);
          return result;
        };
      `,
      "/utils/pendingPayment": "export const rememberPayment=(...args)=>globalThis.__taxiPay.remembered.push(args);",
    },
  });
  const open = (results, props = {}) => {
    const f = globalThis.__taxiPay = { results, calls: [], remembered: [], paid: 0 };
    globalThis.window = { location: { href: "", pathname: "/fixture" } };
    const m = mount(Pay, { kind: "order", id: 42, amountLabel: "600 ₽", onPaid: () => f.paid++, ...props });
    m.render();
    return { m, f };
  };
  async function tap(m, name) {
    button(m.tree, labels[name]).props.onClick();
    await m.settle();
  }
  function holds(m) {
    assert.ok(!button(m.tree, labels.cash) && !button(m.tree, labels.card));
    assert.ok(button(m.tree, labels.check));
    assert.ok(text(m.tree).includes(labels.pending) && !text(m.tree).includes(labels.paid));
  }
  for (const error of [{ status: 409, code: "taxi_checkout_unresolved" }, { status: 503 }, { status: 0 }]) {
    await t.test(lang + " uncertain " + error.status + " blocks cash until canceled by check", async () => {
      const { m, f } = open([{ error }, { status: "canceled" }]);
      await tap(m, "card");
      holds(m);
      await tap(m, "check");
      assert.ok(button(m.tree, labels.cash));
      assert.ok(!text(m.tree).includes(labels.paid));
      assert.deepEqual(f.calls, [[42, "card"], [42, "check"]]);
      assert.equal(f.paid, 0);
    });
  }
  await t.test(lang + " confirmed disabled, not generic 503, leaves cash available", async () => {
    const { m, f } = open([{ error: { status: 503, code: "taxi_online_pay_off" } }, { status: "paid" }]);
    await tap(m, "card");
    assert.ok(button(m.tree, labels.cash) && !button(m.tree, labels.check) && !button(m.tree, labels.card));
    await tap(m, "cash");
    assert.equal(f.paid, 1);
  });
  await t.test(lang + " resumed receipt, pending without link, explicit bank success", async () => {
    const { m, f } = open([{ status: "pending" }, { status: "succeeded" }], { onlinePaymentPending: true });
    holds(m);
    assert.deepEqual(f.calls, []);
    await tap(m, "check");
    holds(m);
    assert.equal(f.paid, 0);
    await tap(m, "check");
    assert.ok(text(m.tree).includes(labels.paid));
    assert.equal(f.paid, 1);
    assert.deepEqual(f.calls, [[42, "check"], [42, "check"]]);
  });
  await t.test(lang + " pending checkout with link redirects, but is not called paid", async () => {
    const link = "https://yookassa.ru/fixture";
    const { m, f } = open([{ status: "pending", confirmation_url: link, payment_id: 13 }]);
    await tap(m, "card");
    holds(m);
    assert.equal(window.location.href, link);
    assert.deepEqual(f.remembered, [[13, "trip", "/fixture"]]);
    assert.equal(f.paid, 0);
  });
}
t.done();
