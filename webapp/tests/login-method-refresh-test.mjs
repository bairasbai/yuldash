// Real LoginScreen + real flow; only HTTP/session/UI platform boundaries are synthetic.
import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount } from "./shallow-harness.mjs";

const t = checker();
const h = globalThis.__loginMethodRefresh = {};
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const pair = { access_token: "synthetic-A", refresh_token: "synthetic-RA", user: { id: 1 } };
globalThis.document = new EventTarget();
document.visibilityState = "visible";
globalThis.window = { open() {}, location: { pathname: "/login", href: "" } };

function reset() {
  Object.assign(h, {
    methods: ["sms", "telegram"], generation: "guest", logins: [], routes: 0,
    verify: deferred(), verifyCalls: 0, start: null, smsRequest: null, startCalls: 0, smsCalls: 0,
  });
}
const mocks = lang => ({
  "/i18n/lang": langMock(lang),
  "/api/client": `export class ApiError extends Error {} export const apiPost=async()=>({}),getSessionGeneration=()=>globalThis.__loginMethodRefresh.generation;`,
  "/api/auth": `const h=globalThis.__loginMethodRefresh;
    export const TELEGRAM_BOT='fixture',lastLoginMethods=()=>['sms','telegram'],fetchLoginMethods=async()=>h.methods;
    export const tgStart=()=>{h.startCalls++;return h.start?h.start.promise:Promise.resolve({request_id:'synthetic-request'});};
    export const tgVerify=()=>{h.verifyCalls++;return h.verify.promise;};
    export const requestSmsCode=()=>{h.smsCalls++;return h.smsRequest?h.smsRequest.promise:Promise.resolve({});};
    export const verifySmsCode=()=>{h.verifyCalls++;return h.verify.promise;};
    export const telegramChatUrl=()=>'',telegramStartUrl=()=>'';`,
  "/auth/AuthProvider": `export const useAuth=()=>({login:(access,refresh)=>{const h=globalThis.__loginMethodRefresh;h.logins.push([access,refresh]);return h.generation='session-'+h.logins.length;}});`,
  "react-router-dom": `export const useNavigate=()=>()=>{globalThis.__loginMethodRefresh.routes++;},useLocation=()=>({});`,
  "/analytics": "export const track=()=>{};",
});
const input = (s, kind) => all(s.tree).find(n => n.type === "input" && n.props.autoComplete === kind);
const submit = s => all(s.tree).find(n => n.type === "button" && n.props.className === "btn-primary login-primary");
async function refresh(s, methods) {
  h.methods = methods;
  document.dispatchEvent(new Event("visibilitychange"));
  await s.settle();
}
async function smsForm(s, lang) {
  button(s.tree, lang === "ru" ? "Войти по номеру" : "Телефон номеры аша").props.onClick();
  await s.settle();
  input(s, "tel").props.onChange({ target: { value: "12345" } });
  await s.settle();
}
async function codeForm(s, method, lang) {
  if (method === "telegram") {
    await button(s.tree, lang === "ru" ? "Войти через Telegram" : "Telegram аша инеү").props.onClick();
  } else {
    await smsForm(s, lang);
    await submit(s).props.onClick();
  }
  await s.settle();
  input(s, "one-time-code").props.onChange({ target: { value: "123456" } });
  await s.settle();
}

for (const lang of ["ru", "ba"]) {
  const Login = await bundleComponent("src/screens/LoginScreen.tsx", { mocks: mocks(lang), real: ["/utils/loginFlow"] });
  async function screenTest(label, run) {
    await t.test(`${lang}: ${label}`, async () => {
      reset();
      const s = mount(Login);
      try { await s.settle(); await run(s); }
      finally { s.unmount(); }
    });
  }
  for (const method of ["telegram", "sms"]) {
    const other = method === "telegram" ? "sms" : "telegram";
    for (const [label, methods, allowed] of [
      ["other method removed", [method], true],
      ["own method removed", [other], false],
      ["all methods removed", [], false],
      ["only order changed", ["telegram", "sms"], true],
    ]) {
      await screenTest(`${method} verify: ${label}`, async s => {
        await codeForm(s, method, lang);
        const verifyButton = submit(s);
        const waiting = verifyButton.props.onClick();
        await verifyButton.props.onClick();
        await refresh(s, methods);
        const stillBusy = allowed ? submit(s).props.disabled : null;
        h.verify.resolve(pair);
        await waiting;
        await s.settle();
        assert.equal(h.verifyCalls, 1, "Repeated action must not verify the one-time code twice");
        assert.equal(h.logins.length, allowed ? 1 : 0);
        assert.equal(h.routes, allowed ? 1 : 0);
        if (allowed) assert.equal(stillBusy, true, "Allowed pending login must remain busy");
      });
    }
    for (const removeOwn of [false, true]) {
      await screenTest(`${method} request: ${removeOwn ? "own" : "other"} method removed`, async s => {
        const request = deferred();
        let waiting;
        if (method === "telegram") {
          h.start = request;
          waiting = button(s.tree, lang === "ru" ? "Войти через Telegram" : "Telegram аша инеү").props.onClick();
        } else {
          await smsForm(s, lang);
          h.smsRequest = request;
          waiting = submit(s).props.onClick();
        }
        await refresh(s, [removeOwn ? other : method]);
        request.resolve({ request_id: "synthetic-held" });
        await waiting;
        await s.settle();
        assert.equal(!!input(s, "one-time-code"), !removeOwn);
        assert.equal(h.logins.length, 0);
      });
    }
    for (const changedTo of ["logged-out", "account-B"]) {
      await screenTest(`${method}: late verify after ${changedTo} cannot publish`, async s => {
        await codeForm(s, method, lang);
        const waiting = submit(s).props.onClick();
        h.generation = changedTo;
        h.verify.resolve(pair);
        await waiting;
        assert.deepEqual(h.logins, []);
        assert.equal(h.routes, 0);
      });
    }
  }
  await screenTest("late verify after unmount cannot publish", async s => {
    await codeForm(s, "telegram", lang);
    const waiting = submit(s).props.onClick();
    s.unmount();
    h.verify.resolve(pair);
    await waiting;
    assert.deepEqual(h.logins, []);
    assert.equal(h.routes, 0);
  });
  for (const rejectOld of [false, true]) {
    await screenTest(`old ${rejectOld ? "failure" : "success"} cannot finish a newer login`, async s => {
      await codeForm(s, "telegram", lang);
      const oldReply = h.verify;
      const old = submit(s).props.onClick();
      button(s.tree, lang === "ru" ? "Назад" : "Артҡа").props.onClick();
      await s.settle();
      h.verify = deferred();
      await codeForm(s, "telegram", lang);
      const current = submit(s).props.onClick();
      if (rejectOld) oldReply.reject(Error("synthetic late network failure"));
      else oldReply.resolve(pair);
      await old;
      await s.settle();
      assert.deepEqual(h.logins, []);
      assert.equal(submit(s).props.disabled, true, "Old finally must not clear the new operation's busy flag");
      h.verify.resolve({ ...pair, access_token: "synthetic-B" });
      await current;
      assert.deepEqual(h.logins, [["synthetic-B", pair.refresh_token]]);
      assert.equal(h.routes, 1);
    });
  }
}
t.done();
