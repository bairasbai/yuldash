// Настоящие ComposeView и PlacePicker; заменены только hooks, карта и API.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { transform } from "esbuild";

const source = readFileSync(new URL("../src/screens/InstantOrderScreen.tsx", import.meta.url), "utf8");
const views = source.slice(source.indexOf("function ComposeView("), source.indexOf("function TrackingView("));
assert.ok(views.includes("function PlacePicker("));
const h = globalThis.__taxiOriginTest = {
  bucket: null, estimates: [], orders: [], used: [], recent: [],
  places: [{id:1,label:"Дом",address:"Уфа, дом",kind:"home",lat:54.73,lng:55.95},
    {id:2,label:"Работа",address:"Уфа, работа",kind:"work",lat:54.75,lng:55.99}],
};
const prelude = `
const h=globalThis.__taxiOriginTest;
const __jsx=(type,props,...children)=>({type,props:{...props,children}}), __fragment='fragment';
const useState=initial=>{const b=h.bucket,i=b.si++;if(!(i in b.states))b.states[i]=typeof initial==='function'?initial():initial;return [b.states[i],v=>b.states[i]=typeof v==='function'?v(b.states[i]):v]};
const useRef=initial=>{const b=h.bucket,i=b.ri++;return b.refs[i]??={current:initial}};
const useEffect=fn=>h.bucket.effects.push(fn);
const useLang=()=>({lang:'ru',appText:(ru,ba)=>ru}), useNavigate=()=>()=>{};
const rememberedPayMethod=()=>'cash',useRouteWeather=()=>null,track=()=>{};
const PAY_METHODS_OPEN=[{code:'cash',shortRu:'Наличные',shortBa:'Наличный'}];
class ApiError extends Error{};
const priceLabel=p=>p+' ₽',categoryLabel=cat=>cat,minDateTimeNow=()=>'',maxDateTimeInDays=()=>'',SCHEDULE_MAX_DAYS=7;
const fetchNearbyDrivers=async()=>({drivers:[]});
const instantEstimate=async body=>{h.estimates.push(body);return {price:300,eta_min:10,distance_km:5,options:[]}};
const createInstantOrder=async body=>{h.orders.push(body);return {id:81,status:'searching'}};
const createScheduledOrder=async()=>({id:82});
const fetchSavedPlaces=async()=>h.places,fetchRecentPlaces=async()=>[];
const markSavedPlaceUsed=async id=>h.used.push(id),addRecentPlace=async p=>h.recent.push(p),geocode=async()=>({items:[]});
`;
const tags = [...new Set([...views.matchAll(/<([A-Z][A-Za-z0-9]*)\b/g)].map(m=>m[1]))]
  .filter(name=>!["PlacePicker","Point","EstimateResult","NearbyDriver","TaxiCategory","OrderStop","PaymentMethod"].includes(name));
const tagStubs = tags.map(name=>`const ${name}='${name}';`).join('\n');
async function compile(text) {
  const result=await transform(prelude+tagStubs+'\n'+text+'\nexport {ComposeView,PlacePicker};',{
    loader:'tsx',format:'esm',jsx:'transform',jsxFactory:'__jsx',jsxFragment:'__fragment',target:'es2022',
  });
  return import('data:text/javascript;base64,'+Buffer.from(result.code).toString('base64'));
}
const {ComposeView,PlacePicker}=await compile(views);
const bucket=()=>({states:[],refs:[],si:0,ri:0,effects:[]});
const render=(fn,props,b)=>{h.bucket=b;b.si=b.ri=0;b.effects=[];return fn(props)};
// Дерево обходим и по слотам шторки (header/body/extra/footer/overlay/map) — форма заказа живёт
// в TaxiSheet, а не в children.
const SLOTS=['children','header','body','extra','footer','overlay','map'];
const all=node=>!node||typeof node!=='object'?[]:[node,...SLOTS.flatMap(k=>[node.props?.[k]].flat(Infinity)).flatMap(all)];
const picker=(tree,origin)=>all(tree).find(n=>n.type===PlacePicker&&!!n.props.origin===origin);
const submit=tree=>all(tree).find(n=>n.type==='button'&&n.props.className?.includes('submit-btn'));
let from=null,to=null,ordered=null;
const compose=bucket();
const props=()=>({from,to,setFrom:p=>from=p,setTo:p=>to=p,onOrdered:o=>ordered=o,onScheduled(){}});
let tree=render(ComposeView,props(),compose);
assert.ok(picker(tree,true),'При from=null доступен выбор подачи');
// Без назначения кнопки «Заказать» нет вовсе — как в Android (hasFooter = toPoint != null):
// заказать нельзя ни так, ни так.
assert.ok(!submit(tree)||submit(tree).props.disabled===true,'Без назначения заказать нельзя');
const previousNavigator=Object.getOwnPropertyDescriptor(globalThis,'navigator');
Object.defineProperty(globalThis,'navigator',{configurable:true,value:{geolocation:{getCurrentPosition(ok,fail){fail({code:1})}}}});
try {
  picker(tree,true).props.onUseMyLocation();
  tree=render(ComposeView,props(),compose);
  assert.equal(from,null);
  assert.ok(all(tree).some(n=>[n.props.children].flat(Infinity).some(v=>typeof v==='string'&&v.includes('Введи адрес подачи'))),'Отказ GPS объясняет ручной ввод');
  async function chooseSaved(pickerProps,index) {
    const state=bucket();let view=render(PlacePicker,pickerProps,state);
    const cleanups=state.effects.map(fn=>fn());
    await new Promise(setImmediate);
    all(view).find(n=>n.type==='input').props.onFocus();
    view=render(PlacePicker,pickerProps,state);
    all(view).filter(n=>n.type==='button'&&n.props.className==='taxi-suggest__row')[index].props.onClick();
    for(const fn of cleanups)if(typeof fn==='function')fn();
  }
  await chooseSaved(picker(tree,true).props,0);
  assert.deepEqual(from,{lat:54.73,lng:55.95,text:'Дом'});
  assert.equal(to,null,'Выбор подачи не подменяет назначение');
  tree=render(ComposeView,props(),compose);
  await chooseSaved(picker(tree,false).props,1);
  const destination=to;
  tree=render(ComposeView,props(),compose);
  const originState=bucket();const chosenOrigin=render(PlacePicker,picker(tree,true).props,originState);
  all(chosenOrigin).find(n=>n.type==='button'&&n.props.className==='taxi-route__chosen').props.onClick();
  assert.equal(from,null);assert.equal(to,destination,'Изменить подачу не сбрасывает назначение');
  tree=render(ComposeView,props(),compose);assert.equal(submit(tree).props.disabled,true);
  await chooseSaved(picker(tree,true).props,0);
  tree=render(ComposeView,props(),compose);
  for(const fn of compose.effects)fn();
  await new Promise(setImmediate);
  tree=render(ComposeView,props(),compose);
  assert.equal(submit(tree).props.disabled,false,'После ручного выбора GPS больше не нужен');
  await submit(tree).props.onClick();
  assert.equal(h.orders.length,1);
  const expected={from_lat:54.73,from_lng:55.95,to_lat:54.75,to_lng:55.99,from_text:'Дом',to_text:'Работа'};
  for(const [key,value]of Object.entries(expected)){assert.equal(h.orders[0][key],value);assert.equal(h.estimates.at(-1)[key],value)}
  assert.equal(ordered.id,81);assert.ok(h.used.includes(1)&&h.used.includes(2));
  // Проверка чувствительности: модель прежнего UI без редактируемого поля А.
  const baseline=views.replace(/<PlacePicker\s+origin[\s\S]*?\n        \/>/,'<div className="taxi-route__row">{from?.text}</div>');
  assert.notEqual(baseline,views);
  const old=await compile(baseline);const oldTree=render(old.ComposeView,props(),bucket());
  assert.equal(all(oldTree).filter(n=>n.type===old.PlacePicker&&n.props.origin).length,0,'Прежняя неподвижная подача не проходит проверку доступности');
  console.log('✓ GPS отказ → ручная подача из сохранённых → отдельное назначение → изменение подачи сохраняет назначение → estimate/create с обеими точками → поиск машины; прежний UI не предоставляет выбор подачи');
} finally {
  if(previousNavigator)Object.defineProperty(globalThis,'navigator',previousNavigator);else delete globalThis.navigator;
  delete globalThis.__taxiOriginTest;
}
