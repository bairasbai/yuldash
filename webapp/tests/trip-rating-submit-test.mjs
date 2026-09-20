// Исполняем настоящий экран: done -> звёзды -> API -> ошибка -> повтор.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { transform } from 'esbuild';
const source=readFileSync(new URL('../src/screens/ActiveTripScreen.tsx',import.meta.url),'utf8');
const h=globalThis.__tripRating={states:[],refs:[],si:0,ri:0,effects:[],calls:[],fail:true,lang:'ru'};
const definitions={
  useState:`v=>{const i=h.si++;if(!(i in h.states))h.states[i]=typeof v==='function'?v():v;return[h.states[i],v=>h.states[i]=typeof v==='function'?v(h.states[i]):v]}`,
  useRef:`v=>{const i=h.ri++;return h.refs[i]??={current:v}}`,
  useEffect:`fn=>h.effects.push(fn)`,useCallback:`fn=>fn`,
  useLang:`()=>({lang:h.lang,appText:(ru,ba)=>h.lang==='ba'?ba:ru})`,
  useParams:`()=>({id:'42'})`,useNavigate:`()=>()=>{}`,
  useAuth:`()=>({user:{id:7}})`,
  fetchBookingDetails:`async id=>({id,status:'done',role:'passenger',from_city:'A',to_city:'B',depart_at:'2026-09-12T10:00:00Z',driver_name:'Test',price:100,seats:1})`,
  rateBooking:`async(...args)=>{h.calls.push(args);if(h.fail)throw new Error('network offline');return {ok:true}}`,
  ratingTags:`()=>[]`,
  isTerminalBookingStatus:`st=>st==='done'||st==='cancelled'`,
};
// Dependencies are inert; the component's loading, form and submission code is unchanged.
const names=[];
const body=source.replace(/import\s+([\s\S]*?)\s+from\s+"[^"]+";/g,(_,bindings)=>{
  const first=bindings.split('{')[0].trim().replace(/,$/,'').trim();
  if(first&&!first.startsWith('type '))names.push(first);
  const named=bindings.match(/\{([\s\S]*)\}/)?.[1];
  if(named)for(const entry of named.split(',')){const n=entry.trim();if(n&&!n.startsWith('type '))names.push(n);}
  return '';
});
const prelude=`const h=globalThis.__tripRating;const __jsx=(type,props,...children)=>({type,props:{...props,children}}),__fragment='fragment';\n`+
  names.map(n=>`const ${n}=${definitions[n]??'()=>null'};`).join('\n');
const compiled=await transform(prelude+body,{loader:'tsx',format:'esm',jsx:'transform',jsxFactory:'__jsx',jsxFragment:'__fragment'});
const Screen=(await import('data:text/javascript;base64,'+Buffer.from(compiled.code).toString('base64'))).default;
const all=n=>n&&typeof n==='object'?[n,...[n.props?.children].flat(Infinity).flatMap(all)]:[];
const texts=n=>!n||typeof n!=='object'?'':[n.props?.children].flat(Infinity).map(v=>typeof v==='string'?v:texts(v)).join('');
const render=()=>{h.si=h.ri=0;h.effects=[];return Screen();};
const flush=()=>new Promise(setImmediate);
render();h.effects.forEach(fn=>fn());await flush();
let tree=render();
const stars=all(tree).filter(n=>n.type==='button'&&n.props.className?.startsWith('rate-star'));
assert.equal(stars.length,5,'Завершённая бронь открывает пять звёзд для оценки');
stars[4].props.onClick();
tree=render();
const send=()=>all(render()).find(n=>n.type==='button'&&texts(n)==='Отправить оценку');
send().props.onClick();await flush();
assert.deepEqual(h.calls,[[42,5,'',[]]],'Оценка отправляется по ID брони');
tree=render();
assert.ok(!texts(tree).includes('Спасибо за оценку'),'Сетевой отказ не изображает успешную оценку');
assert.ok(send(),'После ошибки доступен повтор');
assert.ok(all(tree).some(n=>n.props.role==='alert'),'Ошибка видна человеку');
h.fail=false;
send().props.onClick();await flush();
tree=render();
assert.deepEqual(h.calls,[[42,5,'',[]],[42,5,'',[]]],'Повтор отправляет сохранённую оценку той же брони');
assert.ok(texts(tree).includes('Спасибо за оценку'),'Успех показан только после ответа API');
assert.ok(!send(),'После подтверждённого успеха форма закрыта');
console.log('✓ done: форма оценки доступна; ошибка сохраняет оценку и повтор; успех требует ответа API');
delete globalThis.__tripRating;
