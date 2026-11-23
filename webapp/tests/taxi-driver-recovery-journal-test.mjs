import assert from "node:assert/strict";
import { build } from "esbuild";

const { outputFiles } = await build({
  entryPoints: [new URL("../src/utils/taxiDriverRecoveryJournal.ts", import.meta.url).pathname],
  format: "esm", write: false, bundle: true,
});
const { TaxiDriverRecoveryJournal: Journal } =
  await import("data:text/javascript;base64," + Buffer.from(outputFiles[0].text).toString("base64"));
const owner = Object.freeze({ api: "http://127.0.0.1:8000", scopeHash: "a".repeat(64), epoch: {} });
const handle = "U3ludGhldGljT25seQ==", replacement = "REPLACEMENT";
function slotFixture() {
  let tail = Promise.resolve();
  const f = {
    now: owner, row: null, readFailure: false, persist: true, writeAck: true, afterCommit: null, afterRead: null, afterWork: null,
    exclusively(input, work) {
      const isCurrent = () => !!f.now && input.api === f.now.api
        && input.scopeHash === f.now.scopeHash && input.epoch === f.now.epoch;
      const call = tail.then(() => work({
        isCurrent,
        read: async () => {
          if (f.readFailure) throw Error("Synthetic disk unavailable");
          const row = f.row;
          f.afterRead?.();
          return row;
        },
        commit: async next => {
          if (f.persist) f.row = next;
          f.afterCommit?.();
          return f.writeAck;
        },
      })).then(result => {
        f.afterWork?.();
        return result;
      });
      tail = call.catch(() => {});
      return call;
    },
  };
  return f;
}

{
  const f = slotFixture(), a = new Journal(f), b = new Journal(f);
  const [permit, competing] = await Promise.all([a.prepare(owner, handle), b.prepare(owner, replacement)]);
  assert.ok(permit); assert.equal(competing, null);
  assert.equal(JSON.stringify(permit), "{}");
  assert.equal(f.row, `1\n${owner.api}\n${owner.scopeHash}\n${handle}`);
  let sent = 0;
  assert.equal(await b.consume(permit, () => sent++), "not-invoked");
  assert.equal(sent, 0);
  const used = await Promise.all([a.consume(permit, () => sent++), a.consume(permit, () => sent++)]);
  assert.equal(used.filter(result => result === "invoked-outcome-unknown").length, 1);
  assert.equal(sent, 1);
  const cold = { ...owner, epoch: {} };
  f.now = cold;
  assert.equal(await b.restoreStatus(cold), handle);
  assert.equal(await a.restoreStatus(owner), null);
  assert.equal(await b.prepare(cold, handle), null);
  assert.equal(await b.consume(permit, () => sent++), "not-invoked");
  assert.equal(sent, 1);
  console.log("✓ atomic cross-tab prepare, one admission, and same-session cold restore only");
}
{
  const f = slotFixture(), a = new Journal(f);
  const permit = await a.prepare(owner, handle), stored = f.row;
  let sent = 0;
  for (const next of [{ ...owner, scopeHash: "b".repeat(64), epoch: {} },
    { ...owner, scopeHash: "c".repeat(64), epoch: {} }, { ...owner, api: "https://example.invalid", epoch: {} }]) {
    f.now = next;
    assert.equal(await a.restoreStatus(next), null);
    assert.equal(await a.prepare(next, replacement), null);
    assert.equal(await a.consume(permit, () => sent++), "not-invoked");
    assert.equal(sent, 0);
    assert.equal(await a.reconcile(next, handle, "committed"), false);
    assert.equal(await a.clearAtSessionBoundary(owner), false);
    assert.equal(f.row, stored);
  }
  assert.equal(await a.clearAtSessionBoundary(f.now), true);
  assert.equal(f.row, null);
  f.now = null;
  assert.equal(await a.restoreStatus(owner), null);
  console.log("✓ new login, changed account/environment and stale tab cannot inspect or erase an old slot");
}
{
  const f = slotFixture(), a = new Journal(f);
  f.writeAck = false;
  assert.equal(await a.prepare(owner, handle), null); assert.ok(f.row);
  assert.equal(await a.restoreStatus(owner), null);
  f.writeAck = true;
  assert.equal(await a.clearAtSessionBoundary(owner), true);
  f.persist = false;
  assert.equal(await a.prepare(owner, handle), null); assert.equal(f.row, null);
  f.persist = true;
  assert.equal(await a.clearAtSessionBoundary(owner), true);
  f.readFailure = true;
  assert.equal(await a.prepare(owner, handle), null);
  f.readFailure = false;
  const permit = await a.prepare(owner, handle);
  assert.ok(permit);
  const old = f.row;
  f.row = old.replace(handle, replacement);
  let sent = 0;
  assert.equal(await a.consume(permit, () => sent++), "not-invoked");
  f.row = old;
  assert.equal(await a.consume(permit, () => sent++), "not-invoked");
  assert.equal(sent, 0);
  console.log("✓ false commit, failed readback, unavailable disk and replaced record do not admit");
}
{
  const f = slotFixture(), a = new Journal(f);
  const permit = await a.prepare(owner, handle);
  const old = f.row;
  for (const evidence of ["unknown", "expired", null, { state: "committed" }]) {
    assert.equal(await a.reconcile(owner, handle, evidence), false);
    assert.equal(f.row, old);
  }
  assert.equal(await a.reconcile(owner, replacement, "committed"), false);
  assert.equal(await a.reconcile(owner, handle, "committed"), true);
  assert.equal(f.row, null);
  let sent = 0;
  assert.equal(await a.consume(permit, () => sent++), "not-invoked");
  assert.equal(sent, 0);
  f.row = old + "\n";
  assert.equal(await a.restoreStatus(owner), null);
  assert.equal(await a.prepare(owner, handle), null);
  console.log("✓ unknown/expired evidence is not negative, only exact commitment clears");
}
{
  const f = slotFixture(), a = new Journal(f);
  const next = { ...owner, scopeHash: "b".repeat(64), epoch: {} };
  f.afterCommit = () => { f.now = next; };
  assert.equal(await a.prepare(owner, handle), null);
  assert.equal(await a.restoreStatus(next), null);
  f.afterCommit = null;
  assert.equal(await a.clearAtSessionBoundary(next), true);
  const permit = await a.prepare(next, handle);
  f.afterRead = () => { f.now = owner; };
  let sent = 0;
  assert.equal(await a.consume(permit, () => sent++), "not-invoked");
  assert.equal(sent, 0);
  console.log("✓ auth change during async disk check prevents publication and final admission");
}


{
  for (const failure of ["callback", "adapter-cleanup"]) {
    const f = slotFixture(), a = new Journal(f);
    const permit = await a.prepare(owner, handle);
    const row = f.row;
    let sent = 0;
    if (failure === "adapter-cleanup") f.afterWork = () => { throw Error("Synthetic lock release failure"); };
    assert.equal(await a.consume(permit, () => {
      sent++;
      if (failure === "callback") throw Error("Synthetic caller failure after dispatch");
    }), "invoked-outcome-unknown");
    assert.equal(sent, 1);
    assert.equal(await a.consume(permit, () => sent++), "not-invoked");
    assert.equal(sent, 1);
    assert.equal(f.row, row);
  }
  console.log("✓ dispatch followed by thrown callback or adapter cleanup failure remains unknown and cannot replay");
}
