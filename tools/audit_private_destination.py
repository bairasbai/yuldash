"""Opt-in actual MainActivity private screen/task restoration on the root-owned API35.

Synthetic session/language are provisioned on disk before launch. Real SINGLE_TOP,
Home/STOPPED, saved ActivityRecord, SIGKILL and a plain launcher resume follow.
This checks consumed navigation and its ID, not FCM delivery or an LMKD decision.
"""
from __future__ import annotations
import argparse,hashlib,json,time,traceback
from pathlib import Path
from audit_completed_process import Audit,Fixture,ROOT,PACKAGE,SERIAL

class PrivateAudit(Audit):
    def journey(self,fixture,language,kind):
        label=language+'-'+kind;fixture.start_case(label)
        self.text(label+'-clear','shell','pm','clear',PACKAGE,save=True)
        setup=self.text(label+'-provision','shell','am','instrument','-w','-r','-e','class',
            'com.yuldash.app.CompletedProcessProvisionInstrumentedTest','-e','completedProcessAudit','provision',
            '-e','auditLanguage',language,PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner',save=True,timeout=90)
        assert 'OK (1 test)' in setup and 'no_ui_or_saved_state=true' in setup,setup
        self.text(label+'-end-setup','shell','am','force-stop',PACKAGE)
        self.text(label+'-initial42','shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER',
            '-n',PACKAGE+'/.MainActivity','--es','type','booking_done','--es','id','42',save=True)
        self.find(label+'-completed42',self.text_is('Поездка завершена' if language=='Ru' else 'Сәфәр тамамланды'),seconds=25)
        self.click(label+'-receipt-tap','Квитанция' if language=='Ru' else 'Сәфәр квитанцияһы',scroll=True)
        self.receipt(label+'-receipt42',language);self.screenshot(label+'-receipt42')
        initial_pid=self.pid();task=self.task_id(self.activities(label+'-initial-task'))
        delivery=self.text(label+'-new43','shell','am','start','-W','-f','0x20000000','-a','android.intent.action.MAIN',
            '-c','android.intent.category.LAUNCHER','-n',PACKAGE+'/.MainActivity','--es','type',kind,'--es','id','43',
            '--es','recipient_user_id','11',save=True)
        assert 'intent has been delivered to currently running top-most instance' in delivery and self.pid()==initial_pid,delivery
        title=('Чат с водителем' if language=='Ru' else 'Йөрөтөүсе менән чат') if kind=='order_chat' else ('Поддержка Юлдаш' if language=='Ru' else 'Юлдаш ярҙамы')
        witness='/instant/orders/43/messages' if kind=='order_chat' else '/support/tickets'
        self.find(label+'-screen-before',self.text_is(title),seconds=25);self.screenshot(label+'-screen-before')
        def reads():
            with fixture.lock:return [r for r in fixture.records if r['language']==label and r['method']=='GET' and r['path']==witness]
        deadline=time.monotonic()+12
        while not reads() and time.monotonic()<deadline:time.sleep(.1)
        before=len(reads());assert before>0 and all(r['auth_matches_fixture'] for r in reads())
        self.text(label+'-home','shell','input','keyevent','KEYCODE_HOME');time.sleep(1)
        stopped=self.activities(label+'-stopped');identity,record,bytes_before=self.saved_record(stopped)
        assert self.task_id(stopped)==task and 'state=STOPPED' in record and self.pid()==initial_pid
        self.text(label+'-kill','shell','run-as',PACKAGE,'kill','-9',str(initial_pid),save=True)
        deadline=time.monotonic()+8
        while self.pid(required=False) is not None and time.monotonic()<deadline:time.sleep(.25)
        assert self.pid(required=False) is None
        dead=self.activities(label+'-dead');dead_identity,dead_record,dead_bytes=self.saved_record(dead)
        assert self.task_id(dead)==task and dead_identity==identity and dead_bytes==bytes_before
        assert 'app=null' in dead_record and 'state=DESTROYED' in dead_record
        self.text(label+'-plain-resume','shell','am','start','-W','-f','0x10200000','-a','android.intent.action.MAIN',
            '-c','android.intent.category.LAUNCHER','-n',PACKAGE+'/.MainActivity',save=True)
        restored_pid=self.pid();assert restored_pid!=initial_pid
        resumed=self.activities(label+'-resumed');assert self.task_id(resumed)==task and identity in resumed
        self.find(label+'-screen-after',self.text_is(title),seconds=25);self.screenshot(label+'-screen-after')
        deadline=time.monotonic()+12
        while len(reads())<=before and time.monotonic()<deadline:time.sleep(.1)
        assert len(reads())>before and all(r['auth_matches_fixture'] for r in reads()),'Missing fresh destination GET after plain resume'
        with fixture.lock:
            writes=[r for r in fixture.records if r['language']==label and r['method']!='GET' and r['path'].startswith(('/bookings/','/instant/','/support/','/parcels/'))]
        probes=[r for r in writes if r['method']=='POST' and r['path']=='/bookings/42/winter-check' and r['body']=='{}' and r['auth_matches_fixture']]
        assert not [r for r in writes if r not in probes],'Unexpected private action'
        self.cases.append({'id':label,'notification_type':kind,'language':language,'initial_pid':initial_pid,'restored_pid':restored_pid,
            'same_task':task,'same_activity_record':identity,'saved_bundle_bytes':bytes_before,'real_system_single_top':True,
            'visible_title':title,'fresh_authenticated_get':witness,'get_count_before_kill':before,'get_count_after_restore':len(reads()),
            'private_action_writes':0,'rejected_old42_winter_probes':len(probes),'private_write_attempts':len(writes),
            'restore_inputs':'Plain launcher only; no extras, VM, Bundle, destination, or second provisioning after kill'})
        self.text(label+'-finish','shell','am','force-stop',PACKAGE)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run-isolated-audit',action='store_true');parser.add_argument('--output',required=True)
    args=parser.parse_args();assert args.run_isolated_audit
    folder=Path(args.output).resolve();assert folder.is_relative_to((ROOT/'test-results').resolve());folder.mkdir(exist_ok=False)
    audit=PrivateAudit(folder);fixture=None;reverse=False;success=False;failure=None
    try:
        assert audit.text('sdk','shell','getprop','ro.build.version.sdk')=='35'
        assert audit.text('qemu','shell','getprop','ro.kernel.qemu')=='1'
        assert audit.text('flight','shell','cmd','connectivity','airplane-mode')=='enabled'
        assert 'Active default network: none' in audit.text('network','shell','dumpsys','connectivity',save=True)
        fixture=Fixture();audit.text('reverse','reverse','tcp:5198','tcp:5198');reverse=True
        for i,path in enumerate([ROOT/'android/app/build/outputs/apk/debug/app-debug.apk',ROOT/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']):
            audit.apks[path.relative_to(ROOT).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest();audit.text('install'+str(i),'install','-r',str(path),save=True)
        for language,kind in [('Ru','order_chat'),('Ba','support')]:audit.journey(fixture,language,kind)
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
        result={'success':success,'failure':failure,'external_process_ui_cases':len(audit.cases),'cases':audit.cases,'apk_sha256':audit.apks,
            'calls':audit.calls,'fixture_worker_stopped':fixture is not None and not fixture.worker.is_alive(),'serial':SERIAL,'sdk':35,
            'scope':'Current production Application/MainActivity/default runner, disk-only synthetic setup, actual system SINGLE_TOP delivery; consumed RU taxi chat43 and BA support tickets saved task/SIGKILL/newPID/plainlauncher. Not FCM delivery, phone/LMKD, pre-consume kill, old-version Bundle migration, full Home, all destinations/type-language matrix, live auth/backend/realtime/payment. Exact old42 winter probes are rejected by shared fixture405, not accepted as WinterProtocol; every other private action fails.'}
        (folder/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({'success':success,'cases':len(audit.cases),'failure':failure}),flush=True)
if __name__=='__main__':main()
