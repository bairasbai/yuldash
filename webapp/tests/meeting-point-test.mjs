// RS-03 А: точное место встречи/высадки на сайте — проводка от пикеров до тела брони
// (зеркало backend/tests/test_meeting_points.py и Android MeetingPointTest).
// Пусто — тела брони прежние; ориентир/булавка — meeting/alighting уходят; clear — не шлём.
import assert from "node:assert/strict";
import { bookingCommandStub } from "./booking-command-stub.mjs";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
globalThis.document ??= { body: {} };
const h = globalThis.__meetPoint = {
  states: [], refs: [], effects: [], si: 0, ri: 0, calls: [],
  reset() { this.states = []; this.refs = []; this.effects = []; this.si = this.ri = 0; this.calls = [];
    this.lang = 'ru'; this.lookup = async city => [{id: city === 'Уфа' ? 7 : 8,city,title_ru:'у мечети '+city,title_ba:'мәсет янында '+city}];
    this.propose = async () => ({}); this.answer = async () => ({}); },
};
const modules = {
  react: `const h=globalThis.__meetPoint; export function useState(value){const i=h.si++, states=h.states; if(!(i in states))states[i]=typeof value==='function'?value():value; return [states[i],v=>states[i]=typeof v==='function'?v(states[i]):v]}; export function useRef(value){const i=h.ri++; return h.refs[i]??=( {current:value} )}; export const useEffect=(fn)=>{h.effects.push(fn)}; export const useCallback=fn=>fn;`,
  "react/jsx-runtime": `export const Fragment='fragment'; export const jsx=(type,props)=>({type,props:props??{}}); export const jsxs=jsx;`,
  "react-dom": `export const createPortal=(node)=>node;`,
  "react-router-dom": `export const Link='a'; export const useNavigate=()=> ()=>{}; export const useParams=()=>({id:'42'});`,
  lang: `export const useLang=()=>({lang:globalThis.__meetPoint.lang??'ru',appText:(ru,ba)=>globalThis.__meetPoint.lang==='ba'?ba:ru,t:k=>k});`,
  auth: `export const useAuth=()=>({isAuthed:true});`,
  stageRecovery: `export const DriverStageRecovery=()=>null;`,
  stageHook: `export const useDriverStages=()=>({ready:true,busy:false,pending:[]});`,
  commands: bookingCommandStub(`export const createBooking=body=>{globalThis.__meetPoint.calls.push(body);return Promise.resolve({id:73,status:'pending'})};`),
  bookings: `    export const proposeMeetingPoint=(...args)=>{globalThis.__meetPoint.calls.push(args);return globalThis.__meetPoint.propose(...args)};
    export const withdrawMeetingPoint=async()=>({});
    export const answerMeetingPoint=(...args)=>{globalThis.__meetPoint.calls.push(args);return globalThis.__meetPoint.answer(...args)};
    export const fetchDriverBookings=async()=>[]; export const markArriving=async()=>({}); export const markNoShow=async()=>({}); export const rateBooking=async()=>({});`,
  rides: `export const fetchRide=async(id)=>null;`,
  client: `export class ApiError extends Error {} globalThis.__meetPoint.ApiError=ApiError; export const getSessionGeneration=()=>"same-session";`,
  ownBookings: `export const useOwnBooking=()=>null,useRefreshOwnBookings=()=>()=>{};`,
  features: `const flags={rides:true,taxi:true,courier:true,parcels:true,ads:true,business:true,ready:true};export const useFeatures=()=>flags,getFeatures=()=>flags;`,
  soon: `export default ()=>null;`,
  analytics: `export const track=()=>{};`,
  states: `export const LoadingList='loading'; export const ErrorState='retry';`,
  header: `export default 'header';`,
  ymap: `export default 'ymap';`,
  rowIcons: `export const IconProfile='i',IconStar='i';`,
  cabinetUi: `export const SectionHeader='section';`,
  bookingSegment: `export const BookingSegmentNote='span';`,
  rowStub: `export default 'row-stub';`,
  geo: `export const fetchPickupPoints=async(city)=>globalThis.__meetPoint.lookup(city); export const fetchDistricts=async()=>[]; export const fetchPopularRoutes=async()=>[]; export const fetchSettlements=async()=>[];`,
};
const root = fileURLToPath(new URL("../", import.meta.url));
async function component(path, name = "default") {
  const result = await build({entryPoints:[root+path], bundle:true, write:false, format:"esm", platform:"node", jsx:"automatic", logLevel:"silent",
    define:{"import.meta.env":JSON.stringify({VITE_API_BASE:"https://api.example.test",DEV:false})},
    plugins:[{name:"local-ui",setup(b){
      b.onResolve({filter:/.*/}, args=>{
        const p=args.path;
        const rowKey=args.importer.endsWith('DriverPassengers.tsx')?(p.endsWith('/DriverBoarding')||p.endsWith('/DriverNoShow')||p.endsWith('/FileIncidentCard')?'rowStub':p.endsWith('/cabinetUi')?'cabinetUi':p.endsWith('/Icons')?'rowIcons':p.endsWith('/PickupStopChoice')?'bookingSegment':null):null;
        const key=p.endsWith('/utils/useDriverStages')?'stageHook':p.endsWith('/DriverStageRecovery')?'stageRecovery':rowKey??(Object.hasOwn(modules,p)?p:p.endsWith('/i18n/lang')?'lang':p.endsWith('/auth/AuthProvider')?'auth':p.endsWith('/api/bookings')?'bookings':p.endsWith('/api/bookingCommands')?'commands':p.endsWith('/api/rides')?'rides':p.endsWith('/api/geo')?'geo':p.endsWith('/api/client')?'client':p.endsWith('/booking/OwnBookingsProvider')?'ownBookings':p.endsWith('/features')?'features':p.endsWith('/ComingSoon')?'soon':p.endsWith('/analytics')?'analytics':p.endsWith('/components/States')?'states':p.endsWith('YandexMap')?'ymap':p.endsWith('/components/ScreenHeader')?'header':null);
        return key?{path:key,namespace:'mock'}:null;
      });
      b.onLoad({filter:/.*/,namespace:'mock'},args=>({contents:modules[args.path],loader:'js'}));
    }}]});
  return (await import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64')))[name];
}
const render=(Component,props)=>{h.si=h.ri=0;h.effects=[];return Component(props)};
function all(node){if(!node||typeof node!=='object')return [];return [node,...[node.props?.children].flat(Infinity).flatMap(all)]}
const byName=(tree,name)=>all(tree).filter(n=>n.type?.name===name);
const button=tree=>all(tree).find(n=>n.type==='button'&&n.props.className?.includes('sheet__cta'));

const Sheet=await component('src/components/RideSheet.tsx');
const ride={id:42,driver_id:7,from_city:'Уфа',to_city:'Сибай',depart_at:'2030-01-01T10:00:00',seats_left:3,seats_total:3,price:500,driver_name:'Ринат',driver_rating:5,driver_car:'Lada',category:'regular'};
const book=async()=>{ await button(render(Sheet,{ride,onClose(){}})).props.onClick(); };

let failures=0;
const test=async(name,fn)=>{ try{ await fn(); console.log('✓',name); }catch(e){ failures++; console.log('✗',name,'\n ',e.message); } };

await test('без точек тело брони прежнее — meeting/alighting нет', async () => {
  h.reset();
  await book();
  assert.ok(!('meeting' in h.calls[0]) && !('alighting' in h.calls[0]), JSON.stringify(h.calls[0]));
});

await test('ориентир + пояснение уходят как meeting, высадка не задана — alighting нет', async () => {
  h.reset();
  const tree=render(Sheet,{ride,onClose(){}});
  const pickers=byName(tree,'MeetingPointPicker');
  assert.equal(pickers.length,2,'два пикера: посадка и высадка');
  pickers[0].props.onChange({landmark_id:7,note:'синие ворота'});
  await button(render(Sheet,{ride,onClose(){}})).props.onClick();
  assert.deepEqual(h.calls[0].meeting,{landmark_id:7,note:'синие ворота'});
  assert.ok(!('alighting' in h.calls[0]));
});

await test('булавка уходит как alighting с координатами', async () => {
  h.reset();
  const tree=render(Sheet,{ride,onClose(){}});
  const pickers=byName(tree,'MeetingPointPicker');
  pickers[1].props.onChange({lat:54.7,lng:55.9,note:'у школы'});
  await button(render(Sheet,{ride,onClose(){}})).props.onClick();
  assert.deepEqual(h.calls[0].alighting,{lat:54.7,lng:55.9,note:'у школы'});
  assert.ok(!('meeting' in h.calls[0]));
});

await test('clear — ключ не шлём вовсе', async () => {
  h.reset();
  const tree=render(Sheet,{ride,onClose(){}});
  byName(tree,'MeetingPointPicker')[0].props.onChange({clear:true});
  await button(render(Sheet,{ride,onClose(){}})).props.onClick();
  assert.ok(!('meeting' in h.calls[0]));
});


const Picker = await component('src/components/MeetingPointPicker.tsx', 'MeetingPointPicker');
const Actions = await component('src/components/MeetingActions.tsx', 'MeetingActions');
const textOf = node => {
  if (typeof node === 'string') return node;
  return !node || typeof node !== 'object' ? '' : [node.props?.children].flat(Infinity).map(textOf).join(' ');
};
const click = (tree, label) => {
  const node = all(tree).find(n => n.type === 'button' && textOf(n).includes(label));
  assert.ok(node, 'нет кнопки '+label); return node.props.onClick();
};
const flush = async () => { for (let i=0;i<8;i++) await Promise.resolve(); };
const effects = async () => { const cleanups = h.effects.map(fn => fn()); await flush(); return () => cleanups.forEach(fn => typeof fn === 'function' && fn()); };

await test('ориентиры: загрузка, башкирские чипы и новый город заменяет старые', async () => {
  h.reset(); h.lang='ba'; let value=null;
  const onChange=v=>{value=v};
  let props={city:'Уфа',title:'Место',value,onChange};
  click(render(Picker,props),'Место');
  let tree=render(Picker,props);
  assert.ok(textOf(tree).includes('тейәйбеҙ'));
  const cleanup=await effects();
  tree=render(Picker,props);
  assert.ok(textOf(tree).includes('мәсет янында Уфа'));
  click(tree,'мәсет янында Уфа');
  assert.equal(value.landmark_id,7);
  cleanup();
  props={...props,city:'Сибай',value};
  render(Picker,props); await effects();
  tree=render(Picker,{...props,value});
  assert.equal(value,null,'точка прежней остановки очищена');
  assert.ok(textOf(tree).includes('мәсет янында Сибай'));
  assert.ok(!textOf(tree).includes('мәсет янында Уфа'));
});

await test('ошибка ориентиров дает повтор, пустой ответ дает понятный путь на карту', async () => {
  h.reset(); h.lookup=async()=>{throw new Error('offline')};
  const props={city:'Уфа',title:'Место',value:null,onChange(){}};
  click(render(Picker,props),'Место'); render(Picker,props); await effects();
  let tree=render(Picker,props);
  assert.ok(all(tree).some(n=>n.props.role==='alert'));
  click(tree,'Повторить');
  h.lookup=async()=>[]; render(Picker,props); await effects();
  tree=render(Picker,props);
  assert.ok(textOf(tree).includes('Ориентиров пока нет'));
  assert.ok(textOf(tree).includes('На карте'));
});

await test('пояснение без ориентира сохраняется сразу; движение карты сохраняет свежий текст', async () => {
  h.reset(); let value=null;
  const props=()=>({city:'Уфа',title:'Место',value,onChange:v=>{value=v}});
  click(render(Picker,props()),'Место');
  let tree=render(Picker,props());
  all(tree).find(n=>n.type==='input').props.onChange({target:{value:'синие ворота'}});
  assert.equal(value.note,'синие ворота');
  tree=render(Picker,props()); click(tree,'На карте');
  tree=render(Picker,props());
  const move=all(tree).find(n=>n.type==='ymap'||n.type?.name==='YandexMap')?.props.onCenterChange;
  assert.ok(move);
  all(tree).find(n=>n.type==='input').props.onChange({target:{value:'новые ворота'}});
  render(Picker,props());
  move({lat:52.7,lng:58.6});
  assert.deepEqual(value,{lat:52.7,lng:58.6,note:'новые ворота'});
});

await test('старая карта не меняет выбранное место после закрытия, очистки, смены города или выбора ориентира', async () => {
  const coordinate = { lat: 52.7, lng: 58.6 };
  const map = tree => all(tree).find(n => n.type === 'ymap');
  for (const action of ['hide-map', 'close-picker', 'done', 'clear', 'city', 'landmark']) {
    h.reset();
    let value = { ...coordinate, note: 'у ворот' };
    let changes = 0;
    const onChange = next => { value = next; changes++; };
    const props = (city = 'Уфа') => ({ city, title: 'Место', value, onChange });
    click(render(Picker, props()), 'Место');
    render(Picker, props());
    const cleanup = await effects();
    click(render(Picker, props()), 'На карте');
    const tree = render(Picker, props());
    const moveOldMap = map(tree).props.onCenterChange;
    switch (action) {
      case 'hide-map': click(tree, 'На карте'); break;
      case 'close-picker': click(tree, 'Место'); break;
      case 'done': click(tree, 'Готово'); break;
      case 'clear': click(tree, 'Убрать место'); break;
      case 'city': render(Picker, props('Сибай')); break;
      case 'landmark': click(tree, 'у мечети Уфа'); break;
    }
    const expectedChanges = changes;
    moveOldMap({ lat: 54.7, lng: 55.9 });
    assert.equal(changes, expectedChanges, action);
    if (action === 'clear') assert.deepEqual(value, { clear: true });
    if (action === 'landmark') assert.deepEqual(value, { landmark_id: 7, note: 'у ворот' });
    const nextCity = action === 'city' ? 'Сибай' : 'Уфа';
    if (action === 'city') {
      cleanup();
      render(Picker, props(nextCity));
      await effects();
      assert.equal(value, null);
    }
    let nextTree = render(Picker, props(nextCity));
    assert.equal(map(nextTree), undefined, action);
    if (['close-picker', 'done', 'clear'].includes(action)) {
      click(nextTree, 'Место');
      nextTree = render(Picker, props(nextCity));
      assert.equal(map(nextTree), undefined, action);
    }
    click(nextTree, 'На карте');
    const moveNewMap = map(render(Picker, props(nextCity))).props.onCenterChange;
    const changesAfterReopen = changes;
    moveOldMap({ lat: 54.7, lng: 55.9 });
    assert.equal(changes, changesAfterReopen, action);
    moveNewMap(coordinate);
    assert.equal(changes, changesAfterReopen + 1, action);
    assert.equal(value.lat, coordinate.lat);
    assert.equal(value.lng, coordinate.lng);
  }
});

const details={role:'passenger',status:'confirmed',booking_id:73,meeting_version:4,from_city:'Уфа',to_city:'Сибай',meet_lat:54.7,meet_lng:55.9};
await test('до посадки меняются обе точки, в машине только высадка', async () => {
  for (const status of ['pending','confirmed','onboard']) {
    h.reset(); const tree=render(Actions,{details:{...details,status},onChanged(){}});
    assert.equal(textOf(tree).includes('Изменить место посадки'),status!=='onboard');
    assert.ok(textOf(tree).includes('Изменить место высадки'));
  }
});

await test('отказ сохраняет редактор с черновиком, повтор не дублируется, успех закрывает', async () => {
  h.reset(); let changes=0;
  const props={details,onChanged(){changes++}};
  click(render(Actions,props),'Изменить место высадки');
  let tree=render(Actions,props);
  byName(tree,'MeetingPointPicker')[0].props.onChange({lat:52.7,lng:58.6,note:'у ворот'});
  let reject;
  h.propose=()=>new Promise((_,no)=>{reject=no});
  tree=render(Actions,props);
  click(tree,'Предложить водителю'); click(tree,'Предложить водителю');
  assert.equal(h.calls.length,1);
  assert.ok(all(render(Actions,props)).find(n=>n.type==='button'&&textOf(n).includes('Предложить водителю')).props.disabled);
  reject(new Error('offline')); await flush();
  tree=render(Actions,props);
  assert.deepEqual(byName(tree,'MeetingPointPicker')[0].props.value,{lat:52.7,lng:58.6,note:'у ворот'});
  assert.ok(all(tree).some(n=>n.props.role==='alert'));
  assert.equal(changes,0);
  h.propose=async()=>({});
  click(tree,'Предложить водителю'); await flush();
  assert.equal(byName(render(Actions,props),'MeetingPointPicker').length,0);
  assert.equal(changes,1);
  assert.deepEqual(h.calls[1],[73,'dropoff',{lat:52.7,lng:58.6,note:'у ворот'},4]);
});

await test('из деталей через редактор пояснения ориентир сохраняет свой id при отправке', async () => {
  for (const kind of ['pickup','dropoff']) {
    h.reset();
    const props={details:{...details,meet_landmark_id:17,alight_landmark_id:18},onChanged(){}};
    click(render(Actions,props),kind==='pickup'?'Изменить место посадки':'Изменить место высадки');
    const pickerProps=byName(render(Actions,props),'MeetingPointPicker')[0].props;
    assert.equal(pickerProps.value.landmark_id,kind==='pickup'?17:18);
    const actionState=h.states, actionRefs=h.refs;
    h.states=[];h.refs=[];
    click(render(Picker,pickerProps),pickerProps.title);
    const picker=render(Picker,pickerProps);
    all(picker).find(n=>n.type==='input').props.onChange({target:{value:'новое пояснение'}});
    h.states=actionState;h.refs=actionRefs;
    click(render(Actions,props),'Предложить водителю');await flush();
    assert.equal(h.calls[0][1],kind);
    assert.equal(h.calls[0][2].landmark_id,kind==='pickup'?17:18);
    assert.equal(h.calls[0][2].note,'новое пояснение');
  }
});

const DriverAnswer=await component('src/components/DriverPassengers.tsx','DriverProposalAnswer');
await test('ответ водителя: удержанный запрос не дублируется; ошибка видна и повторяет тот же выбор RU/BA', async () => {
  for (const lang of ['ru','ba']) {
    h.reset();h.lang=lang;let changes=0, reject;
    const b={booking_id:42,status:'confirmed',meeting_version:3,meeting_proposal:{proposal_id:'p42',kind:'pickup'}};
    const props={b,onDone(){changes++}};
    h.answer=()=>new Promise((_,no)=>{reject=no});
    const leave=lang==='ru'?'Оставить прежнее':'Иҫкене ҡалдырыу';
    const tree=render(DriverAnswer,props);
    click(tree,leave);click(tree,leave);
    assert.equal(h.calls.length,1);
    assert.ok(all(render(DriverAnswer,props)).filter(n=>n.type==='button').every(n=>n.props.disabled));
    reject(new Error('offline'));await flush();
    let failed=render(DriverAnswer,props);
    assert.ok(all(failed).some(n=>n.props.role==='alert'));
    assert.ok(textOf(failed).includes(lang==='ru'?'Не получилось отправить ответ':'Яуапты ебәреп булманы'));
    assert.equal(changes,0);
    h.answer=async()=>({});
    click(failed,lang==='ru'?'Повторить':'Ҡабатлау');await flush();
    assert.equal(h.calls.length,2);
    assert.deepEqual(h.calls[1],[42,'p42',3,false]);
    assert.equal(changes,1);
  }
});
await test('старое предложение водителю показывает отказ и перечитывает кабинет', async () => {
  h.reset();let changes=0;
  const stale=new h.ApiError('Место изменилось');stale.code='meeting_point_stale';
  h.answer=async()=>{throw stale};
  const tree=render(DriverAnswer,{b:{booking_id:42,status:'confirmed',meeting_version:3,meeting_proposal:{proposal_id:'p42',kind:'pickup'}},onDone(){changes++}});
  click(tree,'Согласиться');await flush();
  assert.equal(changes,1);
});

await test('clear и обычное предложение показывают разные последствия: RU/BA, посадка/высадка', async () => {
  for (const lang of ['ru','ba']) for (const kind of ['pickup','dropoff']) for (const clear of [true,false]) {
    h.reset();h.lang=lang;
    const props={b:{booking_id:42,status:'confirmed',meeting_version:3,meeting_proposal:{proposal_id:'p42',kind,point:{clear,lat:52.7,lng:58.6,note:'Пояснение'}}},onDone(){}};
    const tree=render(DriverAnswer,props);
    const title=clear
      ? kind==='pickup' ? (lang==='ru'?'Пассажир просит убрать точное место посадки':'Пассажир ултырыуҙың теүәл урынын алып ташлауҙы һорай') : (lang==='ru'?'Пассажир просит убрать точное место высадки':'Пассажир төшөүҙең теүәл урынын алып ташлауҙы һорай')
      : kind==='pickup' ? (lang==='ru'?'Пассажир просит встретиться в другом месте':'Пассажир башҡа урында осрашыуҙы һорай') : (lang==='ru'?'Пассажир просит высадить в другом месте':'Пассажир башҡа урында төшөрөүҙе һорай');
    const explanation=kind==='pickup' ? (lang==='ru'?'Точную точку и пояснение уберём. Место посадки по маршруту останется прежним; детали можно уточнить в чате.':'Теүәл нөктәне һәм аңлатманы алып ташлаясаҡбыҙ. Маршрут буйынса ултырыу урыны үҙгәрмәй; ентеклектәрҙе чатта асыҡларға мөмкин.') : (lang==='ru'?'Точную точку и пояснение уберём. Место высадки по маршруту останется прежним; детали можно уточнить в чате.':'Теүәл нөктәне һәм аңлатманы алып ташлаясаҡбыҙ. Маршрут буйынса төшөү урыны үҙгәрмәй; ентеклектәрҙе чатта асыҡларға мөмкин.');
    assert.ok(textOf(tree).includes(title));
    assert.equal(textOf(tree).includes(explanation),clear);
    assert.equal(textOf(tree).includes('Пояснение'),!clear);
    assert.equal(all(tree).filter(n=>n.type==='a').length,clear?0:1);
    if (clear) {
      click(tree,lang==='ru'?'Согласиться':'Ризалашыу');await flush();
      assert.deepEqual(h.calls[0],[42,'p42',3,true]);
    }
  }
});

if (failures) { console.log(`\nПРОВАЛЕНО: ${failures}`); process.exit(1); }
console.log('\nВСЁ СОШЛОСЬ');
