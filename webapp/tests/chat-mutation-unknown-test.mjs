// Actual TripChat edit/delete handlers with controlled adapters; no browser/DB delivery claim.
import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const t = checker();
const h = globalThis.__chatMutationUnknown = {};
const original = { id: 17, text: "Исходное сообщение", sender_id: 7, deleted: false, edited: false };
globalThis.document = { body: { style: { overflow: "" } }, visibilityState: "visible", addEventListener() {}, removeEventListener() {} };
globalThis.window = { setTimeout: () => 1, clearTimeout() {}, setInterval: () => 2, clearInterval() {}, addEventListener() {}, removeEventListener() {} };

const mocks = lang => ({
  "/i18n/lang": langMock(lang),
  "react-router-dom": `export const useNavigate=()=>()=>{},useParams=()=>({id:'42'}),useLocation=()=>({key:'default',state:null});`,
  "/auth/AuthProvider": `export const useAuth=()=>({user:{id:7}});`,
  "/api/client": `export const getSessionGeneration=()=> 'synthetic-owner';`,
  "/api/bookings": `export const fetchBookingDetails=async()=>({role:'passenger',driver_name:'Синтетический водитель',from_city:'Сибай',to_city:'Акъяр'});`,
  "/api/chat": `export const fetchMessages=async()=>[{...globalThis.__chatMutationUnknown.initial}];
    export const openBookingChat=()=>({close(){},send:async()=>null}),sendMessageRest=async()=>{throw Error('unexpected send');};
    export const editMessage=(booking,id,value)=>{const h=globalThis.__chatMutationUnknown;h.edits.push({booking,id,value});return h.edit(value);};
    export const deleteMessage=(booking,id,scope)=>{const h=globalThis.__chatMutationUnknown;h.deletes.push({booking,id,scope});return h.remove(scope);};`,
  "/utils/outbox": `export const acknowledgeMessage=async()=>{},createOutboxId=()=> 'unused',enqueue=async()=>{},isPermanentRejection=()=>false,outboxCount=()=>0,subscribeOutbox=()=>()=>{},watchOutbox=()=>()=>{};`,
});
function reset() { Object.assign(h, { initial: { ...original }, server: { ...original }, edits: [], deletes: [], edit: async () => {}, remove: async () => {} }); }
const menu = (s, lang) => all(s.tree).find(n => n.type === "button" && n.props["aria-label"] === (lang === "ru" ? "Действия с сообщением" : "Хәбәр менән эштәр"));
const editInput = (s, lang) => all(s.tree).find(n => n.type === "input" && n.props["aria-label"] === (lang === "ru" ? "Поправить сообщение" : "Хәбәрҙе төҙәтеү"));
const body = s => all(s.tree).find(n => n.type === "ChatMessageBody");
const status = (s, phrase) => all(s.tree).find(n => n.props.role === "status" && text(n) === phrase);

for (const lang of ["ru", "ba"]) {
  const Screen = await bundleComponent("src/screens/TripChatScreen.tsx", { mocks: mocks(lang), real: ["/utils/tripChat.js"] });
  async function openChat() {
    const outer = mount(Screen); await outer.settle(); const child = outer.tree;
    assert.equal(typeof child.type, "function", "screen did not return actual TripChat"); outer.unmount();
    const s = mount(child.type, child.props); await s.settle(); assert.equal(body(s)?.props.text, original.text); return s;
  }
  async function openMenu(s) { menu(s, lang).props.onClick(); await s.settle(); }
  await t.test(`${lang}: committed edit with lost ACK preserves draft, states uncertainty and allows explicit retry`, async () => {
    reset(); h.edit = async value => {
      h.server = { ...h.server, text: value, edited: true };
      if (h.edits.length === 1) throw Error("lost acknowledgement after simulated edit commit");
      return { ...h.server };
    };
    const s = await openChat();
    try {
      await openMenu(s); button(s.tree, lang === "ru" ? "Поправить" : "Төҙәтергә").props.onClick(); await s.settle();
      editInput(s, lang).props.onChange({ target: { value: "Исправленный текст" } }); await s.settle();
      button(s.tree, lang === "ru" ? "Сохранить" : "Һаҡлау").props.onClick(); await s.settle();
      assert.equal(h.server.text, "Исправленный текст"); assert.equal(editInput(s, lang)?.props.value, "Исправленный текст");
      assert.ok(status(s, lang === "ru" ? "Не удалось подтвердить изменение. Проверь связь и повтори." : "Үҙгәртеүҙе раҫлап булманы. Бәйләнеште тикшереп ҡабатла."));
      assert.equal(all(s.tree).some(n => n.props.className === "bubble__edited"), false, "unknown ACK was presented as confirmed");
      assert.deepEqual(h.edits, [{ booking: 42, id: 17, value: "Исправленный текст" }], "unexpected automatic retry");
      button(s.tree, lang === "ru" ? "Сохранить" : "Һаҡлау").props.onClick(); await s.settle();
      assert.equal(editInput(s, lang), undefined); assert.equal(body(s)?.props.text, "Исправленный текст");
      assert.equal(all(s.tree).some(n => n.props.role === "status" && /раҫлап булманы|Не удалось подтвердить/.test(text(n))), false, "confirmed retry must clear the previous uncertainty");
      assert.deepEqual(h.edits, [{ booking: 42, id: 17, value: "Исправленный текст" }, { booking: 42, id: 17, value: "Исправленный текст" }]);
    } finally { s.unmount(); }
  });

  for (const scope of ["all", "me"]) await t.test(`${lang}/${scope}: lost delete ACK leaves unconfirmed row and explicit retry completes requested scope`, async () => {
    reset(); h.remove = async () => {
      h.server = { ...h.server, deleted: scope === "all" };
      if (h.deletes.length === 1) throw Error("lost acknowledgement after simulated delete commit");
      return { ...h.server };
    };
    const s = await openChat();
    try {
      const label = scope === "all" ? (lang === "ru" ? "Удалить у всех" : "Барыһынан юйырға") : (lang === "ru" ? "Скрыть у себя" : "Үҙемдән йәшерергә");
      await openMenu(s); button(s.tree, label).props.onClick(); await s.settle();
      assert.equal(body(s)?.props.text, original.text, "unknown ACK was presented as confirmed removal");
      assert.ok(status(s, lang === "ru" ? "Не удалось подтвердить удаление. Проверь связь и повтори." : "Юйыуҙы раҫлап булманы. Бәйләнеште тикшереп ҡабатла."));
      assert.deepEqual(h.deletes, [{ booking: 42, id: 17, scope }], "unexpected automatic retry");
      await openMenu(s); button(s.tree, label).props.onClick(); await s.settle();
      assert.equal(body(s), undefined);
      assert.equal(all(s.tree).some(n => n.props.role === "status" && /раҫлап булманы|Не удалось подтвердить/.test(text(n))), false, "confirmed retry must clear the previous uncertainty");
      assert.equal(text(s.tree).includes(lang === "ru" ? "Сообщение удалено" : "Хәбәр юйылды"), scope === "all");
      assert.deepEqual(h.deletes, [{ booking: 42, id: 17, scope }, { booking: 42, id: 17, scope }]);
    } finally { s.unmount(); }
  });
}
t.done();
