"""Opt-in Home UI -> private booking -> Back -> saved task/process restore on owned API35.

Only synthetic disk session/language are provisioned. Navigation starts with a plain launcher,
then real Home buttons, and resumes with a plain launcher after targeted SIGKILL.
"""
from __future__ import annotations
import argparse,hashlib,json,threading,time,traceback
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from audit_completed_process import Audit,ROOT,PACKAGE,SERIAL,TOKEN

class HomeFixture:
    def __init__(self):
        self.lock=threading.Lock();self.records=[];self.language='Ru';outer=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):self.respond()
            def do_POST(self):self.respond()
            def respond(self):
                raw=self.rfile.read(int(self.headers.get('Content-Length','0')))
                path=self.path.split('?')[0];status=200
                with outer.lock:
                    outer.records.append(dict(language=outer.language,method=self.command,path=path,
                        auth_matches_fixture=self.headers.get('Authorization')=='Bearer '+TOKEN,
                        body=raw.decode('utf-8'),monotonic=time.monotonic()))
                if self.headers.get('Upgrade','').lower()=='websocket':status=400;body={'detail':'local_fixture_no_websocket'}
                elif path=='/bookings/mine':body={'items':[{'id':42,'ride_id':9,'status':'pending','seats':1,'price':400,'from_city':'Уфа','to_city':'Бирск','driver_name':'Водитель','depart_at':'2026-10-08T12:00:00Z'}]}
                elif path=='/bookings/42/details':body={'booking_id':42,'ride_id':9,'role':'passenger','status':'pending','from_city':'Уфа','to_city':'Бирск','contact_unlocked':False}
                elif path=='/bookings/42/role':body={'role':'passenger','status':'pending'}
                elif self.command!='GET' and (path.startswith(('/bookings/','/requests/','/responses/','/incidents/')) or path=='/bookings'):status=405;body={'detail':'unexpected_private_write'}
                elif path=='/instant/orders/active':status=503;body={'detail':'unavailable_in_this_fixture'}
                else:body={'items':[]}
                data=json.dumps(body,ensure_ascii=False).encode('utf-8');self.send_response(status)
                self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(data)));self.end_headers()
                try:self.wfile.write(data)
                except (BrokenPipeError,ConnectionResetError):pass
        self.server=ThreadingHTTPServer(('127.0.0.1',5198),Handler);self.server.daemon_threads=True
        self.worker=threading.Thread(target=self.server.serve_forever,name='home-booking-fixture');self.worker.start()
    def start_case(self,language):self.language=language
    def close(self):self.server.shutdown();self.server.server_close();self.worker.join(5);assert not self.worker.is_alive()

class HomeAudit(Audit):
    def journey(self,fixture,language):
        fixture.start_case(language);label=language+'-home'
        self.text(label+'-clear','shell','pm','clear',PACKAGE,save=True)
        setup=self.text(label+'-provision','shell','am','instrument','-w','-r','-e','class',
            'com.yuldash.app.CompletedProcessProvisionInstrumentedTest','-e','completedProcessAudit','provision',
            '-e','auditLanguage',language,PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner',save=True,timeout=90)
        assert 'OK (1 test)' in setup and 'disk_session_only=true' in setup and 'no_ui_or_saved_state=true' in setup,setup
        self.text(label+'-end-setup','shell','am','force-stop',PACKAGE)
        self.text(label+'-plain-launch','shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',PACKAGE+'/.MainActivity',save=True)
        rides='Поездки' if language=='Ru' else 'Сәфәрҙәр';details='Подробнее' if language=='Ru' else 'Ентекле'
        title='Детали поездки' if language=='Ru' else 'Сәфәр тураһында'
        cancel='Отменить бронь' if language=='Ru' else 'Бронде кире алыу'
        # Fresh pm-clear also presents the real mode introduction; dismiss it via its UI.
        self.click(label+'-mode-introduction','Понятно' if language=='Ru' else 'Аңлашылды')
        self.find(label+'-initial',self.text_is(rides),seconds=25);self.screenshot(label+'-initial')
        self.click(label+'-rides-tap',rides);self.find(label+'-rides',self.text_is(details),seconds=25);self.screenshot(label+'-rides')
        self.click(label+'-booking-tap',details);self.find(label+'-booking',self.text_is(title),seconds=25)
        self.find(label+'-booking-loaded',self.text_is(cancel),seconds=25)
        self.text(label+'-back','shell','input','keyevent','KEYCODE_BACK')
        self.find(label+'-back-rides',self.text_is(details),seconds=25);self.screenshot(label+'-back-rides')
        self.click(label+'-reopen',details);self.find(label+'-booking-before',self.text_is(title),seconds=25)
        self.find(label+'-booking-before-loaded',self.text_is(cancel),seconds=25);self.screenshot(label+'-booking-before')
        first=self.pid();task=self.task_id(self.activities(label+'-initial-task'))
        def reads():
            with fixture.lock:return [r for r in fixture.records if r['language']==language and r['method']=='GET' and r['path']=='/bookings/42/details']
        end=time.monotonic()+12
        while len(reads())<2 and time.monotonic()<end:time.sleep(.1)
        before=len(reads());assert before>=2 and all(r['auth_matches_fixture'] for r in reads())
        self.text(label+'-home','shell','input','keyevent','KEYCODE_HOME');time.sleep(1)
        stopped=self.activities(label+'-stopped');identity,record,size=self.saved_record(stopped)
        assert self.task_id(stopped)==task and 'state=STOPPED' in record and self.pid()==first
        self.text(label+'-kill','shell','run-as',PACKAGE,'kill','-9',str(first),save=True)
        end=time.monotonic()+8
        while self.pid(required=False) is not None and time.monotonic()<end:time.sleep(.25)
        assert self.pid(required=False) is None
        dead=self.activities(label+'-dead');dead_identity,dead_record,dead_size=self.saved_record(dead)
        assert self.task_id(dead)==task and dead_identity==identity and dead_size==size and 'app=null' in dead_record and 'state=DESTROYED' in dead_record
        self.text(label+'-plain-resume','shell','am','start','-W','-f','0x10200000','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',PACKAGE+'/.MainActivity',save=True)
        restored=self.pid();assert restored!=first
        self.find(label+'-booking-after',self.text_is(title),seconds=25)
        self.find(label+'-booking-after-loaded',self.text_is(cancel),seconds=25);self.screenshot(label+'-booking-after')
        resumed=self.activities(label+'-resumed');assert self.task_id(resumed)==task and identity in resumed
        end=time.monotonic()+12
        while len(reads())<=before and time.monotonic()<end:time.sleep(.1)
        assert len(reads())>before and all(r['auth_matches_fixture'] for r in reads())
        with fixture.lock:private=[r for r in fixture.records if r['language']==language and r['method']!='GET' and (r['path'].startswith(('/bookings/','/requests/','/responses/','/incidents/')) or r['path']=='/bookings')]
        assert not private
        self.cases.append(dict(id='home_private_booking_task_'+language,language=language,booking_id=42,
            initial_pid=first,restored_pid=restored,task_id=task,activity_record=identity,saved_bundle_bytes=size,
            authenticated_details_get_before=before,authenticated_details_get_after=len(reads()),private_action_attempts=0,
            path='plain launcher -> Home -> Rides -> pending booking42 -> Back/Rides -> booking42 -> HOME/STOPPED/SIGKILL -> plain launcher/newPID/booking42',
            no_nav_id_vm_bundle_injected=True))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True);args=parser.parse_args()
    folder=(ROOT/args.output).resolve();assert folder.is_relative_to((ROOT/'test-results').resolve());folder.mkdir(exist_ok=False)
    audit=HomeAudit(folder);fixture=None;reverse=False;success=False;failure=None
    try:
        assert audit.text('sdk','shell','getprop','ro.build.version.sdk')=='35'
        assert audit.text('qemu','shell','getprop','ro.kernel.qemu')=='1'
        assert audit.text('flight','shell','cmd','connectivity','airplane-mode')=='enabled'
        assert 'Active default network: none' in audit.text('network','shell','dumpsys','connectivity',save=True)
        fixture=HomeFixture();audit.text('reverse','reverse','tcp:5198','tcp:5198');reverse=True
        for i,path in enumerate([ROOT/'android/app/build/outputs/apk/debug/app-debug.apk',ROOT/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']):
            audit.apks[path.relative_to(ROOT).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest();audit.text('install'+str(i),'install','-r',str(path),save=True)
        for language in ['Ru','Ba']:audit.journey(fixture,language)
        success=True
    except Exception as error:
        failure=repr(error);(folder/'failure.log').write_text(traceback.format_exc(),encoding='utf-8')
        try:audit.screenshot('failure');audit.snapshot('failure-ui');audit.activities('failure-task')
        except Exception:pass
        raise
    finally:
        if fixture:fixture.close();(folder/'http-records.json').write_text(json.dumps(fixture.records,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        if reverse:audit.text('remove-own-reverse','reverse','--remove','tcp:5198',ok=False)
        audit.text('stop-own-app','shell','am','force-stop',PACKAGE,ok=False)
        result=dict(success=success,failure=failure,external_process_ui_cases=len(audit.cases),cases=audit.cases,apk_sha256=audit.apks,calls=audit.calls,
            fixture_worker_stopped=fixture is not None and not fixture.worker.is_alive(),serial=SERIAL,sdk=35,
            scope='Actual production Application/MainActivity/default runner, synthetic disk-only setup, real Home/Rides/pending booking UI, Back/Rides and same saved task/SIGKILL/newPID/plainlauncher. No real SMS/FCM/JWT/backend/WS/payments/phone/LMKD/old-version Bundle/all-type-role-theme-font matrix or native late mutation races. Ordinary POST /me/update is language persistence, not a private booking mutation.')
        (folder/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(success=success,cases=len(audit.cases),failure=failure)),flush=True)
if __name__=='__main__':main()
