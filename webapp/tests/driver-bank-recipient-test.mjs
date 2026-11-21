import assert from "node:assert/strict";
import { bundleComponent, button, mount, text } from "./shallow-harness.mjs";

const base = "/driver/bank-recipient";
const attempt = { setup_id: "a".repeat(32), expires_at: "2030-01-01T00:00:00Z", version: null, mode: "synthetic" };
const receipt = { version: "b".repeat(32), confirmed_at: "2026-11-21T00:00:00Z" };
const revokeVersion = "c".repeat(32);
const original = { state: "unconfigured", version: null, can_revoke: false };
const connected = { state: "configured", version: receipt.version, can_revoke: true };
const props = { userId: 7, generation: "driver-local" };

const transportMock = [
  'export const API_BASE = "http://localhost:8011";',
  "export const authSessionId = () => globalThis.recipientFixture.sessionId;",
  "export const isAccountSession = (owner) => owner.generation === globalThis.recipientFixture.generation;",
  "export const ApiError = class extends Error { constructor(status) { super(); this.status = status; } };",
  "export const apiCommand = (path, owner, options) => globalThis.recipientFixture.command(path, owner, options);",
].join("\n");

const Card = await bundleComponent("src/components/DriverBankRecipientCard.tsx", {
  exportName: "DriverBankRecipientCard",
  real: ["/api/driverBankRecipient", "/utils/driverBankRecipientSetup"],
  mocks: {
    "/api/client": transportMock,
    "./client": transportMock,
    "/i18n/lang": "export const useLang = () => ({ appText: (ru) => ru });",
  },
});

async function withCard(configure, check) {
  const stored = new Map();
  const calls = [];
  const fixture = {
    generation: props.generation, sessionId: "a".repeat(64),
    state: original, receipt: null, confirm: null, outage: false, blockStorage: false,
    async command(path, owner, options) {
      assert.equal(owner.userId, props.userId);
      calls.push({ path, method: options.method, body: options.body, oneShot: options.oneShot });
      if (options.method === "POST") assert.equal(options.oneShot, true);
      if (path === base && !this.outage) return this.state;
      if (path === `${base}/setup`) return attempt;
      if (path === `${base}/setup/${attempt.setup_id}/receipt` && !this.outage) return { confirmation: this.receipt };
      if (path === `${base}/setup/${attempt.setup_id}/confirm`) return this.confirm();
      if (path === `${base}/revoke`) {
        this.state = { state: "disabled", version: revokeVersion, can_revoke: false };
        return this.state;
      }
      throw new Error("Unexpected or unavailable request");
    },
  };
  globalThis.recipientFixture = fixture;
  globalThis.window = { addEventListener() {}, removeEventListener() {} };
  globalThis.localStorage = {
    getItem: (key) => stored.get(key) ?? null,
    setItem: (key, value) => {
      if (fixture.blockStorage) throw new Error("quota");
      stored.set(key, value);
    },
    removeItem: (key) => stored.delete(key),
  };
  configure(fixture);
  const ui = mount(Card, props);
  try {
    await ui.settle();
    await check(ui, fixture, calls, stored);
  } finally {
    ui.unmount();
    delete globalThis.recipientFixture;
    delete globalThis.window;
    delete globalThis.localStorage;
  }
}

async function click(ui, label) {
  const action = button(ui.tree, label);
  assert.ok(action, label);
  assert.notEqual(action.props.disabled, true, label);
  action.props.onClick();
  await ui.settle();
}

await withCard((fixture) => {
  fixture.confirm = () => {
    fixture.state = connected;
    fixture.receipt = receipt;
    fixture.outage = true;
    throw new Error("reply lost");
  };
}, async (ui, fixture, calls, stored) => {
  await click(ui, "Подключить тестовый реквизит");
  await click(ui, "Подтвердить реквизит");
  assert.match(text(ui.tree), /Не получили подтверждение/);
  assert.equal(stored.size, 1);
  assert.ok([...stored.values()].every((value) => !value.includes(`synthetic-${attempt.setup_id}`)));
  assert.equal(calls.filter((call) => call.path.endsWith("/confirm")).length, 1);
  ui.unmount();
  fixture.outage = false;
  const reopened = mount(Card, props);
  try {
    await reopened.settle();
    assert.match(text(reopened.tree), /Подключение подтверждено/);
    assert.equal(calls.filter((call) => call.path.endsWith("/confirm")).length, 1);
    assert.equal(stored.size, 0);
  } finally {
    reopened.unmount();
  }
});
console.log("✓ Потерянное подтверждение восстановилось после нового запуска без второго POST");

await withCard((fixture) => { fixture.blockStorage = true; }, async (ui, _, calls) => {
  await click(ui, "Подключить тестовый реквизит");
  assert.match(text(ui.tree), /Не получили подтверждение/);
  assert.equal(button(ui.tree, "Подтвердить реквизит"), undefined);
  assert.equal(calls.filter((call) => call.path.endsWith("/confirm")).length, 0);
});
console.log("✓ Ошибка хранилища блокирует подтверждение");

await withCard((fixture) => {
  fixture.confirm = () => { throw new Error("request interrupted"); };
}, async (ui, fixture, calls) => {
  await click(ui, "Подключить тестовый реквизит");
  await click(ui, "Подтвердить реквизит");
  await click(ui, "Проверить статус");
  assert.equal(calls.filter((call) => call.path.endsWith("/confirm")).length, 1);
  fixture.confirm = () => {
    fixture.state = connected;
    fixture.receipt = receipt;
    return receipt;
  };
  await click(ui, "Подтвердить реквизит");
  const submitted = calls.filter((call) => call.path.endsWith("/confirm"));
  assert.equal(submitted.length, 2);
  assert.equal(submitted[0].body, submitted[1].body);
  assert.match(text(ui.tree), /Подключение подтверждено/);
});
console.log("✓ Ручной повтор использует прежнюю попытку и точное тело");

await withCard((fixture) => {
  fixture.state = { ...connected, state: "disabled" };
}, async (ui, _, calls) => {
  assert.equal(button(ui.tree, "Заменить тестовый реквизит"), undefined);
  await click(ui, "Отключить реквизит");
  await click(ui, "Подтвердить отключение");
  assert.match(text(ui.tree), /Реквизит отключён/);
  assert.deepEqual(calls.filter((call) => call.path.endsWith("/revoke")).map((call) => JSON.parse(call.body)),
    [{ version: connected.version }]);
});
console.log("✓ На паузе можно отозвать реквизит без нового подключения");

await withCard(() => {}, async (ui, fixture, calls, stored) => {
  await click(ui, "Подключить тестовый реквизит");
  let delivered;
  fixture.confirm = () => new Promise((resolve) => { delivered = resolve; });
  button(ui.tree, "Подтвердить реквизит").props.onClick();
  await ui.settle();
  assert.equal(calls.filter((call) => call.path.endsWith("/confirm")).length, 1);
  fixture.generation = "new-login";
  fixture.sessionId = "d".repeat(64);
  const callsBeforeResponse = calls.length;
  delivered(receipt);
  await ui.settle();
  assert.equal(calls.length, callsBeforeResponse);
  assert.doesNotMatch(text(ui.tree), /Подключение подтверждено/);
  assert.equal(stored.size, 1);
  ui.unmount();
  const anotherLogin = mount(Card, { ...props, generation: fixture.generation });
  try {
    await anotherLogin.settle();
    assert.ok(button(anotherLogin.tree, "Подключить тестовый реквизит"));
    assert.equal(button(anotherLogin.tree, "Подтвердить реквизит"), undefined);
  } finally {
    anotherLogin.unmount();
  }
});
console.log("✓ Запоздалое подтверждение остаётся в границах исходного входа");
