"""Opt-in API35 task/process audit on the root-owned emulator, using a local HTTP fixture.

Build with YULDASH_DEBUG_API_BASE_URL=http://127.0.0.1:5198 and the default runner.
Only session/language are provisioned by instrumentation; UI/save/kill/restore run outside it.
No Activity Bundle, ViewModel, booking context or review is copied into the restored process.
"""
from __future__ import annotations
import argparse,hashlib,json,re,subprocess,threading,time,traceback
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
ADB=Path(r'C:\Users\Bayra\AppData\Local\Android\Sdk\platform-tools\adb.exe')
SERIAL='emulator-5580';PACKAGE='com.yuldash.app';PORT=5198
TOKEN='header.eyJzdWIiOiIxMSJ9.signature';DRAFT='process_draft_42'
class UiSnapshotUnavailable(Exception):pass

class Fixture:
    def __init__(self):
        self.lock=threading.Lock();self.records=[];self.language='Ru';self.thanked=False
        outer=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):self.respond()
            def do_POST(self):self.respond()
            def do_PATCH(self):self.respond()
            def respond(self):
                raw=self.rfile.read(int(self.headers.get('Content-Length','0')))
                path=self.path.split('?')[0];method=self.command;status=200;body={}
                with outer.lock:
                    outer.records.append({'language':outer.language,'method':method,'path':path,
                        'auth_matches_fixture':self.headers.get('Authorization')=='Bearer '+TOKEN,
                        'body':raw.decode('utf-8'),'monotonic':time.monotonic()})
                    if self.headers.get('Upgrade','').lower()=='websocket':
                        status=400;body={'detail':'local_fixture_has_no_websocket'}
                    elif path=='/bookings/mine':
                        body={'items':[{'id':42,'ride_id':9,'status':'done','seats':1,'from_city':'Уфа','to_city':'Бирск','price':400,'driver_name':'Водитель','depart_at':'2026-10-07T12:00:00Z'}]}
                    elif path=='/bookings/42/role':body={'role':'passenger','status':'done','driver_phase':''}
                    elif path=='/bookings/42/messages':body={'items':[]}
                    elif path=='/bookings/42/boarding-code':body={'code':''}
                    elif path=='/bookings/42/tip':body={'already_thanked':outer.thanked,'driver_name':'Водитель'}
                    elif path=='/trips/42/receipt':
                        body={'booking_id':42,'ride_id':9,'role':'passenger','from_city':'Уфа','to_city':'Бирск','depart_at':'2026-10-07T12:00:00Z','seats':1,'amount':400,'pay_method':'cash','paid':True,'counterparty_name':'Водитель','my_stars':0}
                    elif method=='POST' and path=='/bookings/42/thanks':outer.thanked=True
                    elif method=='POST' and path=='/bookings/42/rate':pass
                    elif method!='GET' and path.startswith('/bookings/'):
                        status=405;body={'detail':'unexpected_booking_write'}
                    else:body={'items':[],'role':'passenger'}
                data=json.dumps(body,ensure_ascii=False).encode('utf-8')
                self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8')
                self.send_header('Content-Length',str(len(data)));self.end_headers()
                try:self.wfile.write(data)
                except (BrokenPipeError,ConnectionResetError):pass
        self.server=ThreadingHTTPServer(('127.0.0.1',PORT),Handler)
        self.server.daemon_threads=True
        self.worker=threading.Thread(target=self.server.serve_forever,name='completed-process-fixture');self.worker.start()
    def start_case(self,language):
        with self.lock:self.language=language;self.thanked=False
    def posts(self,language,suffix):
        with self.lock:return [r for r in self.records if r['language']==language and r['method']=='POST' and r['path']=='/bookings/42/'+suffix]
    def close(self):
        self.server.shutdown();self.server.server_close();self.worker.join(5);assert not self.worker.is_alive()

class Audit:
    def __init__(self,folder:Path):
        self.folder=folder;self.calls=[];self.sequence=0;self.cases=[];self.apks={}
    def call(self,label,*args,ok=True,timeout=45):
        start=time.monotonic();p=subprocess.run([str(ADB),'-s',SERIAL,*args],capture_output=True,timeout=timeout)
        self.sequence+=1
        row={'index':self.sequence,'label':label,'args':list(args),'exit':p.returncode,'seconds':time.monotonic()-start}
        self.calls.append(row)
        if ok:assert p.returncode==0,(label,p.stdout.decode('utf-8',errors='replace'),p.stderr.decode('utf-8',errors='replace'))
        return p
    def text(self,label,*args,ok=True,save=False,timeout=45):
        p=self.call(label,*args,ok=ok,timeout=timeout);s=(p.stdout+p.stderr).decode('utf-8',errors='replace')
        if save:(self.folder/(label+'.log')).write_text(s,encoding='utf-8')
        return s.strip()
    def pid(self,required=True):
        s=self.text('pid','shell','pidof',PACKAGE,ok=False)
        if not required:return int(s) if s.isdigit() else None
        assert s.isdigit(),s
        return int(s)
    def snapshot(self,label):
        self.text(label+'-dump','shell','uiautomator','dump','/data/local/tmp/b02-completed-process.xml',save=True)
        b=self.call('read-ui','exec-out','cat','/data/local/tmp/b02-completed-process.xml').stdout
        (self.folder/(label+'.xml')).write_bytes(b)
        try:return ET.fromstring(b)
        except ET.ParseError as e:raise UiSnapshotUnavailable(b[:300].decode('utf-8',errors='replace')) from e
    def screenshot(self,label):
        b=self.call('screenshot','exec-out','screencap','-p').stdout
        assert b.startswith(b'\x89PNG\r\n\x1a\n')
        p=self.folder/(label+'.png');p.write_bytes(b)
        return {'path':p.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(b).hexdigest()}
    def find(self,label,predicate,seconds=18,scroll=False):
        end=time.monotonic()+seconds;swipes=0
        while time.monotonic()<end:
            try:xml=self.snapshot(label)
            except UiSnapshotUnavailable:time.sleep(.3);continue
            nodes=[n for n in xml.iter('node') if predicate(n)]
            if nodes:return nodes[0],xml
            # Explicit permission denial on this isolated, no-location-needed Completed path.
            deny=[n for n in xml.iter('node') if n.get('resource-id','').endswith('/permission_deny_button')]
            if deny:self.tap(deny[0]);continue
            if scroll and swipes<5:
                lists=[n for n in xml.iter('node') if n.get('scrollable')=='true']
                assert lists,'No observed scroll container'
                b=[int(x) for x in re.findall(r'\d+',lists[0].get('bounds',''))];assert len(b)==4
                x=(b[0]+b[2])//2;top=b[1]+(b[3]-b[1])//4;bottom=b[3]-(b[3]-b[1])//4
                start,finish=(top,bottom) if scroll=='up' else (bottom,top)
                self.text('scroll','shell','input','swipe',str(x),str(start),str(x),str(finish),'350');swipes+=1
            time.sleep(.25)
        self.screenshot(label+'-timeout')
        raise AssertionError('UI state not observed: '+label)
    @staticmethod
    def text_is(text):return lambda n:n.get('text')==text
    @staticmethod
    def desc_is(text):return lambda n:n.get('content-desc')==text
    def tap(self,node):
        v=[int(x) for x in re.findall(r'\d+',node.get('bounds',''))];assert len(v)==4 and v[2]>v[0] and v[3]>v[1]
        self.text('tap','shell','input','tap',str((v[0]+v[2])//2),str((v[1]+v[3])//2))
    def click(self,label,text,scroll=False,description=False):
        n,_=self.find(label,self.desc_is(text) if description else self.text_is(text),scroll=scroll);self.tap(n)
    def activities(self,label):return self.text(label,'shell','dumpsys','activity','activities',save=True)
    @staticmethod
    def task_id(dump):
        ids=re.findall(r'ActivityRecord\{[^\n]*\bcom\.yuldash\.app/\.MainActivity\s+t(\d+)',dump)
        assert len(set(ids))==1,('Expected one owned MainActivity task',ids)
        return int(ids[0])
    def receipt(self,label,language,seconds=25):
        # Completed also has a grid item named "Квитанция". Require the title beside
        # the actual Back icon, then the loaded route in that same UI hierarchy.
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            try:xml=self.snapshot(label)
            except UiSnapshotUnavailable:time.sleep(.3);continue
            nodes=list(xml.iter('node'))
            backs=[n for n in nodes if n.get('content-desc')==('Назад' if language=='Ru' else 'Артҡа')]
            titles=[n for n in nodes if n.get('text')=='Квитанция']
            for back in backs:
                b=[int(x) for x in re.findall(r'\d+',back.get('bounds',''))]
                for title in titles:
                    t=[int(x) for x in re.findall(r'\d+',title.get('bounds',''))]
                    if len(b)==len(t)==4 and max(b[1],t[1])<min(b[3],t[3]) and any(n.get('text')=='Уфа → Бирск' for n in nodes):
                        return xml
            time.sleep(.25)
        self.screenshot(label+'-timeout')
        raise AssertionError('Loaded receipt toolbar/card not observed: '+label)
    @staticmethod
    def saved_record(dump):
        record=re.search(r'\*\s+Hist\s*#\d+:\s+ActivityRecord\{([^\n]*com\.yuldash\.app/\.MainActivity\s+t\d+[^\n]*)\}(.*?)(?=\n\s*\* Hist|\n\s*Task\{|\Z)',dump,re.S)
        assert record,'Owned Activity record absent'
        bundle=re.search(r'mHaveState=true\s+mIcicle=Bundle\[mParcelledData.dataSize=(\d+)\]',record.group(2))
        assert bundle and int(bundle.group(1))>0,'Owned Activity has no saved Bundle'
        return record.group(1),record.group(2),int(bundle.group(1))
    def journey(self,fixture,language):
        fixture.start_case(language)
        self.text(language+'-clear','shell','pm','clear',PACKAGE,save=True)
        out=self.text(language+'-provision','shell','am','instrument','-w','-r','-e','class',
            'com.yuldash.app.CompletedProcessProvisionInstrumentedTest','-e','completedProcessAudit','provision',
            '-e','auditLanguage',language,PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner',save=True,timeout=90)
        assert 'OK (1 test)' in out and 'disk_session_only=true' in out and 'no_ui_or_saved_state=true' in out,out
        # Before the first launcher only: end provisioning; it has not created an Activity/task.
        self.text(language+'-end-provision','shell','am','force-stop',PACKAGE)
        self.text(language+'-initial-launch','shell','am','start','-W','-a','android.intent.action.MAIN',
            '-c','android.intent.category.LAUNCHER','-n',PACKAGE+'/.MainActivity','--es','type','booking_done','--es','id','42',save=True)
        completed='Поездка завершена' if language=='Ru' else 'Сәфәр тамамланды'
        self.find(language+'-completed-initial',self.text_is(completed),seconds=25)
        first_pid=self.pid();initial_task=self.task_id(self.activities(language+'-initial-task'))
        if language=='Ru':
            self.click(language+'-star4','4 звезды',description=True)
            self.click(language+'-ontime','Приехал вовремя',scroll=True)
            self.click(language+'-expand','Добавить пару слов',scroll=True)
            n,_=self.find(language+'-review-field',lambda n:n.get('class')=='android.widget.EditText',scroll=True);self.tap(n)
            self.text('type-draft','shell','input','text',DRAFT)
            self.text('hide-keyboard','shell','input','keyevent','KEYCODE_BACK')
            self.find(language+'-draft-before',self.text_is(DRAFT));self.screenshot(language+'-draft-before')
            assert not fixture.posts(language,'rate')
        else:
            self.click(language+'-thanks','«Рәхмәт» әйтеү',scroll=True)
            self.find(language+'-thanks-sent',self.text_is('Рәхмәт ебәрелде'))
            assert len(fixture.posts(language,'thanks'))==1
        self.click(language+'-open-receipt','Квитанция' if language=='Ru' else 'Сәфәр квитанцияһы',scroll=True)
        self.receipt(language+'-receipt-before',language)
        self.screenshot(language+'-receipt-before')
        self.text(language+'-background','shell','input','keyevent','KEYCODE_HOME');time.sleep(1)
        stopped=self.activities(language+'-stopped-task');assert self.task_id(stopped)==initial_task
        # STOPPED must belong to the target Activity record, not the launcher.
        identity,record,bundle_bytes=self.saved_record(stopped)
        assert 'state=STOPPED' in record,'Owned Activity not STOPPED before kill'
        assert self.pid()==first_pid
        self.text(language+'-kill-owned-pid','shell','run-as',PACKAGE,'kill','-9',str(first_pid),save=True)
        end=time.monotonic()+8
        while self.pid(required=False) is not None and time.monotonic()<end:time.sleep(.25)
        assert self.pid(required=False) is None,'Owned app process remains alive'
        dead=self.activities(language+'-task-after-kill');assert self.task_id(dead)==initial_task
        dead_identity,dead_record,dead_bundle_bytes=self.saved_record(dead)
        assert dead_identity==identity and dead_bundle_bytes==bundle_bytes
        assert 'state=DESTROYED' in dead_record and 'app=null' in dead_record
        self.text(language+'-resume-launcher','shell','am','start','-W','-f','0x10200000',
            '-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',PACKAGE+'/.MainActivity',save=True)
        second_pid=self.pid();assert second_pid!=first_pid
        restored=self.activities(language+'-restored-task');assert self.task_id(restored)==initial_task
        assert identity in restored,'Owned ActivityRecord identity changed on resume'
        self.receipt(language+'-receipt-after',language)
        self.screenshot(language+'-receipt-after')
        assert not fixture.posts(language,'rate'),'Automatic rating POST across process death'
        if language=='Ba':assert len(fixture.posts(language,'thanks'))==1,'Repeated thanks before Back'
        self.click(language+'-back','Назад' if language=='Ru' else 'Артҡа',description=True)
        self.find(language+'-completed-after',self.text_is(completed))
        if language=='Ru':
            self.find(language+'-draft-after',self.text_is(DRAFT),scroll=True)
            self.screenshot(language+'-draft-after')
            tag,_=self.find(language+'-ontime-after',self.text_is('Приехал вовремя'),scroll='up')
            # Selected attribute may be on merged clickable parent; exact tags are also verified in POST.
            button,_=self.find(language+'-ready-after',self.text_is('Отправить оценку'))
            assert button.get('enabled')=='true'
            assert not fixture.posts(language,'rate')
            self.tap(button);end=time.monotonic()+10
            while not fixture.posts(language,'rate') and time.monotonic()<end:time.sleep(.1)
            posts=fixture.posts(language,'rate');assert len(posts)==1
            payload=json.loads(posts[0]['body']);assert payload=={'stars':4,'tags':'ontime','text':DRAFT},payload
            assert posts[0]['auth_matches_fixture']
        else:
            self.find(language+'-saved-after',self.text_is('Рәхмәт һаҡланды'),scroll=True)
            self.screenshot(language+'-saved-after');self.click(language+'-saved-pointer','Рәхмәт ебәрелде')
            assert len(fixture.posts(language,'thanks'))==1 and not fixture.posts(language,'rate')
            assert fixture.posts(language,'thanks')[0]['auth_matches_fixture']
        self.cases.append({'id':'completed_task_process_'+language,'language':language,'initial_pid':first_pid,
            'restored_pid':second_pid,'same_task_id':initial_task,'receipt_before_and_after':True,
            'same_activity_record_after_kill':identity,'saved_bundle_bytes_before_and_after_kill':bundle_bytes,
            'real_main_activity':True,'no_instrumentation_during_save_kill_restore':True,
            'rating_posts':len(fixture.posts(language,'rate')),'thanks_posts':len(fixture.posts(language,'thanks')),
            'exact_rating_payload' : language=='Ru','kill':'run-as target app SIGKILL after Home/STOPPED',
            'restore_inputs':'launcher MAIN/category only; no booking/id/draft/VM/Bundle/snapshot passed'})
        self.text(language+'-finish-owned-app','shell','am','force-stop',PACKAGE)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run-isolated-audit',action='store_true');p.add_argument('--output',required=True)
    args=p.parse_args();assert args.run_isolated_audit,'Explicit isolated-device authorization flag required'
    folder=Path(args.output).resolve();assert folder.is_relative_to((ROOT/'test-results').resolve());folder.mkdir(exist_ok=False)
    audit=Audit(folder);fixture=None;reverse=False;success=False;failure=None
    try:
        assert audit.text('sdk','shell','getprop','ro.build.version.sdk')=='35'
        assert audit.text('qemu','shell','getprop','ro.kernel.qemu')=='1'
        assert audit.text('flight','shell','cmd','connectivity','airplane-mode')=='enabled'
        assert 'Active default network: none' in audit.text('connectivity','shell','dumpsys','connectivity',save=True)
        fixture=Fixture();audit.text('reverse','reverse','tcp:5198','tcp:5198');reverse=True
        for i,path in enumerate([ROOT/'android/app/build/outputs/apk/debug/app-debug.apk',ROOT/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']):
            audit.apks[path.relative_to(ROOT).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest()
            audit.text('install'+str(i),'install','-r',str(path),save=True)
        for language in ('Ru','Ba'):audit.journey(fixture,language)
        success=True
    except Exception as e:
        failure=repr(e);(folder/'failure.log').write_text(traceback.format_exc(),encoding='utf-8')
        try:audit.screenshot('failure');audit.snapshot('failure-ui');audit.activities('failure-task')
        except Exception:pass
        raise
    finally:
        if fixture:
            (folder/'http-records.json').write_text(json.dumps(fixture.records,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');fixture.close()
        if reverse:audit.text('remove-own-reverse','reverse','--remove','tcp:5198',ok=False)
        audit.text('stop-owned-audit-app','shell','am','force-stop',PACKAGE,ok=False)
        d={'success':success,'failure':failure,'cases':audit.cases,'unique_process_ui_cases':len(audit.cases),
            'apk_sha256':audit.apks,'calls':audit.calls,'serial':SERIAL,'sdk':35,'fixture_worker_stopped':fixture is not None and not fixture.worker.is_alive(),
            'scope':'Actual production Application/MainActivity on isolated emulator and compiled debug loopback URL. Native only provisions synthetic disk session/language, not nav/id/draft. Actual initial booking_done intent, UI taps, background ActivityManager task, targeted SIGKILL and plain launcher resume with new PID. No physical phone/low-memory selection/live backend/JWT/push delivery/WS/payment/pending accepted POST/role-language matrix. BA flag can hydrate from server GET.'}
        (folder/'result.json').write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'success':success,'cases':len(audit.cases),'failure':failure,'output':str(folder)},ensure_ascii=False),flush=True)
if __name__=='__main__':main()
