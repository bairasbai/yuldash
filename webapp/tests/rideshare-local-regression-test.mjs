// Actual production components; only React hooks, leaf widgets and network are controlled.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { build, transform } from 'esbuild';

const h = globalThis.__rideshareLocal = { components: {} };
const previousLocalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
const passValues = new Map();
Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
  getItem: key => passValues.get(key) ?? null,
  setItem: (key, value) => { if (h.failPassWrites) throw Error('storage unavailable'); passValues.set(key, value); },
  removeItem: key => passValues.delete(key),
} });
const passModule = await build({ entryPoints: [new URL('../src/utils/tripPass.ts', import.meta.url).pathname.replace(/^\/(\w:)/, '$1')], bundle: true, write: false, format: 'esm', platform: 'node' });
h.passStorage = await import('data:text/javascript;base64,' + Buffer.from(passModule.outputFiles[0].text).toString('base64'));
const ownedPassModule = await build({ entryPoints: [new URL('../src/utils/offlineTripPass.ts', import.meta.url).pathname.replace(/^\/(\w:)/, '$1')], bundle: true, write: false, format: 'esm', platform: 'node', plugins: [{ name: 'session-boundary', setup(b) {
  b.onResolve({ filter: /api\/client$/ }, () => ({ path: 'client', namespace: 'session' }));
  b.onLoad({ filter: /.*/, namespace: 'session' }, () => ({ contents: `export const getToken=()=>'fixture',getSessionGeneration=()=>globalThis.__rideshareLocal.generation;` }));
} }] });
h.ownedPassStorage = await import('data:text/javascript;base64,' + Buffer.from(ownedPassModule.outputFiles[0].text).toString('base64'));
h.detailsSync = await import('../src/utils/tripDetailsSync.js');
class ApiError extends Error { constructor(status, message = '') { super(message); this.status = status; } }
h.ApiError = ApiError;
const definitions = {
  useState: `v=>{const i=h.current.i++;const c=h.current;if(!(i in c.values))c.values[i]=typeof v==='function'?v():v;return[c.values[i],x=>{h.stateCalls.push({component:c.type.name,index:i,mounted:c.live});if(!c.live)return;c.values[i]=typeof x==='function'?x(c.values[i]):x;h.dirty=true}];}`,
  useRef: `v=>{const c=h.current,i=c.i++;return c.values[i]??={current:v}}`,
  useEffect: `(fn,deps)=>{const c=h.current,i=c.i++,old=c.values[i];if(!old||!deps||deps.some((v,j)=>!Object.is(v,old.deps?.[j]))){c.values[i]={deps,cleanup:old?.cleanup};h.effects.push(()=>{old?.cleanup?.();c.values[i].cleanup=fn()})}}`,
  useCallback: `(fn,deps)=>{const c=h.current,i=c.i++,old=c.values[i];if(!old||deps.some((v,j)=>!Object.is(v,old.deps[j])))c.values[i]={fn,deps};return c.values[i].fn}`,
  useLang: `()=>({lang:h.lang,appText:(ru,ba)=>{if(!ba)throw Error('missing Bashkir: '+ru);return h.lang==='ba'?ba:ru}})`,
  useParams: `()=>({id:String(h.bookingId)})`, useNavigate: `()=>p=>h.navigation.push(p)`, useAuth: `()=>({user:{id:h.userId}})`,
  getSessionGeneration: `()=>h.generation`, getToken: `()=>"fixture"`, refreshRejectedToken: `async()=>false`, ApiError: `h.ApiError`,
  fetchBookingDetails: `async id=>{h.requests.push('details');if(h.detailsError)throw new h.ApiError(0);const held=h.detailsHolds.shift();if(held){h.detailsHeldRequests.push(id);return held.promise}return {...(h.detailsById.get(id)??h.details)}}`,
  fetchTripState: `async id=>{h.requests.push('state');if(h.tripError)throw new h.ApiError(0);const held=h.tripHolds.shift();if(held){h.tripHeldRequests.push(id);return held.promise}return {...(h.tripById.get(id)??h.trip)}}`,
  fetchBoardingCode: `id=>{h.requests.push('code');h.codeRequests.push(id);return h.codeHolds.get(id)?.promise??Promise.resolve({code:'4931'})}`,
  fetchBookingShares: `async()=>{h.requests.push('shares');if(h.sharesError)throw new h.ApiError(h.sharesError);return h.shares}`,
  fetchTrustedContacts: `async()=>h.contacts`, shareBooking: `async()=>{h.requests.push('create-share');return {}}`,
  setTripStatus: `async(id,status)=>{h.sent.push([id,status]);if(h.sendError!==null)throw new h.ApiError(h.sendError);if(status==='done')h.trip.status='done';h.details.status=h.trip.status;return h.shares??[]}`,
  // Посадку отмечает водитель (решение 17.10.2026): ответы его «Сел» / «Отменить».
  FileIncidentCard: `'FileIncidentCard'`,
  DriverBoardedDispute: `'DriverBoardedDispute'`,
  markBoarded: `async id=>{h.boarded.push(id);h.trip.status='onboard';h.details.status='onboard';return {ok:true,status:'onboard',boarding_undo_sec:300}}`,
  undoBoarded: `async id=>{h.unboarded.push(id);h.trip.status='confirmed';h.details.status='confirmed';return {ok:true,status:'confirmed',boarding_undo_sec:0}}`,
  cancelBooking: `async(id,reason)=>{h.cancelCalls.push([id,reason]);h.trip.status="cancelled";h.details.status="cancelled";if(h.failRoleAfterCancel)h.tripError=true;return {...h.cancelResult}}`,
  CANCEL_REASONS: `[]`,
  useRefreshOwnBookings: `()=>()=>{h.refreshOwnCalls++}`,
  enqueue: `async(...v)=>h.queued.push(v)`,
  fetchReceipt: `async()=>({...h.receipt})`, fetchBookingTip: `async()=>{h.requests.push('tip');return {already_thanked:false,money:{sbp:'+79990000000',name:'Test'}}}`,
  thankBooking: `async id=>h.thanks.push(id)`,
  loadTripPass: `id=>h.passStorage.loadTripPass(id)`, saveVerifiedTripPass: `(p,owner,generation)=>{const snapshot=structuredClone(p);h.passWrites.push(snapshot);h.ownedPassStorage.saveVerifiedTripPass(snapshot,owner,generation);h.passes.set(p.bookingId,{...snapshot,savedAt:Date.now()})}`, dropTripPass: `id=>{h.passStorage.dropTripPass(id);h.passes.delete(id)}`,
  clearTripPassBoardingCode: `id=>h.passStorage.clearTripPassBoardingCode(id)`,
  tripPassStage: `(...args)=>h.passStorage.tripPassStage(...args)`,
  loadOfflineTripPass: `id=>h.ownedPassStorage.loadOfflineTripPass(id)`,
  isTerminalBookingStatus: `s=>s==='done'||s==='cancelled'`,
  shouldApplyBookingDetails: `(...args)=>h.detailsSync.shouldApplyBookingDetails(...args)`, shouldRefreshBookingDetails: `(...args)=>h.detailsSync.shouldRefreshBookingDetails(...args)`,
  reconcilePaymentDraft: `(draft)=>draft`, useRouteWeather: `()=>null`,
  useVisibleInterval: `(ms,fn)=>{h.interval=fn}`,
  openTripLocation: `(id,point)=>{const connection={id,point,closed:false};h.locationConnections.push(connection);return {close(){connection.closed=true}}}`,
  driverInTransit: `(...args)=>h.driverTransit(...args)`, wsBase: `()=>"ws://fixture"`, tripLocationUrl: `()=>"ws://fixture/location"`,
  startDriverLocationShare: `options=>{const share={stopped:false};h.locationShares.push(share);options.onState("sharing");return {stop(){share.stopped=true},retry(){}}}`,
  winterCheckDelay: `()=>Infinity`, wasWinterCheckAsked: `()=>false`, winterCheckNeedsAnswer: `()=>false`, serverMs: `()=>NaN`,
  formatWhen: `()=>''`, priceLabel: `n=>String(n)`, payMethodLabel: `()=>''`,
  SubHeader: `'SubHeader'`, AlertDialog: `'AlertDialog'`, YandexMap: `'YandexMap'`, Link: `'a'`, PayTripCard: `'PayTripCard'`, SbpPay: `'SbpPay'`,
};
async function compile(path, exported = 'default') {
  const source = readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
  const names = [];
  const body = source.replace(/import\s+([\s\S]*?)\s+from\s+"[^"]+";/g, (_, bindings) => {
    const first = bindings.split('{')[0].trim().replace(/,$/, '').trim();
    if (first && !first.startsWith('type ')) names.push(first);
    const named = bindings.match(/\{([\s\S]*)\}/)?.[1];
    for (const entry of named?.split(',') ?? []) { const n = entry.trim(); if (n && !n.startsWith('type ')) names.push(n); }
    return '';
  });
  const prelude = `const h=globalThis.__rideshareLocal;const __jsx=(type,props,...children)=>({type,props:{...props,children}}),__fragment='fragment';\n` + names.map(n => `const ${n}=${definitions[n] ?? (['ShareTripCard', 'StatusPill', 'OfflineTripPass', 'BoardingWait', 'DriverBoarding', 'DriverLocationShare'].includes(n) ? `(...args)=>h.components.${n}(...args)` : '()=>null')};`).join('\n');
  const result = await transform(prelude + body, { loader: 'tsx', format: 'esm', jsx: 'transform', jsxFactory: '__jsx', jsxFragment: '__fragment' });
  return (await import('data:text/javascript;base64,' + Buffer.from(result.code).toString('base64')))[exported];
}
h.components.ShareTripCard = await compile('components/ShareTripCard.tsx');
h.components.StatusPill = await compile('components/StatusPill.tsx', 'StatusPill');
h.components.OfflineTripPass = await compile('components/OfflineTripPass.tsx');
h.components.BoardingWait = await compile('components/BoardingWait.tsx');
h.components.DriverBoarding = await compile('components/DriverBoarding.tsx');
h.components.DriverLocationShare = await compile('components/DriverLocationShare.tsx');
h.driverTransit = await compile('utils/tripLocationShare.ts', 'driverInTransit');
const Active = await compile('screens/ActiveTripScreen.tsx');
const Receipt = await compile('screens/TripReceiptScreen.tsx');
const all = n => n && typeof n === 'object' ? [n, ...[n.props?.children].flat(Infinity).flatMap(all)] : [];
const text = n => n == null || n === false ? '' : typeof n !== 'object' ? String(n) : [n.props?.children].flat(Infinity).map(text).join('');
const button = label => all(h.tree).find(n => n.type === 'button' && text(n) === label);
/** Кнопка, в тексте которой есть подпись (у «Пассажир сел» перед словами стоит значок). */
const buttonWith = label => all(h.tree).find(n => n.type === 'button' && text(n).includes(label));
/** Водитель отметил «Сел» где-то ещё — пассажир узнаёт об этом из очередного опроса. */
async function driverMarksBoarding() {
  h.trip.status = 'onboard'; h.details.status = 'onboard';
  await h.interval(); await settle(Active);
}
function dispose(instance) {
  if (!instance.live) return;
  instance.live = false;
  for (const value of instance.values) value?.cleanup?.();
}
function reset({ role = 'passenger', status = 'confirmed', lang = 'ru', shares = [], sharesError = 0 } = {}) {
  for (const instance of h.instances?.values() ?? []) dispose(instance);
  passValues.clear();
  Object.assign(h, { userId: 7, bookingId: 42, detailsError: false, tripError: false, failPassWrites: false, tripHolds: [], tripHeldRequests: [], detailsHolds: [], detailsHeldRequests: [], locationConnections: [], locationShares: [], cancelCalls: [], cancelResult: { status: 'cancelled', contact_then_cancel: true }, failRoleAfterCancel: false, refreshOwnCalls: 0, detailsById: new Map(), tripById: new Map(), codeHolds: new Map(), codeRequests: [], passes: new Map(), passWrites: [], stateCalls: [], instances: new Map(), effects: [], dirty: false, lang, generation: 'A', requests: [], sent: [], queued: [], thanks: [], navigation: [], shares, sharesError, contacts: [], sendError: null, boarded: [], unboarded: [],
    details: { booking_id: 42, role, status, from_city: 'Уфа', to_city: 'Сибай', depart_at: '2026-10-15T10:00:00Z', contact_unlocked: true, driver_name: 'QA Driver', driver_car: 'QA Car', driver_plate: 'QA001', driver_phone: '+79990000000', pickup: 'QA Pickup', price: 300, pay_method: 'cash', seats: 1 },
    trip: { role, status, driver_phase: 'arriving', arrival_verified: true, peer_id: role === 'driver' ? 7 : 3, boarding_undo_sec: 0 },
    receipt: { role, booking_id: 42, from_city: 'Уфа', to_city: 'Сибай', amount: 300, paid: false, pay_method: 'cash' },
  });
}
function render(Component, props = {}) {
  h.effects = []; h.dirty = false;
  const seen = new Set();
  function visit(n, path) {
    if (Array.isArray(n)) return n.map((v, i) => visit(v, path + '.' + i));
    if (!n || typeof n !== 'object') return n;
    if (typeof n.type === 'function') {
      seen.add(path); let c = h.instances.get(path);
      if (!c || c.type !== n.type) { if (c) dispose(c); c = { type: n.type, values: [], i: 0, live: true }; h.instances.set(path, c); }
      h.current = c; c.i = 0;
      return visit(n.type(n.props), path + '.child');
    }
    return { ...n, props: { ...n.props, children: visit(n.props?.children, path + '.children') } };
  }
  h.tree = visit({ type: Component, props }, 'root');
  for (const [path, c] of h.instances) if (!seen.has(path)) { dispose(c); h.instances.delete(path); }
  for (const effect of h.effects) effect();
  return h.tree;
}
async function settle(Component, props = {}) { for (let i = 0; i < 10; i++) { render(Component, props); await new Promise(setImmediate); if (!h.dirty) return h.tree; } throw Error('render did not settle'); }
let failed = 0, count = 0;
async function test(name, fn) { count++; try { await fn(); console.log('✓ ' + name); } catch (e) { failed++; console.error('✗ ' + name + ': ' + e.message); } }
for (const lang of ['ru', 'ba']) {
  // Посадку отмечает водитель (решение 17.10.2026): своего «Я сел» у пассажира больше нет.
  const sat = lang === 'ru' ? 'Села в машину' : 'Машинаға ултырҙым';
  const wait = lang === 'ru' ? 'Водитель отметит посадку' : 'Йөрөтөүсе ултырыуҙы билдәләр';
  const report = lang === 'ru' ? 'Сообщить о проблеме' : 'Проблема тураһында хәбәр итергә';
  const driverSat = lang === 'ru' ? 'Пассажир сел' : 'Пассажир ултырҙы';
  const inCar = lang === 'ru' ? 'Пассажир в машине' : 'Пассажир машинала';
  const undo = lang === 'ru' ? 'Отменить' : 'Кире алырға';
  const done = lang === 'ru' ? 'Поездка завершена' : 'Сәфәр тамамланды';
  const hint = lang === 'ru' ? 'Назови его водителю при посадке' : 'Ултырғанда йөрөтөүсегә әйт';
  const inTransit = lang === 'ru' ? 'В пути' : 'Юлда';
  await test(`${lang}: confirmed passenger waits for the driver's mark — code, «Водитель отметит посадку», no «Я сел»`, async () => {
    reset({ lang }); await settle(Active);
    assert.ok(!button(sat)); assert.ok(text(h.tree).includes(wait)); assert.ok(text(h.tree).includes(hint)); assert.ok(text(h.tree).includes('4931'));
    assert.ok(button(report), '«Сообщить о проблеме» рядом с ожиданием посадки');
    await driverMarksBoarding();
    assert.deepEqual(h.sent, [], 'пассажир ничего не отправлял'); assert.ok(!h.requests.includes('create-share'));
    assert.ok(!text(h.tree).includes(hint)); assert.ok(!text(h.tree).includes('4931')); assert.ok(!text(h.tree).includes(wait));
    assert.ok(text(h.tree).includes(inTransit)); assert.ok(button(done));
  });
  await test(`${lang}: the driver's mark reaches the passenger without the family-sharing endpoint`, async () => {
    reset({ lang, sharesError: 404 }); await settle(Active); assert.ok(text(h.tree).includes(wait)); assert.ok(!button(sat));
    await driverMarksBoarding(); assert.ok(text(h.tree).includes(inTransit)); assert.ok(button(done)); assert.deepEqual(h.sent, []);
  });
  await test(`${lang}: reloaded onboard passenger has no boarding code, arriving/GPS badge or waiting text`, async () => {
    reset({ lang, status: 'onboard' }); await settle(Active); assert.ok(!text(h.tree).includes(hint)); assert.ok(!text(h.tree).includes('4931'));
    assert.ok(text(h.tree).includes(inTransit)); assert.ok(!text(h.tree).includes('GPS')); assert.ok(!button(sat)); assert.ok(!text(h.tree).includes(wait));
    assert.ok(button(done)); assert.ok(!h.requests.includes('code'));
  });
  await test(`${lang}: confirmed driver has neither passenger code nor own-phone call`, async () => {
    reset({ lang, role: 'driver' }); await settle(Active); assert.ok(!text(h.tree).includes(hint)); assert.ok(!text(h.tree).includes('4931'));
    assert.ok(!all(h.tree).some(n => n.type === 'a' && n.props.href === 'tel:+79990000000')); assert.ok(!h.requests.includes('code')); assert.ok(!button(sat));
    assert.ok(!text(h.tree).includes(wait), 'подпись ожидания — только пассажиру');
  });
  await test(`${lang}: driver marks «Пассажир сел», then «Отменить» returns the booking to confirmed on his screen`, async () => {
    reset({ lang, role: 'driver' }); await settle(Active);
    await buttonWith(driverSat).props.onClick(); await settle(Active);
    assert.deepEqual(h.boarded, [42]); assert.ok(text(h.tree).includes(inCar)); assert.ok(buttonWith(undo + ' · 5:00'));
    await buttonWith(undo).props.onClick(); await settle(Active);
    assert.deepEqual(h.unboarded, [42]); assert.ok(buttonWith(driverSat), 'после отмены снова «Пассажир сел»'); assert.ok(!text(h.tree).includes(inCar));
    await h.interval(); await settle(Active); assert.ok(buttonWith(driverSat), 'опрос подтверждает отмену');
  });
  await test(`${lang}: onboard takes precedence over stale arriving phase`, async () => {
    reset({ lang }); await settle(h.components.StatusPill, { status: 'onboard', phase: 'arriving', arrivalVerified: true }); assert.equal(text(h.tree), inTransit);
  });
  await test(`${lang}: confirmed approaching badge and verified GPS remain available`, async () => {
    reset({ lang }); await settle(h.components.StatusPill, { status: 'confirmed', phase: 'arriving', arrivalVerified: true }); assert.ok(text(h.tree).includes('GPS')); assert.notEqual(text(h.tree), inTransit);
  });
  await test(`${lang}: driver receipt excludes passenger payment, thanks, tip request`, async () => {
    reset({ lang, role: 'driver', status: 'done' }); await settle(Receipt);
    assert.ok(!all(h.tree).some(n => ['PayTripCard', 'SbpPay'].includes(n.type))); assert.ok(!button(lang === 'ru' ? 'Сказать рәхмәт' : 'Рәхмәт әйтеү')); assert.ok(!h.requests.includes('tip'));
    assert.ok(text(h.tree).includes('Уфа')); assert.ok(text(h.tree).includes('300'));
  });
  await test(`${lang}: unpaid passenger retains payment, thanks and enabled tips`, async () => {
    reset({ lang, status: 'done' }); await settle(Receipt);
    assert.ok(all(h.tree).some(n => n.type === 'PayTripCard')); assert.ok(all(h.tree).some(n => n.type === 'SbpPay'));
    const thanks = button(lang === 'ru' ? 'Сказать рәхмәт' : 'Рәхмәт әйтеү'); assert.ok(thanks); await thanks.props.onClick(); await settle(Receipt); assert.deepEqual(h.thanks, [42]);
  });
}
await test('waiting passenger sends nothing: no «Я сел» request, nothing in the offline queue', async () => {
  reset(); h.shares = [{ id: 1, contact_id: 1 }]; await settle(Active);
  assert.ok(!button('Села в машину')); assert.ok(!button('Доехала'));
  await h.interval(); await settle(Active);
  assert.deepEqual(h.sent, []); assert.deepEqual(h.queued, []); assert.equal(h.trip.status, 'confirmed'); assert.ok(text(h.tree).includes('4931'));
});
await test('server rejection of «Доехала» is not queued for a retry', async () => {
  reset({ status: 'onboard' }); h.shares = [{ id: 1, contact_id: 1 }]; h.sendError = 409; await settle(Active);
  await button('Доехала').props.onClick(); await settle(Active); assert.deepEqual(h.sent, [[42, 'arrived']]); assert.deepEqual(h.queued, []);
});
await test('transport failure keeps «Доехала» in the explicit offline queue', async () => {
  reset({ status: 'onboard' }); h.shares = [{ id: 1, contact_id: 1 }]; h.sendError = 0; await settle(Active);
  await button('Доехала').props.onClick(); await settle(Active); assert.deepEqual(h.queued, [[42, 'trip_status', 'arrived']]);
});
await test('passenger «Сообщить о проблеме» opens a dispute with the driver of this booking', async () => {
  reset(); await settle(Active);
  await button('Сообщить о проблеме').props.onClick(); await settle(Active);
  const card = all(h.tree).find(n => n.type === 'FileIncidentCard');
  assert.ok(card, 'разбор не открылся');
  assert.equal(card.props.respondentId, 3, 'разбор — с водителем этой брони (peer_id из /role)');
  assert.equal(card.props.bookingId, 42); assert.equal(card.props.respondentName, 'QA Driver');
});
// Приёмка PR174: «Сообщить о проблеме» не пропадает тогда, когда оно нужнее всего.
await test('passenger «в пути» keeps «Сообщить о проблеме» (marked) — cancel is refused there', async () => {
  reset({ status: 'onboard' }); await settle(Active);
  assert.ok(text(h.tree).includes('Водитель отметил посадку'), 'нет «Водитель отметил посадку» «в пути»');
  assert.ok(text(h.tree).includes('Не садился — сообщи о проблеме.'));
  await button('Сообщить о проблеме').props.onClick(); await settle(Active);
  const card = all(h.tree).find(n => n.type === 'FileIncidentCard');
  assert.ok(card, 'разбор не открылся'); assert.equal(card.props.initialType, 'driver_no_show'); assert.equal(card.props.respondentId, 3);
});
await test('completed trip: passenger reports a problem while the server window is open, not after', async () => {
  reset({ status: 'done' }); h.trip.problem_report_sec = 3600; await settle(Active);
  assert.ok(text(h.tree).includes('Не садился в машину?'), 'после поездки нет «Сообщить о проблеме»');
  reset({ status: 'done' }); h.trip.problem_report_sec = 0; await settle(Active);
  assert.ok(!text(h.tree).includes('Не садился в машину?'), 'окно вышло — кнопки нет');
  reset({ role: 'driver', status: 'done' }); h.trip.problem_report_sec = 3600; await settle(Active);
  assert.ok(!text(h.tree).includes('Не садился в машину?'), 'водителю — не его разбор');
});
await test('driver trip screen: after the undo window — the boarded dispute from the cabinet (Q4)', async () => {
  reset({ role: 'driver', status: 'onboard' }); await settle(Active);
  assert.ok(text(h.tree).includes('Пассажир в машине'));
  const dispute = all(h.tree).find(n => n.type === 'DriverBoardedDispute');
  assert.ok(dispute, 'на экране поездки водителя нет разбора после окна «Отменить»');
  assert.equal(dispute.props.passengerId, 7); assert.equal(dispute.props.bookingId, 42);
  reset({ role: 'driver', status: 'onboard' }); h.trip.boarding_undo_sec = 200; await settle(Active);
  assert.ok(!all(h.tree).some(n => n.type === 'DriverBoardedDispute'), 'пока можно отменить, разбор не нужен');
});
await test('driver undo: a poll started before «Отменить» cannot bring «в пути» back', async () => {
  reset({ role: 'driver' }); await settle(Active);
  await buttonWith('Пассажир сел').props.onClick(); await settle(Active);
  let release; h.tripHolds.push({ promise: new Promise(resolve => { release = resolve; }) });
  const stale = h.interval(); assert.deepEqual(h.tripHeldRequests, [42]);
  await buttonWith('Отменить').props.onClick(); await settle(Active);
  release({ ...h.trip, status: 'onboard', boarding_undo_sec: 290 }); await stale; await settle(Active);
  assert.ok(buttonWith('Пассажир сел'), 'запоздавший опрос вернул «в пути»'); assert.ok(!text(h.tree).includes('Пассажир в машине'));
});
await test('done without a shared link reaches the completed booking UI', async () => {
  reset({ status: 'onboard' }); await settle(Active); assert.ok(button('Поездка завершена'));
  await button('Поездка завершена').props.onClick(); await settle(Active); assert.deepEqual(h.sent, [[42, 'done']]); assert.equal(h.trip.status, 'done'); assert.ok(!button('Поездка завершена'));
});
await test('paid passenger receipt does not offer paying again', async () => {
  reset({ status: 'done' }); h.receipt.paid = true; await settle(Receipt); assert.ok(!all(h.tree).some(n => n.type === 'PayTripCard'));
});

// Held network responses exercise the original component effect/cleanup. The pass
// boundary stores exactly the snapshot/bookingId sent by production saveTripPass.
function holdBoardingCode(bookingId) {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  const pending = { promise, resolve, bookingId };
  h.codeHolds.set(bookingId, pending);
  return pending;
}
async function replyBoardingCode(pending, code) {
  assert.ok(h.codeRequests.includes(pending.bookingId), 'The held request must actually have started');
  pending.resolve({ code });
  await new Promise(setImmediate);
  await new Promise(setImmediate);
}
const Empty = () => null;
await test('held boarding reply after confirmed→onboard cannot update current UI or save a stale pass code', async () => {
  reset(); const pending = holdBoardingCode(42); await settle(Active);
  assert.deepEqual(h.codeRequests, [42]); assert.equal(h.passes.get(42)?.boardingCode, '');
  await driverMarksBoarding();
  assert.equal(h.trip.status, 'onboard'); assert.ok(text(h.tree).includes('В пути'));
  assert.ok(!text(h.tree).includes('Назови его водителю при посадке'));
  const writesBeforeReply = h.passWrites.length, stateCallsBeforeReply = h.stateCalls.length;
  await replyBoardingCode(pending, '428428'); await settle(Active);
  assert.ok(!text(h.tree).includes('428428'));
  assert.equal(h.passWrites.length, writesBeforeReply, 'A stale reply must not call the pass storage boundary');
  assert.equal(h.stateCalls.length, stateCallsBeforeReply, 'Effect cleanup must ignore the stale reply before setState');
  assert.equal(h.passes.get(42)?.boardingCode, '', 'No boarding code existed before this held response');
});
await test('held boarding reply after unmount cannot call old state setters or pass storage', async () => {
  reset(); const pending = holdBoardingCode(42); await settle(Active);
  assert.deepEqual(h.codeRequests, [42]); assert.ok(h.passes.has(42));
  await settle(Empty); assert.equal(h.tree, null);
  const writesBeforeReply = h.passWrites.length, stateCallsBeforeReply = h.stateCalls.length;
  await replyBoardingCode(pending, '428428'); await settle(Empty);
  assert.equal(h.tree, null);
  assert.equal(h.passWrites.length, writesBeforeReply);
  assert.equal(h.stateCalls.length, stateCallsBeforeReply, 'Disposed code effect must not even attempt a setter');
  assert.equal(h.passes.get(42)?.boardingCode, '');
});
for (const oldReplyFirst of [true, false]) await test(`booking42→43: old reply ${oldReplyFirst ? 'before' : 'after'} current reply preserves UI and each pass owner`, async () => {
  reset();
  h.details.pickup_stop = 'Акъяр'; h.details.dropoff_stop = 'Сибай'; h.details.ride_has_stops = true;
  const oldReply = holdBoardingCode(42); await settle(Active);
  assert.deepEqual(h.codeRequests, [42]); const previousPass = structuredClone(h.passes.get(42));
  assert.equal(previousPass.bookingId, 42); assert.equal(previousPass.boardingCode, '');
  assert.equal(previousPass.pickupStop, 'Акъяр'); assert.equal(previousPass.dropoffStop, 'Сибай');
  const currentReply = holdBoardingCode(43);
  h.bookingId = 43;
  h.detailsById.set(43, { ...h.details, booking_id: 43, from_city: 'Баймак', to_city: 'Уфа', pickup_stop: 'Темясово', dropoff_stop: 'Исянгулово' });
  h.tripById.set(43, { ...h.trip, status: 'confirmed' });
  await settle(Active);
  assert.deepEqual(h.codeRequests, [42, 43]);
  assert.equal(all(h.tree).find(n => n.type === 'SubHeader')?.props.title, 'Рейс водителя: Баймак → Уфа'); assert.equal(h.passes.get(43)?.bookingId, 43);
  assert.equal(h.passes.get(43)?.pickupStop, 'Темясово'); assert.equal(h.passes.get(43)?.dropoffStop, 'Исянгулово');
  async function rejectOldReply() {
    const writesBeforeReply = h.passWrites.length, stateCallsBeforeReply = h.stateCalls.length;
    await replyBoardingCode(oldReply, '428428'); await settle(Active);
    assert.ok(!text(h.tree).includes('428428'));
    assert.equal(h.passWrites.length, writesBeforeReply);
    assert.equal(h.stateCalls.length, stateCallsBeforeReply);
    assert.deepEqual(h.passes.get(42), previousPass, 'Old response must not rewrite the previous booking pass');
    assert.equal(all(h.tree).find(n => n.type === 'SubHeader')?.props.title, 'Рейс водителя: Баймак → Уфа');
    assert.equal(h.passes.get(43)?.pickupStop, 'Темясово'); assert.equal(h.passes.get(43)?.dropoffStop, 'Исянгулово');
  }
  if (oldReplyFirst) await rejectOldReply();
  await replyBoardingCode(currentReply, '435435'); await settle(Active);
  assert.ok(text(h.tree).includes('435435'), 'The current booking code must remain visible');
  assert.equal(h.passes.get(43)?.boardingCode, '435435', 'The current successful reply belongs in booking43 pass');
  assert.ok(h.passWrites.some(pass => pass.bookingId === 43 && pass.boardingCode === '435435'));
  assert.ok(!h.passWrites.some(pass => pass.bookingId === 42 && pass.boardingCode === '435435'));
  if (!oldReplyFirst) await rejectOldReply();
  assert.ok(text(h.tree).includes('435435'));
  assert.equal(h.passes.get(43)?.boardingCode, '435435');
  assert.equal(h.passes.get(43)?.pickupStop, 'Темясово'); assert.equal(h.passes.get(43)?.dropoffStop, 'Исянгулово');
  assert.ok(!h.passWrites.some(pass => pass.boardingCode === '428428'));
});

// Actual storage/parser and OfflineTripPass, rather than a hand-written copy of
// the code-clearing rule. Only HTTP and localStorage availability are controlled.
const storedPass = id => h.passStorage.loadTripPass(id);
const codeCards = () => all(h.tree).filter(n => n.props?.className === 'code-card');
async function reloadWithoutBookingApi() {
  await settle(Empty); h.detailsError = true; await settle(Active);
  assert.ok(text(h.tree).includes(h.lang === 'ba' ? 'Бәйләнеш юҡ — һаҡланғанды күрһәтәбеҙ' : 'Нет связи — показываем сохранённое'));
}
for (const lang of ['ru', 'ba']) {
  await test(`${lang}: confirmed code and driver details survive an offline reload before boarding`, async () => {
    reset({ lang }); await settle(Active); const before = structuredClone(storedPass(42));
    assert.equal(before.boardingCode, '4931');
    await reloadWithoutBookingApi();
    assert.equal(codeCards().length, 1); assert.ok(text(h.tree).includes('4931'));
    assert.deepEqual(storedPass(42), before);
  });
  await test(`${lang}: already received code is absent from persisted pass and offline UI after boarding`, async () => {
    reset({ lang }); await settle(Active); const before = storedPass(42);
    assert.equal(before.boardingCode, '4931');
    await driverMarksBoarding();
    const after = storedPass(42); assert.equal(after.boardingCode, '');
    for (const key of ['driverName', 'driverPhone', 'fromCity', 'toCity', 'price', 'pickup']) assert.equal(after[key], before[key]);
    await reloadWithoutBookingApi(); assert.equal(codeCards().length, 0); assert.ok(!text(h.tree).includes('4931'));
    assert.ok(text(h.tree).includes(before.driverName));
  });
  await test(`${lang}: onboard role clears used code even when the follow-up details request fails`, async () => {
    reset({ lang }); await settle(Active); assert.equal(storedPass(42).boardingCode, '4931');
    h.trip.status = 'onboard'; h.detailsError = true;
    await h.interval(); await settle(Active);
    assert.equal(storedPass(42).boardingCode, '');
    await reloadWithoutBookingApi(); assert.equal(codeCards().length, 0);
  });
  await test(`${lang}: fresh onboard details sanitize a legacy cached code even if role is unavailable`, async () => {
    reset({ lang }); await settle(Active); assert.equal(storedPass(42).boardingCode, '4931');
    await settle(Empty); h.details.status = 'onboard'; h.tripError = true; await settle(Active);
    assert.equal(storedPass(42).boardingCode, '');
    await reloadWithoutBookingApi(); assert.equal(codeCards().length, 0);
  });
}
await test('onboard received before effect cleanup rejects a held code reply before storage or setters', async () => {
  reset(); const held = holdBoardingCode(42); await settle(Active);
  h.trip.status = 'onboard'; h.detailsError = true;
  await h.interval(); // deliberately do not render: the old code effect is still alive
  const writes = h.passWrites.length, setters = h.stateCalls.length;
  await replyBoardingCode(held, '428428');
  assert.equal(h.passWrites.length, writes); assert.equal(h.stateCalls.length, setters);
  assert.equal(storedPass(42).boardingCode, '');
  await settle(Active); await reloadWithoutBookingApi(); assert.equal(codeCards().length, 0);
});
for (const status of ['done', 'cancelled']) await test(`${status} still removes the entire offline pass`, async () => {
  reset(); await settle(Active); assert.ok(storedPass(42));
  h.trip.status = status; await h.interval(); await settle(Active);
  assert.equal(storedPass(42), null);
  await settle(Empty); h.detailsError = true; await settle(Active); assert.equal(codeCards().length, 0);
});
await test('boarding one booking does not change the other booking passport', async () => {
  reset(); await settle(Active);
  h.passStorage.saveTripPass({ ...storedPass(42), bookingId: 43, boardingCode: '435435' });
  const other = structuredClone(storedPass(43));
  await driverMarksBoarding();
  assert.deepEqual(storedPass(43), other);
});
await test('unwritable storage removes only the used-code snapshot instead of leaving a reusable code', async () => {
  reset(); await settle(Active);
  h.passStorage.saveTripPass({ ...storedPass(42), bookingId: 43, boardingCode: '435435' });
  const other = structuredClone(storedPass(43)); h.failPassWrites = true; h.detailsError = true;
  h.trip.status = 'onboard'; await h.interval(); await settle(Active);
  assert.equal(storedPass(42), null); assert.deepEqual(storedPass(43), other);
});

for (const observed of [
  { status: 'onboard', lang: 'ru' }, { status: 'onboard', lang: 'ba' },
  { status: 'done', lang: 'ru' }, { status: 'cancelled', lang: 'ru' },
]) await test(`${observed.lang}: late confirmed role cannot undo observed ${observed.status} while details fails`, async () => {
  reset({ lang: observed.lang }); await settle(Active);
  const confirmedReply = { ...h.trip }; let release;
  h.tripHolds.push({ promise: new Promise(resolve => { release = resolve; }) });
  const staleTick = h.interval(); assert.deepEqual(h.tripHeldRequests, [42]);
  h.trip.status = observed.status; h.detailsError = true;
  await h.interval(); await settle(Active);
  const before = structuredClone(storedPass(42)), codes = h.codeRequests.length;
  if (observed.status === 'onboard') assert.equal(before.boardingCode, '');
  else assert.equal(before, null);
  release(confirmedReply); await staleTick; await settle(Active);
  assert.equal(h.codeRequests.length, codes, 'A stale role must not start another boarding-code request');
  assert.deepEqual(storedPass(42), before, 'The observed state must survive the late role response');
  assert.equal(codeCards().length, 0);
  assert.ok(!button(observed.lang === 'ba' ? 'Машинаға ултырҙым' : 'Села в машину'));
});

await test('verified B cannot see A cached pass after details fails', async () => {
  reset(); await settle(Active); assert.equal(storedPass(42).ownerId,7); await settle(Empty);
  h.generation='B';h.userId=8;h.detailsError=true;h.tripError=true;await settle(Active);
  assert.ok(!text(h.tree).includes('QA Driver'));assert.equal(codeCards().length,0);
});
await test('verified session cannot promote a legacy unbound cached pass on details failure', async () => {
  reset();await settle(Active);await settle(Empty);const legacy=storedPass(42);delete legacy.ownerId;delete legacy.sessionGeneration;delete legacy.ownershipId;passValues.set('yuldash.tripPass.42',JSON.stringify(legacy));
  h.detailsError=true;h.tripError=true;await settle(Active);assert.ok(!text(h.tree).includes('QA Driver'));assert.equal(codeCards().length,0);
});

const telLinks = () => all(h.tree).filter(n => n.type === 'a' && String(n.props?.href).startsWith('tel:')).map(n => n.props.href);
function withPrivateDetails(role) {
  Object.assign(h.details, { driver_car_color: 'PRIVATE COLOR', pickup: 'PRIVATE MEETING', pickup_lat: 55.11, pickup_lng: 56.22,
    from_lat: 54.7, from_lng: 55.9, to_lat: 52.7, to_lng: 58.6, minor_passenger: true,
    minor_guardian_name: role === 'driver' ? 'PRIVATE GUARDIAN' : '', minor_guardian_phone: role === 'driver' ? '+79990000001' : '' });
}
function assertCancelledPrivacy() {
  assert.deepEqual(telLinks(), [], 'ни одной настоящей ссылки tel: после отмены');
  for (const value of ['QA Car', 'QA001', 'PRIVATE COLOR', 'PRIVATE MEETING', 'PRIVATE GUARDIAN']) {
    assert.ok(!text(h.tree).includes(value), 'после отмены скрыто: ' + value);
  }
  assert.equal(storedPass(42), null); assert.equal(h.ownedPassStorage.loadOfflineTripPass(42), null);
  assert.equal(h.passStorage.tripPassStage(42, 'A'), 'terminal'); assert.equal(codeCards().length, 0);
  assert.ok(all(h.tree).filter(n => n.type === 'YandexMap').every(n => n.props.me === null), 'на карте нет последней живой точки');
  assert.ok(h.locationConnections.every(c => c.closed), 'приём точки закрыт');
  assert.ok(h.locationShares.every(s => s.stopped), 'реальный DriverLocationShare вызвал stop своего отправителя');
  assert.ok(all(h.tree).some(n => n.type === 'SubHeader' && n.props.title.includes('Уфа') && n.props.title.includes('Сибай')), 'карточка маршрута остаётся');
  assert.ok(text(h.tree).includes('300'), 'запись об оплате остаётся, экран не превращается в пустую карточку');
}
for (const lang of ['ru', 'ba']) for (const role of ['passenger', 'driver']) {
  await test(`${lang}/${role}: cancelled role immediately closes real contacts even when details fails, including after recovery`, async () => {
    reset({ lang, role }); withPrivateDetails(role); await settle(Active);
    assert.ok(telLinks().includes(role === 'passenger' ? 'tel:+79990000000' : 'tel:+79990000001'), 'до отмены настоящий контакт доступен участнику');
    const incoming = h.locationConnections[0];
    if (incoming) { incoming.point({ lat: 55.123, lng: 56.456 }); await settle(Active); assert.ok(all(h.tree).some(n => n.type === 'YandexMap' && n.props.me?.lat === 55.123)); }
    if (role === 'driver') assert.ok(h.locationShares.some(s => !s.stopped));
    const reads = h.requests.filter(r => r === 'details').length;
    h.detailsError = true; h.trip.status = 'cancelled';
    await h.interval();
    incoming?.point({ lat: 55.777, lng: 56.888 }); // callback уже получен, effect cleanup ещё не отработал
    await settle(Active); assertCancelledPrivacy();
    assert.equal(h.requests.filter(r => r === 'details').length, reads + 1, 'одна фоновая сверка действительно отказала');
    await h.interval(); await settle(Active);
    assert.equal(h.requests.filter(r => r === 'details').length, reads + 1, 'терминал не запускает тяжёлый GET каждые 10 секунд');
    h.detailsError = false;
    await h.interval(); await settle(Active); assertCancelledPrivacy();
    await settle(Empty);
    h.details = { ...h.details, status: 'cancelled', contact_unlocked: false, driver_phone: '', driver_car: '', driver_plate: '', pickup: '', minor_guardian_name: '', minor_guardian_phone: '' };
    await settle(Active); assertCancelledPrivacy();
  });
  await test(`${lang}/${role}: a held confirmed details reply cannot restore contacts, passport or live point after cancelled`, async () => {
    reset({ lang, role }); withPrivateDetails(role); await settle(Active);
    const staleDetails = { ...h.details };
    let release; h.detailsHolds.push({ promise: new Promise(resolve => { release = resolve; }) });
    const realNow = Date.now;
    try {
      // Только часы единичного теста: запускаем штатную редкую сверку confirmed details.
      const due = realNow() + h.detailsSync.DETAILS_REFRESH_MS + 1;
      Date.now = () => due;
      await h.interval();
    } finally { Date.now = realNow; }
    assert.deepEqual(h.detailsHeldRequests, [42], 'задержанный GET настоящего экрана действительно начался');
    h.detailsError = true; h.trip.status = 'cancelled';
    await h.interval(); await settle(Active); assertCancelledPrivacy();
    const writes = h.passWrites.length;
    release(staleDetails); await new Promise(setImmediate); await settle(Active);
    assert.equal(h.passWrites.length, writes, 'поздний confirmed GET даже не вызывает сохранение паспорта');
    assertCancelledPrivacy();
    h.locationConnections[0]?.point({ lat: 55.999, lng: 56.999 });
    h.trip.status = 'confirmed'; h.detailsError = false;
    await h.interval(); await settle(Active); assertCancelledPrivacy();
    assert.equal(h.passWrites.length, writes, 'поздний confirmed role тоже не оживляет паспорт');
    h.trip.status = 'done';
    await h.interval(); await settle(Active); assertCancelledPrivacy();
    assert.ok(!button(lang === 'ba' ? 'Сәфәр квитанцияһы' : 'Квитанция поездки'), 'поздний чужой терминал не превращает отмену в завершённую поездку');
  });
  await test(`${lang}/${role}: done retains server-authorized contacts and receipt, while a closed server window hides contacts`, async () => {
    reset({ lang, role }); withPrivateDetails(role); await settle(Active);
    h.trip.status = 'done'; h.details.status = 'done';
    await h.interval(); await settle(Active);
    assert.ok(telLinks().includes(role === 'passenger' ? 'tel:+79990000000' : 'tel:+79990000001'), 'done не запрещает разрешённую сервером связь');
    if (role === 'passenger') assert.ok(text(h.tree).includes('QA001') && text(h.tree).includes('PRIVATE MEETING'));
    assert.equal(storedPass(42), null); assert.ok(h.locationShares.every(s => s.stopped)); assert.ok(h.locationConnections.every(c => c.closed));
    const label = lang === 'ba' ? 'Сәфәр квитанцияһы' : 'Квитанция поездки';
    assert.ok(button(label)); button(label).props.onClick(); assert.deepEqual(h.navigation, ['/receipt/42']);
    await settle(Empty);
    h.details = { ...h.details, contact_unlocked: false, driver_phone: '', driver_plate: '', pickup: '', minor_guardian_name: '', minor_guardian_phone: '' };
    await settle(Active);
    assert.deepEqual(telLinks(), []); assert.ok(!text(h.tree).includes('QA001') && !text(h.tree).includes('PRIVATE MEETING'));
    assert.ok(button(label), 'квитанция остаётся доступна после закрытия окна связи');
  });
}
for (const lang of ['ru', 'ba']) await test(`${lang}: own successful cancellation closes contacts before the next role and details requests fail`, async () => {
  reset({ lang }); withPrivateDetails('passenger'); await settle(Active);
  assert.ok(telLinks().includes('tel:+79990000000') && storedPass(42));
  const incoming = h.locationConnections[0]; incoming?.point({ lat: 55.123, lng: 56.456 }); await settle(Active);
  buttonWith(lang === 'ba' ? 'Сәфәрҙе кире алыу' : 'Отменить поездку').props.onClick(); await settle(Active);
  const dialog = all(h.tree).find(n => n.type === 'AlertDialog'); assert.ok(dialog);
  h.failRoleAfterCancel = true; h.detailsError = true;
  dialog.props.confirm.onClick(); await settle(Active);
  assert.deepEqual(h.cancelCalls, [[42, '']]); assert.equal(h.refreshOwnCalls, 1);
  incoming?.point({ lat: 55.777, lng: 56.888 }); await settle(Active);
  assertCancelledPrivacy();
  assert.ok(!buttonWith(lang === 'ba' ? 'Сәфәрҙе кире алыу' : 'Отменить поездку'), 'успешная отмена немедленно стала эффективным статусом');
});
console.log(`rideshare local regression: ${count - failed}/${count} passed`);
delete globalThis.__rideshareLocal;
if (previousLocalStorage) Object.defineProperty(globalThis, 'localStorage', previousLocalStorage);
else delete globalThis.localStorage;
if (failed) process.exitCode = 1;
