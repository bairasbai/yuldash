// Real LoginScreen, loginFlow, AuthProvider, session storage and storageHealth.
// HTTP, React rendering, browser storage and Web Locks scheduling are synthetic.
import assert from "node:assert/strict";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import { IDBFactory } from "fake-indexeddb";
import { all, bundleComponent, button, checker, langMock, mount } from "./shallow-harness.mjs";

const t = checker();
const root = fileURLToPath(new URL("../", import.meta.url));
const h = globalThis.__loginPublicationCancel = {};
const pair = { access_token: "synthetic-A", refresh_token: "synthetic-RA", user: { id: 1 } };
let moduleId = 0;
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };
const providerMocks = {
  react: `const h=globalThis.__loginPublicationCancel;
    export const createContext=()=>({Provider:'provider'}),useContext=()=>null;
    export const useState=v=>[typeof v==='function'?v():v,()=>{}],useRef=v=>({current:v});
    export const useEffect=()=>{},useCallback=fn=>fn,useMemo=fn=>fn();`,
  "react/jsx-runtime": "export const jsx=(type,props)=>({type,props}),jsxs=jsx;",
  "/api/auth": "export const fetchMe=async()=>({}),refreshSession=async()=>true,logoutServer=()=>globalThis.__loginPublicationCancel.logoutServer();",
  "/push/webPush": "export const disableWebPush=()=>globalThis.__loginPublicationCancel.disableWebPush();",
  "/utils/outbox": "export const clearOutbox=async()=>{};",
  "/utils/formDraft": "export const clearAllDrafts=()=>{};",
  "/utils/privacy": "export const clearPersonalLocal=()=>{},syncPersonalSession=()=>{};",
  "/utils/sessionCaches": "export const clearSessionCaches=async()=>{};",
};
async function fixture() {
  Object.assign(h, {
    queue: [], routes: 0, opens: 0, methods: ["sms", "telegram"], pair, writes: [],
    verify: null, start: null, smsRequest: null,
    disableWebPush: async () => {}, logoutServer: async () => ({ ok: true }),
  });
  const values = new Map();
  globalThis.indexedDB = new IDBFactory();
  globalThis.localStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => { values.set(key, String(value)); h.writes.push([key, String(value)]); },
    removeItem: key => values.delete(key),
  };
  globalThis.window = new EventTarget();
  window.open = () => { h.opens++; };
  window.location = { pathname: "/login", href: "" };
  globalThis.document = new EventTarget();
  document.visibilityState = "visible";
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: { locks: {
    request: (name, work) => new Promise((resolve, reject) => {
      h.queue.push({ name, release: () => { try { resolve(work()); } catch (error) { reject(error); } } });
    }),
  } } });
  const result = await build({
    stdin: { contents: "export {AuthProvider} from './src/auth/AuthProvider';export * from './src/api/client';export * from './src/utils/storageHealth';", resolveDir: root, loader: "ts" },
    bundle: true, write: false, format: "esm", platform: "node", jsx: "automatic",
    define: { "import.meta.env": '{"VITE_API_BASE":"http://qa.invalid"}' },
    footer: { js: `// isolated-publication-${++moduleId}` },
    plugins: [{ name: "provider-platform", setup(b) {
      b.onResolve({ filter: /.*/ }, args => {
        const key = Object.keys(providerMocks).find(key => args.path === key || args.path.endsWith(key));
        return key ? { path: key, namespace: "provider-platform" } : undefined;
      });
      b.onLoad({ filter: /.*/, namespace: "provider-platform" }, args => ({ contents: providerMocks[args.path], loader: "js" }));
    } }],
  });
  const api = await import("data:text/javascript;base64," + Buffer.from(result.outputFiles[0].text).toString("base64"));
  api.activateStorageFence();
  h.api = api;
  h.auth = api.AuthProvider({ children: null }).props.value;
  const Login = await bundleComponent("src/screens/LoginScreen.tsx", { real: ["/utils/loginFlow"], mocks: {
    "/i18n/lang": langMock("ru"),
    "/api/client": "export class ApiError extends Error {} export const apiPost=async()=>({}),getSessionGeneration=()=>globalThis.__loginPublicationCancel.api.getSessionGeneration();",
    "/api/auth": `const h=globalThis.__loginPublicationCancel;
      export const TELEGRAM_BOT='fixture',lastLoginMethods=()=>['sms','telegram'],fetchLoginMethods=async()=>h.methods;
      export const tgStart=()=>h.start?h.start.promise:Promise.resolve({request_id:'synthetic-request'}),tgVerify=()=>h.verify?h.verify.promise:Promise.resolve(h.pair);
      export const requestSmsCode=()=>h.smsRequest?h.smsRequest.promise:Promise.resolve({}),verifySmsCode=()=>h.verify?h.verify.promise:Promise.resolve(h.pair);
      export const telegramChatUrl=()=>'',telegramStartUrl=()=>'';`,
    "/auth/AuthProvider": "export const useAuth=()=>globalThis.__loginPublicationCancel.auth;",
    "react-router-dom": "export const useNavigate=()=>()=>{globalThis.__loginPublicationCancel.routes++;},useLocation=()=>({});",
    "/analytics": "export const track=()=>{};",
  } });
  const s = mount(Login);
  await s.settle();
  return { s, api, values, Login };
}
const submit = s => all(s.tree).find(n => n.type === "button" && n.props.className === "btn-primary login-primary");
const input = (s, kind) => all(s.tree).find(n => n.type === "input" && n.props.autoComplete === kind);
async function verify(s, method = "telegram") {
  if (method === "telegram") await button(s.tree, "Войти через Telegram").props.onClick();
  else {
    button(s.tree, "Войти по номеру").props.onClick(); await s.settle();
    input(s, "tel").props.onChange({ target: { value: "12345" } }); await s.settle();
    await submit(s).props.onClick();
  }
  await s.settle();
  input(s, "one-time-code").props.onChange({ target: { value: "123456" } }); await s.settle();
  return { pending: submit(s).props.onClick() };
}
async function screenTest(label, run) {
  await t.test(label, async () => {
    const f = await fixture();
    try { await run(f); }
    finally { f.s.unmount(); }
  });
}
async function queuedVerify(s, method) {
  const operation = await verify(s, method);
  await s.settle();
  assert.equal(h.queue.length, 1, "Publication must wait for the real sessionMutation Web Lock");
  assert.equal(h.api.getToken(), null);
  return operation;
}
function releasePublication() { h.queue.shift().release(); }
async function drainSessionWork() {
  for (let count = 0; h.queue.length; count++) {
    assert.ok(count < 20, "Session work must not enqueue an endless retry loop");
    releasePublication();
    await new Promise(setImmediate);
  }
}
function assertNoA() {
  assert.ok(!h.writes.some(([key, value]) => key === "yuldash.token" && value === pair.access_token), "Cancelled operation must never write A's token, even temporarily");
  assert.equal(h.routes, 0);
}

for (const method of ["telegram", "sms"]) {
  for (const cancel of ["back", "unmount", "own method disabled"]) {
    await screenTest(`${method}: queued publication cancelled by ${cancel}`, async ({ s, api }) => {
      const { pending } = await queuedVerify(s, method);
      if (cancel === "back") button(s.tree, method === "telegram" ? "Назад" : "Изменить номер").props.onClick();
      else if (cancel === "unmount") s.unmount();
      else {
        h.methods = [method === "telegram" ? "sms" : "telegram"];
        document.dispatchEvent(new Event("visibilitychange"));
        await s.settle();
      }
      releasePublication(); await pending;
      assertNoA();
      assert.equal(api.getToken(), null);
      assert.equal(api.getRefreshToken(), null);
    });
  }
  await screenTest(`${method}: other method removal still allows queued publication`, async ({ s, api }) => {
    const { pending } = await queuedVerify(s, method);
    h.methods = [method];
    document.dispatchEvent(new Event("visibilitychange")); await s.settle();
    releasePublication(); await pending;
    assert.equal(api.getToken(), pair.access_token);
    assert.equal(api.getRefreshToken(), pair.refresh_token);
    assert.equal(h.routes, 1);
    assert.equal(h.writes.filter(([key, value]) => key === "yuldash.token" && value === pair.access_token).length, 1);
  });
}
for (const state of ["logout", "account B", "storage fence"]) {
  await screenTest(`existing ${state} guard also rejects a queued publication`, async ({ s, api, values }) => {
    const { pending } = await queuedVerify(s, "telegram");
    if (state === "storage fence") api.reportStorageFailure();
    else {
      values.set("yuldash.session", state);
      if (state === "account B") { values.set("yuldash.token", "synthetic-B"); values.set("yuldash.refresh", "synthetic-RB"); }
    }
    releasePublication(); await pending;
    assertNoA();
    assert.equal(api.getToken(), state === "account B" ? "synthetic-B" : null);
  });
}
await screenTest("real logout intent wins over login waiting for the session write lock", async ({ s, api }) => {
  const { pending } = await queuedVerify(s, "telegram");
  const originalGeneration = api.getSessionGeneration();
  const logout = h.auth.logout();
  await s.settle();
  assert.equal(h.queue.length, 2, "Both real provider operations must be queued before either obtains the lock");
  assert.equal(api.getSessionGeneration(), originalGeneration, "Logout intent has not committed its generation yet");
  releasePublication(); await pending;
  releasePublication(); await logout;
  assertNoA();
  assert.equal(api.getToken(), null);
  assert.equal(api.getRefreshToken(), null);
});
await screenTest("new provider login supersedes an older queued publication", async ({ s, api }) => {
  const { pending } = await queuedVerify(s, "telegram");
  const newer = h.auth.login("synthetic-B", "synthetic-RB", { id: 2 });
  assert.equal(h.queue.length, 2);
  releasePublication(); await pending;
  assertNoA();
  releasePublication(); await newer;
  assert.equal(api.getToken(), "synthetic-B");
  assert.equal(api.getRefreshToken(), "synthetic-RB");
});
await screenTest("old queued publication cannot steal a newly started operation", async ({ s, api }) => {
  const old = await queuedVerify(s, "telegram");
  button(s.tree, "Назад").props.onClick(); await s.settle();
  h.verify = deferred();
  const fresh = await verify(s, "telegram");
  releasePublication(); await old.pending; await s.settle();
  assertNoA();
  assert.equal(submit(s).props.disabled, true);
  h.verify.resolve({ ...pair, access_token: "synthetic-B", refresh_token: "synthetic-RB" });
  await s.settle();
  assert.equal(h.queue.length, 1);
  releasePublication(); await fresh.pending;
  assert.equal(api.getToken(), "synthetic-B");
  assert.equal(api.getRefreshToken(), "synthetic-RB");
  assert.equal(h.routes, 1);
});
for (const method of ["telegram", "sms"]) {
  for (const heldStep of ["disableWebPush", "logoutServer"]) {
    await screenTest(`${method}: HTTP success after logout starts during ${heldStep} is obsolete`, async ({ s, api }) => {
      const response = h.verify = deferred();
      const { pending } = await verify(s, method);
      const blocked = deferred();
      h[heldStep] = () => blocked.promise;
      const generation = api.getSessionGeneration();
      const logout = h.auth.logout();
      await s.settle();
      assert.equal(api.getSessionGeneration(), generation, "The logout network step must still be pending");
      response.resolve(pair);
      await s.settle(); await drainSessionWork(); await pending;
      blocked.resolve();
      await s.settle(); await drainSessionWork(); await logout;
      assertNoA();
      assert.equal(api.getToken(), null);
      assert.equal(api.getRefreshToken(), null);
    });
  }
  await screenTest(`${method}: request response after logout starts cannot open the code step`, async ({ s }) => {
    const response = deferred();
    let pending;
    if (method === "telegram") {
      h.start = response;
      pending = button(s.tree, "Войти через Telegram").props.onClick();
    } else {
      button(s.tree, "Войти по номеру").props.onClick(); await s.settle();
      input(s, "tel").props.onChange({ target: { value: "12345" } }); await s.settle();
      h.smsRequest = response;
      pending = submit(s).props.onClick();
    }
    const blocked = deferred(); h.disableWebPush = () => blocked.promise;
    const logout = h.auth.logout();
    response.resolve({ request_id: "synthetic-obsolete" });
    await pending; await s.settle();
    const showedCode = !!input(s, "one-time-code");
    blocked.resolve(); await s.settle(); await drainSessionWork(); await logout;
    assert.equal(showedCode, false);
    assert.equal(h.opens, 0);
    assertNoA();
  });
  await screenTest(`${method}: fresh login after completed logout remains available`, async ({ s, api, Login }) => {
    const logout = h.auth.logout();
    await s.settle(); await drainSessionWork(); await logout;
    assert.equal(api.getToken(), null);
    s.unmount();
    const fresh = mount(Login);
    try {
      await fresh.settle();
      h.pair = { ...pair, access_token: "synthetic-B", refresh_token: "synthetic-RB" };
      const { pending } = await verify(fresh, method);
      await fresh.settle(); await drainSessionWork(); await pending;
      assert.equal(api.getToken(), "synthetic-B");
      assert.equal(api.getRefreshToken(), "synthetic-RB");
      assert.equal(h.routes, 1);
    } finally { fresh.unmount(); }
  });
}
await screenTest("overlapping logouts invalidate a verify that began between them", async ({ s, api }) => {
  const first = deferred(), second = deferred();
  const pushResponses = [first, second];
  h.disableWebPush = () => pushResponses.shift().promise;
  const firstLogout = h.auth.logout();
  const response = h.verify = deferred();
  const { pending } = await verify(s, "telegram");
  const secondLogout = h.auth.logout();
  response.resolve(pair);
  await s.settle(); await drainSessionWork(); await pending;
  first.resolve(); await s.settle(); await drainSessionWork(); await firstLogout;
  assertNoA();
  assert.equal(api.getToken(), null);
  second.resolve(); await s.settle(); await drainSessionWork(); await secondLogout;
  assertNoA();
  assert.equal(api.getToken(), null);
});
t.done();
