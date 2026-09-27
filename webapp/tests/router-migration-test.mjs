// Actual React 18, BrowserRouter, Routes, navigation hooks, production App route
// table + auth gates + LoginScreen. Only screen contents/API/auth state and the
// browser History boundary are fixtures. This is not browser/device acceptance.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const React = require('react');
const { act, create } = require('react-test-renderer');
const { BrowserRouter, useLocation, useNavigate, useNavigationType } = require('react-router-dom');
const root = fileURLToPath(new URL('../', import.meta.url));
const fixturePath = root + 'tests/.bundles/router-fixture.cjs';
const mocks = {
  auth: `export const useAuth=()=>globalThis.__routeAuth;`,
  lang: `export const useLang=()=>({lang:'ru',setLang:()=>{},appText:(ru)=>ru});`,
  client: `export class ApiError extends Error{};export const getSessionGeneration=()=> 'fixture-owner';export const apiPost=()=>{throw new Error('Unexpected API');};`,
  apiAuth: `export const TELEGRAM_BOT='fixture',SMS_LOGIN_ENABLED=false;export const tgStart=()=>{},tgVerify=()=>{},telegramChatUrl=()=>'',telegramStartUrl=()=>'',requestSmsCode=()=>{},verifySmsCode=()=>{};`,
  loginFlow: `export const createLoginFlow=deps=>{globalThis.__loginRouteDeps=deps;return {revive(){},dispose(){}};};export const filterLoginCode=x=>x,nextNeedPhone=()=>false;`,
  icons: `export const IconBlock=()=>null,IconLock=IconBlock,IconPhone=IconBlock,IconProfile=IconBlock,IconShield=IconBlock,IconTelegram=IconBlock,IconWarn=IconBlock;`,
  states: `import React from 'react';export const LoadingList=()=>React.createElement('span',{'data-loading':true});`,
  outbox: `export const watchOutbox=()=>()=>{};`,
  nav: `export const useTaxiOrderOnScreen=()=>false;`,
  driver: `export const fetchDriverDebt=async()=>({pay_now_kop:0});`,
  analytics: `export const track=()=>{};`,
  empty: `export default ()=>null;`,
};
const compiled = await build({entryPoints:[root+'src/App.tsx'],bundle:true,write:false,format:'cjs',platform:'node',jsx:'automatic',packages:'external',plugins:[{name:'route-boundaries',setup(b){
  b.onResolve({filter:/.*/},a=>{
    const p=a.path;
    if(/^react(?:-dom|-router-dom)?(?:\/|$)/.test(p))return {path:p,external:true};
    if(p.includes('/screens/')&&!p.endsWith('/LoginScreen'))return {path:p.split('/').at(-1),namespace:'screen'};
    const k=p.endsWith('/auth/AuthProvider')?'auth':p.endsWith('/i18n/lang')?'lang':p.endsWith('/api/client')?'client':p.endsWith('/api/auth')?'apiAuth':p.endsWith('/utils/loginFlow')?'loginFlow':p.endsWith('/components/Icons')?'icons':p.endsWith('/components/States')?'states':p.endsWith('/utils/outbox')?'outbox':p.endsWith('/navSignals')?'nav':p.endsWith('/api/driver')?'driver':p.endsWith('/analytics')?'analytics':p.includes('/components/')&&!/Require(Auth|Admin)$/.test(p)?'empty':null;
    return k?{path:k,namespace:'fixture'}:null;
  });
  b.onLoad({filter:/.*/,namespace:'fixture'},a=>({contents:mocks[a.path],loader:'js'}));
  b.onLoad({filter:/.*/,namespace:'screen'},a=>({contents:`import React from 'react';import {useParams} from 'react-router-dom';export default function Screen(){return React.createElement('section',{'data-screen':${JSON.stringify(a.path)},'data-params':JSON.stringify(useParams())});}`,loader:'js'}));
}}]});
mkdirSync(root+'tests/.bundles',{recursive:true});writeFileSync(fixturePath,compiled.outputFiles[0].text);
const App = require(fixturePath).default;
function browser(initial) {
  const target=new EventTarget();let entries=[{url:new URL(initial,'https://pwa.invalid'),state:null}],index=0;
  const win={document:{},addEventListener:target.addEventListener.bind(target),removeEventListener:target.removeEventListener.bind(target),get location(){return entries[index].url;}};
  win.history={get state(){return entries[index].state;},get length(){return entries.length;},
    replaceState(state,_title,url){entries[index]={url:url?new URL(url,win.location):win.location,state};},
    pushState(state,_title,url){entries.splice(index+1);entries.push({url:new URL(url,win.location),state});index++;},
    go(delta){const next=index+delta;if(next<0||next>=entries.length)return;index=next;target.dispatchEvent(new Event('popstate'));}};
  return win;
}
let current,nav,type,renderer,win,total=0,failed=0;
function Observe(){current=useLocation();nav=useNavigate();type=useNavigationType();return null;}
function tree(){return React.createElement(BrowserRouter,{window:win},React.createElement(Observe),React.createElement(App));}
const tick=()=>new Promise(setImmediate);
async function settle(work){await act(async()=>{await work?.();await tick();await tick();});}
async function mount(url,status='authed',role='passenger'){
  win=browser(url);globalThis.window=win;globalThis.sessionStorage={getItem:()=>null,removeItem:()=>{},setItem:()=>{}};
  globalThis.__routeAuth={status,user:status==='authed'?{id:1,role}:null,retrySession:()=>{},login:()=>{}};
  globalThis.__loginRouteDeps=null;
  await settle(()=>{renderer=create(tree());});
}
const screen=()=>renderer.root.findAll(n=>n.props['data-screen']).map(n=>n.props['data-screen']);
async function check(name,test){total++;try{await test();console.log('PASS '+name);}catch(e){failed++;console.error('FAIL '+name+': '+e.message);}finally{if(renderer)await settle(()=>renderer.unmount());renderer=null;}}

await check('authenticated initial deep link selects real lazy route and keeps query/hash',async()=>{
 await mount('/support/42?source=push#reply');assert.deepEqual(screen(),['SupportTicketScreen']);
 assert.equal(renderer.root.findByProps({'data-screen':'SupportTicketScreen'}).props['data-params'],'{"id":"42"}');
 assert.equal(current.search,'?source=push');assert.equal(current.hash,'#reply');
});
await check('guest protected deep link returns after real login navigation and preserves query/hash',async()=>{
 await mount('/support/42?source=push#reply','guest');assert.equal(current.pathname,'/login');
 assert.equal(current.state.from,'/support/42?source=push#reply');assert.equal(type,'REPLACE');
 globalThis.__routeAuth={...__routeAuth,status:'authed',user:{id:2,role:'passenger'}};
 await settle(()=>{renderer.update(tree());__loginRouteDeps.navigate();});
 assert.equal(current.pathname+current.search+current.hash,'/support/42?source=push#reply');assert.deepEqual(screen(),['SupportTicketScreen']);
 assert.equal(win.history.length,1,'login redirect/return must replace, not create a back loop');
});
await check('browser push, replace and POP back/forward retain location state',async()=>{
 await mount('/map');await settle(()=>nav('/rides?city=Уфа',{state:{origin:'map'}}));
 assert.equal(type,'PUSH');assert.deepEqual(current.state,{origin:'map'});assert.equal(win.history.length,2);
 await settle(()=>nav('/profile',{replace:true}));assert.equal(type,'REPLACE');assert.equal(win.history.length,2);
 await settle(()=>nav(-1));assert.equal(current.pathname,'/map');assert.equal(type,'POP');
 await settle(()=>nav(1));assert.equal(current.pathname,'/profile');assert.deepEqual(screen(),['ProfileScreen']);
});
await check('nested shell route exposes correct dynamic booking parameter',async()=>{
 await mount('/trip/123');assert.deepEqual(screen(),['ActiveTripScreen']);
 assert.equal(renderer.root.findByProps({'data-screen':'ActiveTripScreen'}).props['data-params'],'{"id":"123"}');
});
await check('unknown and root paths replace with splash without loops',async()=>{
 await mount('/does-not-exist/nested');assert.equal(current.pathname,'/splash');assert.deepEqual(screen(),['SplashScreen']);assert.equal(win.history.length,1);
 await settle(()=>nav('/'));assert.equal(current.pathname,'/splash');assert.equal(type,'REPLACE');
});
for(const status of ['loading','unavailable']) await check(`${status} protected route hides private content without losing URL`,async()=>{
 await mount('/support/42?draft=1',status);assert.deepEqual(screen(),[]);assert.equal(current.pathname,'/support/42');
 assert.equal(current.search,'?draft=1');assert.equal(renderer.root.findAllByProps({role:'status'}).length,1);
});
await check('admin guest redirect preserves complete destination, ordinary user cannot render admin',async()=>{
 await mount('/admin/support?queue=open#ticket','guest');assert.equal(current.pathname,'/login');
 assert.equal(current.state.from,'/admin/support?queue=open#ticket');
 globalThis.__routeAuth={...__routeAuth,status:'authed',user:{id:2,role:'passenger'}};
 await settle(()=>{renderer.update(tree());__loginRouteDeps.navigate();});
 assert.equal(current.pathname,'/profile');assert.deepEqual(screen(),['ProfileScreen']);assert.ok(current.state.notice);
});
await check('admin role loads actual lazy admin route',async()=>{
 await mount('/admin/support','authed','admin');assert.deepEqual(screen(),['AdminSupportScreen']);
});
await check('unavailable admin deep link waits for retry and keeps its complete URL',async()=>{
 await mount('/admin/support?queue=open#ticket','unavailable');
 assert.equal(current.pathname+current.search+current.hash,'/admin/support?queue=open#ticket');
 assert.deepEqual(screen(),[]);const retry=renderer.root.findAllByType('button');assert.equal(retry.length,1);
 let retried=false;__routeAuth.retrySession=()=>{retried=true;};await settle(()=>renderer.update(tree()));
 await settle(()=>renderer.root.findByType('button').props.onClick());assert.equal(retried,true);
 globalThis.__routeAuth={...__routeAuth,status:'authed',user:{id:3,role:'admin'}};
 await settle(()=>renderer.update(tree()));assert.deepEqual(screen(),['AdminSupportScreen']);
 assert.equal(current.pathname+current.search+current.hash,'/admin/support?queue=open#ticket');
});
await check('direct login without return state goes to map through production callback',async()=>{
 await mount('/login','guest');await settle(()=>__loginRouteDeps.navigate());assert.equal(current.pathname,'/map');assert.deepEqual(screen(),['HomeScreen']);
});
for(const from of ['https://other.invalid/path','//other.invalid/path','/\\other.invalid/path','\\other.invalid/path','javascript:alert(1)','/\t/other.invalid',42,{pathname:'//other.invalid'}])await check(`login rejects nonlocal return ${JSON.stringify(from)}`,async()=>{
 await mount('/login','guest');await settle(()=>nav('/login',{replace:true,state:{from}}));
 await settle(()=>__loginRouteDeps.navigate());assert.equal(current.pathname,'/map');assert.deepEqual(screen(),['HomeScreen']);
 assert.equal(win.location.origin,'https://pwa.invalid');
});
rmSync(fixturePath,{force:true});
console.log(`Router migration: ${total-failed} passed, ${failed} failed`);if(failed)process.exitCode=1;
