"""Opt-in saved-task/process plus marked private Intent audit on the owned API35.

External am-start supplies local synthetic delivery IDs. The runtime callback carrying
the new Intent (create versus newIntent) is not observed; JVM checks that boundary.
"""
from __future__ import annotations
import argparse,hashlib,json,threading,time,traceback,uuid
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from audit_completed_process import Audit,ROOT,PACKAGE,SERIAL,TOKEN

MARKER='yuldash_navigation_delivery_id'
class FreshFixture:
    def __init__(self):
        self.lock=threading.Lock();self.records=[];self.language='Ru';outer=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):self.respond()
            def do_POST(self):self.respond()
            def respond(self):
                raw=self.rfile.read(int(self.headers.get('Content-Length','0')))
                path=self.path.split('?')[0];status=200
                with outer.lock:outer.records.append(dict(language=outer.language,method=self.command,path=path,
                    auth_matches_fixture=self.headers.get('Authorization')=='Bearer '+TOKEN,body=raw.decode('utf-8')))
                def booking(bid):return dict(id=bid,ride_id=bid+10,status='pending',seats=1,price=400,
                    from_city='Уфа',to_city='Бирск' if bid==42 else 'Салават',driver_name='Водитель',depart_at='2030-01-02T10:00:00Z')
                if self.headers.get('Upgrade','').lower()=='websocket':status=400;body={'detail':'fixture_no_websocket'}
                elif path=='/bookings/mine':body={'items':[booking(42),booking(44)]}
                elif path in ['/bookings/42/role','/bookings/44/role']:body={'role':'passenger','status':'pending'}
                elif path in ['/bookings/42/details','/bookings/44/details']:
                    bid=int(path.split('/')[2]);body={**booking(bid),'booking_id':bid,'role':'passenger','contact_unlocked':False}
                elif self.command!='GET' and (path.startswith(('/bookings/','/requests/','/responses/','/incidents/')) or path=='/bookings'):
                    status=405;body={'detail':'unexpected_private_write'}
                elif path=='/instant/orders/active':status=503;body={'detail':'fixture_no_taxi'}
                else:body={'items':[]}
                data=json.dumps(body,ensure_ascii=False).encode('utf-8');self.send_response(status)
                self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(data)));self.end_headers()
                try:self.wfile.write(data)
                except (BrokenPipeError,ConnectionResetError):pass
        self.server=ThreadingHTTPServer(('127.0.0.1',5198),Handler);self.server.daemon_threads=True
        self.worker=threading.Thread(target=self.server.serve_forever,name='fresh-notification-fixture');self.worker.start()
    def close(self):self.server.shutdown();self.server.server_close();self.worker.join(5);assert not self.worker.is_alive()

class FreshAudit(Audit):
    def launch(self,label,bid=None,delivery=None):
        args=['shell','am','start','-W','-f','0x24000000','-n',PACKAGE+'/.MainActivity'] if bid is not None else [
            'shell','am','start','-W','-f','0x10200000','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',PACKAGE+'/.MainActivity']
        if bid is not None:args+=['--es','type','booking','--es','id',str(bid),'--es',MARKER,delivery,'--es','recipient_user_id','11']
        self.text(label,*args,save=True)
    def loaded(self,label,language,city):
        cancel='Отменить бронь' if language=='Ru' else 'Бронде кире алыу'
        self.find(label+'-loaded',lambda n:n.get('text')==cancel,seconds=25)
        _,xml=self.find(label+'-route',lambda n:city in n.get('text',''),seconds=25)
        assert any(n.get('text')==cancel for n in xml.iter('node'))
        self.screenshot(label)
    def kill_saved(self,label,task,identity=None):
        first=self.pid();self.text(label+'-home','shell','input','keyevent','KEYCODE_HOME');time.sleep(1)
        stopped=self.activities(label+'-stopped');record_id,record,size=self.saved_record(stopped)
        assert self.task_id(stopped)==task and 'state=STOPPED' in record and self.pid()==first
        if identity is not None:assert identity==record_id
        self.text(label+'-kill','shell','run-as',PACKAGE,'kill','-9',str(first),save=True)
        end=time.monotonic()+8
        while self.pid(required=False) is not None and time.monotonic()<end:time.sleep(.25)
        assert self.pid(required=False) is None
        dead=self.activities(label+'-dead');dead_id,dead_record,dead_size=self.saved_record(dead)
        assert self.task_id(dead)==task and dead_id==record_id and dead_size==size and 'app=null' in dead_record and 'state=DESTROYED' in dead_record
        return dict(pid=first,task_id=task,activity_record=record_id,saved_bundle_bytes=size)
    def journey(self,fixture,language):
        fixture.language=language;label=language+'-fresh';old=str(uuid.uuid4());fresh=str(uuid.uuid4())
        self.text(label+'-clear','shell','pm','clear',PACKAGE,save=True)
        setup=self.text(label+'-provision','shell','am','instrument','-w','-r','-e','class',
            'com.yuldash.app.CompletedProcessProvisionInstrumentedTest','-e','completedProcessAudit','provision',
            '-e','auditLanguage',language,PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner',save=True,timeout=90)
        assert 'OK (1 test)' in setup and 'disk_session_only=true' in setup and 'no_ui_or_saved_state=true' in setup
        self.text(label+'-end-setup','shell','am','force-stop',PACKAGE);self.launch(label+'-plain-launch')
        self.click(label+'-mode-introduction','Понятно' if language=='Ru' else 'Аңлашылды')
        self.find(label+'-home-ready',self.text_is('Поездки' if language=='Ru' else 'Сәфәрҙәр'),seconds=25)
        self.launch(label+'-first-marked',42,old);self.loaded(label+'-booking42',language,'Бирск')
        task=self.task_id(self.activities(label+'-initial-task'));first=self.kill_saved(label+'-first-death',task)
        self.launch(label+'-fresh-marked-after-death',44,fresh);second=self.pid();assert second!=first['pid']
        self.loaded(label+'-booking44',language,'Салават')
        dump=self.activities(label+'-fresh-task');assert self.task_id(dump)==task and first['activity_record'] in dump
        def reads(bid):
            with fixture.lock:return [r for r in fixture.records if r['language']==language and r['method']=='GET' and r['path']==f'/bookings/{bid}/details']
        old_reads=len(reads(42));self.launch(label+'-copied-original',42,old)
        self.loaded(label+'-booking44-after-copy',language,'Салават');assert len(reads(42))==old_reads
        before=len(reads(44));assert before>0 and all(r['auth_matches_fixture'] for r in reads(44))
        second_death=self.kill_saved(label+'-second-death',task,first['activity_record'])
        self.launch(label+'-plain-resume');third=self.pid();assert third!=second
        self.loaded(label+'-booking44-after-plain',language,'Салават')
        end=time.monotonic()+12
        while len(reads(44))<=before and time.monotonic()<end:time.sleep(.1)
        assert len(reads(44))>before and all(r['auth_matches_fixture'] for r in reads(44))
        dump=self.activities(label+'-final-task');assert self.task_id(dump)==task and first['activity_record'] in dump
        with fixture.lock:private=[r for r in fixture.records if r['language']==language and r['method']!='GET' and (r['path'].startswith(('/bookings/','/requests/','/responses/','/incidents/')) or r['path']=='/bookings')]
        assert not private
        self.cases.append(dict(id='marked_private_process_'+language,language=language,initial_delivery=old,fresh_delivery=fresh,
            first_death=first,second_death=second_death,second_pid=second,final_pid=third,
            copied_original_details42_get_unchanged=old_reads,authenticated_details44_get_before=before,authenticated_details44_get_after=len(reads(44)),
            private_action_attempts=0,platform_create_or_newIntent_callback_observed=False,
            path='plain launcher/real intro -> marked42 -> saved task/SIGKILL -> fresh marked44/newPID -> old marker42 copy ignored -> saved task/SIGKILL -> plain launcher/newPID/loaded44'))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True);args=parser.parse_args()
    folder=(ROOT/args.output).resolve();assert folder.is_relative_to((ROOT/'test-results').resolve());folder.mkdir(exist_ok=False)
    audit=FreshAudit(folder);fixture=None;reverse=False;success=False;failure=None
    try:
        assert audit.text('sdk','shell','getprop','ro.build.version.sdk')=='35'
        assert audit.text('qemu','shell','getprop','ro.kernel.qemu')=='1'
        assert audit.text('flight','shell','cmd','connectivity','airplane-mode')=='enabled'
        assert 'Active default network: none' in audit.text('network','shell','dumpsys','connectivity',save=True)
        fixture=FreshFixture();audit.text('reverse','reverse','tcp:5198','tcp:5198');reverse=True
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
            scope='Actual production Application/MainActivity/default runner and disk-only synthetic setup. Saved task/SIGKILL/new PID/new marked am-start, known original marker ignored, second saved task/death/plain launcher/loaded44. Callback create versus onNewIntent not observed; fresh create(nonnullBundle) established only by JVM. No remote FCM, shown native PendingIntent, real token/backend/WS/provider/payment/phone/LMKD/old Bundle/full Home/matrix; no VM/nav/Bundle injected.')
        (folder/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(success=success,cases=len(audit.cases),failure=failure)),flush=True)
if __name__=='__main__':main()
