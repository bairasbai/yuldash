import assert from "node:assert/strict";
import { bundleComponent, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";

const key = "rideshare_card_payments";
const blocker = { code: "bank_project_unaccepted", ru: "Банк на проверке", ba: "Банк тикшерелә" };
const base = { stored: null, effective: true, revision: 0, blockers: [], history: [] };
const checks = checker();

async function withSwitch(language, row, lostReply, check) {
  let current = row;
  const reads = [];
  const writes = [];
  globalThis.cardSwitchFixture = {
    get(path, options) {
      reads.push({ path, generation: options._generation, cache: options.cache });
      return Promise.resolve({ switches: { [key]: current } });
    },
    post(path, body, options) {
      writes.push({ path, body, generation: options._generation });
      current = { ...current, stored: false, revision: current.revision + 1 };
      return lostReply ? Promise.reject(new Error("reply lost")) : Promise.resolve(current);
    },
  };
  let view;
  try {
    const page = await bundleComponent("src/screens/AdminSwitchesScreen.tsx", {
      real: ["/api/ownerSwitches"],
      mocks: {
        "/client": [
          "export class ApiError extends Error {}",
          'export const getSessionGeneration = () => "qa-owner";',
          "export const apiGet = (...args) => globalThis.cardSwitchFixture.get(...args);",
          "export const apiPost = (...args) => globalThis.cardSwitchFixture.post(...args);",
        ].join("\n"),
        "/utils/sessionCredentials": "export const subscribeSessionChanges = () => () => {};",
        "/i18n/lang": langMock(language),
      },
    });
    const wrapper = mount(page);
    const child = wrapper.render();
    wrapper.unmount();
    view = mount(child.type, child.props);
    await view.settle();
    await check(view, reads, writes);
  } finally {
    view?.unmount();
    delete globalThis.cardSwitchFixture;
  }
}

function toggle(view) {
  return ofType(view.tree, "button").find((node) => node.props.role === "switch");
}

for (const [language, unavailable] of [["ru", "Сейчас недоступно"], ["ba", "Әле эшләмәй"]]) {
  await checks.test(`Карточный выключатель остаётся заблокированным (${language})`, () =>
    withSwitch(language, base, false, async (view, reads, writes) => {
      assert.equal(toggle(view).props["aria-checked"], false);
      assert.equal(toggle(view).props.disabled, true);
      assert.ok(text(view.tree).includes(unavailable));
      toggle(view).props.onClick();
      await view.settle();
      assert.deepEqual(writes, []);
      assert.deepEqual(reads, [{ path: "/admin/switches", generation: "qa-owner", cache: "no-store" }]);
    }));
}

await checks.test("Сброс отправляет ревизию и подтверждается перечитыванием после потери ответа", () =>
  withSwitch("ba", { ...base, stored: true, blockers: [blocker] }, true, async (view, reads, writes) => {
    assert.ok(text(view.tree).includes(blocker.ba));
    assert.equal(toggle(view).props.disabled, false);
    toggle(view).props.onClick();
    await view.settle();
    assert.deepEqual(writes, [{
      path: `/admin/switches/${key}`, body: { value: false, confirm: false, expected_revision: 0 }, generation: "qa-owner",
    }]);
    assert.equal(reads.length, 2);
    assert.equal(toggle(view).props["aria-checked"], false);
    assert.equal(toggle(view).props.disabled, true);
  }));

await checks.test("Сохранённое включение без известной ревизии нельзя изменить", () =>
  withSwitch("ru", { ...base, stored: true, revision: undefined }, false, async (view, _reads, writes) => {
    assert.equal(toggle(view).props.disabled, true);
    assert.deepEqual(writes, []);
  }));

checks.done();
