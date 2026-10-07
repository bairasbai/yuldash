"""Opt-in real MainActivity pending booking/chat task/process audit on owned API35.

Build with existing debug URL5198/default runner. Provision only disk session/language.
Use actual system SINGLE_TOP delivery while Receipt42 is foreground; hold role43 HTTP
until process kill. Resume via plain launcher; no nav/id/draft/Bundle is provisioned.
"""
from __future__ import annotations
import argparse,hashlib,json,re,threading,time,traceback
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from audit_completed_process import Audit,ROOT,PACKAGE,PORT,TOKEN,SERIAL

class Fixture:
    def __init__(self):
        self.lock=threading.Lock();self.records=[];self.language='Ru';self.pending_received=threading.Event();self.after_kill=threading.Event()
        self.role43_count=0;outer=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):self.respond()
            def do_POST(self):self.respond()
            def respond(self):
                path=self.path.split('?')[0];raw=self.rfile.read(int(self.headers.get('Content-Length','0')))
                status=200;body={};hold=False
                with outer.lock:
                    row={'language':outer.language,'method':self.command,'path':path,'body':raw.decode('utf-8'),
                         'auth_matches_fixture':self.headers.get('Authorization')=='Bearer '+TOKEN,'request_monotonic':time.monotonic()}
                    outer.records.append(row)
                    if path=='/bookings/43/role':
                        outer.role43_count+=1;row['role43_ordinal']=outer.role43_count
                        hold=outer.role43_count==1;row['held_before_kill']=hold
                        outer.pending_received.set()
                    bid=43 if '/43/' in path else 42
                    route='Ишимбай' if bid==43 else 'Бирск';amount=500 if bid==43 else 400
                    if self.headers.get('Upgrade','').lower()=='websocket':status=400;body={'detail':'fixture_no_websocket'}
                    elif path=='/bookings/mine':
                        body={'items':[{'id':bid,'ride_id':9,'status':'done','seats':1,'from_city':'Уфа','to_city':route,'price':amount,'driver_name':'Водитель','depart_at':'2026-10-07T12:00:00Z'}]}
                    elif re.fullmatch(r'/bookings/(42|43)/role',path):body={'role':'passenger','status':'confirmed' if bid==43 else 'done','driver_phase':''}
                    elif re.fullmatch(r'/bookings/(42|43)/details',path):
                        body={'booking_id':bid,'ride_id':9,'role':'passenger','status':'confirmed' if bid==43 else 'done','contact_unlocked':False,'from_city':'Уфа','to_city':route,'depart_at':'2026-10-07T12:00:00Z','seats':1,'price':amount,'pay_method':'cash','driver_name':'Водитель','driver_verified':False}
                    elif re.fullmatch(r'/bookings/(42|43)/tip',path):body={'already_thanked':False,'driver_name':'Водитель'}
                    elif re.fullmatch(r'/bookings/(42|43)/(messages|boarding-code)',path):body={'items':[],'code':''}
                    elif re.fullmatch(r'/trips/(42|43)/receipt',path):
                        body={'booking_id':bid,'ride_id':9,'role':'passenger','from_city':'Уфа','to_city':route,'depart_at':'2026-10-07T12:00:00Z','seats':1,'amount':amount,'pay_method':'cash','paid':True,'counterparty_name':'Водитель','my_stars':0}
                    elif self.command!='GET' and path.startswith('/bookings/'):
                        status=405;body={'detail':'unexpected_booking_write'}
                    else:body={'items':[],'role':'passenger'}
                if hold:
                    assert outer.after_kill.wait(180),'Held request was not released by kill/cleanup'
                data=json.dumps(body,ensure_ascii=False).encode('utf-8')
                self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(data)));self.end_headers()
                try:self.wfile.write(data);written=True
                except (BrokenPipeError,ConnectionResetError):written=False
                with outer.lock:row.update(response_monotonic=time.monotonic(),response_written=written,response_status=status)
        self.server=ThreadingHTTPServer(('127.0.0.1',PORT),Handler);self.server.daemon_threads=True
        self.worker=threading.Thread(target=self.server.serve_forever);self.worker.start()
    def start_case(self,language):
        with self.lock:self.language=language;self.role43_count=0
        self.pending_received.clear();self.after_kill.clear()
    def fresh_role(self):
        with self.lock:return self.role43_count>=2
    def close(self):
        self.after_kill.set();self.server.shutdown();self.server.server_close();self.worker.join(5);assert not self.worker.is_alive()

class BookingAudit(Audit):
    def journey(self,fixture,language):
        notification_type='booking' if language=='Ru' else 'chat'
        fixture.start_case(language);self.text(language+'-clear','shell','pm','clear',PACKAGE,save=True)
        out=self.text(language+'-provision','shell','am','instrument','-w','-r','-e','class',
            'com.yuldash.app.CompletedProcessProvisionInstrumentedTest','-e','completedProcessAudit','provision',
            '-e','auditLanguage',language,PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner',save=True,timeout=90)
        assert 'OK (1 test)' in out and 'no_ui_or_saved_state=true' in out,out
        self.text(language+'-end-provision','shell','am','force-stop',PACKAGE)
        self.text(language+'-initial42','shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER',
            '-n',PACKAGE+'/.MainActivity','--es','type','booking_done','--es','id','42',save=True)
        title='Поездка завершена' if language=='Ru' else 'Сәфәр тамамланды'
        self.find(language+'-completed42',self.text_is(title),seconds=25)
        self.click(language+'-receipt42-tap','Квитанция' if language=='Ru' else 'Сәфәр квитанцияһы',scroll=True)
        self.receipt(language+'-receipt42',language);self.screenshot(language+'-receipt42')
        first_pid=self.pid();task=self.task_id(self.activities(language+'-initial-task'))
        delivery=self.text(language+'-new43','shell','am','start','-W','-f','0x20000000','-a','android.intent.action.MAIN',
            '-c','android.intent.category.LAUNCHER','-n',PACKAGE+'/.MainActivity','--es','type',notification_type,'--es','id','43',save=True)
        assert 'intent has been delivered to currently running top-most instance' in delivery,delivery
        assert self.pid()==first_pid
        assert fixture.pending_received.wait(15),'New43 role request not observed'
        assert not fixture.after_kill.is_set();self.receipt(language+'-pending43-oldreceipt42',language)
        self.screenshot(language+'-pending43-oldreceipt42')
        self.text(language+'-background','shell','input','keyevent','KEYCODE_HOME');time.sleep(1)
        stopped=self.activities(language+'-stopped');identity,record,bundle_bytes=self.saved_record(stopped)
        assert self.task_id(stopped)==task and 'state=STOPPED' in record and self.pid()==first_pid
        self.text(language+'-targeted-kill','shell','run-as',PACKAGE,'kill','-9',str(first_pid),save=True)
        end=time.monotonic()+8
        while self.pid(required=False) is not None and time.monotonic()<end:time.sleep(.25)
        assert self.pid(required=False) is None
        dead=self.activities(language+'-dead');dead_identity,dead_record,dead_bytes=self.saved_record(dead)
        assert self.task_id(dead)==task and dead_identity==identity and dead_bytes==bundle_bytes
        assert 'state=DESTROYED' in dead_record and 'app=null' in dead_record
        fixture.after_kill.set()
        self.text(language+'-plain-resume','shell','am','start','-W','-f','0x10200000','-a','android.intent.action.MAIN',
            '-c','android.intent.category.LAUNCHER','-n',PACKAGE+'/.MainActivity',save=True)
        second_pid=self.pid();assert second_pid!=first_pid
        resumed=self.activities(language+'-resumed');assert self.task_id(resumed)==task and identity in resumed
        end=time.monotonic()+12
        while not fixture.fresh_role() and time.monotonic()<end:time.sleep(.1)
        assert fixture.fresh_role(),'Pending43 lost: no fresh role43 request in restored process'
        self.find(language+'-booking43-title',self.text_is('Детали поездки' if language=='Ru' else 'Сәфәр тураһында'),seconds=25)
        _,loaded=self.find(language+'-booking43-route',self.text_is('Уфа → Ишимбай'),seconds=25,scroll=True)
        assert not any(n.get('text')=='Уфа → Бирск' for n in loaded.iter('node'))
        self.screenshot(language+'-booking43')
        with fixture.lock:
            booking_writes=[r for r in fixture.records if r['language']==language and r['method']!='GET' and r['path'].startswith('/bookings/')]
            roles=[r for r in fixture.records if r['language']==language and r['path']=='/bookings/43/role']
        # Record every POST; only the existing rejected old42 safety arm is outside this navigation criterion.
        probes=[r for r in booking_writes if r['method']=='POST' and r['path']=='/bookings/42/winter-check' and r['body']=='{}' and r['auth_matches_fixture'] and r.get('response_status')==405]
        actions=[r for r in booking_writes if r not in probes]
        assert not actions and all(r['auth_matches_fixture'] for r in roles)
        self.cases.append({'id':'pending_'+notification_type+'43_process_'+language,'initial_pid':first_pid,'restored_pid':second_pid,'same_task':task,
            'same_activity_record':identity,'saved_bundle_bytes':bundle_bytes,'real_system_single_top_delivery':True,
            'held_role_before_kill':True,'fresh_role43_after_resume':True,'loaded_booking43':True,'notification_type':notification_type,'booking_write_attempts':len(booking_writes),'rejected_old42_winter_probes':len(probes),'booking_action_writes':0,
            'restore_inputs':'Plain launcher only; no destination/VM/Bundle/draft/session/language provision between kill and restore'})
        self.text(language+'-finish-app','shell','am','force-stop',PACKAGE)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run-isolated-audit',action='store_true');p.add_argument('--output',required=True);args=p.parse_args()
    assert args.run_isolated_audit;folder=Path(args.output).resolve();assert folder.is_relative_to((ROOT/'test-results').resolve());folder.mkdir(exist_ok=False)
    audit=BookingAudit(folder);fixture=None;reverse=False;success=False;failure=None
    try:
        assert audit.text('sdk','shell','getprop','ro.build.version.sdk')=='35'
        assert audit.text('qemu','shell','getprop','ro.kernel.qemu')=='1'
        assert audit.text('flight','shell','cmd','connectivity','airplane-mode')=='enabled'
        assert 'Active default network: none' in audit.text('connectivity','shell','dumpsys','connectivity',save=True)
        fixture=Fixture();audit.text('own-reverse','reverse','tcp:5198','tcp:5198');reverse=True
        for i,p in enumerate([ROOT/'android/app/build/outputs/apk/debug/app-debug.apk',ROOT/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']):
            audit.apks[p.relative_to(ROOT).as_posix()]=hashlib.sha256(p.read_bytes()).hexdigest();audit.text('install'+str(i),'install','-r',str(p),save=True)
        for language in ('Ru','Ba'):audit.journey(fixture,language)
        success=True
    except Exception as e:
        failure=repr(e);(folder/'failure.log').write_text(traceback.format_exc(),encoding='utf-8')
        try:audit.screenshot('failure');audit.snapshot('failure-ui');audit.activities('failure-task')
        except Exception:pass
        raise
    finally:
        if fixture:
            fixture.close();(folder/'http-records.json').write_text(json.dumps(fixture.records,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        if reverse:audit.text('remove-own-reverse','reverse','--remove','tcp:5198',ok=False)
        audit.text('stop-owned-app','shell','am','force-stop',PACKAGE,ok=False)
        result={'success':success,'failure':failure,'external_process_ui_cases':len(audit.cases),'cases':audit.cases,'apk_sha256':audit.apks,
            'calls':audit.calls,'fixture_worker_stopped':fixture is not None and not fixture.worker.is_alive(),
            'scope':'Real production Application/MainActivity/default runner/compiled loopback5198, synthetic disk-only setup, actual system SINGLE_TOP intent delivery (not FCM), held43HTTP, saved stopped task/SIGKILL/newPID/plainlauncher. RU booking + BA chat destinations; no complete type-language cross matrix/LMKD/phone/livebackend/JWT/FCM/fresh private create(nonnull Bundle)/taxi-parcel-support/public ride/fullHomeflow/payments. Background generic/WS400 and exact rejected POST42/winter-check are recorded but not accepted as those features; zero booking action writes. Every booking POST except that exact rejected old42 safety probe fails this criterion.'}
        (folder/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({'success':success,'cases':len(audit.cases),'failure':failure}),flush=True)
if __name__=='__main__':main()
