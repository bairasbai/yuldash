// Actual TripChat component and asynchronous adapters. No browser/server delivery claim.
import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const tests = checker();
const h = globalThis.__chatHistoryLive = {};
const original = { id: 17, text: "original saved row", sender_id: 7, deleted: false, edited: false };
const other = { id: 18, text: "new live row", sender_id: 8, deleted: false, edited: false };
const clone = list => list.map(row => ({ ...row }));
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
globalThis.document = { body: { style: { overflow: "" } }, visibilityState: "visible", addEventListener() {}, removeEventListener() {} };
globalThis.window = {
  setTimeout: () => 1, clearTimeout() {},
  setInterval: (fn, ms) => { const id = ++h.timerSerial; h.intervals.set(id, { fn, ms }); return id; },
  clearInterval: id => h.intervals.delete(id), addEventListener() {}, removeEventListener() {},
};
function reset() {
  Object.assign(h, {
    generation: "synthetic-owner-A", server: [{ ...original }], gets: 0, inFlight: 0, maxConcurrent: 0,
    posts: [], deletes: [], edits: [], acknowledgements: [], enqueues: [], intervals: new Map(), timerSerial: 0,
    fetch: () => clone(h.server), post: async () => { throw Error("Unexpected send"); },
    edit: async () => { throw Error("Unexpected edit"); },
    remove: async id => { h.server = h.server.filter(row => row.id !== id); return { ...original }; },
  });
}
const mocks = lang => ({
  "/i18n/lang": langMock(lang),
  "react-router-dom": `export const useNavigate=()=>()=>{},useParams=()=>({id:'42'}),useLocation=()=>({key:'default',state:null});`,
  "/auth/AuthProvider": `export const useAuth=()=>({user:{id:7}});`,
  "/api/client": `export const getSessionGeneration=()=>globalThis.__chatHistoryLive.generation;`,
  "/api/bookings": `export const fetchBookingDetails=async()=>({role:'passenger',driver_name:'Synthetic driver',from_city:'Сибай',to_city:'Акъяр'});`,
  "/api/chat": `export const fetchMessages=(booking)=>{const h=globalThis.__chatHistoryLive;h.gets++;h.inFlight++;h.maxConcurrent=Math.max(h.maxConcurrent,h.inFlight);let result;
      try{result=h.fetch(h.gets,booking);}catch(error){result=Promise.reject(error);}return Promise.resolve(result).finally(()=>h.inFlight--);};
    export const openBookingChat=(booking,handlers)=>{const h=globalThis.__chatHistoryLive;h.handlers=handlers;handlers.onOpen?.();return{close(){},send:async()=>null};};
    export const sendMessageRest=(...args)=>{const h=globalThis.__chatHistoryLive;h.posts.push(args);return h.post(...args);};
    export const editMessage=(booking,id,value)=>{const h=globalThis.__chatHistoryLive;h.edits.push([booking,id,value]);return h.edit(id,value);};
    export const deleteMessage=(booking,id,scope)=>{const h=globalThis.__chatHistoryLive;h.deletes.push([booking,id,scope]);return h.remove(id,scope);};`,
  "/utils/outbox": `export const acknowledgeMessage=async(...args)=>{globalThis.__chatHistoryLive.acknowledgements.push(args);},createOutboxId=()=> 'history-live-intent',
    enqueue=async(...args)=>{globalThis.__chatHistoryLive.enqueues.push(args);},isPermanentRejection=()=>false,outboxCount=()=>0,
    subscribeOutbox=()=>()=>{},watchOutbox=fn=>{globalThis.__chatHistoryLive.flush=fn;return()=>{};};`,
});
const bodies = s => all(s.tree).filter(n => n.type === "ChatMessageBody").map(n => n.props.text);
function changed() {
  assert.equal(typeof h.handlers.onHistoryChanged, "function", "history_changed handler is absent");
  h.handlers.onHistoryChanged();
}
const sourceRow = (s, lang) => all(s.tree).find(n => n.type === "button" && n.props["aria-label"] === (lang === "ru" ? "Действия с сообщением" : "Хәбәр менән эштәр"));
async function hide(s, lang) {
  assert.ok(sourceRow(s, lang), "saved row action menu is absent");
  sourceRow(s, lang).props.onClick(); await s.settle();
  const remove = button(s.tree, lang === "ru" ? "Скрыть у себя" : "Үҙемдән йәшерергә");
  assert.ok(remove, "hide-only action is absent"); remove.props.onClick(); await s.settle();
}

for (const lang of ["ru", "ba"]) {
  const Screen = await bundleComponent("src/screens/TripChatScreen.tsx", { mocks: mocks(lang), real: ["/utils/tripChat.js"] });
  async function open() {
    const outer = mount(Screen); await outer.settle(); const child = outer.tree; outer.unmount();
    assert.equal(typeof child.type, "function");
    const s = mount(child.type, child.props); await s.settle();
    assert.deepEqual(bodies(s), [original.text]); assert.equal(h.gets, 1); return s;
  }
  await tests.test(`${lang}: live edit and delete-all reload the authorized history instead of replacing a duplicate frame`, async () => {
    reset(); const s = await open();
    try {
      h.handlers.onMessage({ ...original, text: "stale duplicate" }); await s.settle();
      assert.deepEqual(bodies(s), [original.text]);
      h.server = [{ ...original, text: "edited server row", edited: true }]; changed(); await s.settle();
      assert.equal(h.gets, 2); assert.deepEqual(bodies(s), ["edited server row"]);
      h.server = [{ ...original, text: "", deleted: true }]; changed(); await s.settle();
      assert.equal(h.gets, 3); assert.deepEqual(bodies(s), []);
      assert.match(text(s.tree), lang === "ru" ? /Сообщение удалено/ : /Хәбәр юйылды/);
      h.handlers.onMessage({ ...original }); await s.settle(); assert.deepEqual(bodies(s), []);
    } finally { s.unmount(); }
  });
  for (const operation of ["edit", "all"]) await tests.test(`${lang}: own ${operation} confirmation remains visible when its earlier invalidation GET fails`, async () => {
    reset(); const s = await open(), receipt = deferred();
    const confirmed = operation === "edit"
      ? { ...original, text: "confirmed own edit", edited: true }
      : { ...original, text: "", deleted: true };
    try {
      h.fetch = () => Promise.reject(Error("synthetic refresh failed after own mutation"));
      const mutate = () => { h.server = [{ ...confirmed }]; changed(); return receipt.promise; };
      if (operation === "edit") h.edit = mutate;
      else h.remove = (_id, scope) => { assert.equal(scope, "all"); return mutate(); };
      sourceRow(s, lang).props.onClick(); await s.settle();
      if (operation === "edit") {
        button(s.tree, lang === "ru" ? "Поправить" : "Төҙәтергә").props.onClick(); await s.settle();
        all(s.tree).find(n => n.type === "input" && n.props["aria-label"] === (lang === "ru" ? "Поправить сообщение" : "Хәбәрҙе төҙәтеү"))
          .props.onChange({ target: { value: confirmed.text } }); await s.settle();
        button(s.tree, lang === "ru" ? "Сохранить" : "Һаҡлау").props.onClick();
      } else button(s.tree, lang === "ru" ? "Удалить у всех" : "Барыһынан юйырға").props.onClick();
      await s.settle(); assert.equal(h.gets, 2);
      if (operation === "edit") {
        // While its REST receipt is pending, this row renders the edit control
        // in place of ChatMessageBody. The confirmed body is asserted below.
        const editor = all(s.tree).find(n => n.type === "input" && n.props["aria-label"] === (lang === "ru" ? "Поправить сообщение" : "Хәбәрҙе төҙәтеү"));
        assert.ok(editor, "pending edit lost its editor"); assert.equal(editor.props.value, confirmed.text);
        assert.deepEqual(bodies(s), []);
      } else assert.deepEqual(bodies(s), [original.text]);
      receipt.resolve({ ...confirmed }); await s.settle();
      assert.deepEqual(bodies(s), operation === "edit" ? [confirmed.text] : [], "confirmed known row was discarded because refresh failed");
      if (operation === "edit") assert.equal(all(s.tree).some(n => n.type === "input" && n.props["aria-label"] === (lang === "ru" ? "Поправить сообщение" : "Хәбәрҙе төҙәтеү")), false, "confirmed edit did not close its editor");
      if (operation === "all") assert.match(text(s.tree), lang === "ru" ? /Сообщение удалено/ : /Хәбәр юйылды/);
      const retry = button(s.tree, lang === "ru" ? "Повторить" : "Ҡабатлау"); assert.ok(retry);
      assert.ok(all(s.tree).some(n => n.props.role === "alert"));
      const changes = h.posts.length + h.edits.length + h.deletes.length; assert.equal(changes, 1);
      h.fetch = () => clone(h.server); retry.props.onClick(); await s.settle();
      assert.equal(h.posts.length + h.edits.length + h.deletes.length, changes, "history retry repeated a mutation");
      assert.deepEqual(bodies(s), operation === "edit" ? [confirmed.text] : []);
      assert.equal(h.maxConcurrent, 1);
    } finally { receipt.resolve({ ...confirmed }); s.unmount(); }
  });
  await tests.test(`${lang}: hide in another own window suppresses known and previously unseen late frames`, async () => {
    reset(); const s = await open();
    try {
      h.server = []; changed(); await s.settle(); assert.deepEqual(bodies(s), []);
      h.handlers.onMessage({ ...original }); await s.settle(); assert.deepEqual(bodies(s), []);
      h.handlers.onMessage({ ...other }); await s.settle();
      assert.deepEqual(bodies(s), [], "unseen delayed frame bypassed authoritative history"); assert.equal(h.gets, 3);
      assert.equal(h.maxConcurrent, 1);
    } finally { s.unmount(); }
  });
  await tests.test(`${lang}: repeated invalidations coalesce and discard a superseded held GET`, async () => {
    reset(); const s = await open(), held = deferred();
    try {
      h.fetch = call => call === 2 ? held.promise : clone(h.server);
      h.server = [{ ...original, text: "superseded edit" }]; changed(); await s.settle();
      h.server = [{ ...original, text: "latest edit" }]; changed(); changed(); await s.settle();
      assert.equal(h.gets, 2); assert.equal(h.maxConcurrent, 1); assert.deepEqual(bodies(s), [original.text]);
      held.resolve([{ ...original, text: "superseded edit" }]); await s.settle();
      assert.equal(h.gets, 3); assert.equal(h.maxConcurrent, 1); assert.deepEqual(bodies(s), ["latest edit"]);
    } finally { held.resolve([]); s.unmount(); }
  });
  await tests.test(`${lang}: a new message arriving during a held GET is not misclassified as hidden`, async () => {
    reset(); const s = await open(), held = deferred();
    try {
      h.fetch = call => call === 2 ? held.promise : clone(h.server); changed(); await s.settle();
      h.server = [{ ...original }, { ...other }]; h.handlers.onMessage({ ...other }); await s.settle();
      assert.equal(h.gets, 2); held.resolve([{ ...original }]); await s.settle();
      assert.deepEqual(bodies(s), [original.text, other.text]); assert.equal(h.gets, 3); assert.equal(h.maxConcurrent, 1);
      h.handlers.onMessage({ ...other, text: "late duplicate must not overwrite" }); await s.settle();
      assert.deepEqual(bodies(s), [original.text, other.text]);
    } finally { held.resolve([]); s.unmount(); }
  });
  await tests.test(`${lang}: successful local hide fences a held pre-hide history and ordinary late echo`, async () => {
    reset(); const s = await open(), held = deferred();
    try {
      h.fetch = call => call === 2 ? held.promise : clone(h.server); h.flush(); await s.settle(); assert.equal(h.gets, 2);
      await hide(s, lang); assert.deepEqual(bodies(s), []);
      held.resolve([{ ...original }]); await s.settle(); assert.deepEqual(bodies(s), []);
      h.handlers.onMessage({ ...original }); await s.settle(); assert.deepEqual(bodies(s), []); assert.equal(h.maxConcurrent, 1);
    } finally { held.resolve([]); s.unmount(); }
  });
  await tests.test(`${lang}: normal-mode new live row survives an older in-flight outbox snapshot`, async () => {
    reset(); const s = await open(), held = deferred();
    try {
      h.fetch = call => call === 2 ? held.promise : clone(h.server); h.flush(); await s.settle();
      h.server = [{ ...original }, { ...other }]; h.handlers.onMessage({ ...other }); await s.settle();
      assert.deepEqual(bodies(s), [original.text, other.text], "normal live delivery was unnecessarily delayed");
      held.resolve([{ ...original }]); await s.settle();
      assert.deepEqual(bodies(s), [original.text, other.text]); assert.equal(h.gets, 3); assert.equal(h.maxConcurrent, 1);
    } finally { held.resolve([]); s.unmount(); }
  });
  await tests.test(`${lang}: polling, outbox refresh and invalidation share the same in-flight read`, async () => {
    reset(); const s = await open(), held = deferred();
    try {
      h.fetch = call => call === 2 ? held.promise : clone(h.server); h.flush(); await s.settle();
      h.handlers.onClose(); await s.settle();
      const poll = [...h.intervals.values()].find(timer => timer.ms === 7000); assert.ok(poll);
      poll.fn(); h.flush(); changed(); await s.settle(); assert.equal(h.gets, 2); assert.equal(h.maxConcurrent, 1);
      held.resolve([{ ...original }]); await s.settle(); assert.equal(h.gets, 3); assert.equal(h.maxConcurrent, 1);
    } finally { held.resolve([]); s.unmount(); }
  });
  await tests.test(`${lang}: three unchanged seven-second poll ticks do not invalidate their own slow GET`, async () => {
    reset(); const s = await open(), held = deferred(), unintendedFollowUp = deferred();
    try {
      h.handlers.onClose(); await s.settle();
      const poll = [...h.intervals.values()].find(timer => timer.ms === 7000); assert.ok(poll);
      h.fetch = call => call === 2 ? held.promise : unintendedFollowUp.promise;
      poll.fn(); await s.settle(); assert.equal(h.gets, 2);
      poll.fn(); poll.fn(); poll.fn(); await s.settle(); assert.equal(h.gets, 2); assert.equal(h.maxConcurrent, 1);
      held.resolve([{ ...original, text: "accepted slow history" }]); await s.settle();
      assert.equal(h.gets, 2, "unchanged polling made the slow response stale and caused another GET");
      assert.deepEqual(bodies(s), ["accepted slow history"]);
    } finally { s.unmount(); held.resolve([]); unintendedFollowUp.resolve([]); }
  });
  await tests.test(`${lang}: failed refresh keeps nonempty history and explicit retry only performs GET`, async () => {
    reset(); const s = await open();
    try {
      h.fetch = call => call === 2 ? Promise.reject(Error("synthetic history offline")) : clone(h.server);
      changed(); await s.settle(); assert.deepEqual(bodies(s), [original.text]);
      const retry = button(s.tree, lang === "ru" ? "Повторить" : "Ҡабатлау"); assert.ok(retry, "nonempty failed history has no retry");
      assert.ok(all(s.tree).some(n => n.props.role === "alert"), "refresh error is not visible/accessibly announced");
      h.server = [{ ...original, text: "recovered history" }]; retry.props.onClick(); await s.settle();
      assert.equal(h.gets, 3); assert.deepEqual(bodies(s), ["recovered history"]);
      assert.equal(h.posts.length + h.edits.length + h.deletes.length, 0);
    } finally { s.unmount(); }
  });
  await tests.test(`${lang}: late REST text receipt is acknowledged but not shown before authorized history`, async () => {
    reset(); const s = await open(), receipt = deferred();
    try {
      h.post = () => receipt.promise;
      const input = all(s.tree).find(n => n.type === "input" && n.props["aria-label"] === (lang === "ru" ? "Сообщение" : "Хәбәр"));
      input.props.onChange({ target: { value: "late own receipt" } }); await s.settle();
      all(s.tree).find(n => n.type === "button" && n.props["aria-label"] === (lang === "ru" ? "Отправить" : "Ебәреү")).props.onClick(); await s.settle();
      assert.equal(h.posts.length, 1); await hide(s, lang); assert.deepEqual(bodies(s), []);
      receipt.resolve({ id: 19, sender_id: 7, text: "late own receipt" }); await s.settle();
      assert.deepEqual(bodies(s), []); assert.equal(h.acknowledgements.length, 1); assert.equal(h.posts.length, 1);
      assert.ok(h.gets >= 2); assert.equal(h.maxConcurrent, 1);
    } finally { receipt.resolve({ id: 19, sender_id: 7, text: "late own receipt" }); s.unmount(); }
  });
  await tests.test(`${lang}: own WS acknowledgement clears outbox separately from unknown-row display`, async () => {
    reset(); const s = await open();
    try {
      await hide(s, lang);
      const receipt = { id: 19, sender_id: 7, text: "late owned echo", request_id: "owned-queued-intent" };
      h.handlers.onAcknowledged(receipt); h.handlers.onMessage(receipt); await s.settle();
      assert.deepEqual(bodies(s), []); assert.equal(h.acknowledgements.length, 1);
      assert.equal(h.acknowledgements[0][1], receipt.request_id); assert.equal(h.posts.length, 0); assert.ok(h.gets >= 2);
    } finally { s.unmount(); }
  });
  await tests.test(`${lang}: late voice receipt cannot expose a row absent from authorized history`, async () => {
    reset(); const s = await open(), receipt = deferred(); const voiceUrl = "https://synthetic.invalid/voice/history-live.m4a";
    try {
      h.post = () => receipt.promise;
      all(s.tree).find(n => n.type === "ChatVoiceButton").props.onSend(voiceUrl); await s.settle();
      assert.equal(h.posts.length, 1); await hide(s, lang);
      receipt.resolve({ id: 20, sender_id: 7, text: "", voice_url: voiceUrl }); await s.settle();
      assert.deepEqual(bodies(s), []); assert.equal(all(s.tree).filter(n => n.type === "VoiceBubble").length, 0);
      assert.ok(h.gets >= 2); assert.equal(h.posts.length, 1); assert.equal(h.maxConcurrent, 1);
    } finally { receipt.resolve({ id: 20, sender_id: 7, text: "", voice_url: voiceUrl }); s.unmount(); }
  });
  await tests.test(`${lang}: unknown voice reconciliation and live invalidation share the held history request`, async () => {
    reset(); const s = await open(), held = deferred();
    const voiceUrl = "https://synthetic.invalid/voice/reconcile-live.m4a";
    const voice = { id: 20, sender_id: 7, text: "", voice_url: voiceUrl };
    try {
      h.fetch = call => call === 2 ? held.promise : clone(h.server);
      h.post = async () => { throw Error("synthetic lost voice reply"); };
      all(s.tree).find(n => n.type === "ChatVoiceButton").props.onSend(voiceUrl); await s.settle();
      assert.equal(h.gets, 2); assert.equal(h.posts.length, 1);
      h.server = [{ ...original }, voice]; changed(); await s.settle(); assert.equal(h.gets, 2);
      held.resolve([{ ...original }]); await s.settle();
      assert.equal(h.gets, 3); assert.equal(h.maxConcurrent, 1); assert.equal(h.posts.length, 1);
      assert.equal(all(s.tree).filter(n => n.type === "VoiceBubble").length, 1);
      assert.equal(button(s.tree, lang === "ru" ? "Повторить отправку" : "Ҡабат ебәреү"), undefined);
    } finally { held.resolve([]); s.unmount(); }
  });
  for (const boundary of ["owner", "unmount"]) await tests.test(`${lang}: ${boundary} fences held history and retained invalidation`, async () => {
    reset(); const s = await open(), held = deferred();
    try {
      h.fetch = call => call === 2 ? held.promise : clone(h.server); changed(); await s.settle();
      const retained = h.handlers.onHistoryChanged;
      if (boundary === "owner") h.generation = "synthetic-owner-B"; else s.unmount();
      retained(); held.resolve([{ ...original, text: "private late old owner row" }]); await s.settle();
      assert.equal(h.gets, 2); assert.ok(!bodies(s).includes("private late old owner row"));
    } finally { held.resolve([]); s.unmount(); }
  });
}
tests.done();
