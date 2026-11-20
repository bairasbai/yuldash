
import assert from "node:assert/strict";
import { bundleComponent, button, mount, ofType, text } from "./shallow-harness.mjs";

const card = await bundleComponent("src/components/DriverPaySettingsCard.tsx", {
  exportName: "DriverPaySettingsCard",
  mocks: {
    "/api/client": "export const isAccountSession = () => globalThis.driverPayFixture.active;",
    "/api/driverPaySettings": [
      'export const DRIVER_PAY_METHODS = ["cash", "sbp"];',
      "export const getDriverPaySettings = (...args) => globalThis.driverPayFixture.read(...args);",
      "export const setDriverPaySettings = (...args) => globalThis.driverPayFixture.write(...args);",
    ].join("\n"),
    "/i18n/lang": "export const useLang = () => ({ appText: (ru) => ru, lang: 'ru' });",
    "/utils/format": "export const payMethodLabel = (method) => method;",
  },
});

const empty = { methods: [], updated_at: null };
const cash = { methods: ["cash"], updated_at: "2030-01-01T10:00:00Z" };
const sbp = { methods: ["sbp"], updated_at: "2030-01-01T10:00:01Z" };

async function withCard(reads, write, check) {
  const calls = [];
  globalThis.driverPayFixture = {
    active: true,
    read: async () => {
      const next = reads.shift();
      if (next instanceof Error) throw next;
      return next;
    },
    write: async (owner, methods, revision) => {
      calls.push({ owner, methods, revision });
      return write(calls.length);
    },
  };
  const fixture = mount(card, { userId: 7, generation: "driver-local" });
  try {
    await fixture.settle();
    await check(fixture, calls);
  } finally {
    fixture.unmount();
    delete globalThis.driverPayFixture;
  }
}

function choose(fixture, index) {
  ofType(fixture.tree, "input")[index].props.onChange({ currentTarget: { checked: true } });
}

async function submit(fixture) {
  await fixture.settle();
  const save = button(fixture.tree, "Сохранить");
  assert.equal(save.props.disabled, false);
  save.props.onClick();
  await fixture.settle();
}

await withCard([empty, cash], () => { throw Error("reply lost"); }, async (fixture, calls) => {
  choose(fixture, 0);
  await submit(fixture);
  assert.deepEqual(calls, [{ owner: { userId: 7, generation: "driver-local" }, methods: ["cash"], revision: null }]);
  assert.match(text(fixture.tree), /Способы оплаты сохранены/);
  assert.equal(ofType(fixture.tree, "input")[0].props.checked, true);
});

await withCard([empty, sbp], (attempt) => {
  if (attempt === 1) throw Error("conflict");
  return { methods: ["cash", "sbp"], updated_at: "2030-01-01T10:00:02Z" };
}, async (fixture, calls) => {
  choose(fixture, 0);
  await submit(fixture);
  assert.match(text(fixture.tree), /Проверь текущий выбор/);
  assert.equal(calls.length, 1);
  assert.equal(button(fixture.tree, "Сохранить").props.disabled, true);
  assert.equal(ofType(fixture.tree, "input")[1].props.checked, true);
  choose(fixture, 0);
  await submit(fixture);
  assert.deepEqual(calls[1], {
    owner: { userId: 7, generation: "driver-local" }, methods: ["cash", "sbp"], revision: sbp.updated_at,
  });
});

await withCard([empty, Error("read unavailable"), cash], () => { throw Error("reply lost"); }, async (fixture, calls) => {
  choose(fixture, 0);
  await submit(fixture);
  assert.match(text(fixture.tree), /Сохранение не подтвердилось/);
  assert.equal(button(fixture.tree, "Сохранить"), undefined);
  button(fixture.tree, "Проверить настройки").props.onClick();
  await fixture.settle();
  assert.equal(ofType(fixture.tree, "input")[0].props.checked, true);
  assert.equal(calls.length, 1);
});

console.log("✓ Потерянный ответ сверяется чтением");
console.log("✓ Другой выбор требует нового действия с актуальной ревизией");
console.log("✓ Потерянное сохранение и чтение оставляют только повторную проверку");
