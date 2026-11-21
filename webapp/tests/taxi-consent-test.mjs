import assert from 'node:assert/strict';
import { test as nodeTest } from 'node:test';
const pendingTests = []; let passed = 0;
function test(name, fn) { pendingTests.push(nodeTest(name, () => { fn(); passed++; })); }
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
const output = await build({ entryPoints: [fileURLToPath(new URL('../src/utils/taxiConsent.ts', import.meta.url))],
  bundle: true, write: false, platform: 'node', format: 'esm' });
const h = await import('data:text/javascript;base64,' + Buffer.from(output.outputFiles[0].text).toString('base64'));
const g = '11111111-1111-4111-8111-111111111111';
const another = '22222222-2222-4222-8222-222222222222';
const environment = 'http://127.0.0.1:19308|http://127.0.0.1:19309';
const local = { hostname: '127.0.0.1', origin: 'http://127.0.0.1:19308', protocol: 'http:' };
const quote = { schema: 'taxi-initial-consent-v1', quote_id: g, request_key: another,
  snapshot_digest: '1'.repeat(64), pricing_inputs_digest: '2'.repeat(64), expires_at: '2030-01-01T00:00:00',
  price_kop: 60000, promo_discount_kop: 15000, payable_kop: 45000,
  breakdown: { ride_price_kop: 60000, pickup_fee_kop: 0, options_fee_kop: 0, weather_fee_kop: 0 },
  distance_km: 12, eta_min: 18, round_trip: false };
const clone = x => JSON.parse(JSON.stringify(x));
class Store {
  data = new Map();
  get length() { return this.data.size; }
  key(i) { return [...this.data.keys()][i] ?? null; }
  getItem(k) { return this.data.get(k) ?? null; }
  setItem(k, v) { this.data.set(k, String(v)); }
  removeItem(k) { this.data.delete(k); }
}
test('local experiment defaults OFF and rejects production/deceptive URLs', () => {
  assert.equal(h.taxiConsentEnvironment(undefined, local, 'http://127.0.0.1:19309'), null);
  const credentials = new URL('http://localhost');
  credentials.username = 'synthetic'; // Userinfo alone must be rejected; no password fixture needed.
  for (const value of ['https://yulbash.ru', 'http://localhost.evil', 'http://127.0.0.1.evil',
    credentials.href, '/api', '', 'ftp://localhost', 'http://localhost/?other=1']) {
    assert.equal(h.taxiConsentEnvironment('1', local, value), null);
  }
  assert.equal(h.taxiConsentEnvironment('1', { ...local, hostname: 'app.yulbash.ru' }, 'http://localhost'), null);
  assert.equal(h.taxiConsentEnvironment('1', local, 'http://127.0.0.1:19309'), environment);
  assert.ok(h.taxiConsentEnvironment('1', { ...local, hostname: '[::1]' }, 'http://[::1]:19309'));
});
test('all pricing inputs retain ordered stops/class/options/roundtrip, operational values excluded', () => {
  const body = { from_lat: 1, from_lng: 2, to_lat: 3, to_lng: 4, from_text: 'A', to_text: 'B',
    category: 'comfort', options: ['child', 'pet'], waypoints: [{ lat: 5, lng: 6 }, { lat: 7, lng: 8 }],
    round_trip: true, return_wait_min: 90, comment: 'not in quote', for_name: 'not in quote',
    for_phone: 'not in quote', payment_method: 'cash', women_only: true, price: 1 };
  const pricing = h.taxiConsentInput(body);
  assert.deepEqual(pricing.waypoints, body.waypoints); assert.deepEqual(pricing.options, body.options);
  assert.equal(pricing.category, 'comfort'); assert.equal(pricing.return_wait_min, 90); assert.equal(pricing.round_trip, true);
  for (const field of ['price', 'comment', 'for_name', 'for_phone', 'payment_method', 'women_only']) assert.ok(!(field in pricing));
});
test('net 60000 minus 15000 equals 45000; no client amount can repair incoherent snapshots', () => {
  assert.equal(h.readTaxiQuote(quote).payable_kop, 45000);
  for (const change of [{ payable_kop: 60000 }, { price_kop: 60000.1 }, { promo_discount_kop: -1 },
    { schema: 'other' }, { quote_id: 'not-a-pair' }, { expires_at: 'not-a-date' },
    { breakdown: { ...quote.breakdown, weather_fee_kop: 100 } }]) assert.throws(() => h.readTaxiQuote({ ...quote, ...change }));
});
test('strict replay identity, coherent accepted total and current terminal price have separate meanings', () => {
  const result = { order: { id: 17, role: 'passenger', status: 'done', passenger_price_kop: 60000 },
    consent: { quote: clone(quote), accepted: clone(quote) } };
  assert.equal(h.readTaxiConsentResult(result, quote, 7).id, 17);
  const cheaper = clone(result); cheaper.consent.accepted.price_kop = 50000;
  cheaper.consent.accepted.payable_kop = 35000; cheaper.consent.accepted.breakdown.ride_price_kop = 50000;
  assert.equal(h.readTaxiConsentResult(cheaper, quote, 7).id, 17);
  for (const mutate of [r => r.consent.quote.snapshot_digest = '3'.repeat(64),
    r => r.consent.accepted.request_key = g, r => r.order.role = 'driver', r => r.order.passenger_id = 8,
    r => { r.consent.accepted.price_kop = 70000; r.consent.accepted.payable_kop = 55000; r.consent.accepted.breakdown.ride_price_kop = 70000; }]) {
    const bad = clone(result); mutate(bad); assert.throws(() => h.readTaxiConsentResult(bad, quote, 7));
  }
});
test('recency includes RTT and background elapsed wall/monotonic time; rollback fails closed', () => {
  const stamp = { wall: 1000, monotonic: 500, validForMs: 120000 };
  assert.equal(h.taxiConsentFresh(stamp, 120999, 120499), true);
  assert.equal(h.taxiConsentFresh(stamp, 121000, 120500), false);
  assert.equal(h.taxiConsentFresh(stamp, 1001, 121000), false);
  assert.equal(h.taxiConsentFresh(stamp, 999, 600), false);
  assert.equal(h.taxiConsentFresh(stamp, 1100, 499), false);
});
test('reload marker contains only opaque generation/environment; one tab cannot clear another slot', () => {
  const store = new Store(); const a = h.markTaxiPending(store, g, environment, crypto.randomUUID()), b = h.markTaxiPending(store, g, environment, crypto.randomUUID());
  assert.notEqual(a, b); assert.deepEqual(h.pendingTaxiMarkers(store, g, environment).sort(), [a, b].sort());
  for (const value of store.data.values()) {
    const marker = JSON.parse(value); assert.deepEqual(Object.keys(marker).sort(), ['e', 'g', 'v']);
    assert.equal(marker.g, g); assert.equal(marker.e, environment);
  }
  h.clearTaxiPending(store, a); assert.deepEqual(h.pendingTaxiMarkers(store, g, environment), [b]);
  assert.deepEqual(h.pendingTaxiMarkers(store, another, environment), []); assert.equal(store.length, 1);
  assert.deepEqual(h.pendingTaxiMarkers(store, g, environment), [b]);
});
test('stale generation reader cannot erase a newer unknown-result barrier', () => {
  const store = new Store();
  const current = h.markTaxiPending(store, another, environment, crypto.randomUUID());
  const saved = store.getItem(current);
  assert.deepEqual(h.pendingTaxiMarkers(store, g, environment), []);
  assert.equal(store.getItem(current), saved);
  assert.deepEqual(h.pendingTaxiMarkers(store, another, environment), [current]);
});
test('environment binding and storage failures fail closed', () => {
  const store = new Store(); const key = h.markTaxiPending(store, g, environment, crypto.randomUUID());
  assert.deepEqual(h.pendingTaxiMarkers(store, g, environment + '/other'), []); assert.equal(store.getItem(key) !== null, true);
  assert.throws(() => h.markTaxiPending(store, 'not-a-random-session', environment, another));
  assert.throws(() => h.markTaxiPending({ getItem() { return null; }, setItem() { throw Error('denied'); } }, g, environment, another));
  store.setItem(h.TAXI_CONSENT_MARKER_PREFIX + crypto.randomUUID(), '{broken');
  assert.throws(() => h.pendingTaxiMarkers(store, g, environment));
  assert.throws(() => h.clearTaxiPending(store, 'unrelated-key'));
});

test('v2 suffix is the server key; v1 stays opaque and can never be looked up or cleared as v2', () => {
  const store = new Store();
  const legacy = h.TAXI_CONSENT_MARKER_PREFIX + g;
  store.setItem(legacy, JSON.stringify({ v: 1, g, e: environment }));
  const current = h.markTaxiPending(store, g, environment, another);
  assert.equal(current, h.TAXI_CONSENT_MARKER_PREFIX + another);
  assert.throws(() => h.markTaxiPending(store, g, environment, another));
  const [old, now] = h.pendingTaxiResults(store, g, environment);
  assert.equal(old.requestKey, null); assert.equal(old.version, 1);
  assert.equal(now.requestKey, another); assert.equal(now.version, 2);
  assert.throws(() => h.clearOwnedTaxiPending(store, old, g, environment));
  assert.throws(() => h.clearOwnedTaxiPending(store, now, another, environment));
  assert.throws(() => h.clearOwnedTaxiPending(store, now, g, environment + '/other'));
  store.setItem(current, JSON.stringify({ v: 1, g, e: environment }));
  assert.equal(h.ownsTaxiPending(store, now, g, environment), false);
  store.setItem(current, JSON.stringify({ v: 2, g: another, e: environment }));
  assert.throws(() => h.clearOwnedTaxiPending(store, now, g, environment));
  assert.equal(store.length, 2);
});
test('result is positive-only and historical amount never replaces current authorized card', () => {
  const committed = { schema: 'taxi-consent-result-v1', state: 'committed', order_id: 17, initial_payable_kop: 45000, next: 'fetch_order' };
  assert.equal(h.readTaxiOrderResult(committed).order_id, 17);
  assert.equal(h.readTaxiOrderResult({ schema: 'taxi-consent-result-v1', state: 'unknown' }).state, 'unknown');
  for (const change of [{ state: 'absent' }, { order_id: 0 }, { order_id: 1.5 }, { initial_payable_kop: -1 }, { next: 'create' }, { extra: true }]) {
    assert.throws(() => h.readTaxiOrderResult({ ...committed, ...change }));
  }
  assert.throws(() => h.readTaxiOrderResult({ schema: 'taxi-consent-result-v1', state: 'unknown', order_id: 17 }));
  const order = { id: 17, role: 'passenger', passenger_id: 7, status: 'expired', passenger_price_kop: 60000 };
  assert.equal(h.readRecoveredTaxiOrder(order, 17, 7).passenger_price_kop, 60000);
  for (const change of [{ id: 18 }, { role: 'driver' }, { passenger_id: 8 }, { status: 'scheduled' }, { passenger_price_kop: -1 }, { passenger_price_kop: undefined }]) {
    assert.throws(() => h.readRecoveredTaxiOrder({ ...order, ...change }, 17, 7));
  }
});

await Promise.all(pendingTests);
console.log(`Taxi consent: ${passed} passed`);
