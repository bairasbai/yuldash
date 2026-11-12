import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { IDBFactory } from "fake-indexeddb";

const built = await build({
  stdin: {
    contents: `export * from "./src/api/client"; export * from "./src/api/bookings";
      export * from "./src/api/bookingDecisions"; export * from "./src/utils/bookingCommandStore";
      export * from "./src/utils/offlineTripPass"; export * from "./src/utils/tripPassStore";`,
    resolveDir: fileURLToPath(new URL("../", import.meta.url)), loader: "ts",
  },
  bundle: true, write: false, platform: "node", format: "esm",
  define: { "import.meta.env": JSON.stringify({ VITE_API_BASE: "https://trip-storage.test" }) },
});
const bundle = "data:text/javascript;base64," + Buffer.from(built.outputFiles[0].text).toString("base64");
let instance = 0;
const fresh = () => import(bundle + "#" + ++instance);
const token = id => "test." + Buffer.from(JSON.stringify({ sub: String(id) })).toString("base64url") + ".test";
const booking = 42, rideId = 17, owner = { userId: 7, generation: "A" };
const boardA = "2026-10-24T12:00:00.123456Z", boardB = "2026-10-24T12:00:00.123457Z";
const active = { status: "confirmed" };
const boarding = onboard_at => ({ status: "onboard", onboard_at });
const receipt = at => ({ status: "confirmed", boarding_undo_confirmed_id: "undo-receipt", boarding_undo_onboard_at: at });
const snapshot = {
  bookingId: booking, rideId, fromCity: "Уфа", toCity: "Сибай", departAt: "2026-10-24T12:00:00Z",
  driverName: "QA", driverCar: "QA", driverPlate: "QA001", driverPhone: "QA synthetic",
  pickup: "QA point", pickupStop: "Ургаза", dropoffStop: "Бурибай", rideHasStops: true, price: 300, seats: 1, payMethod: "sbp",
};
const defer = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { resolve, promise };
};

function reset() {
  const values = new Map();
  const installSession = (id, generation) => {
    values.set("yuldash.token", token(id));
    values.set("yuldash.refresh", "refresh-" + generation);
    values.set("yuldash.session", generation);
  };
  installSession(7, "A");
  globalThis.localStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key),
  };
  const factory = new IDBFactory();
  const h = { values, installSession, durability: [], abortNextWrite: false, pause: null, denyRead: false };
  globalThis.indexedDB = {
    open(name, ...args) {
      if (name === "yuldash-trip-passes-v1" && h.denyRead) throw new Error("test storage unavailable");
      const request = factory.open(name, ...args);
      if (name !== "yuldash-trip-passes-v1") return request;
      request.addEventListener("success", () => {
        const db = request.result;
        const start = db.transaction.bind(db);
        db.transaction = (...args) => {
          const tx = start(...args);
          const mode = args[1];
          if (mode === "readwrite") h.durability.push(args[2]?.durability);
          if (mode === "readwrite" && h.abortNextWrite) {
            h.abortNextWrite = false;
            queueMicrotask(() => tx.abort());
          }
          if (h.pause?.mode === mode) {
            const { gate, completed } = h.pause;
            h.pause = null;
            let handler;
            Object.defineProperty(tx, "oncomplete", {
              get: () => () => { completed.resolve(); void gate.promise.then(handler); },
              set: callback => { handler = callback; },
            });
          }
          return tx;
        };
      });
      return request;
    },
  };
  globalThis.fetch = async () => { throw new Error("Unexpected test request"); };
  return h;
}

async function save(api, who = owner, value = snapshot) {
  const revision = await api.readTripPassRevision(who, value.bookingId);
  await api.observeTripPass(who, value.bookingId, active, revision, value);
  await api.saveTripPassCode(who, value.bookingId, revision, "4931");
}
const state = (api, id = booking) => api.readOfflineTrip(id);
const raw = async api => (await api.readTripRecords(owner, () => true))?.get(booking);
const reply = (body, status = 200) => new Response(JSON.stringify(body), { status });
const signal = () => new AbortController().signal;
const commandOwner = api => api.bookingCommandOwner({ id: 7, created_at: "2026-01-01T10:00:00Z" }, "A");
const termsFor = actor => ({ owner: actor, bookingId: booking, status: "confirmed", consequence: "early", termsKey: "a".repeat(64) });
const closed = state => ({ state, scope: "booking.cancel", ref_id: null, ref_kind: null, current: null });
let count = 0;
async function test(name, run) {
  const h = reset(), api = await fresh();
  await run(api, h);
  console.log("PASS " + name);
  count++;
}

await test("legacy ownership pair is ignored and a fresh authenticated pass survives a reload", async (api, h) => {
  h.values.set("yuldash.tripPass.42", JSON.stringify({ ...snapshot, ownerId: 7, sessionGeneration: "A", ownershipId: "old", boardingCode: "0000", savedAt: Date.now() }));
  h.values.set("yuldash.tripPass.42.owner", JSON.stringify({ ownerId: 7, generation: "A", ownershipId: "old" }));
  assert.equal((await state(api)).pass, null);
  assert.deepEqual((await api.listOfflineTripOptions()).trips, []);
  await save(api);
  const reload = await fresh();
  assert.equal((await state(reload)).pass.boardingCode, "4931");
  assert.deepEqual((await reload.listOfflineTripOptions()).trips, [{
    bookingId: booking, fromCity: snapshot.fromCity, toCity: snapshot.toCity, departAt: snapshot.departAt,
  }]);
  assert(h.durability.every(level => level === "strict"));
});

await test("observing boarding strips the code and rejects the late confirmed details and code", async api => {
  await save(api);
  const stale = await api.readTripPassRevision(owner, booking);
  await api.observeTripPass(owner, booking, boarding(boardA), undefined);
  await api.observeTripPass(owner, booking, active, stale, { ...snapshot, pickup: "stale" });
  await api.saveTripPassCode(owner, booking, stale, "old-code");
  const reload = await fresh();
  const view = await state(reload);
  assert.equal(view.status, "onboard");
  assert.equal(view.pass.boardingCode, "");
  assert.equal(view.pass.pickup, snapshot.pickup);
  assert.equal(view.pass.pickupStop, snapshot.pickupStop);
  assert.equal(view.pass.dropoffStop, snapshot.dropoffStop);
});

await test("only a receipt for the latest exact boarding permits a fresh code", async api => {
  await save(api);
  await api.observeTripPass(owner, booking, boarding(boardA), undefined);
  await api.observeTripPass(owner, booking, boarding(boardB), undefined);
  const beforeUndo = await api.readTripPassRevision(owner, booking);
  const reload = await fresh();
  await reload.observeTripPass(owner, booking, receipt(boardA), beforeUndo);
  assert.equal((await state(reload)).status, "onboard");
  await reload.observeTripPass(owner, booking, receipt(boardB), beforeUndo);
  assert.equal((await state(reload)).status, "confirmed");
  assert.equal((await state(reload)).pass.boardingCode, "");
  await reload.saveTripPassCode(owner, booking, beforeUndo, "old");
  assert.equal((await state(reload)).pass.boardingCode, "");
  const afterUndo = await reload.readTripPassRevision(owner, booking);
  await reload.saveTripPassCode(owner, booking, afterUndo, "fresh");
  await reload.observeTripPass(owner, booking, boarding(boardB.replace("Z", "+00:00")), undefined);
  assert.equal((await state(reload)).pass.boardingCode, "fresh");
});

for (const status of ["cancelled", "done"]) {
  await test("terminal history is permanent even when late details are already in flight: " + status, async api => {
    await save(api);
    const stale = await api.readTripPassRevision(owner, booking);
    globalThis.fetch = async () => reply([{ id: booking, status, cancelled_in_transit: status === "cancelled",
      cancelled_in_transit_at: new Date().toISOString() }]);
    await api.fetchMyBookings();
    await api.observeTripPass(owner, booking, active, stale, snapshot);
    await api.observeTripPass(owner, booking, receipt(boardA), stale);
    const reload = await fresh();
    const view = await state(reload);
    assert.equal(view.pass, null);
    assert.equal(view.cancelledInTransit, status === "cancelled");
    const record = await raw(reload);
    assert.equal(record.status, "terminal");
    assert.deepEqual(Object.keys(record).sort(), status === "cancelled" ? ["cancelledInTransitAt", "status"] : ["status"]);
  });
}

await test("the bookings list strips a used code and keeps the exact boarding for undo", async api => {
  await save(api);
  globalThis.fetch = async () => reply([{ id: booking, ...boarding(boardB) }]);
  await api.fetchMyBookings();
  assert.equal((await state(api)).pass.boardingCode, "");
  const restart = await fresh();
  const revision = await restart.readTripPassRevision(owner, booking);
  await restart.observeTripPass(owner, booking, receipt(boardA), revision);
  assert.equal((await state(restart)).status, "onboard");
  await restart.observeTripPass(owner, booking, receipt(boardB), revision);
  assert.equal((await state(restart)).status, "confirmed");
  assert.equal((await state(restart)).pass.boardingCode, "");
});

await test("terminal whole ride also rejects the first delayed snapshot", async api => {
  const stale = await api.readTripPassRevision(owner, booking);
  await api.finishTripPassRide(owner, rideId);
  await api.observeTripPass(owner, booking, active, stale, snapshot);
  assert.equal((await state(await fresh())).status, "terminal");
});

await test("the cancellation is already hidden when the first request and retry are sent", async (api, h) => {
  await save(api);
  const actor = commandOwner(api), stale = await api.readTripPassRevision(owner, booking);
  let postKey, attempts = 0;
  globalThis.fetch = async (url, init) => {
    assert.match(url, /\/commands\/v1\/cancel$/);
    const key = new Headers(init.headers).get("Idempotency-Key");
    assert(key);
    assert.equal((await state(await fresh())).status, "held");
    assert.equal((await raw(api)).pass, undefined);
    if (++attempts === 1) { postKey = key; throw new Error("test response lost"); }
    assert.equal(key, postKey);
    return reply({ id: booking, ride_id: rideId, passenger_id: 7, status: "cancelled" });
  };
  const gate = defer(), completed = defer();
  h.pause = { mode: "readwrite", gate, completed };
  const cancellation = api.cancelPassengerBooking(actor, termsFor(actor), "", signal());
  await completed.promise;
  assert.equal(attempts, 0, "Do not send before the strict transaction completion reaches the caller");
  gate.resolve();
  await assert.rejects(cancellation, { kind: "pending" });
  await api.observeTripPass(owner, booking, active, stale, snapshot);
  const restart = await fresh();
  assert.equal((await state(restart)).pass, null);
  const [saved] = await restart.readDriverDecisions(actor);
  assert.equal((await restart.repeatDriverDecision(actor, saved, signal())).state, "applied");
  assert.equal((await state(await fresh())).status, "terminal");
  assert.equal(attempts, 2);
});

await test("a failed preflight commit sends no cancellation and a later attempt can succeed", async (api, h) => {
  await save(api);
  const actor = commandOwner(api);
  h.abortNextWrite = true;
  let sends = 0;
  globalThis.fetch = async () => { sends++; throw new Error("not allowed"); };
  await assert.rejects(api.cancelPassengerBooking(actor, termsFor(actor), "", signal()), { kind: "storage" });
  assert.equal(sends, 0);
  assert.equal((await state(await fresh())).pass.boardingCode, "4931");
});

for (const status of ["rejected", "closed_no_effect"]) {
  await test("verified " + status + " opens only a fresh check and never restores old content", async api => {
    await save(api);
    const actor = commandOwner(api), oldRevision = await api.readTripPassRevision(owner, booking);
    globalThis.fetch = async () => { throw new Error("lost response"); };
    await assert.rejects(api.cancelPassengerBooking(actor, termsFor(actor), "", signal()), { kind: "pending" });
    const [saved] = await api.readDriverDecisions(actor);
    globalThis.fetch = async () => reply({ state: "unseen", scope: null, ref_kind: null, ref_id: null, current: null });
    assert.equal((await api.checkDriverDecision(actor, saved, false, signal())).state, "unseen");
    assert.equal((await state(api)).status, "held");
    globalThis.fetch = async () => reply(closed(status));
    assert.equal((await api.checkDriverDecision(actor, saved, status === "closed_no_effect", signal())).state, status);
    await api.observeTripPass(owner, booking, active, oldRevision, snapshot);
    const reload = await fresh();
    assert.equal((await state(reload)).pass, null);
    await save(reload);
    assert.equal((await state(reload)).pass.boardingCode, "4931");
  });
}

await test("a closed cancellation cannot return from a late tab, including after a later boarding", async api => {
  await save(api);
  const otherTab = await fresh();
  assert.equal(await otherTab.releaseTripPassCancellation(owner, booking, "old-key"), true);
  await save(api);
  assert.equal(await api.holdTripPassCancellation(owner, booking, "old-key"), "closed");
  assert.equal((await state(api)).pass.boardingCode, "4931");
  await api.observeTripPass(owner, booking, boarding(boardA), undefined);
  await api.observeTripPass(owner, booking, receipt(boardA), await api.readTripPassRevision(owner, booking));
  assert.equal(await otherTab.holdTripPassCancellation(owner, booking, "old-key"), "closed");
  assert.equal(await api.holdTripPassCancellation(owner, booking, "new-key"), "held");
  assert.equal(await otherTab.releaseTripPassCancellation(owner, booking, "old-key"), true);
  assert.equal((await state(api)).status, "held");
});

await test("a new receipt after missed boarding requires a new code even after a prior undo", async api => {
  await save(api);
  await api.observeTripPass(owner, booking, receipt(boardA), await api.readTripPassRevision(owner, booking));
  let revision = await api.readTripPassRevision(owner, booking);
  await api.saveTripPassCode(owner, booking, revision, "from first undo");
  await api.observeTripPass(owner, booking, receipt(boardB), revision);
  assert.equal((await state(api)).pass.boardingCode, "");
  await api.saveTripPassCode(owner, booking, revision, "late");
  assert.equal((await state(await fresh())).pass.boardingCode, "");
  revision = await api.readTripPassRevision(owner, booking);
  await api.saveTripPassCode(owner, booking, revision, "from second undo");
  assert.equal((await state(api)).pass.boardingCode, "from second undo");
});

await test("an old cancelled booking does not reopen the emergency hint, and driver gets no hint", async api => {
  const ancient = new Date(Date.now() - 7 * 60 * 60 * 1000).toISOString();
  await api.observeTripPass(owner, booking, { status: "cancelled", role: "passenger", cancelled_in_transit: true,
    cancelled_in_transit_at: ancient }, undefined);
  assert.equal((await state(api)).cancelledInTransit, false);
  await api.observeTripPass(owner, booking + 1, { status: "cancelled", role: "driver", cancelled_in_transit: true,
    cancelled_in_transit_at: new Date().toISOString() }, undefined);
  assert.equal((await state(api, booking + 1)).cancelledInTransit, false);
});

await test("session credentials are published only after the old generation is durably revoked", async (api, h) => {
  await save(api);
  const gate = defer(), completed = defer();
  h.pause = { mode: "readwrite", gate, completed };
  const next = api.setSession(token(8), "refresh-B");
  await completed.promise;
  assert.equal(api.getSessionGeneration(), "A");
  assert.equal(api.getToken(), token(7));
  assert.equal((await state(await fresh())), null);
  gate.resolve();
  await next;
  assert.equal(api.getAccountSession().userId, 8);
  h.installSession(7, "A"); // Simulate rollback of the browser's localStorage credentials.
  assert.equal((await state(await fresh())), null);
  await save(api);
  assert.equal((await state(api)), null);
});

await test("a read captured before an account switch cannot return the old pass", async (api, h) => {
  await save(api);
  const gate = defer(), completed = defer();
  h.pause = { mode: "readonly", gate, completed };
  const reading = state(api);
  await completed.promise;
  h.installSession(8, "B");
  gate.resolve();
  assert.equal(await reading, null);
  const actor = { userId: 8, generation: "B" };
  await save(api, actor, { ...snapshot, driverName: "QA B" });
  assert.equal((await state(api)).pass.driverName, "QA B");
});

await test("a failed database read has no localStorage fallback", async (api, h) => {
  await save(api);
  h.values.set("yuldash.tripPass.42", JSON.stringify(snapshot));
  h.denyRead = true;
  const denied = await state(api);
  assert.equal(denied.status, "storage-unavailable");
  assert.equal(denied.pass, null);
  assert.equal(await api.listOfflineTripOptions(), null);
});

console.log(count + " trip pass durability checks passed");
