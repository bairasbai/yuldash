// Execute the production AuthProvider logout + Web Push code with browser boundaries replaced.
import assert from "node:assert/strict";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";

const mocks = {
  react: `export const createContext=()=>({Provider:'provider'}); export const useCallback=f=>f;
    export const useEffect=()=>{}; export const useMemo=f=>f(); export const useRef=v=>({current:v});
    export const useState=v=>[typeof v==='function'?v():v, n=>globalThis.__events.push(['state',n])];
    export const useContext=()=>null;`,
  "react/jsx-runtime": `export const jsx=(type,props)=>({type,props}); export const jsxs=jsx;`,
  "../api/client": `export const getToken=()=> 'local-token'; export const setSession=(...v)=>globalThis.__events.push(['session',...v]);
    export const getSessionGeneration=()=>globalThis.__owner??0; export const revokedGeneration=owner=>'revoked:'+owner,SESSION_REVOKE_PREFIX='yuldash.session.revoked.';
    export const revokeSession=owner=>{globalThis.__events.push(['revoke',owner]);globalThis.__owner=revokedGeneration(owner);};
    export const setRefreshHandler=()=>{}; export const setUnauthorizedHandler=()=>{};`,
  "../api/auth": `export const fetchMe=async()=>({}); export const refreshSession=async()=>({});
    export const logoutServer=async()=>{globalThis.__events.push(['server-logout']);};`,
  "../api/push": `export class PushBackendMissing extends Error {}; export const sendWebPushSubscription=async()=>{};
    export const unsubscribeWebPush=async endpoint=>{globalThis.__events.push(['push-revoke',endpoint]);};`,
  "../utils/outbox": `export const clearOutbox=()=>globalThis.__events.push(['outbox-clear']);`,
  "../utils/formDraft": `export const clearAllDrafts=()=>globalThis.__events.push(['drafts-clear']);`,
  "../utils/privacy": `export const syncPersonalSession=()=>{}; export const clearPersonalLocal=()=>globalThis.__events.push(['personal-clear']);`,
};
const bundled = await build({
  stdin: { contents: `export { AuthProvider } from './src/auth/AuthProvider.tsx';`,
    resolveDir: fileURLToPath(new URL("../", import.meta.url)), loader: "tsx" },
  bundle: true, write: false, platform: "node", format: "esm", jsx: "automatic",
  define: { "import.meta.env": "{}" },
  plugins: [{ name: "browser-boundaries", setup(b) {
    b.onResolve({filter: /.*/}, a => a.path in mocks ? {path:a.path, namespace:"fixture"} : undefined);
    b.onLoad({filter: /.*/, namespace:"fixture"}, a => ({contents:mocks[a.path], loader:"js"}));
  }}],
});
const { AuthProvider } = await import("data:text/javascript;base64," + Buffer.from(bundled.outputFiles[0].text).toString("base64"));
globalThis.window = { Notification: {}, PushManager: {} };
globalThis.localStorage = { getItem:()=>null, setItem:()=>{}, removeItem:k=>globalThis.__events.push(['remove',k]) };
let failed = 0;
async function check(label, registration) {
  globalThis.__events = [];
  globalThis.__owner = 0;
  Object.defineProperty(globalThis, "navigator", {configurable:true, value:{serviceWorker:{
    ready: new Promise(()=>{}), getRegistration: async()=>registration,
  }}});
  const { logout } = AuthProvider({children:null}).props.value;
  let timer;
  const completed = await Promise.race([
    logout().then(()=>true), new Promise(resolve=>{timer=setTimeout(()=>resolve(false),200);}),
  ]);
  clearTimeout(timer);
  try {
    assert.equal(completed, true, "logout blocked by serviceWorker.ready");
    assert.ok(__events.some(e=>e[0]==='server-logout'));
    assert.ok(__events.some(e=>e[0]==='revoke' && e[1]===0));
    assert.ok(__events.some(e=>e[0]==='state' && e[1]==='guest'));
    for (const action of ['outbox-clear','drafts-clear','personal-clear']) assert.ok(__events.some(e=>e[0]===action));
    if (registration?.pushManager) {
      const actions = __events.map(e=>e[0]);
      assert.ok(actions.indexOf('push-revoke') < actions.indexOf('browser-unsubscribe'));
      assert.ok(actions.indexOf('browser-unsubscribe') < actions.indexOf('server-logout'));
    }
    console.log('✓ '+label);
  } catch(e) { failed++; console.error('✗ '+label+': '+e.message); }
}
await check('logout finishes with no registration and permanently pending ready', undefined);
await check('logout revokes installed subscription before clearing account despite pending ready', {
  pushManager:{getSubscription:async()=>({endpoint:'https://push.invalid/local-fixture',
    unsubscribe:async()=>{__events.push(['browser-unsubscribe']);return true;}})},
});
if (failed) process.exitCode=1;
