// Real client + outbox; fault-injected Web Storage, IndexedDB and fetch only.
// No real network and no dependency on browser timing.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { IDBFactory } from 'fake-indexeddb';
import { fileURLToPath } from 'node:url';
const bundle = await build({
  stdin: { contents: "export * from './src/api/client'; export * from './src/utils/outbox';",
    resolveDir: fileURLToPath(new URL('../', import.meta.url)), loader: 'ts' },
  bundle: true, write: false, platform: 'node', format: 'esm', define: { 'import.meta.env': '{}' },
});
let serial = 0, total = 0, failed = 0;
async function instance() {
  return import('data:text/javascript;base64,' + Buffer.from(bundle.outputFiles[0].text + `\n// ${serial++}`).toString('base64'));
}
async function environment() {
  globalThis.indexedDB = new IDBFactory();
  const data = new Map(), fault = { write: 0, writes: 0, read: false, remove: false };
  globalThis.localStorage = {
    getItem: key => { if (fault.read) throw new Error('Storage unavailable'); return data.get(key) ?? null; },
    setItem: (key, value) => { if (++fault.writes === fault.write) throw new Error('Quota exhausted'); data.set(key, String(value)); },
    removeItem: key => { if (fault.remove) throw new Error('Removal denied'); data.delete(key); },
  };
  globalThis.fetch = async () => { throw new Error('Unexpected fetch; external network prohibited'); };
  return { api: await instance(), data, fault };
}
async function check(name, test) {
  total++;
  try { await test(await environment()); console.log(`PASS ${name}`); }
  catch (error) { failed++; console.error(`FAIL ${name}: ${error.message}`); }
}
function snapshot(api) { return [api.getToken(), api.getRefreshToken(), api.getSessionGeneration()]; }
for (const write of [1, 2, 3]) {
  await check(`login write failure ${write} leaves one complete session after reload`, async ({ api, fault }) => {
    api.setSession('A', 'refresh-A');
    const before = snapshot(api);
    fault.writes = 0; fault.write = write;
    let rejected = false;
    try { api.setSession('B', 'refresh-B'); } catch { rejected = true; }
    const reloaded = await instance();
    if (rejected) assert.deepEqual(snapshot(reloaded), before);
    else { assert.deepEqual(snapshot(reloaded).slice(0, 2), ['B', 'refresh-B']); assert.notEqual(reloaded.getSessionGeneration(), before[2]); }
  });
}
for (const write of [1, 2]) {
  await check(`refresh write failure ${write} never leaves a mixed pair`, async ({ api, fault }) => {
    api.setSession('A', 'refresh-A');
    const before = snapshot(api);
    fault.writes = 0; fault.write = write;
    let rejected = false;
    try { api.rotateSession('A-new', 'refresh-A-new', before[2]); } catch { rejected = true; }
    assert.deepEqual(snapshot(await instance()), rejected ? before : ['A-new', 'refresh-A-new', before[2]]);
  });
}
await check('failed logout commit preserves the complete prior session and rejects', async ({ api, fault }) => {
  api.setSession('A', 'refresh-A'); const before = snapshot(api);
  fault.writes = 0; fault.write = 1;
  assert.throws(() => api.setSession(null, null));
  assert.deepEqual(snapshot(await instance()), before);
});
await check('logout tombstone survives failed legacy cleanup and late refresh', async ({ api, data, fault }) => {
  data.set('yuldash.session', 'legacy-A'); data.set('yuldash.token', 'A'); data.set('yuldash.refresh', 'refresh-A');
  fault.remove = true;
  api.setSession(null, null);
  const reloaded = await instance();
  assert.equal(reloaded.getToken(), null); assert.equal(reloaded.getRefreshToken(), null);
  assert.equal(reloaded.rotateSession('A-new', 'refresh-A-new', 'legacy-A'), false);
  assert.equal(reloaded.getToken(), null);
});
await check('legacy refresh is atomic without rewriting the owner or deleting legacy base', async ({ api, data, fault }) => {
  data.set('yuldash.session', 'legacy-A'); data.set('yuldash.token', 'A'); data.set('yuldash.refresh', 'refresh-A');
  assert.deepEqual(snapshot(api), ['A', 'refresh-A', 'legacy-A']);
  fault.writes = 0; fault.write = 1;
  assert.throws(() => api.rotateSession('A-new', 'refresh-A-new', 'legacy-A'));
  assert.deepEqual(snapshot(await instance()), ['A', 'refresh-A', 'legacy-A']);
  fault.write = 0;
  assert.equal(api.rotateSession('A-new', 'refresh-A-new', 'legacy-A'), true);
  assert.deepEqual(snapshot(await instance()), ['A-new', 'refresh-A-new', 'legacy-A']);
  assert.equal(data.get('yuldash.session'), 'legacy-A');
  assert.equal(data.get('yuldash.token'), 'A'); assert.equal(data.get('yuldash.refresh'), 'refresh-A');
});
await check('read failure never becomes a fabricated guest or generation', async ({ api, fault }) => {
  api.setSession('A', 'refresh-A'); fault.read = true;
  assert.throws(() => api.getToken()); assert.throws(() => api.getRefreshToken()); assert.throws(() => api.getSessionGeneration());
  fault.read = false;
});
await check('legacy pair without a marker can refresh without losing its ownership', async ({ api, data }) => {
  data.set('yuldash.token', 'A'); data.set('yuldash.refresh', 'refresh-A');
  assert.equal(api.rotateSession('A-new', 'refresh-A-new', ''), true);
  assert.deepEqual(snapshot(await instance()), ['A-new', 'refresh-A-new', '']);
});
await check('corrupt committed record cannot fall back to leftover legacy credentials', async ({ api, data }) => {
  data.set('yuldash.session', '{"version":1,"generation":"A","access":42}');
  data.set('yuldash.token', 'legacy-A'); data.set('yuldash.refresh', 'legacy-refresh-A');
  assert.throws(() => api.getToken()); assert.throws(() => api.getRefreshToken()); assert.throws(() => api.getSessionGeneration());
  api.setSession(null, null); assert.equal(api.getToken(), null);
});
await check('failed account switch cannot send A outbox as B; successful switch cannot inherit it', async ({ api, fault }) => {
  api.setSession('A', 'refresh-A'); await api.enqueue(42, 'message', 'private A');
  fault.writes = 0; fault.write = 1;
  assert.throws(() => api.setSession('B', 'refresh-B'));
  fault.write = 0;
  const calls = [];
  globalThis.fetch = async (_url, init) => { calls.push(init.headers.Authorization); return new Response('{}'); };
  assert.equal(await api.flushOutbox(), true); assert.deepEqual(calls, ['Bearer A']);
  await api.enqueue(42, 'message', 'private A again');
  api.setSession('B', 'refresh-B'); await api.flushOutbox();
  assert.deepEqual(calls, ['Bearer A']); assert.equal(api.hasPending(), false);
});
await check('account switch after generation check never sends old mutation with new credentials', async ({ api }) => {
  api.setSession('A', 'refresh-A');
  const original = localStorage.getItem;
  let reads = 0;
  // Another tab commits B just after this tab reads A's generation guard.
  localStorage.getItem = key => {
    const value = original(key);
    if (key === 'yuldash.session' && ++reads === 2) api.setSession('B', 'refresh-B');
    return value;
  };
  const bearers = [];
  globalThis.fetch = async (_url, init) => { bearers.push(init.headers.Authorization); return new Response('{}'); };
  await assert.rejects(api.apiPost('/me/delete', {}));
  assert.deepEqual(bearers, ['Bearer A']);
  assert.equal(api.getToken(), 'B');
});
for (const replacement of ['login', 'logout']) {
  await check(`late refresh after generation read cannot overwrite another tab's ${replacement}`, async ({ api }) => {
    api.setSession('A', 'refresh-A');
    const generation = api.getSessionGeneration(), original = localStorage.getItem;
    let switchPending = true;
    localStorage.getItem = key => {
      const value = original(key);
      if (key === 'yuldash.session' && switchPending) {
        switchPending = false;
        api.setSession(replacement === 'login' ? 'B' : null, replacement === 'login' ? 'refresh-B' : null);
      }
      return value;
    };
    assert.equal(api.rotateSession('A-new', 'refresh-A-new', generation), false);
    const expected = replacement === 'login' ? ['B', 'refresh-B'] : [null, null];
    assert.deepEqual(snapshot(await instance()).slice(0, 2), expected);
  });
}
await check('logout clears its rotation slot and ignores it even if cleanup fails', async ({ api, data, fault }) => {
  api.setSession('A', 'refresh-A'); const generation = api.getSessionGeneration();
  api.rotateSession('A-new', 'refresh-A-new', generation);
  const key = 'yuldash.session.rotation.' + generation;
  assert.ok(data.has(key)); fault.remove = true;
  api.setSession(null, null);
  assert.ok(data.has(key)); assert.equal((await instance()).getToken(), null);
});
await check('normal logout removes the previous generation rotation credentials', async ({ api, data }) => {
  api.setSession('A', 'refresh-A'); const generation = api.getSessionGeneration();
  api.rotateSession('A-new', 'refresh-A-new', generation);
  api.setSession(null, null);
  assert.equal(data.has('yuldash.session.rotation.' + generation), false);
  assert.equal((await instance()).getToken(), null);
});
await check('corrupt or misowned rotation never supplies another account credentials', async ({ api, data }) => {
  api.setSession('A', 'refresh-A'); const generation = api.getSessionGeneration();
  data.set('yuldash.session.rotation.' + generation, JSON.stringify({ version: 1, generation: 'B', access: 'B', refresh: 'refresh-B' }));
  assert.throws(() => api.getToken()); assert.throws(() => api.getRefreshToken());
  api.setSession(null, null); assert.equal(api.getToken(), null);
});
await check('v2 pointer contains no secrets and revoke physically removes both credential slots', async ({api,data})=>{
 api.setSession('private-access-A','private-refresh-A');const owner=api.getSessionGeneration();
 api.rotateSession('rotated-access-A','rotated-refresh-A',owner);
 assert.deepEqual(JSON.parse(data.get('yuldash.session')),{version:2,generation:owner,authenticated:true});
 api.revokeSession(owner);assert.equal(api.getToken(),null);assert.equal(api.getRefreshToken(),null);
 assert.equal(data.has('yuldash.session.credentials.'+owner),false);assert.equal(data.has('yuldash.session.rotation.'+owner),false);
 assert.equal([...data.values()].some(v=>v.includes('access-A')||v.includes('refresh-A')),false);
});
for(const boundary of ['credentials','pointer'])for(const timing of ['before','after']){
 await check(`revoke ${timing} staged login ${boundary} cannot leave credential bytes`,async({api,data})=>{
  const original=localStorage.setItem;let injected=false,owner;
  localStorage.setItem=(key,value)=>{
   const matches=boundary==='credentials'?key.startsWith('yuldash.session.credentials.'):key==='yuldash.session';
   if(matches&&!injected){injected=true;owner=JSON.parse(value).generation;if(timing==='before')api.revokeSession(owner);original(key,value);if(timing==='after')api.revokeSession(owner);}
   else original(key,value);
  };
  api.setSession('private-A','private-refresh-A');assert.equal(injected,true);assert.equal(api.getToken(),null);
  assert.equal(data.has('yuldash.session.credentials.'+owner),false);
 });
}
for(const timing of ['before','after']) await check(`revoke ${timing} late rotation write cannot leave credential bytes`,async({api,data})=>{
 api.setSession('A','refresh-A');const owner=api.getSessionGeneration(),original=localStorage.setItem;let injected=false;
 localStorage.setItem=(key,value)=>{if(!injected&&key.startsWith('yuldash.session.rotation.')){injected=true;if(timing==='before')api.revokeSession(owner);original(key,value);if(timing==='after')api.revokeSession(owner);}else original(key,value);};
 assert.equal(api.rotateSession('late-A','late-refresh-A',owner),false);assert.equal(api.getToken(),null);
 assert.equal(data.has('yuldash.session.credentials.'+owner),false);assert.equal(data.has('yuldash.session.rotation.'+owner),false);
});
await check('failed pointer commit removes unpublished payload and preserves A',async({api,data,fault})=>{
 api.setSession('A','refresh-A');const before=snapshot(api);fault.writes=0;fault.write=2;
 assert.throws(()=>api.setSession('B','refresh-B'));assert.deepEqual(snapshot(await instance()),before);
 assert.equal([...data.values()].some(v=>v.includes('refresh-B')),false);
});
await check('failed orphan removal remains unselected and revoked reads never use legacy fallback',async({api,data,fault})=>{
 api.setSession('A','refresh-A');fault.writes=0;fault.write=2;fault.remove=true;
 assert.throws(()=>api.setSession('B','refresh-B'));assert.equal(api.getToken(),'A');
 data.set('yuldash.token','old-shared-token');api.revokeSession(api.getSessionGeneration());
 assert.equal((await instance()).getToken(),null);assert.equal((await instance()).getRefreshToken(),null);
});
await check('A to B replacement then logout removes both exact credential owners',async({api,data})=>{
 api.setSession('A','refresh-A');const ownerA=api.getSessionGeneration();api.rotateSession('A-new','refresh-A-new',ownerA);
 api.setSession('B','refresh-B');const ownerB=api.getSessionGeneration();
 assert.equal(data.has('yuldash.session.credentials.'+ownerA),false);assert.equal(data.has('yuldash.session.rotation.'+ownerA),false);
 assert.deepEqual(snapshot(api).slice(0,2),['B','refresh-B']);
 assert.equal(api.rotateSession('late-A','late-refresh-A',ownerA),false);
 api.revokeSession(ownerB);assert.equal(data.has('yuldash.session.credentials.'+ownerB),false);
 assert.equal(api.getToken(),null);assert.equal(api.getSessionGeneration(),api.revokedGeneration(ownerB));
 assert.equal(JSON.parse(data.get('yuldash.session')).generation,ownerB);
});
await check('legacy metadata reads do not touch denied credential keys or skip previous-owner cleanup',async({api,data})=>{
 data.set('yuldash.session','legacy-A');
 data.set('yuldash.session.rotation.legacy-A',JSON.stringify({version:1,generation:'legacy-A',access:'old-A',refresh:'old-refresh-A'}));
 const read=localStorage.getItem,trace=[];
 localStorage.getItem=key=>{trace.push(key);if(key==='yuldash.token'||key==='yuldash.refresh')throw new Error('Secret read denied');return read(key);};
 assert.equal(api.getSessionGeneration(),'legacy-A');
 api.setSession('B','refresh-B');assert.equal(api.getToken(),'B');
 assert.equal(data.has('yuldash.session.rotation.legacy-A'),false);
 assert.equal(trace.some(key=>key==='yuldash.token'||key==='yuldash.refresh'),false);
});
console.log(`Atomic session storage: ${total - failed} passed, ${failed} failed`);
if (failed) process.exitCode = 1;
