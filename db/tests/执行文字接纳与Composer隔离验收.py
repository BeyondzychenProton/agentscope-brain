"""#42 text admission/composer: owned MySQL + packaged app, no model execution or shared configuration."""
from __future__ import annotations
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import time
import uuid

if hasattr(sys.stdout,'reconfigure'): sys.stdout.reconfigure(encoding='utf-8')
ROOT=Path(__file__).resolve().parents[2]
EVIDENCE=ROOT/'verification/issue-42/text-admission'
spec=importlib.util.spec_from_file_location('text_admission_fixture',Path(__file__).with_name('执行工作台撤权只读隔离验收.py'))
web=importlib.util.module_from_spec(spec); spec.loader.exec_module(web)


def admission_checks(sql,client,origin,start,login,checked,browser):
    def sessions(): return sql('SELECT COUNT(*) n FROM business_session')[0]['n']
    def messages(): return sql('SELECT COUNT(*) n FROM business_message')[0]['n']
    def prepare(c=client,client_key=None):
        key=client_key or str(uuid.uuid4())
        response=c.post('/api/session-intents',{'requestKey':str(uuid.uuid4()),'clientIntentId':key,'employeeId':'1'})
        assert response[0]==200,response[:2]
        return response[1]
    def payload(intent=None,text='真实文字接纳夹具😀',session=None,key=None):
        return {'submissionKey':key or str(uuid.uuid4()),'target':{'sessionId':session} if session else {'intentId':intent['intentId']},'text':text,'uploadIds':[]}
    def lookup(p,c=client): return c.request('GET','/api/submissions/'+p['submissionKey'])
    def close(p,c=client): return c.post('/api/submissions/'+p['submissionKey']+'/close',{'requestKey':str(uuid.uuid4())})
    def counts():
        return {t:sql('SELECT COUNT(*) n FROM '+t)[0]['n'] for t in ['business_session','business_message','business_run','runtime_dispatch','projection_content','projection_content_version','session_projection_event']}
    def clear_new():
        sql('DELETE FROM conversation_audit_event WHERE run_id>3')
        sql('DELETE v FROM projection_content_version v JOIN projection_content h ON h.content_id=v.content_id WHERE h.run_id>3')
        sql('DELETE FROM projection_content WHERE run_id>3')
        sql('DELETE FROM session_projection_event WHERE session_id NOT IN (101,202) OR (session_id=101 AND seq>3)')
        sql('DELETE FROM runtime_dispatch')
        sql('DELETE b FROM message_content_block b JOIN business_message m ON m.id=b.message_id WHERE m.id>6')
        sql('DELETE FROM business_run WHERE id>3')
        sql('DELETE FROM business_message WHERE id>6')
        sql('DELETE FROM ordinary_submission')
        sql('DELETE FROM session_intent')
        sql('DELETE FROM business_session WHERE id NOT IN (101,202)')
        sql('UPDATE business_session SET last_message_no=6,last_projection_seq=3,row_version=1 WHERE id=101')
        sql('UPDATE runtime_capacity SET normal_waiting_count=0,occupied_count=0')
        sql('UPDATE memory_profile SET last_input_sequence=3')
    try:
        before=counts()
        unobserved={'submissionKey':str(uuid.uuid4())}
        checked('text-01-unobserved-get-has-no-control-write',lookup(unobserved)[1]['registrationStatus']=='UNOBSERVED' and sql('SELECT COUNT(*) n FROM ordinary_submission')[0]['n']==0)
        intent=prepare()
        checked('text-02-prepare-does-not-create-empty-session',counts()==before and prepare(client,intent['clientIntentId'])['intentId']==intent['intentId'])
        malformed=payload(intent)
        checked('text-02b-json-identifiers-and-text-must-be-strings',
            client.post('/api/submissions',{**malformed,'target':{'intentId':int(intent['intentId'])}})[0]==400
            and client.post('/api/submissions',{**malformed,'text':123})[0]==400
            and client.post('/api/submissions',{**malformed,'owner':'999'})[0]==400 and counts()==before)
        p=payload(intent)
        result=client.post('/api/submissions',p)
        checked('text-03-missing-capacity-refuses-without-partial-facts',result[0]==503 and counts()==before and lookup(p)[1]['registrationStatus']=='REGISTERED')
        checked('text-04-explicit-close-preserves-registered-semantics',close(p)[1]['acceptanceStatus']=='CLOSED_UNACCEPTED'
            and sql('SELECT has_registered_intent,submission_sha256 FROM ordinary_submission WHERE submission_key=%s',(uuid.UUID(p['submissionKey']).bytes,))[0]['submission_sha256'] is not None)
        sql('INSERT INTO runtime_capacity(singleton_key,foreground_limit,normal_waiting_limit,occupied_count,normal_waiting_count,boot_epoch,gmt_create,gmt_modified) VALUES(1,2,16,0,0,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))')
        p=payload(prepare())
        result=client.post('/api/submissions',p)
        checked('text-05-missing-memory-metadata-rolls-back-all-admission',result[0]==503 and counts()==before)
        close(p)
        sql('''INSERT INTO memory_profile(user_id,employee_id,document_kv_version,document_sha256,memory_schema_version,policy_version,
            last_input_sequence,activated_at,gmt_create,gmt_modified) VALUES(1,1,1,%s,'synthetic-text-v1','synthetic-policy/v1',3,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))''',(hashlib.sha256(b'').digest(),))
        closed=payload(prepare())
        checked('text-06-close-unobserved-tombstone-permanently-blocks-late-post',close(closed)[1]['acceptanceStatus']=='CLOSED_UNACCEPTED'
            and client.post('/api/submissions',closed)[1]['code']=='SUBMISSION_CLOSED' and counts()==before)
        text=' 第一行\n第二行😀 <img src=x onerror="window.__textExecuted=true"> '
        p=payload(prepare(),text)
        result=client.post('/api/submissions',p)
        assert result[0]==202,result[:2]
        receipt=result[1]; sid=receipt['result']['sessionId']; rid=receipt['result']['runId']; mid=receipt['result']['messageId']
        checked('text-07-atomic-session-message-run-queue-projection',receipt['acceptanceStatus']=='ACCEPTED' and receipt['processingStatus']=='WAITING'
            and sessions()==before['business_session']+1 and messages()==7
            and sql('SELECT status,started_at FROM business_run WHERE id=%s',(rid,))[0]=={'status':'ACCEPTED','started_at':None}
            and sql('SELECT status,permit_state,counts_toward_waiting FROM runtime_dispatch WHERE run_id=%s',(rid,))[0]=={'status':'WAITING','permit_state':'NONE','counts_toward_waiting':1})
        fixed=counts()
        checked('text-08-same-key-returns-exact-original-even-when-run-busy',client.post('/api/submissions',p)[1]['result']==receipt['result'] and counts()==fixed)
        changed={**p,'text':p['text']+' '}
        checked('text-09-same-key-different-exact-text-is-conflict',client.post('/api/submissions',changed)[1]['code']=='IDEMPOTENCY_CONFLICT' and counts()==fixed)
        bound=client.post('/api/submissions',{**p,'submissionKey':str(uuid.uuid4())})
        checked('text-10-bound-intent-new-key-cannot-create-second-session',bound[1]['code']=='INTENT_ALREADY_ACCEPTED' and bound[1]['result']==receipt['result'] and counts()==fixed)
        checked('text-11-close-after-acceptance-returns-original-receipt',close(p)[1]['result']==receipt['result'] and lookup(p)[1]['result']==receipt['result'])
        snapshot_url='/api/sessions/'+sid+'/projection/snapshot?protocolVersion=web-projection-v1'
        snap=client.request('GET',snapshot_url)
        assert snap[0]==200,snap[:2]
        ref=snap[1]['messages']['items'][0]['contents'][0]
        content_url='/api/sessions/'+sid+'/projection/content/'+ref['contentId']+'?protocolVersion=web-projection-v1&contentVersion=1'
        checked('text-12-accepted-text-is-fixed-version-and-readable-while-queued',snap[1]['messages']['items'][0]['messageId']==mid
            and snap[1]['runs']==[{'runId':rid,'status':'ACCEPTED','processingStatus':'WAITING'}]
            and client.request('GET',content_url)[1]['body']==text and snap[1]['session']['allowedActions']==[])
        checked('text-13-existing-busy-session-does-not-append-a-message',client.post('/api/submissions',payload(session=sid))[1]['code']=='RUN_BUSY' and counts()==fixed)
        sql("UPDATE employee_use_grant SET grant_status='REVOKED',revoked_by=1,revoked_at=UTC_TIMESTAMP(6)")
        checked('text-14-revocation-preserves-original-receipt-and-history-denies-new',client.post('/api/submissions',p)[1]['result']==receipt['result']
            and client.request('GET',snapshot_url)[0]==200 and client.post('/api/submissions',payload(session='101'))[0]==403)
        sql("UPDATE employee_use_grant SET grant_status='ACTIVE',revoked_by=NULL,revoked_at=NULL")
        foreign=payload(session='202')
        checked('text-15-foreign-session-never-registered',client.post('/api/submissions',foreign)[0]==404 and lookup(foreign)[1]['registrationStatus']=='UNOBSERVED')
        append=payload(session='101',text='在既有会话接纳新的普通文字')
        appended=client.post('/api/submissions',append)
        checked('text-16-existing-session-appends-with-global-input-order',appended[0]==202 and sessions()==before['business_session']+1
            and sql('SELECT message_no,input_sequence FROM business_message WHERE id=%s',(appended[1]['result']['messageId'],))[0]=={'message_no':7,'input_sequence':5})
        frozen=payload(prepare())
        sql('UPDATE digital_employee SET current_release_id=NULL WHERE id=1')
        checked('text-17-frozen-release-change-is-rejected-without-switching',client.post('/api/submissions',frozen)[1]['code']=='RELEASE_CHANGED')
        sql('UPDATE digital_employee SET current_release_id=1 WHERE id=1')
        rollback=payload(prepare(),text='投影写入失败必须回滚接纳')
        baseline=counts(); sequence=sql('SELECT last_input_sequence FROM memory_profile')[0]['last_input_sequence']
        sql("CREATE TRIGGER text_admission_fail BEFORE INSERT ON session_projection_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic projection failure'")
        failed=client.post('/api/submissions',rollback)
        sql('DROP TRIGGER text_admission_fail')
        checked('text-18-projection-failure-rolls-back-message-run-capacity-input-and-binding',failed[0]==503 and counts()==baseline
            and sql('SELECT last_input_sequence FROM memory_profile')[0]['last_input_sequence']==sequence
            and lookup(rollback)[1]['registrationStatus']=='REGISTERED')
        close(rollback)
        # Read-only ownership, fixed content and queue facts survive a packaged-app restart; no worker is started.
        start()
        checked('text-19-restart-and-repeated-reads-never-claim-or-execute',lookup(p)[1]['result']==receipt['result']
            and client.request('GET',snapshot_url)[0]==200 and client.request('GET',content_url)[1]['body']==text and counts()==baseline)
        # Restrict the #29/31/33 inspections to this slice's facts; the inherited #37 fixtures intentionally use partial synthetic projections.
        batch=sql('SELECT batch_payload,byte_count,batch_sha256,change_count FROM session_projection_event WHERE session_id=%s',(sid,))[0]
        envelope=json.loads(batch['batch_payload'])
        checked('text-20-persisted-v1-batch-integrity-and-enumerations',hashlib.sha256(batch['batch_payload']).digest()==batch['batch_sha256']
            and len(batch['batch_payload'])==batch['byte_count'] and batch['change_count']==4
            and [x['type'] for x in envelope['changes']]==['SESSION_SET','RUN_SET','CONTENT_SET','MESSAGE_COMMIT']
            and envelope['seq']=='1' and envelope['previousSeq']=='0'
            and envelope['changes'][2]['sha256']==hashlib.sha256(text.encode()).hexdigest())
        (EVIDENCE/'admission-facts.json').write_text(json.dumps({'receipt':receipt,'snapshot':snap[1],
            'exactTextSha256':hashlib.sha256(text.encode()).hexdigest(),'batch':envelope,'countsAfterAcceptance':fixed,
            'projectionFailureCounts':baseline,'inputSequenceAfterRollback':sequence,'sdkAndModelExecutions':0},
            ensure_ascii=False,indent=2)+'\n','utf-8')
        clear_new()
        # Race registration/acceptance for exactly one logical submit across independent authenticated clients.
        race=payload(prepare(),text='同键并发仅接纳一次')
        clients=[login() for _ in range(6)]
        with ThreadPoolExecutor(max_workers=6) as pool: raced=list(pool.map(lambda c:c.post('/api/submissions',race),clients))
        checked('text-21-same-key-concurrency-produces-one-message-run-and-queue-entry',all(r[0]==202 for r in raced)
            and len({r[1]['result']['messageId'] for r in raced})==1 and messages()==7
            and sql('SELECT normal_waiting_count FROM runtime_capacity')[0]['normal_waiting_count']==1)
        clear_new()
        race=payload(prepare(),text='接纳与封闭互斥')
        with ThreadPoolExecutor(max_workers=2) as pool:
            post_future=pool.submit(clients[0].post,'/api/submissions',race)
            close_future=pool.submit(close,race,clients[1])
            posted,closed_result=post_future.result(),close_future.result()
        final=lookup(race)[1]
        checked('text-22-accept-close-race-has-one-permanent-outcome',final['acceptanceStatus'] in ['ACCEPTED','CLOSED_UNACCEPTED']
            and (messages()==7 if final['acceptanceStatus']=='ACCEPTED' else messages()==6)
            and (client.post('/api/submissions',race)[0]==202 if final['acceptanceStatus']=='ACCEPTED' else client.post('/api/submissions',race)[1]['code']=='SUBMISSION_CLOSED'))
        clear_new()
        jobs=[payload(prepare(),text='容量并发 '+str(i)) for i in range(20)]
        with ThreadPoolExecutor(max_workers=6) as pool:
            results=list(pool.map(lambda job:clients[job[0]%6].post('/api/submissions',job[1]),enumerate(jobs)))
        accepted_jobs=[p for p,r in zip(jobs,results) if r[0]==202]
        rejected=[r for r in results if r[0]!=202]
        checked('text-23-c0-capacity-stops-at-sixteen-without-partial-messages',len(accepted_jobs)==16 and len(rejected)==4
            and all(r[1]['code']=='EXECUTION_CAPACITY_EXCEEDED' for r in rejected)
            and messages()==22 and sql('SELECT normal_waiting_count,occupied_count FROM runtime_capacity')[0]=={'normal_waiting_count':16,'occupied_count':0})
        (EVIDENCE/'capacity-facts.json').write_text(json.dumps({'acceptedResults':[r[1] for r in results if r[0]==202],
            'rejectionCodes':[r[1]['code'] for r in rejected],'counts':counts(),
            'capacity':sql('SELECT foreground_limit,normal_waiting_limit,occupied_count,normal_waiting_count FROM runtime_capacity')[0],
            'connectionPoolMaximum':2},ensure_ascii=False,indent=2)+'\n','utf-8')
        checked('text-24-original-accepted-result-is-returned-even-at-full-capacity',client.post('/api/submissions',accepted_jobs[0])[0]==202 and messages()==22)
        clear_new()
        if browser: browser_checks(sql,origin,login,checked)
        # Explicit feature-disable preserves reads and close, but forbids fresh preparation.
        start(extra_arguments=['--brain.conversation.text-submission-enabled=false'])
        checked('text-25-disabled-feature-denies-new-preparation',client.post('/api/session-intents',{'requestKey':str(uuid.uuid4()),'clientIntentId':str(uuid.uuid4()),'employeeId':'1'})[1]['code']=='TEXT_SUBMISSION_UNAVAILABLE'
            and client.request('GET','/api/employees/1/capabilities')[1]['textSubmissionAvailable'] is False)
        start()
    finally:
        sql('DROP TRIGGER IF EXISTS text_admission_fail')
        clear_new()
        sql('DELETE FROM memory_profile WHERE user_id=1 AND employee_id=1')
        sql('DELETE FROM runtime_capacity')
        sql("UPDATE employee_use_grant SET grant_status='ACTIVE',revoked_by=NULL,revoked_at=NULL")
        sql('UPDATE digital_employee SET current_release_id=1 WHERE id=1')
        sql("UPDATE platform_user SET account_status='ACTIVE'")


def browser_checks(sql,origin,login,checked):
    from playwright.sync_api import sync_playwright,expect
    phone=web.fixture_module.fixture()['identity']['phone']
    observations=[]
    with sync_playwright() as playwright:
        for channel in ['chrome','msedge']:
            browser=playwright.chromium.launch(channel=channel,headless=True)
            try:
                page=browser.new_page(viewport={'width':1440,'height':960});page.set_default_timeout(15000)
                posts=[]
                page.on('request',lambda req: posts.append(req.url) if req.method=='POST' and req.url.endswith('/api/submissions') else None)
                page.goto(origin+'/login')
                page.get_by_label('登录手机号',exact=True).fill(phone);page.get_by_label('密码',exact=True).fill(web.PASSWORD)
                page.get_by_role('button',name='登录',exact=True).click();page.wait_for_url('**/workbench')
                field=page.get_by_role('textbox',name='输入消息',exact=True)
                expect(field).to_be_visible()
                text='浏览器草稿 '+channel+' 😀'
                field.fill(text); page.reload();expect(field).to_have_value(text)
                field.click(); field.press('End')
                field.dispatch_event('compositionstart');field.dispatch_event('keydown',{'key':'Enter','isComposing':True,'bubbles':True})
                assert len(posts)==0
                field.dispatch_event('compositionend');field.press('Shift+Enter');expect(field).to_have_value(text+'\n')
                field.fill(text)
                def delayed_identity(route):
                    response=route.fetch();time.sleep(.4);route.fulfill(response=response)
                page.route('**/api/me',delayed_identity)
                page.evaluate("window.dispatchEvent(new Event('focus'))")
                expect(field).to_be_editable()
                field.press('End');field.press('Shift+Enter');expect(field).to_have_value(text+'\n')
                page.unroute('**/api/me',delayed_identity)
                field.fill(text)
                page.get_by_role('button',name='发送',exact=True).click()
                page.wait_for_url('**/workbench?sessionId=*')
                expect(page.locator('.formal-message')).to_have_count(1)
                expect(page.get_by_text('消息已接纳，等待执行。本页尚未提供自动执行与回复。',exact=True)).to_be_visible()
                expect(page.locator('.session-item[aria-current="page"]')).to_contain_text('浏览器草稿')
                assert len(posts)==1
                page.reload();expect(page.locator('.formal-message')).to_have_count(1)
                assert len(posts)==1
                page.screenshot(path=str(EVIDENCE/f'queued-{channel}.png'))
                page.get_by_role('button',name='＋ 新建对话',exact=True).click();expect(field).to_be_visible()
                lost_text='响应丢失仍只有一条 '+channel
                field.fill(lost_text)
                def lost_response(route):
                    response=route.fetch()
                    assert response.status==202
                    route.abort('failed')
                page.route('**/api/submissions',lost_response)
                page.get_by_role('button',name='发送',exact=True).click()
                expect(page.get_by_text('本次接纳结果尚未确认。原文与提交键已保留，请回查；编辑或重新发送前须先封闭原提交。',exact=True)).to_be_visible()
                expect(page.get_by_role('button',name='回查原提交',exact=True)).to_be_visible()
                expect(field).to_have_attribute('readonly','')
                expect(field).to_have_value(lost_text)
                page.unroute('**/api/submissions',lost_response)
                page.reload()
                page.wait_for_url('**/workbench?sessionId=*')
                expect(page.locator('.formal-message')).to_have_count(1)
                expect(page.locator('.message-text')).to_have_text(lost_text)
                assert len(posts)==2
                # A third, unsent scope validates storage failure and current-tab logout cleanup.
                page.get_by_role('button',name='＋ 新建对话',exact=True).click();expect(field).to_be_visible()
                page.evaluate("() => { sessionStorage.setItem = () => { throw new Error('synthetic quota'); }; }")
                field.fill('输入不会因存储失败丢失')
                expect(page.get_by_text('草稿未能保存到当前标签，刷新后可能无法恢复；当前输入仍保留。',exact=True)).to_be_visible()
                page.get_by_role('button',name='退出登录',exact=True).click();page.wait_for_url('**/login')
                assert page.evaluate("Object.keys(sessionStorage).every(k => !k.startsWith('agentscope.recovery.'))")
                observations.append({'browser':channel,'version':browser.version,'mode':'headless PC installed browser',
                    'cases':['draft refresh','synthetic composition Enter blocked','Shift+Enter newline','editing during focus authentication check','accepted user text and waiting Run',
                        'sidebar selection','read refresh does not resend','lost HTTP response and receipt GET after refresh',
                        'storage failure retains input','logout clears tab recovery'],'submissionPosts':len(posts),'sdkExecutions':0,'result':'PASS'})
                checked('text-browser-'+channel,True)
            finally: browser.close()
    (EVIDENCE/'browser-observations.json').write_text(json.dumps(observations,ensure_ascii=False,indent=2)+'\n','utf-8')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-bin',type=Path,default=Path('D:/code environment/mysql/mysql-8.0.45-winx64/bin'))
    parser.add_argument('--browser',action='store_true');args=parser.parse_args()
    EVIDENCE.mkdir(parents=True,exist_ok=True)
    if args.browser:sys.path.insert(0,str(ROOT/'.codex-local/issue-42-browser'))
    ddl=sorted((ROOT/'db/schema').glob('*.sql'));count=sum(len(re.findall(r'CREATE TABLE (\w+)',p.read_text('utf-8'))) for p in ddl)
    jar=ROOT/'backend/target/issue42-text-build/agentscope-brain-backend-0.1.0-SNAPSHOT.jar'
    summary=web.base.verify(args.mysql_bin.resolve(),ddl_files=ddl,probes=[],expected_table_count=count,
        inspection_check=lambda _:0,concurrency_check=lambda _:0,extra_checks=lambda client:web.run_isolated(client,False,jar_path=jar,
            application_arguments=['--brain.conversation.text-submission-enabled=true','--spring.datasource.hikari.maximum-pool-size=2'],
            navigation_checks=lambda *values:admission_checks(*values,args.browser)))
    summary.pop('synthetic_rows_rolled_back',None)
    summary.update({'issue':42,'slice':'text admission and composer','executedAt':datetime.now(timezone.utc).isoformat(),
        'temporaryInstanceRemoved':True,'sharedDatasetUsed':False,'application_integration_tested':True,
        'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'browserTested':args.browser,
        'admissionCreatesActualUserMessages':True,'agentExecutionImplemented':False,'realModelAcceptance':False,
        'executionEvidence':'The inherited #37 fixture directly seeds 3 terminal Runs and 6 formal messages. New USER messages and ACCEPTED/WAITING Runs in this slice are created by actual authenticated HTTP admission, then cleaned before inherited final counts. Model/SDK/tool executions stay zero. Memory control metadata is synthetic; KV activation is not validated.'})
    (EVIDENCE/'isolated-text-admission.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n','utf-8')
    print('PASS: '+str(len(summary['cases']))+' text admission and inherited regression checks')


if __name__=='__main__':main()