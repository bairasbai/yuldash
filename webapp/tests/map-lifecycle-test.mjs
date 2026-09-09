import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

// Execute YandexMap itself: React scheduling and the remote SDK are test boundaries.
const mocks = {
  react: `export const useRef=v=>globalThis.hooks.ref(v); export const useState=v=>globalThis.hooks.state(v);
    export const useEffect=(f,d)=>globalThis.hooks.effect(f,d);`,
  '../i18n/lang': `export const useLang=()=>({appText:(ru)=>ru});`,
  './Icons': `export const IconPin=()=>null;`,
  'react/jsx-runtime': `export const jsx=(type,props)=>({type,props}); export const jsxs=jsx;`,
};
const built = await build({entryPoints:[fileURLToPath(new URL('../src/components/YandexMap.tsx',import.meta.url))],
  bundle:true,write:false,platform:'node',format:'esm',jsx:'automatic',
  define:{'import.meta.env.VITE_YANDEX_MAPS_JS_KEY':'"local-test-key"'},
  plugins:[{name:'boundaries',setup(b){
    b.onResolve({filter:/.*/},a=>a.path in mocks?{path:a.path,namespace:'fixture'}:undefined);
    b.onLoad({filter:/.*/,namespace:'fixture'},a=>({contents:mocks[a.path],loader:'js'}));
  }}]});
let generation=0;
async function scenario(name, body) {
  const slots=[]; let cursor=0; const pending=[];
  globalThis.hooks={
    ref(v){const i=cursor++;return slots[i]??= {current:v};},
    state(v){const i=cursor++;slots[i]??={value:v};return [slots[i].value,n=>{slots[i].value=typeof n==='function'?n(slots[i].value):n;}];},
    effect(f,d){const i=cursor++;const old=slots[i];if(!old||d.some((v,j)=>v!==old.deps[j])){
      pending.push(()=>{old?.cleanup?.();slots[i]={deps:d,cleanup:f()};});
    }},
  };
  const scripts=[]; const added=[]; const listeners={};
  globalThis.document={getElementById:id=>scripts.find(s=>s.id===id)??null,
    createElement:()=>({addEventListener:(event,fn)=>{listeners[event]=fn;},remove(){scripts.splice(scripts.indexOf(this),1);}}),
    head:{appendChild:s=>scripts.push(s)}};
  const timers=new Map();let timerId=0;
  globalThis.window={setTimeout:f=>{timers.set(++timerId,f);return timerId;},clearTimeout:id=>timers.delete(id)};
  const api={Map:class {geoObjects={removeAll(){added.length=0;},add:o=>added.push(o)};setBounds(){}setCenter(){}destroy(){}},
    Placemark:class {constructor(point,data){this.point=point;this.data=data;}events={add(){}};},
    Polyline:class {constructor(points){this.points=points;}}};
  const {default:MapView}=await import('data:text/javascript;base64,'+Buffer.from(built.outputFiles[0].text+`\n// ${generation++}`).toString('base64'));
  function render(props={}) {cursor=0;const tree=MapView(props);function attach(n){if(!n||typeof n!=='object')return;if(n.props?.ref)n.props.ref.current={};const c=n.props?.children;(Array.isArray(c)?c:[c]).forEach(attach);}attach(tree);pending.splice(0).forEach(f=>f());return tree;}
  function unmount(){slots.forEach(s=>s?.cleanup?.());slots.length=0;}
  await body({api,render,unmount,scripts,added,timers});
  unmount(); console.log('✓ '+name);
}
let failures=0;
for (const [name,body] of [
  ['route and initial markers appear when async map becomes ready',async({api,render,added})=>{
    window.ymaps=api; const props={from:{lat:54,lng:55},to:{lat:55,lng:56},route:true,markers:[{id:1,lat:54.5,lng:55.5}]};
    render(props);await Promise.resolve();await Promise.resolve();render(props);
    assert.equal(added.length,4,'initial route and all three markers must be drawn after SDK readiness');
    render({...props,to:{lat:56,lng:57}});assert.equal(added.length,4);
  }],
  ['failed script can be loaded again on next mount',async({render,unmount,scripts})=>{
    render();assert.equal(scripts.length,1);const failed=scripts[0];failed.onerror();
    await Promise.resolve();await Promise.resolve();unmount();render();
    assert.ok(scripts.some(s=>s!==failed),'retry must create a fresh script instead of waiting on an already failed element');
  }],
  ['stalled SDK exposes an error and a working retry action',async({render,scripts,timers})=>{
    render();const old=scripts[0];assert.equal(timers.size,1);[...timers.values()][0]();
    for(let i=0;i<6;i++)await Promise.resolve();const tree=render();
    const nodes=[];function walk(n){if(!n||typeof n!=='object')return;nodes.push(n);const c=n.props?.children;(Array.isArray(c)?c:[c]).forEach(walk);}walk(tree);
    const retry=nodes.find(n=>n.type==='button');assert.ok(retry,'failed map must offer an actionable retry');
    retry.props.onClick();render();assert.ok(scripts.some(s=>s!==old));
  }],
]) {try{await scenario(name,body);}catch(e){failures++;console.error('✗ '+name+': '+e.message);}}
if(failures)process.exitCode=1;
