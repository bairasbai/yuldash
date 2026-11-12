// Actual production components; only React hooks, leaf widgets and network are controlled.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { IDBFactory } from 'fake-indexeddb';
import { build, transform } from 'esbuild';
import { GPS } from './gps-grant-fixture.mjs';

const h = globalThis.__rideshareLocal = { components: {} };
const previousLocalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
const passValues = new Map();
Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
  get length() { return passValues.size; },
  key: index => [...passValues.keys()][index] ?? null,
  getItem: key => passValues.get(key) ?? null,
  setItem: (key, value) => { if (h.failPassWrites) throw Error('storage unavailable'); passValues.set(key, value); },
  removeItem: key => passValues.delete(key),
} });
h.http = {
  async details(id) {
    h.requests.push("details");
    if (h.detailsError) throw new h.ApiError(0);
    const held = h.detailsHolds.shift();
    if (held) { h.detailsHeldRequests.push(id); return held.promise; }
    return { ...(h.detailsById.get(id) ?? h.details) };
  },
  async role(id) {
    h.requests.push("state");
    if (h.tripError) throw new h.ApiError(0);
    const held = h.tripHolds.shift();
    if (held) { h.tripHeldRequests.push(id); return held.promise; }
    return { ...(h.tripById.get(id) ?? h.trip) };
  },
  async code(id) {
    h.requests.push("code");
    h.codeRequests.push(id);
    return h.codeHolds.get(id)?.promise ?? { code: "4931" };
  },
};
const passModule = await build({
  stdin: {
    contents: `export * from "./src/utils/offlineTripPass"; export * from "./src/utils/tripPassStore";
      export {fetchBookingDetails,fetchTripState,fetchBoardingCode} from "./src/api/bookings";`,
    resolveDir: fileURLToPath(new URL("../", import.meta.url)), loader: "ts",
  },
  bundle: true, write: false, format: "esm", platform: "node",
  plugins: [{ name: "controlled-trip-transport", setup(b) {
    b.onResolve({ filter: /^(\.\/client|\.\.\/api\/client)$/ }, () => ({ path: "client", namespace: "boundary" }));
    b.onResolve({ filter: /^\.\/storageHealth$/ }, () => ({ path: "storage", namespace: "boundary" }));
    b.onLoad({ filter: /.*/, namespace: "boundary" }, ({ path }) => ({ contents: path === "client" ? `
      const h=globalThis.__rideshareLocal;
      export const getSessionGeneration=()=>h.generation,getAccountSession=()=>({userId:h.userId,generation:h.generation}),
        isAccountSession=owner=>owner.generation===h.generation&&owner.userId===h.userId;
      export const apiGet=async(path, options)=>{
        const [,,id,route]=path.split("/");
        const result=await h.http[route==="boarding-code"?"code":route](Number(id));
        options?.signal?.throwIfAborted();
        if(options?._generation&&options._generation!==h.generation)throw new h.ApiError(409);
        return result;
      };
      export const apiPost=()=>{throw Error("Unexpected POST in read transport");};
    ` : `
      const h=globalThis.__rideshareLocal;
      export const offlineStorageTrusted=()=>!h.diskFenced,reportStorageFailure=()=>{h.diskFenced=true;};
    ` }));
  } }],
});
h.passStorage = await import("data:text/javascript;base64," + Buffer.from(passModule.outputFiles[0].text).toString("base64"));
const tripOwner = () => ({ userId: h.userId, generation: h.generation });
async function seedPass(value) {
  const owner = tripOwner(), revision = await h.passStorage.readTripPassRevision(owner, value.bookingId);
  await h.passStorage.observeTripPass(owner, value.bookingId, { status: "confirmed" }, revision, value);
  await h.passStorage.saveTripPassCode(owner, value.bookingId, revision, value.boardingCode);
}
function passFixture() {
  const factory = new IDBFactory();
  globalThis.indexedDB = {
    open(...args) {
      if (h.denyPassRead) throw Error("storage unavailable");
      const request = factory.open(...args);
      request.addEventListener("success", () => {
        const db = request.result, start = db.transaction.bind(db);
        db.transaction = (...options) => {
          const tx = start(...options);
          if (options[1] !== "readwrite") return tx;
          if (h.failPassWrites) {
            queueMicrotask(() => tx.abort());
            return tx;
          }
          const objectStore = tx.objectStore.bind(tx);
          tx.objectStore = (...names) => {
            const store = objectStore(...names), put = store.put.bind(store);
            store.put = (row, generation) => {
              tx.addEventListener("complete", () => {
                if (generation !== h.generation) return;
                const trips = row.trips ?? new Map(), passes = new Map();
                for (const [id, record] of trips) if (record.pass) {
                  passes.set(id, record.pass);
                  if (!isDeepStrictEqual(h.passes.get(id), record.pass)) h.passWrites.push(record.pass);
                }
                h.records = trips;
                h.passes = passes;
              });
              return put(row, generation);
            };
            return store;
          };
          return tx;
        };
      });
      return request;
    },
  };
}
h.detailsSync = await import('../src/utils/tripDetailsSync.js');
class ApiError extends Error { constructor(status, message = '') { super(message); this.status = status; } }
h.ApiError = ApiError;
const definitions = {
  BookingSegmentNote: `props=>h.components.BookingSegmentNote(props)`,
  useState: `v=>{const i=h.current.i++;const c=h.current;if(!(i in c.values))c.values[i]=typeof v==='function'?v():v;return[c.values[i],x=>{h.stateCalls.push({component:c.type.name,index:i,mounted:c.live});if(!c.live)return;c.values[i]=typeof x==='function'?x(c.values[i]):x;h.dirty=true}];}`,
  useRef: `v=>{const c=h.current,i=c.i++;return c.values[i]??={current:v}}`,
  useEffect: `(fn,deps)=>{const c=h.current,i=c.i++,old=c.values[i];if(!old||!deps||deps.some((v,j)=>!Object.is(v,old.deps?.[j]))){c.values[i]={deps,cleanup:old?.cleanup};h.effects.push(()=>{old?.cleanup?.();c.values[i].cleanup=fn()})}}`,
  useCallback: `(fn,deps)=>{const c=h.current,i=c.i++,old=c.values[i];if(!old||deps.some((v,j)=>!Object.is(v,old.deps[j])))c.values[i]={fn,deps};return c.values[i].fn}`,
  useMemo: `(fn,deps)=>{const c=h.current,i=c.i++,old=c.values[i];if(!old||deps.some((v,j)=>!Object.is(v,old.deps[j])))c.values[i]={value:fn(),deps};return c.values[i].value}`,
  useLang: `()=>({lang:h.lang,appText:(ru,ba)=>{if(!ba)throw Error('missing Bashkir: '+ru);return h.lang==='ba'?ba:ru}})`,
  useParams: `()=>({id:String(h.bookingId)})`, useNavigate: `()=>p=>h.navigation.push(p)`, useAuth: `()=>({user:{id:h.userId}})`,
  getSessionGeneration: `()=>h.generation`, getToken: `()=>"fixture"`, refreshRejectedToken: `async()=>false`, ApiError: `h.ApiError`,
  fetchBookingDetails: `(...args)=>h.passStorage.fetchBookingDetails(...args)`,
  fetchTripState: `(...args)=>h.passStorage.fetchTripState(...args)`,
  fetchBoardingCode: `(...args)=>h.passStorage.fetchBoardingCode(...args)`,
  fetchBookingShares: `async()=>{h.requests.push('shares');if(h.sharesError)throw new h.ApiError(h.sharesError);return h.shares}`,
  fetchTrustedContacts: `async()=>h.contacts`, shareBooking: `async()=>{h.requests.push('create-share');return {}}`,
  setTripStatus: `async(id,status)=>{h.sent.push([id,status]);if(h.sendError!==null)throw new h.ApiError(h.sendError);if(status==='done')h.trip.status='done';h.details.status=h.trip.status;return h.shares??[]}`,
  // Посадку отмечает водитель (решение 17.10.2026): ответы его «Сел» / «Отменить».
  FileIncidentCard: `'FileIncidentCard'`,
  DriverBoardedDispute: `'DriverBoardedDispute'`,
  markBoarded: `async id=>{const owner={userId:h.userId,generation:h.generation};h.boarded.push(id);const result={ok:true,status:'onboard',onboard_at:h.boardingAt,boarding_undo_sec:300};Object.assign(h.trip,result);Object.assign(h.details,result);const held=h.markHolds.shift();if(held){h.markHeldRequests.push(id);await held.promise;}const reply=h.markReplyTransform?h.markReplyTransform({...result}):result;await h.passStorage.observeTripPass(owner,id,reply,undefined);return reply}`,
  undoBoarded: `async id=>{h.unboarded.push(id);const result={ok:true,status:'onboard',onboard_at:h.trip.onboard_at,boarding_undo_sec:0,boarding_undo_pending:true,boarding_undo_request_id:'undo-'+id,boarding_undo_wait_sec:120};Object.assign(h.trip,result);Object.assign(h.details,result);return result}`,
  respondBoardingUndo: `async(...args)=>{const [id,requestId,confirm]=args;const owner={userId:h.userId,generation:h.generation};const revision=await h.passStorage.readTripPassRevision(owner,id);h.undoResponses.push(args);const held=h.undoResponseHolds.shift();if(held){const reply=await held.promise;await h.passStorage.observeTripPass(owner,id,reply,revision);return reply;}const original=h.trip.onboard_at;const result=confirm?{ok:true,status:'confirmed',onboard_at:null,boarding_undo_pending:false,boarding_undo_request_id:null,boarding_undo_wait_sec:0,boarding_undo_confirmed_id:requestId,boarding_undo_onboard_at:original}:{ok:true,status:'onboard',onboard_at:original,boarding_undo_pending:false,boarding_undo_request_id:null,boarding_undo_wait_sec:0};Object.assign(h.trip,result);Object.assign(h.details,result);await h.passStorage.observeTripPass(owner,id,result,revision);return result}`,
  isConfirmedBoardingUndo: `(...args)=>h.matchesBoardingUndo(...args)`,
  PassengerBoardingUndo: `props=>{h.undoProps=props;return h.components.PassengerBoardingUndo(props)}`,
  sameBoardingTimestamp: `(...args)=>h.sameBoardingTimestamp(...args)`,
  isStaleBoardingAfterUndo: `(...args)=>h.isStaleBoardingAfterUndo(...args)`,
  isNewBoardingUndo: `(...args)=>h.isNewBoardingUndo(...args)`,
  usePassengerCancellation: `options=>h.passengerCancellation(options)`,
  bookingCommandOwner: `(user,generation)=>user?{userId:user.id,generation,namespace:String(user.id)}:null`,
  isCurrentBookingOwner: `owner=>owner.userId===h.userId&&owner.generation===h.generation`,
  readDriverDecisions: `async()=>[]`,
  preparePassengerCancellation: `async(owner,bookingId,status)=>({owner,bookingId,status,consequence:"early",termsKey:"a".repeat(64)})`,
  cancelPassengerBooking: `async(owner,terms,reason)=>{h.cancelCalls.push([terms.bookingId,reason]);await h.passStorage.finishTripPass(owner,terms.bookingId);h.trip.status="cancelled";h.details.status="cancelled";if(h.failRoleAfterCancel)h.tripError=true;return {state:"applied",booking:{...h.cancelResult}}}`,
  CANCEL_REASONS: `[]`,
  useRefreshOwnBookings: `()=>()=>{h.refreshOwnCalls++}`,
  enqueue: `async(...v)=>h.queued.push(v)`,
  fetchReceipt: `async()=>({...h.receipt})`, fetchBookingTip: `async()=>{h.requests.push('tip');return {already_thanked:false,money:{sbp:'+79990000000',name:'Test'}}}`,
  thankBooking: `async id=>h.thanks.push(id)`,
  readOfflineTrip: `id=>h.passStorage.readOfflineTrip(id)`,
  useStoredTripData: `(...args)=>h.useStoredTripData(...args)`,
  subscribeTripPassChanges: `listener=>h.passStorage.subscribeTripPassChanges(listener)`,
  isTerminalBookingStatus: `s=>s==='done'||s==='cancelled'`,
  shouldApplyBookingDetails: `(...args)=>h.detailsSync.shouldApplyBookingDetails(...args)`, shouldRefreshBookingDetails: `(...args)=>h.detailsSync.shouldRefreshBookingDetails(...args)`,
  reconcilePaymentDraft: `(draft)=>draft`, useRouteWeather: `()=>null`,
  useVisibleInterval: `(ms,fn)=>{h.interval=fn}`,
  openTripLocation: `(id,point)=>{const connection={id,point,closed:false};h.locationConnections.push(connection);return {close(){connection.closed=true}}}`,
  driverInTransit: `(...args)=>h.driverTransit(...args)`, wsBase: `()=>"ws://fixture"`, tripLocationUrl: `()=>"ws://fixture/location"`,
  rideShareOpen: `id=>h.gps.open(id)`, rideShareRetry: `()=>h.gps.retry()`,
  rideShareEnable: `(...args)=>h.gps.enable(...args)`, subscribeRideShare: `fn=>h.gps.subscribe(fn)`,
  rideShareTerminal: `id=>h.gps.terminal(id)`,
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
h.components.BookingSegmentNote = await compile('components/PickupStopChoice.tsx', 'BookingSegmentNote');
h.components.ShareTripCard = await compile('components/ShareTripCard.tsx');
h.components.StatusPill = await compile('components/StatusPill.tsx', 'StatusPill');
h.components.OfflineTripPass = await compile('components/OfflineTripPass.tsx');
h.components.BoardingWait = await compile('components/BoardingWait.tsx');
h.components.DriverBoarding = await compile('components/DriverBoarding.tsx');
h.components.PassengerBoardingUndo = await compile('components/PassengerBoardingUndo.tsx');
h.matchesBoardingUndo = await compile('utils/boardingUndo.ts', 'isConfirmedBoardingUndo');
h.sameBoardingTimestamp = await compile('utils/boardingUndo.ts', 'sameBoardingTimestamp');
h.isStaleBoardingAfterUndo = await compile('utils/boardingUndo.ts', 'isStaleBoardingAfterUndo');
h.isNewBoardingUndo = await compile('utils/boardingUndo.ts', 'isNewBoardingUndo');
h.components.DriverLocationShare = await compile('components/DriverLocationShare.tsx');
h.passengerCancellation = await compile('components/PassengerCancellation.tsx', 'usePassengerCancellation');
h.useStoredTripData = await compile('utils/useStoredTripData.ts', 'useStoredTripData');
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
  h.trip.status = 'onboard'; h.details.status = 'onboard'; h.trip.onboard_at = h.boardingAt; h.details.onboard_at = h.boardingAt;
  await h.interval(); await settle(Active);
}
function dispose(instance) {
  if (!instance.live) return;
  instance.live = false;
  for (const value of instance.values) value?.cleanup?.();
}
const BOARDING_AT = '2026-10-24T12:00:00.123456Z';
function reset({ role = 'passenger', status = 'confirmed', lang = 'ru', shares = [], sharesError = 0 } = {}) {
  for (const instance of h.instances?.values() ?? []) dispose(instance);
  h.gps?.stop();
  passValues.clear();
  globalThis.window = new EventTarget();
  globalThis.document = new EventTarget();
  passFixture();
  Object.assign(h, { markHolds: [], markHeldRequests: [], markReplyTransform: null, boardingAt: BOARDING_AT, undoResponses: [], undoResponseHolds: [], userId: 7, bookingId: 42, detailsError: false, tripError: false, failPassWrites: false, denyPassRead: false, tripHolds: [], tripHeldRequests: [], detailsHolds: [], detailsHeldRequests: [], locationConnections: [], locationShares: [], cancelCalls: [], cancelResult: { status: 'cancelled', contact_then_cancel: true }, failRoleAfterCancel: false, refreshOwnCalls: 0, detailsById: new Map(), tripById: new Map(), codeHolds: new Map(), codeRequests: [], records: new Map(), passes: new Map(), passWrites: [], diskFenced: false, stateCalls: [], instances: new Map(), effects: [], dirty: false, lang, generation: 'A', requests: [], sent: [], queued: [], thanks: [], navigation: [], shares, sharesError, contacts: [], sendError: null, boarded: [], unboarded: [],
    details: { booking_id: 42, ride_id: 7, role, status, from_city: 'Уфа', to_city: 'Сибай', depart_at: '2026-10-15T10:00:00Z', contact_unlocked: true, driver_name: 'QA Driver', driver_car: 'QA Car', driver_plate: 'QA001', driver_phone: '+79990000000', pickup: 'QA Pickup', price: 300, pay_method: 'cash', seats: 1 },
    trip: { role, status, onboard_at: status === 'onboard' ? BOARDING_AT : null, driver_phase: 'arriving', arrival_verified: true, peer_id: role === 'driver' ? 7 : 3, boarding_undo_sec: 0 },
    receipt: { role, booking_id: 42, from_city: 'Уфа', to_city: 'Сибай', amount: 300, paid: false, pay_method: 'cash' },
  });
  h.gpsElapsed = 1000; h.gpsTimers = []; h.gpsIds = 0; h.gpsReadGate = null;
  // Настоящий memory-grant/controller; только GPS-транспорт, HTTP и часы подменены.
  h.gps = GPS.createRideShare({
    start(_id, hooks) { const share = { stopped: false }; h.locationShares.push(share); hooks.onState('sharing');
      return { stop() { share.stopped = true; }, retry() {} }; },
    async fetchRows() { if (h.gpsReadGate) await h.gpsReadGate; return h.trip.role === 'driver' ? [{ booking_id: 42, ride_id: h.details.ride_id, status: h.trip.status, pickup_stop: '', no_show_wait_sec: 600 }] : []; },
    session: () => h.generation, origin: () => 'https://api.example.test', hasToken: () => true,
    now: () => h.gpsElapsed, wall: Date.now, newId: () => 'fixture-live-grant-'+(++h.gpsIds),
    setTimer(fn, ms) { const timer = { fn, at: h.gpsElapsed + ms, done: false }; h.gpsTimers.push(timer); return timer; },
    clearTimer(timer) { if (timer) timer.done = true; },
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
async function settle(Component, props = {}) {
  for (let i = 0, quiet = 0; i < 140; i++) {
    if (i === 0 || h.dirty) { render(Component, props); quiet = 0; }
    await new Promise(setImmediate);
    if (!h.dirty && ++quiet >= 24) return h.tree;
  }
  throw Error('render did not settle');
}
async function until(predicate) {
  for (let i = 0; i < 100; i++) {
    if (predicate()) return;
    await new Promise(setImmediate);
  }
  throw Error('Expected request did not start');
}
const Empty = () => null;
const storedPass = id => h.passes.get(id) ?? null;
const storedStage = id => h.records.get(id)?.status ?? 'confirmed';
let failed = 0, count = 0;
async function test(name, fn) { count++; try { await fn(); console.log('✓ ' + name); } catch (e) { failed++; console.error('✗ ' + name + ': ' + e.message); } }
for (const lang of ['ru', 'ba']) {
  // Посадку отмечает водитель (решение 17.10.2026): своего «Я сел» у пассажира больше нет.
  const sat = lang === 'ru' ? 'Села в машину' : 'Машинаға ултырҙым';
  const wait = lang === 'ru' ? 'Водитель отметит посадку' : 'Йөрөтөүсе ултырыуҙы билдәләр';
  const report = lang === 'ru' ? 'Сообщить о проблеме' : 'Проблема тураһында хәбәр итергә';
  const driverSat = lang === 'ru' ? 'Пассажир сел' : 'Пассажир ултырҙы';
  const inCar = lang === 'ru' ? 'Пассажир в машине' : 'Пассажир машинала';
  const undo = lang === 'ru' ? 'Исправить посадку' : 'Ултырыуҙы төҙәтергә';
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
  await test(`${lang}: driver requests correction, only the passenger confirmation returns to confirmed`, async () => {
    reset({ lang, role: 'driver' }); await settle(Active);
    await buttonWith(driverSat).props.onClick(); await settle(Active);
    assert.deepEqual(h.boarded, [42]); assert.ok(text(h.tree).includes(inCar)); assert.ok(buttonWith(undo + ' · 5:00'));
    await buttonWith(undo).props.onClick(); await settle(Active);
    assert.deepEqual(h.unboarded, [42]); assert.equal(h.trip.status, 'onboard');
    assert.ok(text(h.tree).includes(inCar)); assert.ok(!buttonWith(driverSat), 'driver request must not undo boarding');
    assert.ok(text(h.tree).includes(lang === 'ru' ? 'Ждём подтверждения пассажира' : 'Пассажирҙың раҫлауын көтәбеҙ'));
    await settle(Empty); h.trip.role = 'passenger'; h.details.role = 'passenger'; await settle(Active);
    await button(lang === 'ru' ? 'Я ещё не сел' : 'Мин әле ултырманым').props.onClick(); await settle(Active);
    assert.deepEqual(h.undoResponses, [[42, 'undo-42', true, 'A']]); assert.equal(h.trip.status, 'confirmed');
    assert.ok(text(h.tree).includes(wait), 'the matching passenger receipt returns to boarding wait');
    await settle(Empty); h.trip.role = 'driver'; h.details.role = 'driver'; await settle(Active);
    assert.ok(buttonWith(driverSat)); assert.ok(!text(h.tree).includes(inCar));
    await h.interval(); await settle(Active); assert.ok(buttonWith(driverSat), 'poll confirms passenger-approved correction');
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
await test('passenger consent: a poll started before confirmation cannot restore the old onboard stage', async () => {
  reset({ status: 'onboard' }); Object.assign(h.trip, { boarding_undo_pending: true, boarding_undo_request_id: 'undo-42', boarding_undo_wait_sec: 120 });
  await settle(Active); const oldOnboard = { ...h.trip };
  let release; h.tripHolds.push({ promise: new Promise(resolve => { release = resolve; }) });
  const stale = h.interval(); await until(()=>h.tripHeldRequests.length>0); assert.deepEqual(h.tripHeldRequests, [42]);
  await button('Я ещё не сел').props.onClick(); await settle(Active);
  assert.equal(h.trip.status, 'confirmed'); assert.ok(text(h.tree).includes('Водитель отметит посадку'));
  release(oldOnboard); await stale; await settle(Active);
  assert.ok(text(h.tree).includes('Водитель отметит посадку'), 'late poll restored the pre-consent boarded state');
  assert.ok(!text(h.tree).includes('Водитель отметил посадку'));
});
for (const receipt of [
  { status: 'confirmed' },
  { status: 'confirmed', boarding_undo_confirmed_id: 'undo-42', boarding_undo_onboard_at: '2026-10-24T12:00:00.123455Z' },
  { status: 'confirmed', boarding_undo_confirmed_id: 'undo-42', boarding_undo_onboard_at: '2026-10-24T12:00:00.123457Z' },
]) await test('passenger callback cannot undo without the receipt of this exact boarding microsecond: ' + JSON.stringify(receipt), async () => {
  reset({ status: 'onboard' }); Object.assign(h.trip, { boarding_undo_pending: true, boarding_undo_request_id: 'undo-42', boarding_undo_wait_sec: 120 });
  await settle(Active); assert.ok(h.undoProps?.onChanged);
  h.undoProps.onChanged(receipt); await settle(Active);
  assert.ok(text(h.tree).includes('Водитель отметил посадку')); assert.ok(!text(h.tree).includes('Водитель отметит посадку'));
});
await test('a matching passenger receipt is accepted, while terminal booking still refuses that receipt', async () => {
  const receipt = { status: 'confirmed', onboard_at: null, boarding_undo_confirmed_id: 'undo-42', boarding_undo_onboard_at: '2026-10-24T12:00:00.123456+00:00' };
  reset({ status: 'onboard' }); await settle(Active); h.undoProps.onChanged(receipt); await settle(Active);
  assert.ok(text(h.tree).includes('Водитель отметит посадку'));
  for (const status of ['done', 'cancelled']) {
    reset({ status: 'onboard' }); await settle(Active); const staleAnswer = h.undoProps.onChanged;
    h.trip.status = status; h.details.status = status; await h.interval(); await settle(Active);
    staleAnswer(receipt); await settle(Active);
    assert.ok(!text(h.tree).includes('Водитель отметит посадку'), status + ' was reopened by consent');
  }
});
for (const terminal of ['done', 'cancelled']) await test('a delayed consent receipt in a role response cannot reopen observed ' + terminal, async () => {
  reset({ status: 'onboard' }); await settle(Active);
  const receipt = { ...h.trip, status: 'confirmed', boarding_undo_confirmed_id: 'undo-42', boarding_undo_onboard_at: BOARDING_AT };
  let release; h.tripHolds.push({ promise: new Promise(resolve => { release = resolve; }) });
  const stale = h.interval(); await until(()=>h.tripHeldRequests.length>0); assert.deepEqual(h.tripHeldRequests, [42]);
  h.trip.status = terminal; h.details.status = terminal; await h.interval(); await settle(Active);
  release(receipt); await stale; await settle(Active);
  assert.ok(!text(h.tree).includes('Водитель отметит посадку'), 'old receipt reopened terminal trip ' + terminal);
  assert.ok(!button('Я ещё не сел'));
});
await test('passenger-approved correction restores the boarding code and both stops, including offline reload', async () => {
  reset(); Object.assign(h.details, { pickup_stop: 'Ургаза', dropoff_stop: 'Бурибай', ride_has_stops: true }); await settle(Active);
  await driverMarksBoarding(); assert.equal(storedPass(42).boardingCode, '');
  Object.assign(h.trip, { boarding_undo_pending: true, boarding_undo_request_id: 'undo-42', boarding_undo_wait_sec: 120 }); await h.interval(); await settle(Active);
  const before = h.codeRequests.length; button('Я ещё не сел').props.onClick(); await settle(Active);
  assert.equal(storedStage(42), 'confirmed'); assert.ok(text(h.tree).includes('4931'));
  assert.equal(h.codeRequests.length, before + 1); assert.equal(storedPass(42).boardingCode, '4931');
  assert.equal(storedPass(42).pickupStop, 'Ургаза'); assert.equal(storedPass(42).dropoffStop, 'Бурибай');
  await settle(Empty); h.detailsError = true; h.tripError = true; await settle(Active);
  assert.ok(text(h.tree).includes('4931')); assert.ok(text(h.tree).includes('Ургаза') && text(h.tree).includes('Бурибай'));
});
for (const source of ['role', 'reload-details']) await test(source + ': server consent receipt reopens the saved original boarding code', async () => {
  reset({ status: 'onboard' }); Object.assign(h.details, { onboard_at: BOARDING_AT }); await settle(Active);
  assert.equal(storedStage(42), 'onboard');
  const receipt = { status: 'confirmed', onboard_at: null, boarding_undo_confirmed_id: 'undo-42', boarding_undo_onboard_at: BOARDING_AT };
  Object.assign(h.trip, receipt); Object.assign(h.details, receipt);
  if (source === 'role') { await h.interval(); await settle(Active); }
  else { await settle(Empty); await settle(Active); }
  assert.equal(storedStage(42), 'confirmed'); assert.ok(text(h.tree).includes('4931'));
});
for (const replyEpoch of ['original', 'missing', 'invalid']) await test('held mark after remote passenger consent cannot reboard the old trip (' + replyEpoch + ' epoch), but a new boarding can', async () => {
  reset({ role: 'driver' }); await settle(Active);
  let release; h.markHolds.push({ promise: new Promise(done => { release = done; }) });
  buttonWith('Пассажир сел').props.onClick(); await settle(Active);
  assert.deepEqual(h.markHeldRequests, [42]); assert.equal(h.trip.status, 'onboard');
  await h.interval(); await settle(Active); assert.ok(text(h.tree).includes('Пассажир в машине'));
  const receipt = { status: 'confirmed', onboard_at: null, boarding_undo_sec: 0, boarding_undo_pending: false, boarding_undo_confirmed_id: 'remote-consent-A', boarding_undo_onboard_at: BOARDING_AT };
  Object.assign(h.trip, receipt); Object.assign(h.details, receipt);
  await h.interval(); await settle(Active); assert.ok(buttonWith('Пассажир сел'));
  if (replyEpoch !== 'original') h.markReplyTransform = result => ({ ...result, onboard_at: replyEpoch === 'missing' ? undefined : 'unknown epoch' });
  release(); await new Promise(setImmediate); await settle(Active);
  assert.equal(h.trip.status, 'confirmed', 'the server stayed confirmed');
  assert.ok(buttonWith('Пассажир сел'), 'old mark reply reboarded UI after passenger-approved undo');
  assert.ok(!text(h.tree).includes('Пассажир в машине'));
  assert.equal(storedStage(42), 'confirmed');
  h.markReplyTransform = null; h.boardingAt = '2026-10-24T12:00:00.123457Z';
  buttonWith('Пассажир сел').props.onClick(); await settle(Active);
  assert.equal(h.trip.status, 'onboard'); assert.equal(h.trip.onboard_at, h.boardingAt);
  assert.ok(text(h.tree).includes('Пассажир в машине'), 'a genuinely different boarding must remain possible');
  assert.equal(storedStage(42), 'onboard');
});
await test('an onboard details request started before consent cannot erase the newly restored boarding code', async () => {
  reset({ status: 'onboard' }); Object.assign(h.details, { onboard_at: BOARDING_AT }); Object.assign(h.trip, { boarding_undo_pending: true, boarding_undo_request_id: 'undo-42', boarding_undo_wait_sec: 120 }); await settle(Active);
  const staleDetails = { ...h.details }; let release; h.detailsHolds.push({ promise: new Promise(done => { release = done; }) });
  const realNow = Date.now;
  try { const due = realNow() + h.detailsSync.DETAILS_REFRESH_MS + 1; Date.now = () => due; await h.interval(); } finally { Date.now = realNow; }
  await until(()=>h.detailsHeldRequests.length>0); assert.deepEqual(h.detailsHeldRequests, [42]); button('Я ещё не сел').props.onClick(); await settle(Active);
  assert.equal(storedPass(42).boardingCode, '4931'); const writes = h.passWrites.length;
  release(staleDetails); await new Promise(setImmediate); await settle(Active);
  assert.equal(storedPass(42).boardingCode, '4931', 'old onboard details erased the code restored by passenger consent');
  assert.equal(h.passWrites.length, writes, 'old details must be rejected before any passport write');
});
await test('held «I am in the car» reply and its callback cannot replace a newer boarding of the same booking', async () => {
  reset({ status: 'onboard' }); Object.assign(h.trip, { boarding_undo_pending: true, boarding_undo_request_id: 'undo-42', boarding_undo_wait_sec: 120 }); await settle(Active);
  let resolve; h.undoResponseHolds.push({ promise: new Promise(done => { resolve = done; }) });
  const staleCallback = h.undoProps.onChanged; button('Я в машине').props.onClick(); await settle(Active);
  const newer = '2026-10-24T12:00:00.123457Z'; Object.assign(h.trip, { onboard_at: newer, boarding_undo_pending: false, boarding_undo_request_id: null, boarding_undo_wait_sec: 0 }); Object.assign(h.details, { onboard_at: newer });
  await h.interval(); await settle(Active);
  const oldReply = { status: 'onboard', onboard_at: BOARDING_AT, boarding_undo_pending: false };
  resolve(oldReply); await new Promise(setImmediate); await settle(Active); staleCallback(oldReply); await settle(Active);
  assert.equal(h.undoProps.terms.onboard_at, newer); assert.ok(text(h.tree).includes('Водитель отметил посадку'));
  assert.equal(storedStage(42), 'onboard'); assert.equal(storedPass(42).boardingCode, '');
});
await test('overlapping role polls keep boarding B after a held boarding A reply, then accept the real receipt B', async () => {
  reset({ status: 'onboard' }); Object.assign(h.details, { onboard_at: BOARDING_AT });
  Object.assign(h.trip, { boarding_undo_pending: true, boarding_undo_request_id: 'request-A', boarding_undo_wait_sec: 120 }); await settle(Active);
  const staleA = { ...h.trip }; let release;
  h.tripHolds.push({ promise: new Promise(done => { release = done; }) }); const heldA = h.interval();
  const newer = '2026-10-24T12:00:00.123457Z';
  Object.assign(h.trip, { onboard_at: newer, boarding_undo_pending: true, boarding_undo_request_id: 'request-B', boarding_undo_wait_sec: 80, boarding_undo_sec: 0 });
  Object.assign(h.details, { onboard_at: newer }); await h.interval(); await settle(Active);
  assert.equal(h.undoProps.terms.onboard_at, newer); assert.equal(h.undoProps.terms.boarding_undo_request_id, 'request-B');
  release(staleA); await heldA; await settle(Active);
  assert.equal(h.trip.onboard_at, newer, 'server stayed on boarding B');
  assert.equal(h.undoProps.terms.onboard_at, newer, 'late role A replaced the accepted boarding B');
  assert.equal(h.undoProps.terms.boarding_undo_request_id, 'request-B'); assert.equal(h.undoProps.terms.boarding_undo_wait_sec, 80);
  const receiptB = { status: 'confirmed', onboard_at: null, boarding_undo_confirmed_id: 'receipt-B', boarding_undo_onboard_at: newer, boarding_undo_pending: false, boarding_undo_request_id: null };
  Object.assign(h.trip, receiptB); Object.assign(h.details, receiptB); await h.interval(); await settle(Active);
  assert.ok(text(h.tree).includes('Водитель отметит посадку')); assert.equal(storedStage(42), 'confirmed'); assert.equal(storedPass(42).boardingCode, '4931');
});
for (const oldEpoch of [BOARDING_AT, undefined, 'not-a-boarding']) await test('a fresh role callback cannot replace a known newer epoch with ' + String(oldEpoch), async () => {
  reset({ status: 'onboard' }); Object.assign(h.details, { onboard_at: BOARDING_AT }); await settle(Active);
  const newer = '2026-10-24T12:00:00.123457Z';
  Object.assign(h.trip, { onboard_at: newer, boarding_undo_pending: true, boarding_undo_request_id: 'request-B', boarding_undo_wait_sec: 80 }); Object.assign(h.details, { onboard_at: newer });
  await h.interval(); await settle(Active);
  h.tripHolds.push({ promise: Promise.resolve({ ...h.trip, onboard_at: oldEpoch, boarding_undo_pending: false, boarding_undo_request_id: 'stale-request', boarding_undo_wait_sec: 0, boarding_undo_sec: 300 }) });
  await h.interval(); await settle(Active);
  assert.equal(h.undoProps.terms.onboard_at, newer); assert.equal(h.undoProps.terms.boarding_undo_request_id, 'request-B');
  assert.equal(h.undoProps.terms.boarding_undo_pending, true); assert.equal(h.undoProps.terms.boarding_undo_wait_sec, 80); assert.equal(storedStage(42), 'onboard');
});
await test('duplicate epoch B still updates its terms, while new C rejects receipt B and accepts receipt C', async () => {
  reset({ status: 'onboard' }); Object.assign(h.details, { onboard_at: BOARDING_AT }); await settle(Active);
  const newer = '2026-10-24T12:00:00.123457Z', newest = '2026-10-24T12:00:00.123458Z';
  Object.assign(h.trip, { onboard_at: newer, boarding_undo_pending: true, boarding_undo_request_id: 'request-B', boarding_undo_wait_sec: 80 }); Object.assign(h.details, { onboard_at: newer });
  await h.interval(); await settle(Active);
  Object.assign(h.trip, { onboard_at: newer.replace('Z', '+00:00'), boarding_undo_wait_sec: 70 }); await h.interval(); await settle(Active);
  assert.equal(h.undoProps.terms.boarding_undo_wait_sec, 70, 'same UTC epoch must still accept its fresh terms');
  Object.assign(h.trip, { onboard_at: newest, boarding_undo_request_id: 'request-C' }); Object.assign(h.details, { onboard_at: newest }); await h.interval(); await settle(Active);
  const receiptB = { status: 'confirmed', onboard_at: null, boarding_undo_confirmed_id: 'receipt-B', boarding_undo_onboard_at: newer, boarding_undo_pending: false };
  h.tripHolds.push({ promise: Promise.resolve(receiptB) }); await h.interval(); await settle(Active);
  assert.equal(h.undoProps.terms.onboard_at, newest); assert.equal(h.undoProps.terms.boarding_undo_request_id, 'request-C');
  const receiptC = { ...receiptB, boarding_undo_confirmed_id: 'receipt-C', boarding_undo_onboard_at: newest };
  Object.assign(h.trip, receiptC); Object.assign(h.details, receiptC); await h.interval(); await settle(Active);
  assert.ok(text(h.tree).includes('Водитель отметит посадку')); assert.equal(storedStage(42), 'confirmed');
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
  await seedPass({ ...storedPass(42), bookingId: 43, boardingCode: '435435' });
  const other = structuredClone(storedPass(43));
  await driverMarksBoarding();
  assert.deepEqual(storedPass(43), other);
});
await test('unwritable storage hides the used code and aborts without altering a different booking', async () => {
  reset(); await settle(Active);
  await seedPass({ ...storedPass(42), bookingId: 43, boardingCode: '435435' });
  const other = structuredClone(storedPass(43)); h.failPassWrites = true; h.detailsError = true;
  h.trip.status = 'onboard'; await h.interval(); await settle(Active);
  assert(h.diskFenced);
  assert.equal(await h.passStorage.readOfflineTrip(42), null);
  assert.equal(codeCards().length, 0);
  assert.deepEqual(storedPass(43), other);
});

for (const observed of [
  { status: 'onboard', lang: 'ru' }, { status: 'onboard', lang: 'ba' },
  { status: 'done', lang: 'ru' }, { status: 'cancelled', lang: 'ru' },
]) await test(`${observed.lang}: late confirmed role cannot undo observed ${observed.status} while details fails`, async () => {
  reset({ lang: observed.lang }); await settle(Active);
  const confirmedReply = { ...h.trip }; let release;
  h.tripHolds.push({ promise: new Promise(resolve => { release = resolve; }) });
  const staleTick = h.interval(); await until(()=>h.tripHeldRequests.length>0); assert.deepEqual(h.tripHeldRequests, [42]);
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

await test('online boarding code still works when the offline database cannot be opened', async () => {
  reset(); h.denyPassRead = true;
  await settle(Active);
  assert(text(h.tree).includes('4931'));
  assert(h.codeRequests.includes(42));
  assert.equal((await h.passStorage.readOfflineTrip(42)).pass, null);
});

await test('an unseen boarding undo requests a fresh online code while local storage stays unavailable', async () => {
  reset(); h.denyPassRead = true;
  await settle(Active);
  assert(text(h.tree).includes('4931'));
  const before = h.codeRequests.length;
  let answer;
  h.codeHolds.set(42, { promise: new Promise(resolve => { answer = resolve; }) });
  Object.assign(h.trip, { boarding_undo_confirmed_id: 'fresh-receipt', boarding_undo_onboard_at: BOARDING_AT });
  await h.interval(); await settle(Active);
  assert(h.codeRequests.length > before);
  assert(!text(h.tree).includes('4931'));
  answer({ code: '5678' });
  await settle(Active);
  assert(text(h.tree).includes('5678'));
  assert.equal((await h.passStorage.readOfflineTrip(42)).pass, null);
});

await test('verified B cannot see A cached pass after details fails', async () => {
  reset(); await settle(Active); assert.equal(storedPass(42).ownerId,7); await settle(Empty);
  h.generation='B';h.userId=8;h.detailsError=true;h.tripError=true;await settle(Active);
  assert.ok(!text(h.tree).includes('QA Driver'));assert.equal(codeCards().length,0);
});
await test('verified session cannot promote a legacy unbound cached pass on details failure', async () => {
  reset();await settle(Active);await settle(Empty);
  const { ownerId, sessionGeneration, ...legacy } = storedPass(42);
  passFixture(); h.records.clear(); h.passes.clear();
  passValues.set('yuldash.tripPass.42',JSON.stringify(legacy));
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
  assert.equal(storedPass(42), null);
  assert.equal(storedStage(42), 'terminal'); assert.equal(codeCards().length, 0);
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
    if (role === 'driver') {
      assert.equal(h.locationShares.length, 0, 'серверный active сам не разрешает GPS');
      await button(lang === 'ru' ? 'Включить передачу места' : 'Урынды ебәреүҙе ҡабыҙырға').props.onClick();
      await settle(Active);
      assert.ok(h.locationShares.some(s => !s.stopped), 'настоящее явное нажатие включило передачу до проверки отмены');
    }
    const reads = h.requests.filter(r => r === 'details').length;
    let releaseGps;
    if (role === 'driver') h.gpsReadGate = new Promise(resolve => { releaseGps = resolve; });
    h.detailsError = true; h.trip.status = 'cancelled';
    await h.interval();
    incoming?.point({ lat: 55.777, lng: 56.888 }); // callback уже получен, effect cleanup ещё не отработал
    await settle(Active); assertCancelledPrivacy();
    // Старый carrier уже закрыт от authoritative /role, хотя fresh OWN GET ещё висит.
    // Ни часы, ни grace/HANDOFF здесь не исполнялись; все privacy asserts остаются немедленными.
    releaseGps?.(); h.gpsReadGate = null; await settle(Active); assertCancelledPrivacy();
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
    await until(()=>h.detailsHeldRequests.length>0); assert.deepEqual(h.detailsHeldRequests, [42], 'задержанный GET настоящего экрана действительно начался');
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
for (const instance of h.instances.values()) dispose(instance);
h.gps.stop();
delete globalThis.__rideshareLocal;
if (previousLocalStorage) Object.defineProperty(globalThis, 'localStorage', previousLocalStorage);
else delete globalThis.localStorage;
if (failed) process.exitCode = 1;
