import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { IDBFactory, IDBKeyRange } from "fake-indexeddb";
import { build } from "esbuild";
import { checker } from "./shallow-harness.mjs";

const { outputFiles } = await build({
  stdin: {
    contents: 'export * from "./src/utils/chatReceipts"; export * from "./src/utils/chatReceiptStore";',
    resolveDir: fileURLToPath(new URL("../", import.meta.url)),
    loader: "ts",
  },
  bundle: true, write: false, format: "esm", platform: "node",
});
const load = name => import("data:text/javascript;base64," + Buffer.from(outputFiles[0].text + "\n// " + name).toString("base64"));
const first = await load("first tab");
const second = await load("second tab");
globalThis.IDBKeyRange = IDBKeyRange;

const tests = checker();
const cutoff = "2026-11-21T10:00:00Z";
const message = (id, fields = {}) => ({ id, sender_id: 8, text: `Fixture ${id}`, created_at: cutoff, ...fields });
const scope = { generation: "first-login", bookingId: 42, userId: 7 };
const current = () => true;
const flags = (message_id, fields = {}) => ({
  message_id, peer_delivered: false, peer_read: false, my_delivered: false, my_read: false, ...fields,
});
const save = (client, entries, recipient = scope) => client.saveChatReceipts(recipient, current, entries);
const pending = (client, recipient = scope) => client.pendingChatReceipts(recipient, current, 100);
const row = (id, read = false) => ({ message: message(id), read });

await tests.test("cutoff normalizes REST UTC and offsets, and filters previews or inaccessible messages", () => {
  const since = first.chatTimestamp(cutoff);
  assert.equal(first.chatTimestamp("2026-11-21T10:00:00"), since);
  assert.equal(first.chatTimestamp("2026-11-21T13:00:00+03:00"), since);
  for (const fields of [
    { created_at: undefined }, { created_at: "2026-11-21T09:59:59Z" },
    { deleted: true }, { hidden_for_me: true }, { sender_id: scope.userId }, { from_admin: true },
    { text: undefined }, { text: "" },
  ]) assert.equal(first.receiptCandidate(message(110, fields), scope.userId, since), false);
  assert.equal(first.receiptCandidate(message(120, { text: "", voice_url: "/voice/fixture" }), scope.userId, since), true);
  assert.equal(first.receiptCandidate(message(120, { created_at: undefined, timestamp: cutoff }), scope.userId, since), true);
});

await tests.test("after reload only the explicit persisted message 120 is acknowledged", async () => {
  globalThis.indexedDB = new IDBFactory();
  await save(first, [row(120, true)]);
  const recovered = await pending(second);
  assert.equal(recovered[0].message.text, "Fixture 120");
  assert.deepEqual(second.receiptBatch(recovered), { delivered_ids: [120], read_ids: [120] });
  await save(second, [row(110)]);
  assert.deepEqual(first.receiptBatch(await pending(first)), { delivered_ids: [110, 120], read_ids: [120] });
});

await tests.test("batches are bounded and stale receipt snapshots preserve read independently by ID", () => {
  const bulk = Array.from({ length: 132 }, (_, i) => row(110 + i * 10, i % 2 === 0));
  const batch = first.receiptBatch(bulk);
  assert.equal(batch.delivered_ids.length, 100);
  assert.equal(batch.read_ids.length, 50);
  assert.ok(batch.read_ids.every(id => batch.delivered_ids.includes(id)));
  const event = first.mergeChatReceipt(flags(120, { peer_read: true }), flags(120));
  assert.deepEqual(event, flags(120, { peer_delivered: true, peer_read: true }));
  assert.deepEqual(first.mergeChatReceipt(undefined, flags(110)), flags(110));
});

await tests.test("two tabs merge durable delivery/read and replay after a lost reply", async () => {
  globalThis.indexedDB = new IDBFactory();
  await save(first, [row(120)]);
  const old = first.receiptBatch(await pending(first));
  await Promise.all([save(first, [row(120, true)]), save(second, [row(120)])]);
  await second.settleChatReceipts(scope, current, old.delivered_ids, [flags(120, { my_delivered: true })]);
  assert.deepEqual(second.receiptBatch(await pending(second)), { delivered_ids: [120], read_ids: [120] });
  await first.settleChatReceipts(scope, current, [120], [flags(120, { my_delivered: true, my_read: true })]);
  assert.deepEqual(await pending(second), []);
});

await tests.test("rejected IDs and booking closure clear the scoped queue", async () => {
  globalThis.indexedDB = new IDBFactory();
  const elsewhere = { ...scope, bookingId: 43 };
  await save(first, [row(110), row(120)]);
  await save(second, [row(110)], elsewhere);
  await second.settleChatReceipts(scope, current, [110], []);
  assert.deepEqual((await pending(first)).map(item => item.messageId), [120]);
  await first.clearChatReceipts(scope, current);
  assert.deepEqual(await pending(second), []);
  assert.equal((await pending(second, elsewhere)).length, 1);
});

await tests.test("late old-account writes are rejected and the generation switch erases stored bodies", async () => {
  globalThis.indexedDB = new IDBFactory();
  const replacement = { ...scope, generation: "new-login", userId: 9 };
  await save(first, [row(120)]);
  await save(second, [row(110)], replacement);
  let active = true;
  const late = first.saveChatReceipts(scope, () => active, [row(130)]);
  active = false;
  await assert.rejects(late, /scope changed/);
  await second.pruneChatReceipts(replacement.generation, current);
  assert.deepEqual(await pending(first), []);
  assert.equal((await pending(second, replacement))[0].messageId, 110);
});

await tests.test("the request interval is reserved atomically across tabs", async () => {
  globalThis.indexedDB = new IDBFactory();
  const result = await Promise.all([first, second].map(client =>
    client.claimChatReceiptSend(scope, current, first.RECEIPT_BATCH_INTERVAL)));
  assert.equal(result.filter(delay => delay === 0).length, 1);
  assert.ok(result.some(delay => delay > 0 && delay <= 1500));
  assert.deepEqual(await pending(first), []);
});

tests.done();
