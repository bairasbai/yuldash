const { chromium } = require('playwright');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const out = '/tmp/yuldash-main-repair-evidence/browser';
const result = { server: 'http://127.0.0.1:4199', browser: null, syntheticHTTP: true, cases: [] };
(async () => {
  const browser = await chromium.launch({ executablePath: '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  result.browser = browser.version();
  try {
    for (const lang of ['ru']) for (const theme of ['light']) {
      for (const method of ['telegram']) for (const action of ['success']) {
        const context = await browser.newContext({ viewport: { width: 411, height: 891 }, colorScheme: theme, serviceWorkers: 'block' });
        const page = await context.newPage();
        const caseResult = {lang, theme, method, action, passed: false, pageErrors: [], external: [], apiPaths: []};
        page.on('pageerror', e => caseResult.pageErrors.push(e.stack));
        try {
          await context.route('**/*', async route => {
            const u = new URL(route.request().url());
            if (u.origin !== result.server) { caseResult.external.push(u.origin); return route.abort(); }
            if (!u.pathname.startsWith('/api/')) return route.continue();
            caseResult.apiPaths.push(u.pathname);
            let body = {};
            if (u.pathname.endsWith('/auth/tg/start')) body = {request_id:'synthetic-request'};
            else if (u.pathname.endsWith('/auth/tg/verify') || u.pathname.endsWith('/auth/verify')) {
              body = {access_token:'synthetic-browser-A',refresh_token:'synthetic-browser-RA',user:{id:1,name:'QA',phone:null}};
            } else if (u.pathname.endsWith('/me')) body = {id:1,name:'QA',phone:null};
            else if (u.pathname.endsWith('/rides/near') || u.pathname.endsWith('/requests/near')) body = {items:[],count:0};
            else if (u.pathname.endsWith('/bookings/mine')) body = [];
            else if (u.pathname.endsWith('/rides/cities')) body = {from:[],to:[]};
            else if (u.pathname.endsWith('/settlements/popular-routes')) body = {routes:[]};
            else if (u.pathname.endsWith('/seasonal-events')) body = {items:[]};
            else if (u.pathname.endsWith('/feed')) body = {items:[],rides_today:0,riders_total:0,drivers_total:0,drivers_active:0};
            else if (u.pathname.endsWith('/config/features')) body = {taxi:false,courier:false,parcels:false,ads:false,business:false};
            else if (u.pathname.endsWith('/auth/request-code')) body = {sent:true};
            else if (u.pathname.endsWith('/events')) body = {ok:true};
            else throw new Error('Unhandled synthetic API response: '+u.pathname);
            await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)});
          });
          await context.addInitScript(({lang,theme}) => {
            localStorage.setItem('yuldash.lang',lang); localStorage.setItem('yuldash.theme',theme);
            window.open = () => null;
            window.__qaTokenWrites = [];
            const set = Storage.prototype.setItem;
            Storage.prototype.setItem = function(k,v) { if(k === 'yuldash.token') window.__qaTokenWrites.push(v); return set.call(this,k,v); };
          }, {lang,theme});
          await page.goto(result.server+'/login');
          if(method==='telegram') await page.locator('.login-tg').click();
          else {
            await page.getByRole('button',{name:lang==='ru'?'Войти по номеру телефона':'Телефон номеры аша инеү',exact:true}).click();
            await page.locator('input[autocomplete=tel]').fill('12345');
            await page.locator('.login-primary').click();
          }
          await page.locator('input[autocomplete=one-time-code]').waitFor();
          await page.evaluate(async () => {
            let started;
            const ready = new Promise(resolve => { started=resolve; });
            void navigator.locks.request('yuldash-session-write', async () => {
              started(); await new Promise(resolve => {window.__qaRelease=resolve;});
            });
            await ready;
          });
          await page.locator('input[autocomplete=one-time-code]').fill('123456');
          await page.locator('.login-primary').click();
          await page.waitForFunction(async()=> (await navigator.locks.query()).pending.some(x=>x.name==='yuldash-session-write'));
          assert.equal(await page.evaluate(()=>localStorage.getItem('yuldash.token')),null);
          if(action==='back') await page.getByRole('button',{name:lang==='ru'?(method==='telegram'?'Назад':'Изменить номер'):(method==='telegram'?'Артҡа':'Номерҙы үҙгәртеү'),exact:true}).click();
          else if(action==='unmount') await page.evaluate(()=>{history.pushState({},'', '/rules');window.dispatchEvent(new PopStateEvent('popstate'));});
          await page.evaluate(()=>window.__qaRelease());
          if(action==='success') { await page.waitForFunction(()=>localStorage.getItem('yuldash.token')==='synthetic-browser-A'); await page.waitForURL('**/map'); }
          else {
            await page.waitForFunction(async()=> !(await navigator.locks.query()).pending.some(x=>x.name==='yuldash-session-write'));
            await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
          }
          const state = await page.evaluate(()=>({token:localStorage.getItem('yuldash.token'),refresh:localStorage.getItem('yuldash.refresh'),writes:window.__qaTokenWrites,path:location.pathname,lang:document.documentElement.lang,theme:document.documentElement.dataset.theme}));
          caseResult.state=state;
          assert.equal(state.token,action==='success'?'synthetic-browser-A':null);
          assert.equal(state.refresh,action==='success'?'synthetic-browser-RA':null);
          assert.deepEqual(state.writes,action==='success'?['synthetic-browser-A']:[]);
          assert.equal(state.lang,lang); assert.equal(state.theme,theme);
          if(action==='back') {
            assert.equal(state.path,'/login');
            if(method==='telegram') await page.locator('.login-tg').waitFor();
            else await page.locator('input[autocomplete=tel]').waitFor();
            await page.waitForFunction(()=>getComputedStyle(document.querySelector('.login-card')).opacity==='1');
            const name=`${lang}-${theme}-${method}-cancel.png`;
            await page.screenshot({path:out+'/'+name,fullPage:true}); caseResult.screenshot=name;
          }
          if(action==='unmount') assert.equal(state.path,'/rules');
          await page.waitForLoadState('networkidle');
          await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
          await context.close();
          assert.deepEqual(caseResult.pageErrors,[]);
          assert.deepEqual(caseResult.external,[]);
          caseResult.passed=true;
          console.log('PASS',lang,theme,method,action);
        } finally {result.cases.push(caseResult);await context.close();fs.writeFileSync(out+'/matrix.json',JSON.stringify(result,null,2));}
      }
    }
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
